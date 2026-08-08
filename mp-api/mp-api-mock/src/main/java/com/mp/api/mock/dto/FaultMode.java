package com.mp.api.mock.dto;

/**
 * mock 下游的注入模式，运行期可切换。
 *
 * <p><b>必须区分「下游已执行」与「下游未执行」</b>。若注入只实现为「抛异常且不落账本」，那么 {@code UNKNOWN} 收敛时查到「未发放」并重发，结果正确 —— 但该场景下即使把
 * {@code UNKNOWN} 误判为 {@code FAIL} 走补偿，结果同样正确，四分类的价值根本看不出来。
 *
 * <p>要构造的是 {@link #TIMEOUT_AFTER_COMMIT}：下游已执行成功但调用方没收到结果。此时误判 {@code FAIL} 触发补发即为重复发放 ——
 * 这才是四分类要挡的事（《分阶段方案》§5.3）。
 */
public enum FaultMode {

    /** 正常返回，默认模式 */
    SUCCESS,

    /**
     * 先写账本，再抛超时。
     *
     * <p>期望：短退避查单 → 查得 → {@code GRANT_SUCCESS}，不重发；两侧账本各 1 条。
     */
    TIMEOUT_AFTER_COMMIT,

    /**
     * 不写账本直接抛超时。
     *
     * <p>期望：短退避查单 → 连续查无达阈值 → 以<b>原 {@code opNo}</b> 重发；最终两侧各 1 条。
     */
    TIMEOUT_BEFORE_COMMIT,

    /**
     * 受理但不完成，第 N 次查单转成功。
     *
     * <p>期望：长退避，退避序列与短退避不同。
     */
    PROCESSING,

    /** 确定失败。<b>唯一允许走失败分支的一类</b>。 */
    FAIL
}
