package com.mp.benefit.service.impl;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.api.reward.service.RewardService;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** V0 冒烟实现。V1 结束时删除，届时改为 createTrade / payCallback / grantBenefit / queryOrder。 */
@DubboService
@Service
public class BenefitOrderServiceImpl implements BenefitOrderService {

    private static final Logger log = LoggerFactory.getLogger(BenefitOrderServiceImpl.class);

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private RewardService rewardService;

    @Override
    public String smoke(String bizNo) {
        log.info("smoke benefit-order start, bizNo={}", bizNo);
        return "benefit-order," + rewardService.smoke(bizNo);
    }
}
