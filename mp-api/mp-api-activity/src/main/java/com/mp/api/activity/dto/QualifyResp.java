package com.mp.api.activity.dto;

import java.io.Serializable;

/**
 * 资格决策结果（FR-C02）。
 *
 * <p><b>{@code reasonCode} 必填，且必须区分「不符合条件」与「系统异常」</b>（BR-C-07）。只返回一个
 * 布尔值时，风控依赖挂掉与用户确实不符合条件在调用方看来完全一样 —— 前者该重试、该告警，后者不该。
 *
 * <p><b>咨询阶段通过不代表下单通过</b>（BR-C-08）：本接口只读、无副作用，结果不构成对后续下单的 承诺，创建单据时须重新校验关键条件。
 */
public class QualifyResp implements Serializable {

    private boolean pass;

    /** 标准原因码，取 {@code QualifyReason} 的枚举名 */
    private String reasonCode;

    /** 错误码：通过为空；业务拒绝 {@code 1201}；依赖异常 {@code 5201} */
    private String errorCode;

    /** 服务端解析出的城市，供调用方复用，避免各自再解析一遍 */
    private String resolvedCity;

    /** 命中的活动号。按场景路由时，调用方据此知道选中了哪个活动 */
    private String activityId;

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getResolvedCity() {
        return resolvedCity;
    }

    public void setResolvedCity(String resolvedCity) {
        this.resolvedCity = resolvedCity;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }
}
