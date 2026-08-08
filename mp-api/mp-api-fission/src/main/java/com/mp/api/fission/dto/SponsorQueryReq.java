package com.mp.api.fission.dto;

/**
 * 师傅进场（FR-F01）。
 *
 * <p>{@code activityId} 可选：给定则查该活动，为空则按 {@code scene} 路由。
 */
public class SponsorQueryReq {

    /** 师傅平台用户 ID */
    private String sponsorId;

    private String scene;

    /** 可选。为空时按 scene 路由 */
    private String activityId;

    /** 终端信息，进资格决策 */
    private String city;

    private String channel;

    private String deviceId;

    private String clientIp;

    public String getSponsorId() {
        return sponsorId;
    }

    public void setSponsorId(String sponsorId) {
        this.sponsorId = sponsorId;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }
}
