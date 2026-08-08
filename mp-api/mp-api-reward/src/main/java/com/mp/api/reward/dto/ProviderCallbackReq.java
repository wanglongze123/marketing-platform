package com.mp.api.reward.dto;

import com.mp.common.enums.RetStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应方异步通知报文（FR-B06、技术方案 §4.3）。V3 PR-9 引入。
 *
 * <p><b>{@code notifySeq} 是幂等的第二维，不能省</b>：同一个 {@code opNo} 会收到多条语义不同的通知 （先受理后成功、供应方补发），只按 {@code
 * opNo} 去重会把第二条真实通知当成重传丢弃。判「这是不是 同一条通知」的依据是流水号。
 *
 * <p><b>验签字段集合与 {@link #signFields()} 一致</b>：签名覆盖全部业务字段而非只签结果 —— 只签结果 则攻击者可以拿一条真实的成功通知改掉 {@code
 * opNo}，把 A 单的发放算到 B 单头上，结果没变、签名照样对。 这与支付通知验签是同一处置（{@code PayNotifySigner} 的注释）。
 */
public class ProviderCallbackReq {

    /** 发奖幂等键，通知据它定位原发放 */
    private String opNo;

    /** 供应方通知流水，重传保持不变。幂等的第二维 */
    private String notifySeq;

    /** 本条通知携带的结果。{@code UNKNOWN} 无意义 —— 供应方不会通知「我也不知道」 */
    private RetStatus result;

    private String providerOrderNo;

    private String errorCode;

    /** 供应方签名 */
    private String sign;

    /**
     * 参与签名的字段。
     *
     * <p><b>{@code sign} 自身不入签</b>，其余业务字段全入。空值统一签成空串，而不是跳过该字段 —— 跳过会让「字段缺失」与「字段为空」签出同一个值，攻击者可借此删字段。
     */
    public Map<String, String> signFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("opNo", opNo == null ? "" : opNo);
        fields.put("notifySeq", notifySeq == null ? "" : notifySeq);
        fields.put("result", result == null ? "" : result.name());
        fields.put("providerOrderNo", providerOrderNo == null ? "" : providerOrderNo);
        fields.put("errorCode", errorCode == null ? "" : errorCode);
        return fields;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getNotifySeq() {
        return notifySeq;
    }

    public void setNotifySeq(String notifySeq) {
        this.notifySeq = notifySeq;
    }

    public RetStatus getResult() {
        return result;
    }

    public void setResult(RetStatus result) {
        this.result = result;
    }

    public String getProviderOrderNo() {
        return providerOrderNo;
    }

    public void setProviderOrderNo(String providerOrderNo) {
        this.providerOrderNo = providerOrderNo;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getSign() {
        return sign;
    }

    public void setSign(String sign) {
        this.sign = sign;
    }
}
