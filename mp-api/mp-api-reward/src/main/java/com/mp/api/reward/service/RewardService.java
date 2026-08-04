package com.mp.api.reward.service;

import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;

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
}
