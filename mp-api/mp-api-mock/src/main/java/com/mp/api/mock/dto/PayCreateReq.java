package com.mp.api.mock.dto;

/** mock 支付下单入参。 */
public class PayCreateReq {

    /** 商户订单号（= bizNo），支付方回调时原样带回 */
    private String outTradeNo;

    private long amount;
    private String currency;
    private String userId;

    public String getOutTradeNo() {
        return outTradeNo;
    }

    public void setOutTradeNo(String outTradeNo) {
        this.outTradeNo = outTradeNo;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
