package com.mp.api.benefit.dto;

/** 支付结果通知入参。 */
public class PayCallbackReq {

    /**
     * 商户订单号（= bizNo）。<b>回调据此定位主单，不用 tradeNo</b>。
     *
     * <p>tradeNo 在「支付下单成功但回填前进程崩溃」的窗口内可能仍为 NULL， 此时按 tradeNo 定位会找不到业务单 —— 钱收了，订单还停在 WAIT_PAY。
     */
    private String outTradeNo;

    /** 支付侧单号，用于回填与对账，不是定位依据 */
    private String tradeNo;

    /** 通知流水号，回调携带、重传时不变。参与幂等键 tradeNo + "_" + notifySeq */
    private String notifySeq;

    /**
     * V1 仅接受 SUCCESS / FAILED。<b>不入幂等键</b> —— 入了键，「先到 SUCCESS、 后到
     * CLOSED」的乱序通知两条都能插入，第二条会把已支付订单关闭。乱序由主单条件更新拦截。
     */
    private String payStatus;

    /** 实付金额，分 */
    private long payAmount;

    private String currency;
    private String merchantId;

    public String getOutTradeNo() {
        return outTradeNo;
    }

    public void setOutTradeNo(String outTradeNo) {
        this.outTradeNo = outTradeNo;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public String getNotifySeq() {
        return notifySeq;
    }

    public void setNotifySeq(String notifySeq) {
        this.notifySeq = notifySeq;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public long getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(long payAmount) {
        this.payAmount = payAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }
}
