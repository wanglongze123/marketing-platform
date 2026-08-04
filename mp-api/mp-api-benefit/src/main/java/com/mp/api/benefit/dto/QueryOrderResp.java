package com.mp.api.benefit.dto;

import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import java.util.List;

/**
 * 订单查询出参。
 *
 * <p><b>三子状态各自返回，不合并成单一 biz_status</b> —— 展示态由三者派生、不落库， 因为「支付成功且退款中」这类组合无法由单一枚举表达。
 */
public class QueryOrderResp {

    private String bizNo;
    private String userId;
    private String activityId;
    private String skuId;

    private PayStatus payStatus;
    private GrantStatus grantStatus;
    private RefundStatus refundStatus;

    private long orderAmount;
    private Long payAmount;
    private String tradeNo;
    private int configVersion;

    private List<FulfillmentItem> fulfillments;

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
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

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public List<FulfillmentItem> getFulfillments() {
        return fulfillments;
    }

    public void setFulfillments(List<FulfillmentItem> fulfillments) {
        this.fulfillments = fulfillments;
    }
}
