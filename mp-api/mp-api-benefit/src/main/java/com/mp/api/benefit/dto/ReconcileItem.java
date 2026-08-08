package com.mp.api.benefit.dto;

/**
 * 对账十五项（技术方案 §6.8）。V3 PR-10 引入。
 *
 * <p><b>{@code autoRepair} 挂在枚举上，不写在编排的 {@code if} 里</b>：十五项分两批 —— 单库扫描 + 补建任务的
 * 那批处置动作幂等（任务自带幂等闸），跨库或跨系统比对的那批<b>一律只告警不自动改数</b>。判据挂在项上， 编排只按它分流；写成 {@code if (item ==
 * AMOUNT_MISMATCH || ...)} 则每加一项都要回头改那个条件，而漏改的 后果是「对一笔金额不一致的单自动改了数」。
 *
 * <p><b>为什么「只告警」这条要如此强调</b>（§6.8）：可自愈的差异有一个共同点 —— 修复动作是「重新驱动一条
 * 已有的收敛通路」，通路自带幂等，重复驱动无害。而金额、库存计数、额度计数这三类的正确值取决于历史， 直接改数会把一次错误固化成新的基线，此后对账再也看不出它错过。
 */
public enum ReconcileItem {

    /** 第 1 项：已收款未履约 → 补建 {@code GRANT} 任务 */
    PAID_NOT_GRANTED(true),

    /** 第 2 项：已退款权益未回收 → 补建 {@code REVOKE} 任务 + 告警 */
    REFUNDED_NOT_REVOKED(true),

    /** 第 3 项：发奖单在 reward 侧查无 → 告警人工核，<b>不自动补发</b> */
    GRANT_MISSING_DOWNSTREAM(false),

    /** 第 4 项：操作记录长期非终态 → 补建查单任务 */
    OP_UNRESOLVED(true),

    /** 第 5 项：金额不一致 → P0 告警，<b>禁止自动改单</b> */
    AMOUNT_MISMATCH(false),

    /** 第 6 项：库存与单据数不一致 / 超卖 → 告警 + 产出 {@code stock_oversold_total} */
    STOCK_MISMATCH(false),

    /** 第 7 项：徒弟已发师傅未返 → 补建 {@code SPONSOR_REWARD} 任务 */
    SPONSOR_NOT_REWARDED(true),

    /** 第 8 项：支付成功本地无单 → 补记 + 建履约任务。V3 无对账文件，留位不实现 */
    PAY_WITHOUT_ORDER(true),

    /** 第 9 项：已关闭单仍占库存 → 补建 {@code STOCK_RELEASE} 任务 */
    CLOSED_HOLDING_STOCK(true),

    /** 第 10 项：关系完成但轮次进度未推进 → 重放关系后处理 */
    RELATION_PROGRESS_LAG(true),

    /** 第 11 项：重复发奖检出 → P0 告警 + 产出 {@code reward_duplicate_total} */
    DUPLICATE_GRANT(false),

    /** 第 12 项：发奖成功但关系未推进 → 重放 {@code advanceAfterGrantConfirmed} */
    GRANT_DONE_RELATION_LAG(true),

    /** 第 13 项：发奖在途标志超时 → 强制查单收敛，仍未定则告警 */
    GRANTING_UNTIL_EXPIRED(true),

    /** 第 14 项：关单中间态未收敛 → 强制 {@code QUERY_CLOSE} 查单 */
    STUCK_CLOSING(true),

    /** 第 15 项：限购额度与单据数不一致 → 告警 + 人工核，<b>禁止自动改 {@code used_qty}</b> */
    QUOTA_MISMATCH(false);

    private final boolean autoRepair;

    ReconcileItem(boolean autoRepair) {
        this.autoRepair = autoRepair;
    }

    /** 该项的差异是否可自动补偿。{@code false} 即只告警，不得改数。 */
    public boolean isAutoRepair() {
        return autoRepair;
    }
}
