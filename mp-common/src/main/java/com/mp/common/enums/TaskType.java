package com.mp.common.enums;

/**
 * 可靠任务类型，落 {@code benefit_task.task_type}。
 *
 * <p>每一类自带死信阈值：<b>查单类与执行类的失败语义不同</b>。查单是读操作，重试无副作用，且下游 {@code PROCESSING} 本就可能持续较久，阈值取
 * 10；执行类每次重试都是一次下游调用， 连续 5 次失败已说明不是瞬时故障（《分阶段方案》§5.6 ⑤）。
 *
 * <p>阈值挂在类型上而非调度器里：调度器不该知道「哪些任务是查单」，否则每加一类任务就要回头 改调度器的 if。
 */
public enum TaskType {

    /** 履约发放。支付成功事务内落库，本地消息表的主用例 */
    GRANT(5, false),

    /** 发放结果查单。{@code UNKNOWN} 的收敛通路 */
    QUERY_GRANT(10, true),

    /** 订单关闭。建单事务内落库，{@code next_time = expire_time}。V2 PR-6 接入 */
    CLOSE_ORDER(5, false),

    /** 关单结果查单。V2 PR-6 接入 */
    QUERY_CLOSE(10, true),

    /** 库存扣减，从支付回调事务中移出。V2 PR-5 接入 */
    STOCK_CONSUME(5, false),

    /** 库存释放。V2 PR-6 接入 */
    STOCK_RELEASE(5, false),

    /** 限购额度释放。V2 PR-5 接入 */
    QUOTA_RELEASE(5, false),

    /** 退款。V3 接入 */
    REFUND(5, false),

    /** 退款结果查单。V3 接入 */
    QUERY_REFUND(10, true),

    /** 权益回收。V3 接入 */
    REVOKE(5, false);

    private final int maxRetry;
    private final boolean query;

    TaskType(int maxRetry, boolean query) {
        this.maxRetry = maxRetry;
        this.query = query;
    }

    /** 超过该次数仍未成功即进 {@code DEAD}，不再重试 */
    public int getMaxRetry() {
        return maxRetry;
    }

    /** 查单类任务：读操作，重试无副作用 */
    public boolean isQuery() {
        return query;
    }
}
