package com.mp.fission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 权益可靠任务（本地消息表）。
 *
 * <p>任务与触发它的业务状态变更同库同事务 —— 这是「已收款必履约」的根：ACK 支付前 {@code GRANT} 任务已落库，进程在此后任何一点崩溃，调度器重启后续跑。若改用消息队列，
 * 「改状态」与「发消息」跨两个系统，中间的崩溃窗口无法消除。
 *
 * <p>{@code opNo} 在任务建立时固化，重试只读不重生成 —— 重发时携带同一幂等键，被下游挡住， 这正是「幂等键复用」承担的兜底。重新派生等于每次重试都是一笔新操作。
 */
@TableName("fission_task")
public class FissionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    /** 关联业务号，取 {@code play_biz_record_no} */
    private String bizNo;

    private String taskType;

    /** 关联操作单号。无下游单的任务用确定性本地键填充，不留空 —— 唯一索引不对 NULL 去重 */
    private String opNo;

    /** PENDING/DOING/DONE/DEAD */
    private String status;

    /** 下次执行时间，退避后推 */
    private LocalDateTime nextTime;

    private Integer retryCount;

    /** 当前持有实例标识。写回时作 fencing 条件，防止过期持有者覆盖接管者的结果 */
    private String leaseOwner;

    /** 租约到期时间，过期即可被其他实例接管 */
    private LocalDateTime leaseExpire;

    private String payload;

    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskNo() {
        return taskNo;
    }

    public void setTaskNo(String taskNo) {
        this.taskNo = taskNo;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

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

    public LocalDateTime getNextTime() {
        return nextTime;
    }

    public void setNextTime(LocalDateTime nextTime) {
        this.nextTime = nextTime;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public LocalDateTime getLeaseExpire() {
        return leaseExpire;
    }

    public void setLeaseExpire(LocalDateTime leaseExpire) {
        this.leaseExpire = leaseExpire;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
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
