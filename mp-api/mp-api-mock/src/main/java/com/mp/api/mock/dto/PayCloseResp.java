package com.mp.api.mock.dto;

import com.mp.common.enums.RetStatus;

/**
 * mock 关单出参。
 *
 * <p><b>四分类在此不可压成布尔</b>：关单是外部 RPC，同样会返回未知。压成「关掉了 / 没关掉」会让 「超时，不知道对方关没关」被迫二选一 ——
 * 判「关掉了」则可能钱已收而订单被关，判「没关掉」则 库存被永久占着。四分类各自的处置见技术方案 §4.5：
 *
 * <ul>
 *   <li>{@code SUCCESS} —— 确认未支付、已关闭，可释放库存
 *   <li>{@code FAIL} —— 对方回报<b>已支付成功</b>，拒绝关闭（BR-B-16）
 *   <li>{@code UNKNOWN} / {@code PROCESSING} —— 结果未定，进 {@code CLOSING} 并查单，<b>不释放库存</b>
 * </ul>
 */
public class PayCloseResp {

    private RetStatus retStatus;

    /** 支付方视角的当前状态，仅日志与排障用，不作为状态机判据 */
    private String payState;

    private String errorCode;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getPayState() {
        return payState;
    }

    public void setPayState(String payState) {
        this.payState = payState;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
