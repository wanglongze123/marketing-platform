package com.mp.api.reward.service;

import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.dto.ProviderCallbackReq;
import com.mp.api.reward.dto.ProviderCallbackResp;
import com.mp.api.reward.dto.RevokeRewardReq;
import com.mp.api.reward.dto.RevokeRewardResp;

/**
 * 统一发奖（公共能力层）。唯一对接奖励供应方的出口，玩法层不直接对接供应方。
 *
 * <p>被两个玩法共用，签名不含任何单一玩法的专有概念（形状冻结项 6）。
 */
public interface RewardService {

    /**
     * 发放奖励。同 {@code opNo} 重复调用返回同结果，由 {@code uk_op_no} 保证。
     *
     * <p>返回 {@code UNKNOWN} 时调用方<b>必须走 queryGrant 查单，禁止直接判失败</b>。
     */
    GrantRewardResp grantReward(GrantRewardReq req);

    /**
     * 按原幂等号查单，供 UNKNOWN / PROCESSING 收敛。
     *
     * <p>V1 提供实现但主链路不调用，V2 由查单任务驱动。
     */
    GrantRewardResp queryGrant(String opNo);

    /**
     * 向下游查单并收敛：把未定的明细项推进到终态。
     *
     * <p>与 {@link #queryGrant} 的区别：后者只读平台侧记录，本方法会真的去问下游。 由 {@code QUERY_GRANT} 任务驱动，<b>复用原 {@code
     * opNo}</b> —— 下游按同一键返回首次的 结果，重查不产生新发放。
     *
     * @return 仍未定则返回 {@code UNKNOWN} 或 {@code PROCESSING}，调用方据此按各自的退避序列继续
     */
    GrantRewardResp reconcileGrant(String opNo);

    /**
     * 回收已发放的权益（BR-B-30）。V3 PR-7 引入。
     *
     * <p>同 {@code revokeNo} 重复调用返回同结果，由 {@code reward_revoke_record.uk_revoke_no} 保证。
     * <b>回收键与发奖键不复用</b>（BR-C-11）—— 复用会让回收撞上 {@code uk_op_no} 被当成发奖重传吞掉： 权益实际没回收，而调用方拿到「成功」。
     *
     * <p><b>「仅当未使用才回收」由供应方原子判定</b>，平台不先查再回收：查完到回收之间用户可以把券 花掉，于是券已核销而平台以为回收成功、退了钱。返回的 {@code
     * usageStatus} 是最终依据。
     *
     * <p>返回 {@code UNKNOWN} 时调用方<b>不得推进退款</b>：回收结果未定即权益可能仍在外，此时退款 就是「退了钱权益还在」。
     */
    RevokeRewardResp revokeReward(RevokeRewardReq req);

    /**
     * 供应方异步通知的<b>唯一入口</b>（FR-B06、技术方案 §4.3）。V3 PR-9 引入。
     *
     * <p>验签 → 幂等落通知记录 → 推进发放记录 → 发 {@code RewardGrantResultEvent} 事件。
     *
     * <p><b>发事件而非同步 RPC 回调上游</b>：本方法在公共能力层，而需要知道结果的是玩法层。同步回调 即下层调上层，违反 §1.1 单向依赖；且 {@code reward}
     * 被两个玩法共用，轮询要每个上游各写一套。
     *
     * <p><b>事件负责加速收敛，查单负责保证收敛</b>（§6.7）：消费侧的幂等与 {@code QUERY_GRANT} 任务 必须并存。不得因为「有了回调就不用查单了」而去掉查单
     * —— 事件一丢即永久悬挂。
     *
     * <p><b>幂等按 {@code (opNo, notifySeq)} 两维判</b>：同一 {@code opNo} 会收到多条语义不同的通知，只按 {@code opNo}
     * 去重会把第二条真实通知当成重传丢弃。重复投递返回 {@code accepted=true} —— ACK 的语义是 「别再投了」，返回失败会让供应方一直重投。
     */
    ProviderCallbackResp providerCallback(ProviderCallbackReq req);
}
