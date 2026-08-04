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
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RetStatus;
import com.mp.reward.entity.RewardGrantItem;
import com.mp.reward.entity.RewardGrantRecord;
import com.mp.reward.repository.RewardGrantItemMapper;
import com.mp.reward.repository.RewardGrantRecordMapper;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 统一发奖实现。
 *
 * <p><b>幂等由 {@code uk_op_no} 唯一索引兜底，不由「先查后插」保证</b> —— 后者存在并发窗口，
 * 两个线程可能同时查不到再同时插入。正确形状是直接插入、捕获冲突后返回原结果。
 */
@DubboService
@Service
public class RewardServiceImpl implements RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardServiceImpl.class);

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private MockProviderService mockProviderService;

    private final RewardGrantRecordMapper recordMapper;
    private final RewardGrantItemMapper itemMapper;

    public RewardServiceImpl(
            RewardGrantRecordMapper recordMapper, RewardGrantItemMapper itemMapper) {
        this.recordMapper = recordMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * 发放奖励。
     *
     * <p>三段式：① 落 PROCESSING 中间态（幂等出口在此）② 事务外调下游 ③ 回写终态。
     *
     * <p>① 必须先于 ② —— 若等结果回来才插记录，RPC 发出后崩溃将没有任何痕迹， 而查单收敛正是以这条记录为锚点。V1 缺的只是查单任务，不是中间态。
     */
    @Override
    public GrantRewardResp grantReward(GrantRewardReq req) {
        String opNo = req.getOpNo();

        // ① 落中间态。冲突即已处理过，直接返回原结果（幂等出口）
        RewardGrantRecord record = new RewardGrantRecord();
        record.setOpNo(opNo);
        record.setBizOrderNo(req.getBizOrderNo());
        record.setPlayType(req.getPlayType());
        record.setActivityId(req.getActivityId());
        record.setReceiverId(req.getReceiverId());
        record.setResult(RetStatus.PROCESSING.name());
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 不是错误，是唯一索引生效。不打 ERROR、不告警（《开发规范》§7.3）
            log.info("grantReward duplicated, return existing result, opNo={}", opNo);
            return queryGrant(opNo);
        }

        // ② 事务外调下游。逐项调用，各项独立记录结果
        List<GrantItemResult> results = new ArrayList<>();
        boolean allSuccess = true;
        for (RewardItem item : req.getRewardItems()) {
            GrantItemResult r = grantOne(opNo, req.getReceiverId(), item);
            results.add(r);
            if (r.getRetStatus() != RetStatus.SUCCESS) {
                allSuccess = false;
            }
        }

        // ③ 回写汇总终态
        RetStatus summary = allSuccess ? RetStatus.SUCCESS : RetStatus.FAIL;
        RewardGrantRecord update = new RewardGrantRecord();
        update.setId(record.getId());
        update.setResult(summary.name());
        update.setVersion(record.getVersion());
        recordMapper.updateById(update);

        log.info(
                "grantReward done, opNo={}, bizOrderNo={}, items={}, result={}",
                opNo,
                req.getBizOrderNo(),
                results.size(),
                summary);

        return buildResp(summary, results);
    }

    /** 单项发放：调供应方后落明细。V1 下游固定成功，V2 在此补四分类分支。 */
    private GrantItemResult grantOne(String opNo, String receiverId, RewardItem item) {
        ProviderGrantReq downReq = new ProviderGrantReq();
        downReq.setOpNo(opNo);
        downReq.setProviderProductId(item.getProviderProductId());
        downReq.setReceiverId(receiverId);
        downReq.setQty(item.getQty());

        ProviderGrantResp downResp = mockProviderService.grant(downReq);

        // V1 只实现 SUCCESS 分支。V2 补 FAIL / PROCESSING / UNKNOWN，
        // 其中 UNKNOWN 须保持中间态并以原 opNo 挂查单任务，禁止判失败
        if (downResp.getRetStatus() != RetStatus.SUCCESS) {
            throw new UnsupportedOperationException(
                    "V2 实现四分类的非 SUCCESS 分支，实际返回 " + downResp.getRetStatus());
        }

        RewardGrantItem entity = new RewardGrantItem();
        entity.setOpNo(opNo);
        entity.setItemSeq(item.getItemSeq());
        entity.setRewardType(item.getRewardType());
        entity.setProviderType(item.getProviderType());
        entity.setProviderOrderNo(downResp.getProviderOrderNo());
        entity.setResult(RetStatus.SUCCESS.name());
        itemMapper.insert(entity);

        GrantItemResult r = new GrantItemResult();
        r.setItemSeq(item.getItemSeq());
        r.setRetStatus(RetStatus.SUCCESS);
        r.setProviderOrderNo(downResp.getProviderOrderNo());
        return r;
    }

    /** 按原幂等号查单。V1 主链路只在幂等冲突时调用，V2 由查单任务驱动。 */
    @Override
    public GrantRewardResp queryGrant(String opNo) {
        RewardGrantRecord record =
                recordMapper.selectOne(
                        Wrappers.<RewardGrantRecord>lambdaQuery()
                                .eq(RewardGrantRecord::getOpNo, opNo));
        if (record == null) {
            GrantRewardResp resp = new GrantRewardResp();
            resp.setRetStatus(RetStatus.FAIL);
            resp.setRetCode(ErrorCode.INVALID_PARAM);
            resp.setRetMsg("发奖记录不存在: " + opNo);
            return resp;
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

    private GrantRewardResp buildResp(RetStatus summary, List<GrantItemResult> items) {
        GrantRewardResp resp = new GrantRewardResp();
        resp.setRetStatus(summary);
        resp.setItems(items);
        return resp;
    }
}
