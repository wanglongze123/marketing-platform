package com.mp.common.enums;

/**
 * 操作记录的本地执行态，落 {@code *_op_record.status}。
 *
 * <p>与 {@link RetStatus} 分列：本地态是我方对这次操作的判断，{@code downstream_result} 是对方的回报。 注意失败值拼写不同（{@code
 * FAILED} vs {@code FAIL}），不是笔误。
 */
public enum OpStatus {
    INIT,
    PROCESSING,
    SUCCESS,
    FAILED,
    UNKNOWN
}
