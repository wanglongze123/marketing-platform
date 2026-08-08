package com.mp.api.fission.dto;

/**
 * 徒弟加入（FR-F06）。
 *
 * <p><b>{@code outBizNo} 与 {@code outFlowNo} 不天然相等</b>（BR-F-14、BR-F-15）：
 *
 * <ul>
 *   <li>{@code outBizNo} 标识<b>一次师徒关系</b>，须与后续完成操作携带的一致
 *   <li>{@code outFlowNo} 标识<b>本次操作</b>，用于幂等
 * </ul>
 *
 * <p>把两者当成一个字段，会让「同一关系的加入与完成」和「同一次调用的重传」共用一把键 —— 完成操作会被当成加入的重传而静默命中幂等。
 */
public class FollowerJoinReq {

    private String groupId;

    private String followerId;

    /** 上游业务号，标识一次师徒关系 */
    private String outBizNo;

    /** 上游流水号，标识本次操作，用于幂等 */
    private String outFlowNo;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getFollowerId() {
        return followerId;
    }

    public void setFollowerId(String followerId) {
        this.followerId = followerId;
    }

    public String getOutBizNo() {
        return outBizNo;
    }

    public void setOutBizNo(String outBizNo) {
        this.outBizNo = outBizNo;
    }

    public String getOutFlowNo() {
        return outFlowNo;
    }

    public void setOutFlowNo(String outFlowNo) {
        this.outFlowNo = outFlowNo;
    }
}
