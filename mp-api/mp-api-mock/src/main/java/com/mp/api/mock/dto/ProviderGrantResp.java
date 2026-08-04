package com.mp.api.mock.dto;

import com.mp.common.enums.RetStatus;

/**
 * mock 供应方发放出参。
 *
 * <p><b>结构必须是四分类形状</b>，即使 V1 只会返回 SUCCESS —— V2 给 mock 加注入开关时只改实现不改契约。
 */
public class ProviderGrantResp {

    private RetStatus retStatus;

    /** 供应方发放单号 */
    private String providerOrderNo;

    private String errorCode;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
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
