package com.mp.reward.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;
import com.mp.api.mock.dto.ProviderRevokeReq;
import com.mp.api.mock.dto.ProviderRevokeResp;
import com.mp.api.mock.service.MockProviderService;
import com.mp.api.reward.dto.GrantItemResult;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.dto.ProviderCallbackReq;
import com.mp.api.reward.dto.ProviderCallbackResp;
import com.mp.api.reward.dto.RevokeRewardReq;
import com.mp.api.reward.dto.RevokeRewardResp;
import com.mp.api.reward.dto.RewardItem;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RetStatus;
import com.mp.common.event.GrantResultPublisher;
import com.mp.common.event.RewardGrantResultEvent;
import com.mp.common.security.ProviderNotifySigner;
import com.mp.reward.entity.RewardGrantItem;
import com.mp.reward.entity.RewardGrantRecord;
import com.mp.reward.entity.RewardRevokeRecord;
import com.mp.reward.repository.RewardGrantItemMapper;
import com.mp.reward.repository.RewardGrantRecordMapper;
import com.mp.reward.repository.RewardRevokeRecordMapper;
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
    private final RewardRevokeRecordMapper revokeRecordMapper;
    private final ProviderNotifySigner signer;
    private final GrantResultPublisher publisher;

    public RewardServiceImpl(
            RewardTxService tx,
            RewardGrantRecordMapper recordMapper,
            RewardGrantItemMapper itemMapper,
            RewardRevokeRecordMapper revokeRecordMapper,
            ProviderNotifySigner signer,
            GrantResultPublisher publisher) {
        this.signer = signer;
        this.publisher = publisher;
        this.tx = tx;
        this.recordMapper = recordMapper;
        this.itemMapper = itemMapper;
        this.revokeRecordMapper = revokeRecordMapper;
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
            if (record == null) {
                // 记录在 queryGrant 与此处之间消失（人工清理等）。返回 UNKNOWN 而非抛异常：
                // 这条链路本身就是在收敛未定态，此处抛出会让调用方连「未定」都拿不到
                log.warn(
                        "grantReward record vanished between query and re-dispatch, opNo={}", opNo);
                return buildResp(RetStatus.UNKNOWN, List.of());
            }
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
     * 回收已发放的权益（BR-B-30）。V3 PR-7。
     *
     * <p>三段式与 {@link #grantReward} 同构：① 落 {@code PROCESSING} ② 事务外调下游 ③ 回写终态。 幂等出口在 ①，由 {@code
     * uk_revoke_no} 兜底而非「先查后插」。
     *
     * <p><b>「仅当未使用才回收」不在本方法判断</b>：平台先查 {@code usageStatus} 再决定要不要调用， 在两步之间存在窗口 ——
     * 查到未使用、用户随即核销、平台再回收，于是券已花掉而平台以为回收成功、 退了钱。判定与动作必须由持有该券的供应方原子完成，本方法只转发它回传的 {@code usageStatus}。
     *
     * <p><b>异常映射 {@code UNKNOWN} 而非 {@code FAIL}</b>：与发奖同理，异常可能发生在 RPC 发出之后。 判 {@code FAIL}
     * 会让调用方以为「权益还在」而拒绝退款 —— 而权益可能已被收走，用户既没权益 也没退款。
     */
    @Override
    public RevokeRewardResp revokeReward(RevokeRewardReq req) {
        String revokeNo = req.getRevokeNo();

        // ① 落中间态。冲突即已受理过，走幂等出口
        try {
            tx.createRevokeProcessing(req);
        } catch (DuplicateKeyException e) {
            RewardRevokeRecord existing = revokeRecordMapper.selectByRevokeNo(revokeNo);
            if (existing != null && !RetStatus.PROCESSING.name().equals(existing.getResult())) {
                // 已收敛：返回原结果，这是幂等的正常出口。不打 ERROR、不告警
                log.info("revokeReward duplicated, return existing result, revokeNo={}", revokeNo);
                return buildRevokeResp(
                        RetStatus.valueOf(existing.getResult()),
                        existing.getUsageStatus(),
                        existing.getProviderOrderNo(),
                        existing.getErrorCode());
            }
            // 仍是 PROCESSING：原调用可能未到达下游，以原 revokeNo 重发。
            // 下游按 revokeNo 幂等，若原调用其实已到达，重发返回首次结果，不二次回收
            log.info("revokeReward re-dispatched with original revokeNo, revokeNo={}", revokeNo);
        }

        // ② 事务外调下游
        RetStatus status;
        String usageStatus = null;
        String providerOrderNo = null;
        String errorCode = null;
        try {
            ProviderRevokeReq downReq = new ProviderRevokeReq();
            downReq.setRevokeNo(revokeNo);
            downReq.setGrantOpNo(req.getOpNo());
            downReq.setReceiverId(req.getReceiverId());
            ProviderRevokeResp downResp = mockProviderService.revoke(downReq);
            status = downResp.getRetStatus();
            usageStatus = downResp.getUsageStatus();
            providerOrderNo = downResp.getProviderOrderNo();
            errorCode = downResp.getErrorCode();
        } catch (Exception e) {
            log.warn("provider revoke failed, treat as UNKNOWN, revokeNo={}", revokeNo, e);
            status = RetStatus.UNKNOWN;
        }

        // ③ 回写终态。UNKNOWN / PROCESSING 不回写，记录保持 PROCESSING 等收敛
        if (status != RetStatus.UNKNOWN && status != RetStatus.PROCESSING) {
            int rows = tx.finishRevoke(revokeNo, status, usageStatus, providerOrderNo, errorCode);
            if (rows == 0) {
                log.info("revokeReward already settled by another path, revokeNo={}", revokeNo);
            }
        } else {
            log.info(
                    "revokeReward unresolved, keep PROCESSING, revokeNo={}, result={}",
                    revokeNo,
                    status);
        }

        log.info(
                "revokeReward done, revokeNo={}, grantOpNo={}, result={}, usage={}",
                revokeNo,
                req.getOpNo(),
                status,
                usageStatus);
        return buildRevokeResp(status, usageStatus, providerOrderNo, errorCode);
    }

    /**
     * 供应方异步通知（FR-B06）。V3 PR-9。
     *
     * <p>四步：验签 → 幂等落通知记录并推进发放态（同事务）→ 发事件 → ACK。
     *
     * <p><b>验签是第一件事，先于定位记录</b>：未验签就查库等于让任何人都能拿一个 {@code opNo} 探测 平台有没有这笔发放。与 {@code payCallback}
     * 同一处置。
     *
     * <p><b>{@code UNKNOWN} 的通知直接拒绝</b>：供应方不会通知「我也不知道」——这类报文要么是构造的，
     * 要么是对接错误。放行它会把发放记录推向一个它本来就在的状态，且发一条无意义的事件。
     *
     * <p><b>事件在事务提交之后发</b>：事务内发事件的话，消费侧可能在事务提交前就收到并回查 —— 读到 的是推进之前的旧状态。V3 是进程内同步投递，这个窗口尤其真实。
     */
    @Override
    public ProviderCallbackResp providerCallback(ProviderCallbackReq req) {
        String opNo = req.getOpNo();
        String notifySeq = req.getNotifySeq();

        if (!signer.verify(req.signFields(), req.getSign())) {
            // 验签不过不 ACK：这条通知不可信，不能告诉供应方「收到了」。
            // 且不留任何痕迹 —— 落库等于让伪造者能往平台写数据（BR-B-12 的同一条）
            log.warn("providerCallback signature invalid, opNo={}, notifySeq={}", opNo, notifySeq);
            return ProviderCallbackResp.rejected(ErrorCode.PROVIDER_NOTIFY_SIGN_INVALID);
        }
        if (opNo == null || opNo.isBlank() || notifySeq == null || notifySeq.isBlank()) {
            // 两者都是幂等键的组成部分。缺任一则唯一索引失去意义 —— 空串能反复插入同一个值
            log.warn("providerCallback missing key fields, opNo={}, notifySeq={}", opNo, notifySeq);
            return ProviderCallbackResp.rejected(ErrorCode.INVALID_PARAM);
        }
        RetStatus result = req.getResult();
        if (result != RetStatus.SUCCESS && result != RetStatus.FAIL) {
            log.warn(
                    "providerCallback carries non-terminal result {}, reject, opNo={}",
                    result,
                    opNo);
            return ProviderCallbackResp.rejected(ErrorCode.INVALID_PARAM);
        }

        boolean advanced;
        try {
            advanced =
                    tx.applyNotify(
                            opNo, notifySeq, result, req.getProviderOrderNo(), req.getErrorCode());
        } catch (DuplicateKeyException e) {
            // 同一条通知重投：ACK 但不重复处理，也不再发事件。
            // 返回 accepted=true —— ACK 的语义是「别再投了」，返回失败会让供应方一直重投
            log.info(
                    "providerCallback duplicated, ack without reprocessing, opNo={}, seq={}",
                    opNo,
                    notifySeq);
            return ProviderCallbackResp.accepted(true);
        }

        // 事务已提交，此刻发事件。affected_rows=0（已被查单先收敛）同样发 ——
        // 消费侧按 opNo 幂等，多一条事件只是多一次无副作用的回查；而漏发会让
        // 「查单已收敛 reward 侧、玩法层却还没被通知」这一段只能等玩法层自己的查单
        publisher.publish(
                new RewardGrantResultEvent(
                        opNo, receiverOf(opNo), result, req.getProviderOrderNo(), notifySeq));

        log.info(
                "providerCallback done, opNo={}, seq={}, result={}, advanced={}",
                opNo,
                notifySeq,
                result,
                advanced);
        return ProviderCallbackResp.accepted(false);
    }

    /**
     * 批量按幂等号查发放结果，供对账比对（§6.8）。V3 PR-10。
     *
     * <p><b>查无的键不放进返回</b>：对账第 3 项要找的正是「平台有履约明细而 reward 侧查无」的那些键， 补占位行会把差异抹平。
     *
     * <p><b>只查主表不查明细</b>：对账比的是「这笔发放在 reward 侧是什么状态」，逐项明细对它没有意义， 而按 {@code opNo} 批量拉明细会让返回集随权益项数膨胀
     * —— 对账一次扫几百笔单，这个膨胀是实打实的。
     */
    @Override
    public java.util.Map<String, GrantRewardResp> batchQueryByOpNos(java.util.List<String> opNos) {
        if (opNos == null || opNos.isEmpty()) {
            // 空列表会让 foreach 拼出 IN ()，MySQL 语法错误。在此拦下而非交给调用方 ——
            // 「对账这一批没有要比对的键」是正常情形，不该要求每个调用点各判一次
            return java.util.Map.of();
        }
        java.util.Map<String, GrantRewardResp> result = new java.util.LinkedHashMap<>();
        for (RewardGrantRecord record : recordMapper.selectByOpNos(opNos)) {
            result.put(
                    record.getOpNo(), buildResp(RetStatus.valueOf(record.getResult()), List.of()));
        }
        return result;
    }

    /** 取收奖人，供事件体携带。记录不存在时返回 {@code null} —— 通知先于发放记录到达是可能的。 */
    private String receiverOf(String opNo) {
        RewardGrantRecord record = existingRecord(opNo);
        return record == null ? null : record.getReceiverId();
    }

    private static RevokeRewardResp buildRevokeResp(
            RetStatus status, String usageStatus, String providerOrderNo, String errorCode) {
        RevokeRewardResp resp = new RevokeRewardResp();
        resp.setRetStatus(status);
        resp.setUsageStatus(usageStatus);
        resp.setProviderOrderNo(providerOrderNo);
        resp.setErrorCode(errorCode);
        return resp;
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
     * UNKNOWN} 写成终态等于宣称「这笔已经定了」，而它并没有定。
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
