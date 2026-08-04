package com.mp.api.reward.service;

/** 统一发奖（公共能力层）。V0 只有 smoke，V1 加 grantReward / queryGrant。 */
public interface RewardService {

    /** V0 冒烟：调下游 + 写库，返回链路标识。V1 结束时删除。 */
    String smoke(String bizNo);
}
