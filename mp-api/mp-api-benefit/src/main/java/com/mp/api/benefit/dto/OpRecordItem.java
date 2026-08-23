package com.mp.api.benefit.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作记录行，供排查用的时间线展示。
 *
 * <p><b>{@code status} 与 {@code downstreamResult} 分列返回，不合并</b> —— 前者是本地执行态 （{@code OpStatus}:
 * INIT/PROCESSING/SUCCESS/FAILED/UNKNOWN），后者是下游四分类原值 （{@code RetStatus}:
 * SUCCESS/FAIL/PROCESSING/UNKNOWN）。两者刻意拼写不同（FAILED vs FAIL），
 * 合并展示会掩盖「本地记为失败、下游实际成功」这类差异，而那正是排查时要找的东西。
 *
 * <p>字段类型用 String 而非枚举：本接口是排查视图，需要如实回显库里的值。若库中因历史数据 存在枚举外的取值，用枚举反序列化会直接抛异常，反而看不到问题。
 */
public class OpRecordItem implements Serializable {

    private String opNo;
    private String opType;

    /** 至多一次的操作恒空串；可多次的取外部单号 */
    private String opSeq;

    /** 本地执行态，取值见 {@code OpStatus} */
    private String status;

    /** 下游四分类原值，取值见 {@code RetStatus}。与 {@code status} 分列 */
    private String downstreamResult;

    private Integer retryCount;
    private String errorCode;
    private String outOrderNo;
    private String parentOpNo;

    private LocalDateTime createTime;
    private LocalDateTime finishTime;

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
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

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getOutOrderNo() {
        return outOrderNo;
    }

    public void setOutOrderNo(String outOrderNo) {
        this.outOrderNo = outOrderNo;
    }

    public String getParentOpNo() {
        return parentOpNo;
    }

    public void setParentOpNo(String parentOpNo) {
        this.parentOpNo = parentOpNo;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }
}
