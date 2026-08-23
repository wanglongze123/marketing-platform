package com.mp.gateway.config;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.api.reward.service.RewardService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gateway 对玩法层的远程引用。V4 新增，仅在分布式形态下装配。
 *
 * <p>设计理由与 {@code BenefitRemoteConfig} 一致，见其类注释。
 *
 * <p><b>只有一个引用</b>：全仓的 HTTP 入口只有 {@code BenefitOrderController}（{@code /api/benefit}）与两个签名端点。
 * {@code FissionService} 虽有 {@code @DubboService} 暴露，但目前没有 HTTP 入口 —— 前端的裂变页走的是查询端点， 写路径尚未开放。补
 * fission 的 controller 时，在此加一个 {@code @Bean} 即可。
 *
 * <p><b>超时给到 10 秒</b>，比服务间调用宽：这条是「用户请求 → 玩法层」的第一跳，它背后可能串着 活动查询、库存预占、发奖扇出等多次下游调用。取值短于扇出整体超时会让
 * gateway 先于业务层放弃， 而那时订单其实已经建好 —— 用户看到失败，库存却被占住。
 */
@Configuration
@ConditionalOnProperty(name = "mp.dubbo.remote", havingValue = "true")
public class GatewayRemoteConfig {

    @Bean
    @DubboReference(protocol = "tri", timeout = 10000, retries = 0, check = false)
    public ReferenceBean<BenefitOrderService> gatewayBenefitOrderService() {
        return new ReferenceBean<>();
    }

    /**
     * 供应方回调的转发目标。V4 新增，配合 {@code RewardCallbackController}。
     *
     * <p><b>{@code retries = 0}</b>：回调处理内部会推进发放状态并发事件，框架层重试等于把 同一条通知处理两遍。它幂等（{@code opNo +
     * notifySeq} 唯一），但重试掩盖的是「第一次到底 成没成」这个信息 —— 供应方那边会因超时而自己重投，那才是这条通路该有的重试。
     */
    @Bean
    @DubboReference(protocol = "tri", timeout = 10000, retries = 0, check = false)
    public ReferenceBean<RewardService> gatewayRewardService() {
        return new ReferenceBean<>();
    }
}
