package com.mp.api.benefit.dto;

/**
 * 人工处置结果（FR-C07）。V3 PR-10 引入。
 *
 * <p>{@code reusedKey} 回报本次复用的原幂等键，供操作人核对——<b>它是「没有新造键」的可见证据</b>。 连点两次时两次返回同一个键，而账本里仍只有一条。
 */
public class ManualRepairResp {

    private boolean accepted;

    /** 本次复用的原幂等键。{@code MARK_DONE} / {@code EXPORT_EVIDENCE} 无此项 */
    private String reusedKey;

    /** 导出对账证据时的载荷；其余动作为空 */
    private String evidence;

    private String reasonCode;

    public static ManualRepairResp accepted(String reusedKey) {
        ManualRepairResp resp = new ManualRepairResp();
        resp.accepted = true;
        resp.reusedKey = reusedKey;
        return resp;
    }

    public static ManualRepairResp evidence(String payload) {
        ManualRepairResp resp = new ManualRepairResp();
        resp.accepted = true;
        resp.evidence = payload;
        return resp;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getReusedKey() {
        return reusedKey;
    }

    public void setReusedKey(String reusedKey) {
        this.reusedKey = reusedKey;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }
}
