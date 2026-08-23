package com.mp.api.reward.dto;

import com.mp.common.enums.RetStatus;
import java.io.Serializable;

/** 单个奖励项的发放结果。每项独立四分类，不因整体成功而掩盖单项失败。 */
public class GrantItemResult implements Serializable {

    private int itemSeq;

    /** 该项独立的四分类结果 */
    private RetStatus retStatus;

    /** 供应方返回的下游单号，支撑按下游单号反查业务 */
    private String providerOrderNo;

    private String errorCode;

    public int getItemSeq() {
        return itemSeq;
    }

    public void setItemSeq(int itemSeq) {
        this.itemSeq = itemSeq;
    }

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
