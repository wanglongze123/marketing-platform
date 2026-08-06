package com.mp.gateway.controller;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.PreConsultReq;
import com.mp.api.benefit.dto.PreConsultResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.common.enums.RetStatus;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权益售卖对外端点。
 *
 * <p>对外输出 {@code {code, message, data, traceId}}，<b>四分类语义不暴露给端</b>。
 *
 * <p>{@code grantBenefit} 不对外暴露 —— 它是内部编排入口，V1 由 payCallback 调用， V2 由任务调度器驱动。
 */
@RestController
@RequestMapping("/api/benefit")
public class BenefitOrderController {

    @Autowired private BenefitOrderService benefitOrderService;

    /**
     * 预咨询：试算 + 签发咨询凭证。只读，无业务单据副作用。
     *
     * <p>端上必须先调本端点再调 {@code /trade} —— 后者要求携带凭证，无凭证一律 {@code 4003}。
     */
    @PostMapping("/consult")
    public ApiResponse<PreConsultResp> preConsult(@RequestBody PreConsultReq req) {
        return ok(benefitOrderService.preConsult(req));
    }

    @PostMapping("/trade")
    public ApiResponse<CreateTradeResp> createTrade(@RequestBody CreateTradeReq req) {
        return ok(benefitOrderService.createTrade(req));
    }

    /**
     * 支付结果通知。
     *
     * <p><b>PROCESSING / UNKNOWN 不向端返回失败</b>：结果未定时告诉用户「失败」，用户会重试， 形成重复下单。仅在 data.status 标记处理中。
     */
    @PostMapping("/pay-callback")
    public ApiResponse<Map<String, Object>> payCallback(@RequestBody PayCallbackReq req) {
        RetStatus status = benefitOrderService.payCallback(req);
        return ok(Map.of("status", status.name()));
    }

    /**
     * 关闭订单（用户取消 / 运营清理）。超时关闭由 {@code CLOSE_ORDER} 任务触发，不走本端点。
     *
     * <p>已支付的单返回 {@code 1741} 拒绝关闭（BR-B-16）；关单结果未定时进 {@code CLOSING} 并落查单任务， 端上据此提示「处理中」而非「已关闭」——
     * 后者会让用户以为钱不会被扣。
     */
    @PostMapping("/close/{bizNo}")
    public ApiResponse<Map<String, Object>> closeOrder(@PathVariable String bizNo) {
        RetStatus status = benefitOrderService.closeOrder(bizNo, "");
        return ok(Map.of("status", status.name()));
    }

    @GetMapping("/order/{bizNo}")
    public ApiResponse<QueryOrderResp> queryOrder(@PathVariable String bizNo) {
        return ok(benefitOrderService.queryOrder(bizNo));
    }

    /**
     * 收敛过程快照：操作记录 + 可靠任务当前值。
     *
     * <p>验收对象是状态迁移过程，{@code queryOrder} 的终态无法区分「正确收敛」与「未发生故障」。 同时是演示入口：注入超时 → 观察 {@code
     * GRANT_UNKNOWN} 停留 → 退避收敛 → 发放记录仍为 1 条。
     *
     * <p><b>V2 不加鉴权</b>：单进程、仅本地运行。V3 拆分布式后它会暴露跨服务的内部单据状态， 届时移入独立运维端口（《分阶段方案》§5.6 ⑥）。
     */
    @GetMapping("/convergence/{bizNo}")
    public ApiResponse<ConvergenceResp> queryConvergence(@PathVariable String bizNo) {
        return ok(benefitOrderService.queryConvergence(bizNo));
    }

    private static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
