package com.mp.gateway.controller;

import com.mp.api.reward.dto.ProviderCallbackReq;
import com.mp.api.reward.dto.ProviderCallbackResp;
import com.mp.api.reward.service.RewardService;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应方发放结果回调的 HTTP 入口。V4 新增。
 *
 * <p><b>为什么到 V4 才有</b>：{@code providerCallback} 此前只经 Dubbo 暴露，集成测试直接注入 {@code RewardService}
 * 调用它，单进程形态下这就够了。拆服务后它成了一条**外部系统打进来的** 通路 —— 真实的权益供应方发的是 HTTP 请求，不会用我们的 RPC 协议，没有这个端点它就永远 只能被测试调到。
 *
 * <p>这也是 V4 实测暴露的一处缺口：分布式形态跑起来后想验证「事件经 MQ 流转」，才发现 触发事件的唯一路径在生产形态下根本没有入口。V3 单进程时看不出来，因为测试和业务在同一个
 * 容器里。
 *
 * <p><b>路径与验签沿用既有约定</b>：请求体就是 {@code ProviderCallbackReq}，签名由 {@code
 * /api/fault/provider-notify/sign} 签发（mock 供应方不主动推送，保持「通知是外部事件」的形状）。 验签在 {@code RewardServiceImpl}
 * 内部完成，本类只做转发。
 */
@RestController
@RequestMapping("/api/reward")
public class RewardCallbackController {

    @Autowired private RewardService rewardService;

    /** 供应方发放结果通知。幂等由 {@code opNo + notifySeq} 保证，重复投递只更新一次。 */
    @PostMapping("/provider-callback")
    public ApiResponse<ProviderCallbackResp> providerCallback(
            @RequestBody ProviderCallbackReq req) {
        ApiResponse<ProviderCallbackResp> resp =
                ApiResponse.ok(rewardService.providerCallback(req));
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
