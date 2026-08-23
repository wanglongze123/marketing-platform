package com.mp.api.benefit.dto;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** 支付结果通知入参。 */
public class PayCallbackReq implements Serializable {

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

    /**
     * 支付方签名（V2 PR-6b 引入）。<b>验签不过一律拒绝，且不更新任何业务状态</b>（BR-B-12）。
     *
     * <p>验签与金额校验挡的是两件事：验签证明「这条通知来自支付方」，金额校验证明「金额与本单应付 一致」。只做后者挡不住伪造 —— 伪造者只需先自己下一单，就知道该填多少钱。
     *
     * <p>本字段自身<b>不参与签名计算</b>：它就是计算结果。
     */
    private String sign;

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

    public String getSign() {
        return sign;
    }

    public void setSign(String sign) {
        this.sign = sign;
    }

    /**
     * 参与签名的字段集合。<b>签发侧与验签侧必须调用同一个方法</b>，否则两边的字段集合迟早漂移 —— 而漂移的表现是「验签一直失败」或更糟的「少签了某个字段而没人发现」。
     *
     * <p>{@code sign} 自身不在其中：它是计算结果。
     */
    public Map<String, String> signFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("outTradeNo", outTradeNo);
        fields.put("tradeNo", tradeNo);
        fields.put("notifySeq", notifySeq);
        fields.put("payStatus", payStatus);
        fields.put("payAmount", String.valueOf(payAmount));
        fields.put("currency", currency);
        fields.put("merchantId", merchantId);
        return fields;
    }
}
