package com.mp.api.mock.dto;

import com.mp.common.enums.RetStatus;
import java.io.Serializable;

/** mock 支付下单出参。结构为四分类形状，V2 加注入开关时只改实现不改契约。 */
public class PayCreateResp implements Serializable {

    private RetStatus retStatus;
    private String tradeNo;
    private String errorCode;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
