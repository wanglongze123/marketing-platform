package com.mp.gateway.controller;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.OpRecordItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.QueryOrderPageReq;
import com.mp.api.benefit.dto.QueryOrderPageResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.QuerySkuResp;
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

    @GetMapping("/order/{bizNo}")
    public ApiResponse<QueryOrderResp> queryOrder(@PathVariable String bizNo) {
        return ok(benefitOrderService.queryOrder(bizNo));
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

    private static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
