package com.mp.reward.service;

import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.RevokeRewardReq;
import com.mp.common.enums.RetStatus;
import com.mp.reward.config.RewardTx;
import com.mp.reward.entity.RewardGrantItem;
import com.mp.reward.entity.RewardGrantRecord;
import com.mp.reward.entity.RewardNotifyRecord;
import com.mp.reward.entity.RewardRevokeRecord;
import com.mp.reward.repository.RewardGrantItemMapper;
import com.mp.reward.repository.RewardGrantRecordMapper;
import com.mp.reward.repository.RewardNotifyRecordMapper;
import com.mp.reward.repository.RewardRevokeRecordMapper;
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
    private final RewardRevokeRecordMapper revokeRecordMapper;
    private final RewardNotifyRecordMapper notifyRecordMapper;

    public RewardTxService(
            RewardGrantRecordMapper recordMapper,
            RewardGrantItemMapper itemMapper,
            RewardRevokeRecordMapper revokeRecordMapper,
            RewardNotifyRecordMapper notifyRecordMapper) {
        this.recordMapper = recordMapper;
        this.itemMapper = itemMapper;
        this.revokeRecordMapper = revokeRecordMapper;
        this.notifyRecordMapper = notifyRecordMapper;
    }

    /**
     * 供应方通知：<b>落通知记录 + 推进发放记录与明细，同一事务</b>。V3 PR-9。
     *
     * <p><b>两者必须同事务</b>：通知记录落了而发放记录没推进，则这条通知已被 {@code uk_notify} 标记为 「处理过」，重投时走幂等出口直接返回 —— 而发放态还停在
     * {@code PROCESSING}。反过来推进了却没落 通知记录，则重投会再推进一次，且再发一次事件。
     *
     * <p><b>先插通知记录，靠 {@code DuplicateKeyException} 判重传，不做「先查后插」</b>：后者存在并发窗口，
     * 同一条通知被并发投递两次时可能同时查不到、同时插入。这与 {@code grantReward} 的幂等出口同形。
     *
     * <p><b>明细的回写限定「未终结」</b>：已 {@code SUCCESS} 的项不被后到的通知改写，与 {@code settleByGrantOpNo} 同一处置。汇总走
     * {@code finishIfProcessing}，{@code affected_rows=0} 表示已被查单先收敛 —— 不是错误， 事件照发（消费侧幂等）。
     *
     * @return 是否真的推进了汇总态；{@code false} 表示已被别的路径收敛
     * @throws org.springframework.dao.DuplicateKeyException 该通知已处理过，由调用方捕获走幂等出口
     */
    @RewardTx
    public boolean applyNotify(
            String opNo,
            String notifySeq,
            RetStatus result,
            String providerOrderNo,
            String errorCode) {
        RewardNotifyRecord notify = new RewardNotifyRecord();
        notify.setOpNo(opNo);
        notify.setNotifySeq(notifySeq);
        notify.setProviderOrderNo(providerOrderNo);
        notify.setResult(result.name());
        notify.setErrorCode(errorCode);
        notifyRecordMapper.insert(notify);

        itemMapper.settleUnresolved(opNo, result.name(), providerOrderNo);
        return recordMapper.finishIfProcessing(opNo, result.name()) > 0;
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

    // ---- 回收（V3 PR-7），三段式与发奖同构 ----

    /**
     * 回收 ①：落 {@code PROCESSING} 中间态。冲突由调用方捕获后走幂等出口。
     *
     * <p>与发奖的 {@link #createProcessing} 是同一处置，理由也相同：若等结果回来才插记录，回收 RPC 发出后崩溃将没有任何痕迹 ——
     * 而权益可能已经被收走了。此时用户既没有权益也没有退款， 且平台无从知道回收发生过。
     */
    @RewardTx
    public RewardRevokeRecord createRevokeProcessing(RevokeRewardReq req) {
        RewardRevokeRecord record = new RewardRevokeRecord();
        record.setRevokeNo(req.getRevokeNo());
        record.setBizOrderNo(req.getBizOrderNo());
        record.setOpNo(req.getOpNo());
        record.setReceiverId(req.getReceiverId());
        record.setResult(RetStatus.PROCESSING.name());
        revokeRecordMapper.insert(record);
        return record;
    }

    /**
     * 回收 ③：回写终态与供应方回传的使用态。
     *
     * <p>{@code UNKNOWN} 不在此处回写 —— 与发奖同理，它是中间态，保持 {@code PROCESSING} 等收敛。
     */
    @RewardTx
    public int finishRevoke(
            String revokeNo,
            RetStatus result,
            String usageStatus,
            String providerOrderNo,
            String errorCode) {
        return revokeRecordMapper.finishIfProcessing(
                revokeNo, result.name(), usageStatus, providerOrderNo, errorCode);
    }
}
