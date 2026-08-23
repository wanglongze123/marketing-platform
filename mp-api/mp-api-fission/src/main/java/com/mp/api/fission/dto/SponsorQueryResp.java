package com.mp.api.fission.dto;

import java.io.Serializable;

/**
 * 师傅进场结果（FR-F01）。
 *
 * <p><b>无可参与活动是正常业务结果</b>（BR-F-01）：{@code available=false} + {@code reasonCode}，
 * 与系统异常使用不同响应码。把它做成异常会让「这个用户今天没活动可参与」和「资格服务挂了」 在调用方看来一样。
 */
public class SponsorQueryResp implements Serializable {

    /** 是否有可参与活动 */
    private boolean available;

    /** 不可参与时的原因码，取自资格决策 */
    private String reasonCode;

    private String activityId;

    /** 裂变组号。手动开轮的活动在此为空 —— 未开轮不是错误（BR-F-03） */
    private String groupId;

    private Integer roundNo;

    /** 当前轮次进度 */
    private Integer progress;

    private Integer targetCount;

    /** 邀请凭证，后续接口据此恢复师傅身份与裂变组 */
    private String inviteToken;

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(Integer targetCount) {
        this.targetCount = targetCount;
    }

    public String getInviteToken() {
        return inviteToken;
    }

    public void setInviteToken(String inviteToken) {
        this.inviteToken = inviteToken;
    }
}
