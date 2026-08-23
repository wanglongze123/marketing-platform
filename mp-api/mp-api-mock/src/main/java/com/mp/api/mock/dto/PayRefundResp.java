package com.mp.api.mock.dto;

import com.mp.common.enums.RetStatus;
import java.io.Serializable;

/**
 * mock 支付方退款出参。
 *
 * <p>四分类形状，与发放/回收一致：{@code UNKNOWN} 表示「不知道退没退」，调用方须查单收敛而非 重发 —— 重发一笔可能已成功的退款就是重复退款。
 */
public class PayRefundResp implements Serializable {

    private RetStatus retStatus;

    /** 支付方退款单号 */
    private String refundOrderNo;

    private String errorCode;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getRefundOrderNo() {
        return refundOrderNo;
    }

    public void setRefundOrderNo(String refundOrderNo) {
        this.refundOrderNo = refundOrderNo;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
