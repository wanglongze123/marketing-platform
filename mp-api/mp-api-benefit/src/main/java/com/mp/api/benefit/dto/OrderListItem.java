package com.mp.api.benefit.dto;

import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import java.time.LocalDateTime;

/**
 * 订单列表行。
 *
 * <p><b>不含履约明细</b> —— 列表页只渲染主单信息，明细由详情接口按 bizNo 单查。列表里带明细会 让每行多一次关联查询，且列表本就不展示它。
 *
 * <p>三子状态各自返回，与 {@link QueryOrderResp} 一致：列表页同样需要区分「支付成功且退款中」 这类组合，合并成单一状态就表达不了。
 */
public class OrderListItem {

    private String bizNo;
    private String skuId;
    private String activityId;

    private PayStatus payStatus;
    private GrantStatus grantStatus;
    private RefundStatus refundStatus;

    private long orderAmount;
    private Long payAmount;
    private String tradeNo;

    private LocalDateTime createTime;

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public PayStatus getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(PayStatus payStatus) {
        this.payStatus = payStatus;
    }

    public GrantStatus getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(GrantStatus grantStatus) {
        this.grantStatus = grantStatus;
    }

    public RefundStatus getRefundStatus() {
        return refundStatus;
    }

    public void setRefundStatus(RefundStatus refundStatus) {
        this.refundStatus = refundStatus;
    }

    public long getOrderAmount() {
        return orderAmount;
    }

    public void setOrderAmount(long orderAmount) {
        this.orderAmount = orderAmount;
    }

    public Long getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(Long payAmount) {
        this.payAmount = payAmount;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
