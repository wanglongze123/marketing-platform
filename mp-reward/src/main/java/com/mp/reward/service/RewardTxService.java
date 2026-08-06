package com.mp.reward.service;

import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.common.enums.RetStatus;
import com.mp.reward.config.RewardTx;
import com.mp.reward.entity.RewardGrantItem;
import com.mp.reward.entity.RewardGrantRecord;
import com.mp.reward.repository.RewardGrantItemMapper;
import com.mp.reward.repository.RewardGrantRecordMapper;
import org.springframework.stereotype.Service;

/**
 * 发奖的事务边界，独立成 bean（能力清单第 9 项）。
 *
 * <p>与 {@code OrderTxService} 同构，理由也相同：{@code @Transactional} 依赖 Spring 代理，同类内部 调用不经过代理、注解静默失效 ——
 * 事务看起来配了、实际没开。V1 的 {@code mp-reward} 全程无事务 边界类，单项发放且下游固定成功，多次自动提交观察不到中间态，缺陷因此没有暴露。
 *
 * <p>三个方法即三个事务单元，对应三段式的三步：中间态落库、明细写入、终态回写。事务内只有 DB 操作，无 RPC —— 下游调用发生在两个事务之间，这正是「中间态必须先落库」的原因。
 */
@Service
public class RewardTxService {

    private final RewardGrantRecordMapper recordMapper;
    private final RewardGrantItemMapper itemMapper;

    public RewardTxService(RewardGrantRecordMapper recordMapper, RewardGrantItemMapper itemMapper) {
        this.recordMapper = recordMapper;
        this.itemMapper = itemMapper;
    }

    /**
     * ① 落中间态。冲突由调用方捕获后走幂等出口。
     *
     * <p>必须先于下游调用 —— 若等结果回来才插记录，RPC 发出后崩溃将没有任何痕迹，而查单收敛正是 以这条记录为锚点。
     */
    @RewardTx
    public RewardGrantRecord createProcessing(GrantRewardReq req) {
        RewardGrantRecord record = new RewardGrantRecord();
        record.setOpNo(req.getOpNo());
        record.setBizOrderNo(req.getBizOrderNo());
        record.setPlayType(req.getPlayType());
        record.setActivityId(req.getActivityId());
        record.setReceiverId(req.getReceiverId());
        record.setResult(RetStatus.PROCESSING.name());
        recordMapper.insert(record);
        return record;
    }

    /**
     * ② 写单项明细。
     *
     * <p>走 upsert 而非 insert：查单收敛后回写同一项，重入时更新结果而不新增行（{@code uk_op_item}）。 写成 insert 会让重发在此抛 {@code
     * DuplicateKeyException} 中断收敛。
     */
    @RewardTx
    public void saveItem(
            String opNo,
            int itemSeq,
            String rewardType,
            String providerType,
            String providerOrderNo,
            RetStatus result,
            String errorCode) {
        RewardGrantItem entity = new RewardGrantItem();
        entity.setOpNo(opNo);
        entity.setItemSeq(itemSeq);
        entity.setRewardType(rewardType);
        entity.setProviderType(providerType);
        entity.setProviderOrderNo(providerOrderNo);
        entity.setResult(result.name());
        entity.setErrorCode(errorCode);
        itemMapper.upsert(entity);
    }

    /**
     * ③ 回写汇总终态。
     *
     * <p>条件更新限定前置状态为 {@code PROCESSING}：已收敛的记录不被后到的结果覆盖。 {@code UNKNOWN} 不在此处回写 —— 它本就是中间态，保持
     * {@code PROCESSING} 等查单。
     */
    @RewardTx
    public int finish(String opNo, RetStatus summary) {
        return recordMapper.finishIfProcessing(opNo, summary.name());
    }
}
