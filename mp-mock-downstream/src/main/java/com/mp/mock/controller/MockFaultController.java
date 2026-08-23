package com.mp.mock.controller;

import com.mp.api.mock.dto.FaultMode;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * mock 下游的故障注入控制面。V4 从 gateway 迁入。
 *
 * <p><b>为什么必须随 mock 走</b>：{@link FaultInjector} / {@link PayLedger} / {@link ProviderLedger}
 * 都是<b>进程内状态</b>（{@code ConcurrentHashMap} 与 volatile 字段）。V3 单进程时 gateway 与 mock 同处一个
 * JVM，控制面放哪都改得到；V4 拆开后 gateway 里已没有这些对象，隔进程调用改不到别人的堆。
 *
 * <p><b>路径保持 {@code /api/fault/**} 不变</b>：k6 脚本、集成测试、README 的演示序列都在用这些 URL。迁移改的是「谁来响应」，不是「怎么调」——
 * 单进程形态下仍由同一个进程响应，分布式形态下由 mock 服务响应，调用方无感。
 *
 * <p><b>两个签名端点没有一起迁过来</b>：它们的入参是 {@code PayCallbackReq} 与 {@code
 * ProviderCallbackReq}，分属玩法层与公共能力层。本模块是最下层，引那两个 {@code mp-api} 即依赖倒挂（《开发规范》§3.2）。故签名端点留在
 * gateway，它们只依赖 {@code mp-common} 的签名器，本就无归属矛盾。
 *
 * <p><b>V4 收口时本控制面必须下线或移入独立运维端口</b>（《分阶段方案》§6A.1 第 9 项）：能改变下游行为等于能伪造发放结果。迁到 mock 服务后风险面反而更清楚了 ——
 * 它现在和 mock 的业务端口在同一个进程里，收口时整体处理即可。
 */
@RestController
@RequestMapping("/api/fault")
public class MockFaultController {

    private final FaultInjector injector;
    private final ProviderLedger ledger;
    private final PayLedger payLedger;

    public MockFaultController(FaultInjector injector, ProviderLedger ledger, PayLedger payLedger) {
        this.injector = injector;
        this.ledger = ledger;
        this.payLedger = payLedger;
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
     * 它要靠账本里只有一条来证明。
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
