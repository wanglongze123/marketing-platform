package com.mp.api.mock.dto;

import com.mp.common.enums.RetStatus;

/**
 * mock 供应方回收出参。
 *
 * <p><b>{@code usageStatus} 与 {@code retStatus} 分列</b>：前者是「这张券现在什么状态」，后者是
 * 「这次回收操作成没成」。已核销的券回收失败，两个字段分别是 {@code USED} 与 {@code FAIL} ——
 * 合成一个的话，调用方无从区分「回收失败因为已用掉」与「回收失败因为供应方报错」，而前者 不该重试、后者该。
 */
public class ProviderRevokeResp {

    private RetStatus retStatus;

    /** 回收时该权益的真实状态，由供应方原子判定 */
    private String usageStatus;

    private String providerOrderNo;

    private String errorCode;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getUsageStatus() {
        return usageStatus;
    }

    public void setUsageStatus(String usageStatus) {
        this.usageStatus = usageStatus;
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
}
