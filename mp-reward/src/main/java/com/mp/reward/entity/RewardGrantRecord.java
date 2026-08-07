package com.mp.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 发奖幂等记录主表。{@code uk_op_no} 保证同一 opNo 至多一条。 */
@TableName("reward_grant_record")
public class RewardGrantRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调用方操作单号 = 幂等键 */
    private String opNo;

    /** 调用方业务单号，支撑按业务单查发奖 */
    private String bizOrderNo;

    private String playType;
    private String activityId;
    private String receiverId;

    /** 汇总结果 SUCCESS/FAIL/PROCESSING/UNKNOWN */
    private String result;

    private String errorCode;

    /**
     * 表中的 {@code version} 列，<b>当前不做乐观锁</b>，仅为映射完整而保留。理由同 {@code PlayBizRecord.version}：
     * 未注册拦截器故注解不生效，且终态回写走 {@code finishIfProcessing} 的条件更新，不用 {@code updateById}。
     */
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getBizOrderNo() {
        return bizOrderNo;
    }

    public void setBizOrderNo(String bizOrderNo) {
        this.bizOrderNo = bizOrderNo;
    }

    public String getPlayType() {
        return playType;
    }

    public void setPlayType(String playType) {
        this.playType = playType;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
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
