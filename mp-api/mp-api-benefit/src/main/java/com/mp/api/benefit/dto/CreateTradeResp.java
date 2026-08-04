package com.mp.api.benefit.dto;

/** 下单出参。 */
public class CreateTradeResp {

    /** 业务主单号，同时作为支付侧的商户订单号 */
    private String bizNo;

    /** 支付单号，由支付方返回后回填 */
    private String tradeNo;

    private String payStatus;

    /** 应付金额，分 */
    private long orderAmount;

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public long getOrderAmount() {
        return orderAmount;
    }

    public void setOrderAmount(long orderAmount) {
        this.orderAmount = orderAmount;
    }
}
