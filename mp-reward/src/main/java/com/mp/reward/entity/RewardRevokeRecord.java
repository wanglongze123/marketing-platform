package com.mp.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 回收幂等记录。{@code uk_revoke_no} 保证同一 {@code revokeNo} 至多一条。
 *
 * <p><b>与 {@link RewardGrantRecord} 分表而非共用</b>：发奖与回收各有独立幂等键，且各自都要有唯一 索引承载（BR-C-11）。共用一张表意味着共用
 * {@code uk_op_no} —— 回收请求会撞上原发奖那一行被当成 「发奖重传」吞掉，权益实际没回收而调用方拿到「成功」。
 */
@TableName("reward_revoke_record")
public class RewardRevokeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 回收幂等键 */
    private String revokeNo;

    private String bizOrderNo;

    /** 被回收的原发奖操作单号 */
    private String opNo;

    private String receiverId;

    /** 回收当时供应方回传的真实使用态，与 {@code result} 分列 */
    private String usageStatus;

    /** SUCCESS/FAIL/PROCESSING/UNKNOWN */
    private String result;

    private String providerOrderNo;

    private String errorCode;

    /** 映射完整而保留，不做乐观锁 —— 理由同 {@link RewardGrantRecord#getVersion()} */
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRevokeNo() {
        return revokeNo;
    }

    public void setRevokeNo(String revokeNo) {
        this.revokeNo = revokeNo;
    }

    public String getBizOrderNo() {
        return bizOrderNo;
    }

    public void setBizOrderNo(String bizOrderNo) {
        this.bizOrderNo = bizOrderNo;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public String getUsageStatus() {
        return usageStatus;
    }

    public void setUsageStatus(String usageStatus) {
        this.usageStatus = usageStatus;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getProviderOrderNo() {
        return providerOrderNo;
    }

    public void setProviderOrderNo(String providerOrderNo) {
        this.providerOrderNo = providerOrderNo;
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
