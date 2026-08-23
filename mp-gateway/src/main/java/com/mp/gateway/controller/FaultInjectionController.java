package com.mp.gateway.controller;

import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.reward.dto.ProviderCallbackReq;
import com.mp.common.security.PayNotifySigner;
import com.mp.common.security.ProviderNotifySigner;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知签名端点，供手工验证与演示使用。
 *
 * <p><b>V4 前这里还承载故障模式切换与账本查询</b>，现已按「控制面随状态走」迁出：mock 行为与两侧账本 归 {@code mp-mock-downstream}（{@code
 * MockFaultController}），L3 冲突计数归 {@code mp-benefit-order}（{@code
 * ContentionController}）。它们操作的都是进程内状态，拆服务后 gateway 够不着。
 *
 * <p><b>签名端点反而留下了</b>：它们不碰任何进程内状态，只用 {@code mp-common} 的两个签名器做一次纯计算。 入参 {@code PayCallbackReq} /
 * {@code ProviderCallbackReq} 分属玩法层与公共能力层，放进 mock 模块 才是依赖倒挂 —— 最下层不该认识上层的 DTO。
 *
 * <p><b>路径与语义一字未改</b>：{@code /api/fault/pay-notify/sign} 与 {@code /api/fault/provider-notify/sign}
 * 仍是原来那两个 URL。
 *
 * <p><b>不做成 Spring Profile 或测试作用域 Bean</b>：手工验证在运行中的实例上进行，需要不重启切换；
 * 自动化测试与人工验证共用同一入口，否则「测试里验过的」与「演示时跑的」是两套代码 （《分阶段方案》§5.3）。
 *
 * <p><b>V4 收口时必须下线或移入独立运维端口</b>（§6A.1 第 9 项）：能签发通知等于能伪造收款。
 */
@RestController
@RequestMapping("/api/fault")
public class FaultInjectionController {

    private final PayNotifySigner payNotifySigner;
    private final ProviderNotifySigner providerNotifySigner;

    public FaultInjectionController(
            PayNotifySigner payNotifySigner, ProviderNotifySigner providerNotifySigner) {
        this.payNotifySigner = payNotifySigner;
        this.providerNotifySigner = providerNotifySigner;
    }

    /**
     * 为一条支付通知计算签名，供手工验证与演示使用。
     *
     * <p><b>为什么必须有这个端点</b>：真实链路里签名由支付平台算出，而 mock 支付方不会主动回调 （保持「通知是外部事件」的形状，见 {@code
     * MockPayService}）。没有它，加上验签之后 {@code /api/benefit/pay-callback} 就<b>谁都调不通</b> ——
     * 自动化测试有注入的签名器可用，手工 curl 无从下手，「测试里验过的」与「演示时跑的」就此分家。
     *
     * <p>它<b>不发送通知</b>，只回签名值 —— 调用方拿去拼进自己的请求体。这样保留了「通知由外部 触发」的形状，也让演示者能看清「哪些字段参与了签名」。
     */
    @PostMapping("/pay-notify/sign")
    public ApiResponse<Map<String, Object>> signPayNotify(@RequestBody PayCallbackReq req) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 复用请求对象自身的 signFields()，与验签侧走同一段代码 ——
        // 另写一份字段清单则两处迟早漂移，而漂移的表现是「演示时怎么都验不过」
        data.put("sign", payNotifySigner.sign(req.signFields()));
        data.put("signedFields", req.signFields());
        return ok(data);
    }

    /**
     * 为一条供应方通知计算签名，供手工验证与演示使用。V3 PR-9。
     *
     * <p><b>存在理由与 {@code /pay-notify/sign} 一字不差</b>：真实链路里签名由供应方算出，而 mock 供应方
     * 不会主动回调（保持「通知是外部事件」的形状）。没有它，加上验签之后 {@code providerCallback} 手工 调不通 —— 自动化测试有注入的签名器，演示者无从下手。
     *
     * <p><b>mock 供应方不主动推通知，通知由外部触发</b>：自动推送会让「已发放未通知」这个中间态无法 被观察，而退出标准第 17 条正要在这个状态上做文章 ——
     * 关掉事件后仍应由查单收敛。这也避免了 mock 反向依赖平台（{@code mp-mock-downstream} 只依赖 {@code mp-api-mock} 与 {@code
     * mp-common}，依赖方向由 pom 强制）。
     *
     * <p><b>V4 必须下线或移入独立运维端口</b>：能签发供应方通知等于能伪造发放成功，而伪造的成功会让 发放记录进终态、此后不再被查单推进 —— 且它不像重复发奖那样能被对账数出来。
     */
    @PostMapping("/provider-notify/sign")
    public ApiResponse<Map<String, Object>> signProviderNotify(
            @RequestBody ProviderCallbackReq req) {
        Map<String, Object> data = new LinkedHashMap<>();
        // 复用请求对象自身的 signFields()，与验签侧走同一段代码 ——
        // 另写一份字段清单则两处迟早漂移，而漂移的表现是「演示时怎么都验不过」
        data.put("sign", providerNotifySigner.sign(req.signFields()));
        data.put("signedFields", req.signFields());
        return ok(data);
    }

    private static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
