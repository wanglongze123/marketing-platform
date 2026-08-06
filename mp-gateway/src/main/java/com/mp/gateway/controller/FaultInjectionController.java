package com.mp.gateway.controller;

import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.security.PayNotifySigner;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障注入控制面，运行期切换 mock 行为。
 *
 * <p><b>不做成 Spring Profile 或测试作用域 Bean</b>：手工验证在运行中的实例上进行，需要不重启切换；
 * 自动化测试与人工验证共用同一入口，否则「测试里验过的」与「演示时跑的」是两套代码 （《分阶段方案》§5.3）。
 *
 * <p><b>V2 不加鉴权</b>：单进程、仅本地运行。V3 拆分布式后本端点能改变下游行为，必须下线或 移入独立运维端口（§5.6 ⑥）。
 *
 * <p>演示序列：切 {@code TIMEOUT_AFTER_COMMIT} → 下单支付 → 观察 {@code GRANT_UNKNOWN} 停留 → 查单收敛为 {@code
 * GRANT_SUCCESS} → 两侧账本各 1 条。
 */
@RestController
@RequestMapping("/api/fault")
public class FaultInjectionController {

    private final FaultInjector injector;
    private final ProviderLedger ledger;
    private final PayLedger payLedger;
    private final PayNotifySigner payNotifySigner;

    public FaultInjectionController(
            FaultInjector injector,
            ProviderLedger ledger,
            PayLedger payLedger,
            PayNotifySigner payNotifySigner) {
        this.injector = injector;
        this.ledger = ledger;
        this.payLedger = payLedger;
        this.payNotifySigner = payNotifySigner;
    }

    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> current() {
        return ok(snapshot());
    }

    /** 切换供应方模式。{@code PROCESSING} 可用 {@code turns} 指定第几次查单转成功。 */
    @PostMapping("/provider/{mode}")
    public ApiResponse<Map<String, Object>> setProvider(
            @PathVariable FaultMode mode,
            @RequestParam(name = "turns", required = false) Integer turns) {
        injector.setProviderMode(mode);
        if (turns != null) {
            injector.setProcessingTurns(turns);
        }
        return ok(snapshot());
    }

    @PostMapping("/pay/{mode}")
    public ApiResponse<Map<String, Object>> setPay(@PathVariable FaultMode mode) {
        injector.setPayMode(mode);
        return ok(snapshot());
    }

    /**
     * 复位全部模式。
     *
     * <p><b>不清账本</b>：账本是「下游已发放」的事实记录，演示中途清掉会让「无重复发放」的断言 失去依据。清账本单独走 {@code DELETE
     * /api/fault/ledger}，且只在开始一轮新演示时用。
     */
    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset() {
        injector.reset();
        return ok(snapshot());
    }

    /**
     * 清空下游账本，只在开始一轮新演示时用。
     *
     * <p>与 {@code /reset} 分开：模式是「接下来怎么表现」，账本是「已经发生过什么」。演示中途把 账本清掉，「无重复发放」的断言就失去了依据 ——
     * 它恰恰要靠账本里只有一条来证明。
     */
    @DeleteMapping("/ledger")
    public ApiResponse<Map<String, Object>> clearLedger() {
        ledger.clear();
        payLedger.clear();
        return ok(snapshot());
    }

    /**
     * 把支付单标记为「已支付」，构造「关单时对方已收款」的场景。
     *
     * <p>真实链路里这一步由用户在收银台完成，mock 没有收银台，故留一个显式入口。演示「已支付的单 拒绝关闭」（BR-B-16）时必须先调它 —— 否则平台问「能关吗」，mock
     * 答「能」，那条分支根本走不到。
     *
     * <p><b>不是「发一条支付成功通知」</b>：它只改支付方自己的账本，平台的 {@code pay_status} 不动。 两者的区别正是这个端点存在的理由 ——
     * 关单要以支付方的状态为准（BR-B-17）。
     */
    @PostMapping("/pay-ledger/{outTradeNo}/paid")
    public ApiResponse<Map<String, Object>> markPaid(@PathVariable String outTradeNo) {
        payLedger.markPaid(outTradeNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outTradeNo", outTradeNo);
        data.put("payState", String.valueOf(payLedger.find(outTradeNo)));
        return ok(data);
    }

    /**
     * 为一条支付通知计算签名，供手工验证与演示使用。
     *
     * <p><b>为什么必须有这个端点</b>：真实链路里签名由支付平台算出，而 mock 支付方不会主动回调 （保持「通知是外部事件」的形状，见 {@code
     * MockPayService}）。没有它，加上验签之后 {@code /api/benefit/pay-callback} 就<b>谁都调不通</b> ——
     * 自动化测试有注入的签名器可用，手工 curl 无从下手，「测试里验过的」与「演示时跑的」就此分家。
     *
     * <p>它<b>不发送通知</b>，只回签名值 —— 调用方拿去拼进自己的请求体。这样保留了「通知由外部 触发」的形状，也让演示者能看清「哪些字段参与了签名」。
     *
     * <p><b>V2 不加鉴权，V3 必须下线</b>：能签发通知等于能伪造收款，与 {@code /api/fault} 下其余 端点同属演示设施（《分阶段方案》§5.6 ⑥）。
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

    /** 支付方账本快照：关单判据的那一侧。 */
    @GetMapping("/pay-ledger/{outTradeNo}")
    public ApiResponse<Map<String, Object>> payLedgerEntry(@PathVariable String outTradeNo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outTradeNo", outTradeNo);
        data.put("payState", String.valueOf(payLedger.find(outTradeNo)));
        return ok(data);
    }

    /** 下游账本快照：跨过服务边界的那一侧，「无重复发放」的最终判据。 */
    @GetMapping("/ledger/{opNo}")
    public ApiResponse<Map<String, Object>> ledgerEntry(@PathVariable String opNo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("opNo", opNo);
        data.put("granted", ledger.contains(opNo));
        data.put("providerOrderNo", ledger.find(opNo));
        data.put("ledgerSize", ledger.size());
        return ok(data);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerMode", injector.providerMode().name());
        data.put("payMode", injector.payMode().name());
        data.put("ledgerSize", ledger.size());
        return data;
    }

    private static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
