package com.mp.api.reward.dto;

/**
 * 通知处理结果（ACK）。V3 PR-9 引入。
 *
 * <p><b>重传返回 {@code accepted=true}，不返回失败</b>：ACK 的语义是「我收到了，别再投了」。重传 返回失败会让供应方一直重投同一条通知 ——
 * 而它本就已经处理过了。{@code duplicated} 只用于观测与 断言，不改变 ACK 语义。
 *
 * <p><b>验签不过是唯一返回 {@code accepted=false} 的情形</b>：它意味着这条通知不可信，不能 ACK。
 */
public class ProviderCallbackResp {

    private boolean accepted;

    /** 本条通知此前已处理过。仍算受理，供观测与用例断言 */
    private boolean duplicated;

    private String errorCode;

    public static ProviderCallbackResp accepted(boolean duplicated) {
        ProviderCallbackResp resp = new ProviderCallbackResp();
        resp.accepted = true;
        resp.duplicated = duplicated;
        return resp;
    }

    public static ProviderCallbackResp rejected(String errorCode) {
        ProviderCallbackResp resp = new ProviderCallbackResp();
        resp.accepted = false;
        resp.errorCode = errorCode;
        return resp;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public boolean isDuplicated() {
        return duplicated;
    }

    public void setDuplicated(boolean duplicated) {
        this.duplicated = duplicated;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
