package com.mp.common.enums;

/**
 * 操作类型，落 {@code play_op_record.op_type}。
 *
 * <p>决定 {@code op_seq} 取值（见《开发规范》§5.6）：判据是「同类操作在一单上是否只应发生一次」。
 *
 * <p><b>查单是读操作，不建 op_record</b> —— 应更新原记录的 retry_count / downstream_result / finish_time。
 */
public enum OpType {

    /** 每单至多一次，op_seq = "" */
    CREATE_TRADE(true),

    /** 每单至多一次，op_seq = "" */
    GRANT_BENEFIT(true),

    /** 每单至多一次，op_seq = ""。V2 引入 */
    CLOSE_ORDER(true),

    /** 每单至多一次，op_seq = ""。V3 引入 */
    REVOKE_BENEFIT(true),

    /** 每单至多一次，op_seq = ""。V3 引入 */
    CREATE_REFUND(true),

    /**
     * <b>op_seq = notifySeq，不是空串</b>。同一订单会收到多条语义不同的通知（成功/失败/关单结果）， 各自都要留痕。取空串会让第二条通知在 uk_biz_op
     * 上冲突被拒，执行不到条件更新那一步。
     */
    PAY_CALLBACK(false),

    /** op_seq = 上游通知流水号。V3 引入 */
    REFUND_CALLBACK(false),

    /** op_seq = 外部工单号，允许多次处置。V3 引入 */
    MANUAL_REPAIR(false),

    /** op_seq = ""。V3 引入 */
    RECONCILE(true);

    /** 该类操作在一单上是否只应发生一次；true 则 op_seq 恒为空串 */
    private final boolean atMostOnce;

    OpType(boolean atMostOnce) {
        this.atMostOnce = atMostOnce;
    }

    public boolean isAtMostOnce() {
        return atMostOnce;
    }
}
