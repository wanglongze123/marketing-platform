package com.mp.api.benefit.service;

/** 权益售卖（玩法层）。V0 只有 smoke，V1 加 createTrade / payCallback / grantBenefit / queryOrder。 */
public interface BenefitOrderService {

    /** V0 冒烟：调公共能力层，返回链路标识。V1 结束时删除。 */
    String smoke(String bizNo);
}
