package com.mp.api.fission.dto;

/**
 * 徒弟完成，触发双向发奖（FR-F07）。
 *
 * <p>{@code outBizNo} 须与加入时携带的一致（BR-F-14）—— 它标识这一次师徒关系；{@code outFlowNo} 标识本次确权，两把发奖幂等键由它同源派生。
 */
public class FollowerDoneReq {

    private String groupId;

    private String followerId;

    /** 上游业务号，须与加入时一致 */
    private String outBizNo;

    /** 上游流水号，标识本次确权。徒弟发奖与师傅返奖的幂等键由它派生 */
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
