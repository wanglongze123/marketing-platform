package com.mp.api.benefit.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 收敛过程快照，按 {@code bizNo} 返回操作记录与可靠任务的当前值。
 *
 * <p><b>提供的是快照，序列由观察者累积</b>：任务每轮重试原地覆盖 {@code nextTime} / {@code retryCount}，
 * 表中任一时刻只有当前值，不存在「相邻两次」。测试每驱动一轮调度器读一次，序列存在测试变量里； 人工验证按固定间隔轮询记录（《分阶段方案》§5.4）。
 *
 * <p><b>不为序列建事件日志表</b>：那会让一张仅测试使用的表混进业务 schema，且每轮重试多一次 写放大。收敛历史的正式载体是 {@code
 * play_op_record.retry_count} 与对账。
 *
 * <p>V3 拆分布式后本响应会暴露跨服务的内部单据状态，届时需加鉴权或移入独立运维端口。
 */
public class ConvergenceResp implements Serializable {

    private String bizNo;

    /** 三子状态的当前值，便于与操作记录对照 */
    private String payStatus;

    private String grantStatus;
    private String refundStatus;

    private List<OpRecordSnapshot> opRecords;
    private List<TaskSnapshot> tasks;

    /** 操作记录快照。 */
    public static class OpRecordSnapshot implements Serializable {
        private String opType;
        private String opSeq;

        /** 本地执行态 */
        private String status;

        /** 下游四分类结果。与 status 分列 —— 合并即无法区分 PROCESSING 与 UNKNOWN */
        private String downstreamResult;

        private Integer retryCount;

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
    }

    /** 可靠任务快照：当前退避位置与租约持有者。 */
    public static class TaskSnapshot implements Serializable {
        private String taskType;
        private String opNo;
        private String status;
        private Integer retryCount;

        /** 下次执行时间，退避后推。相邻两轮之差即退避间隔 */
        private String nextTime;

        private String leaseOwner;
        private String leaseExpire;

        public String getTaskType() {
            return taskType;
        }

        public void setTaskType(String taskType) {
            this.taskType = taskType;
        }

        public String getOpNo() {
            return opNo;
        }

        public void setOpNo(String opNo) {
            this.opNo = opNo;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(Integer retryCount) {
            this.retryCount = retryCount;
        }

        public String getNextTime() {
            return nextTime;
        }

        public void setNextTime(String nextTime) {
            this.nextTime = nextTime;
        }

        public String getLeaseOwner() {
            return leaseOwner;
        }

        public void setLeaseOwner(String leaseOwner) {
            this.leaseOwner = leaseOwner;
        }

        public String getLeaseExpire() {
            return leaseExpire;
        }

        public void setLeaseExpire(String leaseExpire) {
            this.leaseExpire = leaseExpire;
        }
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public String getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(String grantStatus) {
        this.grantStatus = grantStatus;
    }

    public String getRefundStatus() {
        return refundStatus;
    }

    public void setRefundStatus(String refundStatus) {
        this.refundStatus = refundStatus;
    }

    public List<OpRecordSnapshot> getOpRecords() {
        return opRecords;
    }

    public void setOpRecords(List<OpRecordSnapshot> opRecords) {
        this.opRecords = opRecords;
    }

    public List<TaskSnapshot> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskSnapshot> tasks) {
        this.tasks = tasks;
    }
}
