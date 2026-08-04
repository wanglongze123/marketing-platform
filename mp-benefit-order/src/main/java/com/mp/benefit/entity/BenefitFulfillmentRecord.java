package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 履约明细，按权益项一项一行。{@code uk_biz_item} 保证同单同项至多一条。 */
@TableName("benefit_fulfillment_record")
public class BenefitFulfillmentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fulfillmentNo;
    private String playBizRecordNo;
    private String benefitItemId;
    private String providerType;
    private String providerProductId;
    private String providerOrderNo;

    /** 发奖幂等键，确定性派生 bizNo + "_G_" + providerType */
    private String grantOpNo;

    /** 单项发放态 NOT_START/GRANTING/SUCCESS/FAILED/UNKNOWN，不带 GRANT_ 前缀 */
    private String grantStatus;

    private String revokeNo;
    private String revokeStatus;
    private LocalDateTime revokeTime;
    private String usageStatus;
    private LocalDateTime lastSyncTime;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private String errorCode;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFulfillmentNo() {
        return fulfillmentNo;
    }

    public void setFulfillmentNo(String fulfillmentNo) {
        this.fulfillmentNo = fulfillmentNo;
    }

    public String getPlayBizRecordNo() {
        return playBizRecordNo;
    }

    public void setPlayBizRecordNo(String playBizRecordNo) {
        this.playBizRecordNo = playBizRecordNo;
    }

    public String getBenefitItemId() {
        return benefitItemId;
    }

    public void setBenefitItemId(String benefitItemId) {
        this.benefitItemId = benefitItemId;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getProviderProductId() {
        return providerProductId;
    }

    public void setProviderProductId(String providerProductId) {
        this.providerProductId = providerProductId;
    }

    public String getProviderOrderNo() {
        return providerOrderNo;
    }

    public void setProviderOrderNo(String providerOrderNo) {
        this.providerOrderNo = providerOrderNo;
    }

    public String getGrantOpNo() {
        return grantOpNo;
    }

    public void setGrantOpNo(String grantOpNo) {
        this.grantOpNo = grantOpNo;
    }

    public String getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(String grantStatus) {
        this.grantStatus = grantStatus;
    }

    public String getRevokeNo() {
        return revokeNo;
    }

    public void setRevokeNo(String revokeNo) {
        this.revokeNo = revokeNo;
    }

    public String getRevokeStatus() {
        return revokeStatus;
    }

    public void setRevokeStatus(String revokeStatus) {
        this.revokeStatus = revokeStatus;
    }

    public LocalDateTime getRevokeTime() {
        return revokeTime;
    }

    public void setRevokeTime(LocalDateTime revokeTime) {
        this.revokeTime = revokeTime;
    }

    public String getUsageStatus() {
        return usageStatus;
    }

    public void setUsageStatus(String usageStatus) {
        this.usageStatus = usageStatus;
    }

    public LocalDateTime getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(LocalDateTime lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public LocalDateTime getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(LocalDateTime beginTime) {
        this.beginTime = beginTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
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
