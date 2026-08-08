package com.mp.fission.task;

import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.fission.entity.FissionTask;

/**
 * 一类任务的执行逻辑。
 *
 * <p><b>返回四分类而非布尔</b>：调度器据此决定退避序列与是否进失败分支。返回 true/false 会 迫使实现方把 {@code UNKNOWN} 压成 {@code
 * false}，而「不知道」被当成「失败」正是资损的起点。
 */
public interface FissionTaskHandler {

    /** 本处理器负责的任务类型 */
    TaskType taskType();

    /**
     * 执行任务。
     *
     * <p>抛异常等同于返回 {@code UNKNOWN} —— 异常可能发生在 RPC 发出之后，下游未必没执行， 由调度器统一按短退避重试。实现方不必自己 catch。
     */
    RetStatus handle(FissionTask task);
}
