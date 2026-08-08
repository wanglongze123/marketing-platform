package com.mp.fission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 裂变关系（师徒关系单据）。膨胀表，需过期治理。 */
@TableName("fission_relation")
public class FissionRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String relationId;
    private String groupId;
    private String activityId;
    private String sponsorId;
    private String followerId;

    /** 上游业务号；INVITED 阶段尚无值，建联/加入时条件回填 */
    private String outBizNo;

    /** 非终态恒为 ACTIVE；进终态时置为 relation_id 以释放唯一性 */
    private String activeFlag;

    private String status;

    /** 发奖在途豁免截止时间：NULL=不在途 */
    private LocalDateTime grantingUntil;

    private String rewardSnapshot;
    private String shareMethod;
    private String opNo;
    private LocalDateTime expireTime;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRelationId() {
        return relationId;
    }

    public void setRelationId(String relationId) {
        this.relationId = relationId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSponsorId() {
        return sponsorId;
    }

    public void setSponsorId(String sponsorId) {
        this.sponsorId = sponsorId;
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

    public String getActiveFlag() {
        return activeFlag;
    }

    public void setActiveFlag(String activeFlag) {
        this.activeFlag = activeFlag;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getGrantingUntil() {
        return grantingUntil;
    }

    public void setGrantingUntil(LocalDateTime grantingUntil) {
        this.grantingUntil = grantingUntil;
    }

    public String getRewardSnapshot() {
        return rewardSnapshot;
    }

    public void setRewardSnapshot(String rewardSnapshot) {
        this.rewardSnapshot = rewardSnapshot;
    }

    public String getShareMethod() {
        return shareMethod;
    }

    public void setShareMethod(String shareMethod) {
        this.shareMethod = shareMethod;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
