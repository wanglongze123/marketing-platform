package com.mp.reward.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;
import com.mp.api.mock.service.MockProviderService;
import com.mp.api.reward.dto.GrantItemResult;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.dto.RewardItem;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.RetStatus;
import com.mp.reward.entity.RewardGrantItem;
import com.mp.reward.entity.RewardGrantRecord;
import com.mp.reward.repository.RewardGrantItemMapper;
import com.mp.reward.repository.RewardGrantRecordMapper;
import com.mp.reward.service.RewardTxService;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 统一发奖实现：编排层，不含事务。
 *
 * <p><b>幂等由 {@code uk_op_no} 唯一索引兜底，不由「先查后插」保证</b> —— 后者存在并发窗口，
 * 两个线程可能同时查不到再同时插入。正确形状是直接插入、捕获冲突后返回原结果。
 *
 * <p>事务边界全部收在 {@link RewardTxService}（能力清单第 9 项）。
 */
@DubboService
@Service
public class RewardServiceImpl implements RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardServiceImpl.class);

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private MockProviderService mockProviderService;

    private final RewardTxService tx;
    private final RewardGrantRecordMapper recordMapper;
    private final RewardGrantItemMapper itemMapper;

    public RewardServiceImpl(
            RewardTxService tx,
            RewardGrantRecordMapper recordMapper,
            RewardGrantItemMapper itemMapper) {
        this.tx = tx;
        this.recordMapper = recordMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * 发放奖励。
     *
     * <p>三段式：① 落 PROCESSING 中间态（幂等出口在此）② 事务外调下游 ③ 回写终态。
     *
     * <p>① 必须先于 ② —— 若等结果回来才插记录，RPC 发出后崩溃将没有任何痕迹， 而查单收敛正是以这条记录为锚点。
     */
    @Override
    public GrantRewardResp grantReward(GrantRewardReq req) {
        String opNo = req.getOpNo();

        // ① 落中间态。冲突即已受理过，走幂等出口
        RewardGrantRecord record;
        try {
            record = tx.createProcessing(req);
        } catch (DuplicateKeyException e) {
            // 不是错误，是唯一索引生效。不打 ERROR、不告警（《开发规范》§7.3）
            GrantRewardResp existing = queryGrant(opNo);
            if (existing.getRetStatus() != RetStatus.PROCESSING) {
                // 已收敛：返回原结果，这是幂等的正常出口
                log.info("grantReward duplicated, return existing result, opNo={}", opNo);
                return existing;
            }
            // 仍是 PROCESSING：调用方（查单任务判定原调用未到达后）以原 opNo 重发。
            // 此时直接返回原记录等于什么都没做 —— 下游确实没发过，这笔单会永远停在未定态。
            // 继续往下走真正调用下游，幂等由下游账本按 opNo 兜底：若原调用其实已到达，
            // 重发被 put-if-absent 挡下并返回首次的单号，不产生第二笔发放
            log.info("grantReward re-dispatched with original opNo, opNo={}", opNo);
            record = existingRecord(opNo);
        }

        // ② 事务外调下游。逐项调用，各项独立记录结果
        List<GrantItemResult> results = new ArrayList<>();
        for (RewardItem item : req.getRewardItems()) {
            results.add(grantOne(opNo, req.getReceiverId(), item));
        }

        // ③ 汇总并回写终态
        RetStatus summary = summarize(results);
        writeBackSummary(opNo, summary, record.getBizOrderNo(), results.size());
        return buildResp(summary, results);
    }

    /**
     * 汇总各项结果。
     *
     * <p><b>取「最不确定」的那一项，而非「多数」或「首个」</b>：只要有一项结果未知，整笔就未收敛 —— 汇总成 {@code SUCCESS} 会让调用方以为发完了，汇总成
     * {@code FAIL} 则会触发对一笔可能已成功的发放 做补偿。优先级 {@code UNKNOWN} > {@code PROCESSING} > {@code FAIL} >
     * {@code SUCCESS}。
     *
     * <p>{@code FAIL} 排在 {@code SUCCESS} 之前但在两个未定态之后：部分失败对调用方是确定的失败 （可以补偿），而未定态不可补偿。
     */
    private RetStatus summarize(List<GrantItemResult> results) {
        boolean hasUnknown = false;
        boolean hasProcessing = false;
        boolean hasFail = false;
        for (GrantItemResult r : results) {
            switch (r.getRetStatus()) {
                case UNKNOWN -> hasUnknown = true;
                case PROCESSING -> hasProcessing = true;
                case FAIL -> hasFail = true;
                default -> {}
            }
        }
        if (hasUnknown) {
            return RetStatus.UNKNOWN;
        }
        if (hasProcessing) {
            return RetStatus.PROCESSING;
        }
        return hasFail ? RetStatus.FAIL : RetStatus.SUCCESS;
    }

    /**
     * 回写终态。
     *
     * <p><b>{@code UNKNOWN} 与 {@code PROCESSING} 不回写</b>：记录保持 {@code PROCESSING} 等查单收敛。 把 {@code
     * UNKNOWN} 写成终态等于宣称「这笔已经定了」，而它恰恰没定。
     */
    private void writeBackSummary(
            String opNo, RetStatus summary, String bizOrderNo, int itemCount) {
        if (summary == RetStatus.UNKNOWN || summary == RetStatus.PROCESSING) {
            log.info(
                    "grantReward unresolved, keep PROCESSING for query task,"
                            + " opNo={}, bizOrderNo={}, summary={}",
                    opNo,
                    bizOrderNo,
                    summary);
            return;
        }

        int rows = tx.finish(opNo, summary);
        if (rows == 0) {
            // 已被查单等其他路径收敛，先到的终态作数。不是错误
            log.info("grantReward already settled by another path, opNo={}", opNo);
            return;
        }
        log.info(
                "grantReward done, opNo={}, bizOrderNo={}, items={}, result={}",
                opNo,
                bizOrderNo,
                itemCount,
                summary);
    }

    /**
     * 单项发放：调供应方后落明细，四分类逐类处置。
     *
     * <p><b>异常映射 {@code UNKNOWN} 而非 {@code FAIL}</b>：异常可能发生在 RPC 发出之后，下游未必没执行。 判 {@code FAIL}
     * 等于替下游断言「没做」—— 而这个断言没有依据，据此补发即为重复发放。
     */
    private GrantItemResult grantOne(String opNo, String receiverId, RewardItem item) {
        ProviderGrantReq downReq = new ProviderGrantReq();
        downReq.setOpNo(opNo);
        downReq.setProviderProductId(item.getProviderProductId());
        downReq.setReceiverId(receiverId);
        downReq.setQty(item.getQty());

        RetStatus status;
        String providerOrderNo = null;
        String errorCode = null;
        try {
            ProviderGrantResp downResp = mockProviderService.grant(downReq);
            status = downResp.getRetStatus();
            providerOrderNo = downResp.getProviderOrderNo();
            errorCode = downResp.getErrorCode();
        } catch (Exception e) {
            // 超时、连接失败、序列化错误 —— 一律 UNKNOWN，交由查单收敛
            log.warn("provider call failed, treat as UNKNOWN, opNo={}", opNo, e);
            status = RetStatus.UNKNOWN;
        }

        // 明细无论何种结果都落库：UNKNOWN 的那一行正是查单任务的作用对象，
        // 不落库则收敛时不知道该查哪些项
        tx.saveItem(
                opNo,
                item.getItemSeq(),
                item.getRewardType(),
                item.getProviderType(),
                providerOrderNo,
                status,
                errorCode);

        GrantItemResult r = new GrantItemResult();
        r.setItemSeq(item.getItemSeq());
        r.setRetStatus(status);
        r.setProviderOrderNo(providerOrderNo);
        r.setErrorCode(errorCode);
        return r;
    }

    /**
     * 按原幂等号查单。
     *
     * <p><b>记录不存在返回 {@code UNKNOWN}，不返回 {@code FAIL}</b>：查无可能是事务尚未提交（三段式的 ① 与调用方读之间存在窗口），判 {@code
     * FAIL} 会让调用方据此走补偿。V1 此处 返回 {@code FAIL} 是缺陷，V2 修正。
     */
    @Override
    public GrantRewardResp queryGrant(String opNo) {
        RewardGrantRecord record =
                recordMapper.selectOne(
                        Wrappers.<RewardGrantRecord>lambdaQuery()
                                .eq(RewardGrantRecord::getOpNo, opNo));
        if (record == null) {
            log.info("queryGrant found no record, return UNKNOWN, opNo={}", opNo);
            return buildResp(RetStatus.UNKNOWN, List.of());
        }

        List<RewardGrantItem> items =
                itemMapper.selectList(
                        Wrappers.<RewardGrantItem>lambdaQuery()
                                .eq(RewardGrantItem::getOpNo, opNo)
                                .orderByAsc(RewardGrantItem::getItemSeq));

        List<GrantItemResult> results = new ArrayList<>(items.size());
        for (RewardGrantItem i : items) {
            GrantItemResult r = new GrantItemResult();
            r.setItemSeq(i.getItemSeq());
            r.setRetStatus(RetStatus.valueOf(i.getResult()));
            r.setProviderOrderNo(i.getProviderOrderNo());
            r.setErrorCode(i.getErrorCode());
            results.add(r);
        }
        return buildResp(RetStatus.valueOf(record.getResult()), results);
    }

    /**
     * 向下游查单并收敛：把未定的明细项推进到终态。
     *
     * <p>由 {@code QUERY_GRANT} 任务驱动。<b>复用原 {@code opNo}</b> —— 这是幂等键复用的兑现处： 下游按同一键返回首次的结果，重查不产生新发放。
     *
     * @return 收敛后的整笔状态；仍未定则返回 {@code UNKNOWN} 或 {@code PROCESSING}，调用方据此继续退避
     */
    @Override
    public GrantRewardResp reconcileGrant(String opNo) {
        RewardGrantRecord record =
                recordMapper.selectOne(
                        Wrappers.<RewardGrantRecord>lambdaQuery()
                                .eq(RewardGrantRecord::getOpNo, opNo));
        if (record == null) {
            // 平台侧无记录：原调用可能连中间态都没落成，交由调用方判断是否重发
            log.info("reconcileGrant found no record, opNo={}", opNo);
            return buildResp(RetStatus.UNKNOWN, List.of());
        }
        if (!RetStatus.PROCESSING.name().equals(record.getResult())) {
            // 已收敛，直接返回原结果 —— 不再打扰下游
            return queryGrant(opNo);
        }

        ProviderGrantResp downResp;
        try {
            downResp = mockProviderService.queryGrant(opNo);
        } catch (Exception e) {
            log.warn("provider query failed, stay UNKNOWN, opNo={}", opNo, e);
            return buildResp(RetStatus.UNKNOWN, List.of());
        }

        RetStatus downstream = downResp.getRetStatus();
        if (downstream == RetStatus.PROCESSING || downstream == RetStatus.UNKNOWN) {
            // 仍未定：保持中间态，调用方按各自的退避序列继续查
            return buildResp(downstream, List.of());
        }

        // 已定：把所有未终结的明细项推进到该结果，再回写汇总
        List<RewardGrantItem> items =
                itemMapper.selectList(
                        Wrappers.<RewardGrantItem>lambdaQuery().eq(RewardGrantItem::getOpNo, opNo));
        for (RewardGrantItem i : items) {
            RetStatus current = RetStatus.valueOf(i.getResult());
            if (current == RetStatus.SUCCESS || current == RetStatus.FAIL) {
                continue;
            }
            itemMapper.updateResult(
                    opNo, i.getItemSeq(), downstream.name(), downResp.getProviderOrderNo());
        }
        tx.finish(opNo, downstream);

        log.info("reconcileGrant converged, opNo={}, result={}", opNo, downstream);
        return queryGrant(opNo);
    }

    private RewardGrantRecord existingRecord(String opNo) {
        return recordMapper.selectOne(
                Wrappers.<RewardGrantRecord>lambdaQuery().eq(RewardGrantRecord::getOpNo, opNo));
    }

    private GrantRewardResp buildResp(RetStatus summary, List<GrantItemResult> items) {
        GrantRewardResp resp = new GrantRewardResp();
        resp.setRetStatus(summary);
        resp.setItems(items);
        return resp;
    }
}
