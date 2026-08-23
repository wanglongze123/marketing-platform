package com.mp.api.benefit.dto;

import java.io.Serializable;

/** 预咨询入参。只读，无业务单据副作用。 */
public class PreConsultReq implements Serializable {

    private String userId;
    private String activityId;
    private String skuId;

    // V3 加：ReqTerminalInfo（城市、渠道）、RiskInfo。二者是凭证的绑定项（PRD FR-B01），
    // 加字段时须一并进签名 —— 进了签名，旧凭证在新版本下自然验签失败，无需另做版本迁移

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }
}
