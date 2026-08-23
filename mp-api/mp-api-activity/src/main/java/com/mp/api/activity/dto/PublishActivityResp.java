package com.mp.api.activity.dto;

import java.io.Serializable;

/** 发布结果：新版本号 + 发布后状态。 */
public class PublishActivityResp implements Serializable {

    private String activityId;

    /** 本次发布产生的配置版本号，递增 */
    private int version;

    /** 发布后的活动状态，为 {@code SCHEDULED} */
    private String status;

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
