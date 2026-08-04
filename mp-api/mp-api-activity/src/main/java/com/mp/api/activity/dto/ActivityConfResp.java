package com.mp.api.activity.dto;

/** 活动配置。V1 直接查库，V3 加本地缓存 + 版本快照。 */
public class ActivityConfResp {

    private String activityId;
    private String name;
    private String playType;
    private String scene;
    private String status;

    /** 当前发布配置版本。下单时冻结进主单 config_version，履约与退款一律读快照 */
    private int curVersion;

    /** 活动是否在可参与时间窗内且状态为 ONLINE */
    private boolean available;

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlayType() {
        return playType;
    }

    public void setPlayType(String playType) {
        this.playType = playType;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getCurVersion() {
        return curVersion;
    }

    public void setCurVersion(int curVersion) {
        this.curVersion = curVersion;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }
}
