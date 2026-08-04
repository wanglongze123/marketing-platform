package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 权益操作记录，与主单状态变更同事务。
 *
 * <p>两道唯一索引各挡一类重入：{@code uk_idempotent} 挡同一幂等键发两次； {@code uk_biz_op(bizNo, opType, opSeq)}
 * 挡同一业务语义生成了两个不同的键。
 */
@TableName("play_op_record")
public class PlayOpRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String opNo;
    private String idempotentKey;
    private String playBizRecordNo;

    /** 操作主体用户 ID */
    private String subjectId;

    private String activityId;
    private String opType;

    /** 至多一次的操作恒空串；可多次的取外部单号。严禁内部自增（《开发规范》§5.6） */
    private String opSeq;

    /** 本地执行态 INIT/PROCESSING/SUCCESS/FAILED/UNKNOWN */
    private String status;

    /** 下游四分类原值 SUCCESS/FAIL/PROCESSING/UNKNOWN。与 status 分列 */
    private String downstreamResult;

    private String parentOpNo;
    private String outOrderNo;
    private String recoverContext;
    private String errorCode;
    private Integer retryCount;
    private String reqDigest;
    private String respDigest;
    private String operator;
    private String reason;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime finishTime;

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

    public String getIdempotentKey() {
        return idempotentKey;
    }

    public void setIdempotentKey(String idempotentKey) {
        this.idempotentKey = idempotentKey;
    }

    public String getPlayBizRecordNo() {
        return playBizRecordNo;
    }

    public void setPlayBizRecordNo(String playBizRecordNo) {
        this.playBizRecordNo = playBizRecordNo;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getOpType() {
        return opType;
    }

    public void setOpType(String opType) {
        this.opType = opType;
    }

    public String getOpSeq() {
        return opSeq;
    }

    public void setOpSeq(String opSeq) {
        this.opSeq = opSeq;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDownstreamResult() {
        return downstreamResult;
    }

    public void setDownstreamResult(String downstreamResult) {
        this.downstreamResult = downstreamResult;
    }

    public String getParentOpNo() {
        return parentOpNo;
    }

    public void setParentOpNo(String parentOpNo) {
        this.parentOpNo = parentOpNo;
    }

    public String getOutOrderNo() {
        return outOrderNo;
    }

    public void setOutOrderNo(String outOrderNo) {
        this.outOrderNo = outOrderNo;
    }

    public String getRecoverContext() {
        return recoverContext;
    }

    public void setRecoverContext(String recoverContext) {
        this.recoverContext = recoverContext;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getReqDigest() {
        return reqDigest;
    }

    public void setReqDigest(String reqDigest) {
        this.reqDigest = reqDigest;
    }

    public String getRespDigest() {
        return respDigest;
    }

    public void setRespDigest(String respDigest) {
        this.respDigest = respDigest;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }
}
