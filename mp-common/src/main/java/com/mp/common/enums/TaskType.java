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

    /**
     * 库存回补：退款成功后把 {@code consumed} 还回可售。V3 PR-10 后置补入。
     *
     * <p><b>与 {@code STOCK_RELEASE} 是两件事，不可合并</b>：那个还 {@code locked}（交易未成立，预占归还）， 本类型还 {@code
     * consumed}（交易已成立后反悔，已售归还）。合并会让退款去减一个早已归零的 {@code locked}，被下界谓词挡下 —— 不报错，而库存实际没回补。
     *
     * <p><b>也不返还限购额度</b>：技术方案 §3.4 的口径表把这个不对称写死了 —— 商品可以再卖给别人，故
     * 库存回补；而「买了再退」若能刷回额度，限购即被绕过，故额度不还。本类型的执行体因此只碰 {@code marketing_stock}，{@code quota_status} 停在
     * {@code LOCKED} 不动。
     *
     * <p><b>缺了它，对账第 6 项在任何含退款的场景里每轮必报一次假差异</b>：该项比对 {@code consumed} 与「已支付且未退款成功」的份数，退款后分母减少而
     * {@code consumed} 不动。而假告警会让资损哨兵 失效 —— §3.4 原话：「退款/关单后的口径必须定死，否则对账第 6 项在任何含退款的压测里都会报差异」。
     *
     * <p>归执行类（阈值 5）：它是一条带下界的 UPDATE，每单幂等由主单 {@code stock_status} 的条件更新 （{@code CONSUMED →
     * RESTORED}）承担。
     */
    STOCK_RESTORE(5, false),

    /** 限购额度释放。V2 PR-5 接入 */
    QUOTA_RELEASE(5, false),

    /**
     * 师傅返奖。徒弟确权的四写事务内落库，异步执行（BR-F-20）。V3 PR-4 接入。
     *
     * <p>归执行类（阈值 5）：每次重试都是一次下游发奖调用，连续 5 次失败已说明不是瞬时故障。
     *
     * <p><b>失败不回滚徒弟已成功的奖励</b>：两笔发放各自独立幂等、独立重试。回滚徒弟奖等于 因为师傅没拿到而把已发给徒弟的收回去 —— 对用户即已到手的奖励被收回。
     */
    SPONSOR_REWARD(5, false),

    /**
     * 关系过期治理。V3 PR-5 接入。
     *
     * <p>归执行类：它是批量 UPDATE，重试无副作用但也不需要长阈值 —— 到期关系下一轮扫描照样会被 捞到，不必靠本任务反复重试。
     */
    RELATION_EXPIRE(5, false),

    /**
     * 退款。<b>V3 保留定义但不入队</b>。
     *
     * <p>退款由调用方（人工处置、客服工单）显式发起，没有「系统自己该退这笔款」的时刻 —— 与 {@code GRANT} 由支付成功自动触发不同。故没有任何代码路径 {@code
     * enqueue} 本类型。
     *
     * <p><b>结果未定时落的是 {@code QUERY_REFUND} 而非本类型</b>：退款的收敛通路只查不发。多发一笔奖 可回收，多退一笔钱要走人工追讨 ——
     * 两者的失效代价不对称，故退款侧不做「查无满阈值即重发」 那一段（发放侧有）。
     *
     * <p>保留定义而非删除：V4 接入自动退款场景（如活动下线批量退款）时它有位置，且 {@code manualRepair} 的「重试退款」动作按类型分派时需要它作为标识。
     */
    REFUND(5, false),

    /** 退款结果查单。<b>退款侧唯一的收敛通路</b>，只查不发。V3 PR-8 接入 */
    QUERY_REFUND(10, true),

    /**
     * 权益回收结果收敛。V3 PR-7 接入。
     *
     * <p>归执行类（阈值 5）：每次重试都是一次真实的回收调用，不是只读查询。下游按 {@code revokeNo} 幂等，重试不会二次回收。
     */
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
