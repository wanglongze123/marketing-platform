package com.mp.gateway.controller;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.ManualRepairReq;
import com.mp.api.benefit.dto.ManualRepairResp;
import com.mp.api.benefit.dto.OpRecordItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.PreConsultReq;
import com.mp.api.benefit.dto.PreConsultResp;
import com.mp.api.benefit.dto.QueryOrderPageReq;
import com.mp.api.benefit.dto.QueryOrderPageResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.QuerySkuResp;
import com.mp.api.benefit.dto.ReconcileReport;
import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.common.enums.RetStatus;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    // ------------------------------------------------------------------
    // 只读查询。无副作用，故用 GET 且不携带幂等键。
    // ------------------------------------------------------------------

    /**
     * 订单列表。
     *
     * <p>路径用复数 {@code /orders} 与单查的 {@code /order/{bizNo}} 区分，避免 {@code /order} 同时承载 两种语义。
     */
    @GetMapping("/orders")
    public ApiResponse<QueryOrderPageResp> queryOrders(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String activityId,
            @RequestParam(required = false) String payStatus,
            @RequestParam(required = false) String grantStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        QueryOrderPageReq req = new QueryOrderPageReq();
        req.setUserId(userId);
        req.setActivityId(activityId);
        req.setPayStatus(payStatus);
        req.setGrantStatus(grantStatus);
        req.setPage(page);
        req.setSize(size);
        return ok(benefitOrderService.queryOrderPage(req));
    }

    /** 商品详情。端侧据此渲染商品页，不必硬编码 seed 数据。 */
    @GetMapping("/sku/{skuId}")
    public ApiResponse<QuerySkuResp> querySku(@PathVariable String skuId) {
        return ok(benefitOrderService.querySku(skuId));
    }

    /** 某单的操作记录时间线，排查用。 */
    @GetMapping("/order/{bizNo}/op-records")
    public ApiResponse<List<OpRecordItem>> queryOpRecords(@PathVariable String bizNo) {
        return ok(benefitOrderService.queryOpRecords(bizNo));
    }

    // ------------------------------------------------------------------
    // 运维设施。有副作用，故用 POST。
    // ------------------------------------------------------------------

    /**
     * 跑一轮对账（FR-C06）。V3 PR-10。
     *
     * <p>演示入口：人为改库制造差异 → 调本端点 → 观察差异被检出、可自愈的项补建了任务、只告警的项 出现在报告里但未改数。
     *
     * <p><b>与 {@code /api/fault/**} 同属运维设施，V3 交付前须移入独立端口或加鉴权</b>：它能触发补建 任务，即能改变业务链路的走向。
     */
    @PostMapping("/reconcile")
    public ApiResponse<ReconcileReport> reconcile() {
        return ok(benefitOrderService.reconcile());
    }

    /**
     * 人工处置（FR-C07）。V3 PR-10。
     *
     * <p><b>{@code operator} / {@code reason} / {@code ticketNo} 必填</b>，由服务层校验后落审计（BR-C-27）。
     *
     * <p>同样属运维设施：它是绕过自动链路的写入口，交付前须加鉴权 —— 无鉴权的「标记人工完成」等于 任何人都能把一笔未收敛的单标成成功。
     */
    @PostMapping("/manual-repair")
    public ApiResponse<ManualRepairResp> manualRepair(@RequestBody ManualRepairReq req) {
        return ok(benefitOrderService.manualRepair(req));
    }

    private static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
