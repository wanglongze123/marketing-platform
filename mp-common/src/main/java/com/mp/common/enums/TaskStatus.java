package com.mp.common.enums;

/**
 * 可靠任务的生命周期，落 {@code *_task.status}。
 *
 * <p>{@code PENDING → DOING → DONE}，失败则回 {@code PENDING} 并退避，超阈进 {@code DEAD}。
 *
 * <p><b>没有 FAILED</b>：单次执行失败不是终态，任务回到 {@code PENDING} 等下一轮。只有超过死信阈值 才停止重试 ——
 * 用「失败」表达可重试状态会让调度扫描漏掉它们，任务从此无人问津。
 */
public enum TaskStatus {

    /** 待执行，由 {@code idx_sched(status, next_time)} 扫描 */
    PENDING,

    /** 已被某实例领取，持有租约。租约过期后可被接管（{@code idx_lease}） */
    DOING,

    /** 已完成，终态 */
    DONE,

    /** 死信，终态。超过死信阈值仍未成功，停止重试并告警，等人工处置 */
    DEAD
}
