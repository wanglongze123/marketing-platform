package com.mp.gateway.controller;

import com.mp.api.mock.dto.FaultMode;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.ProviderLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public FaultInjectionController(FaultInjector injector, ProviderLedger ledger) {
        this.injector = injector;
        this.ledger = ledger;
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
     * <p><b>不清账本</b>：账本是「下游已发放」的事实记录，演示中途清掉会让「无重复发放」的断言 失去依据。清账本单独走 {@code /ledger}，且只在开始一轮新演示时用。
     */
    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset() {
        injector.reset();
        return ok(snapshot());
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
