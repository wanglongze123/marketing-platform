package com.mp.api.benefit.dto;

/**
 * 人工处置的七类动作（PRD FR-C07）。V3 PR-10 引入。
 *
 * <p><b>前五类都是「重新驱动一条已有的收敛通路」，必须复用原幂等键</b>：重试发奖用原 {@code grantOpNo}、 重试回收用原 {@code revokeNo}、重试退款用原
 * {@code refundNo}。新造键即绕开 {@code uk_biz_op}，等于给人工 处置开了一个可以重复发奖的后门 —— 而人工处置是最容易被重复点击的入口（客服连点）。
 *
 * <p><b>{@code callsDownstream} 挂在枚举上</b>：它决定这个动作要不要复用原键、要不要计入下游调用。写成 编排里的 {@code if}
 * 则每加一个动作都要回头改那个条件。
 */
public enum RepairAction {

    /** 重查支付：驱动 {@code QUERY_CLOSE} 通路，只读 */
    REQUERY_PAY(true),

    /** 重查发奖：驱动 {@code QUERY_GRANT} 通路，只读 */
    REQUERY_GRANT(true),

    /** 重试发奖：以原 {@code grantOpNo} 补建 {@code GRANT} 任务 */
    RETRY_GRANT(true),

    /** 重试回收：以原 {@code revokeNo} 补建 {@code REVOKE} 任务 */
    RETRY_REVOKE(true),

    /** 重试退款：以原 {@code refundNo} 补建 {@code QUERY_REFUND} 任务，<b>只查不发</b> */
    RETRY_REFUND(true),

    /**
     * 标记人工完成：<b>唯一会写终态而不调下游的动作</b>。
     *
     * <p>故它必须落 {@code operator} 与 {@code reason}（BR-C-27），且在对账中可被识别为人工干预、 <b>不计入自动收敛率</b> ——
     * 否则「人工把单子标成功」会让收敛率看起来是 100%，而那个数正是 用来判断自动化程度的。
     */
    MARK_DONE(false),

    /** 导出对账证据：只读，不改任何状态 */
    EXPORT_EVIDENCE(false);

    private final boolean callsDownstream;

    RepairAction(boolean callsDownstream) {
        this.callsDownstream = callsDownstream;
    }

    /** 该动作是否会驱动下游调用。{@code false} 的两个动作不产生任何外部请求。 */
    public boolean isCallsDownstream() {
        return callsDownstream;
    }
}
