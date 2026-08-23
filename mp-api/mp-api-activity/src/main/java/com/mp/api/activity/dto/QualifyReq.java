package com.mp.api.activity.dto;

import java.io.Serializable;

/**
 * 资格决策入参（FR-C02）。
 *
 * <p><b>客户端上传的城市、身份、持有状态仅作提示</b>（BR-C-06）：最终以服务端上下文为准。本 DTO 里的 {@code city} / {@code channel}
 * 是客户端声明值，实现侧须以服务端解析结果覆盖 —— 否则用户改一个 请求字段就能绕开城市限制。
 */
public class QualifyReq implements Serializable {

    private String userId;

    private String activityId;

    /** 场景，activityId 为空时按场景路由 */
    private String scene;

    /** 客户端声明的城市，仅作提示 */
    private String city;

    /** 渠道 */
    private String channel;

    /** 设备标识 */
    private String deviceId;

    /** 客户端 IP */
    private String clientIp;

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

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
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
