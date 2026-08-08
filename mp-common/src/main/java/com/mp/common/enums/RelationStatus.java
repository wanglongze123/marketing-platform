package com.mp.common.enums;

/**
 * 裂变关系状态（PRD §4.2、技术方案 §3.3）。
 *
 * <p>迁移：{@code INVITED → CONNECTED → JOINED → DONE}；非终态均可转 {@code EXPIRED} / {@code CANCEL}。
 *
 * <p><b>三个终态各自都必须释放 {@code active_flag}</b>。该列做「部分唯一」：非终态恒为 {@code ACTIVE}（受 {@code
 * uk_group_follower_active} 约束，至多一条），进终态时置为该行的 {@code relation_id}（天然唯一，等于把这行移出唯一性约束）。
 *
 * <p>漏掉任一条终态路径都是<b>静默失败</b>：该行仍占着 {@code (group_id, follower_id, 'ACTIVE')}，
 * 该师徒下一轮分享插入时唯一键冲突，而「先插后判」模式会把冲突当幂等命中 —— 静默返回那条 已终结的关系。用户点了分享看似成功，实际什么也没发生，裂变复购场景全断且最难排查。
 */
public enum RelationStatus {

    /** 已邀请：师傅分享后建立 */
    INVITED(false),

    /** 已建联：徒弟点击落地页 */
    CONNECTED(false),

    /** 已加入：徒弟提交加入，回填 out_biz_no */
    JOINED(false),

    /** 已完成（终态）：双向发奖成功 */
    DONE(true),

    /** 已过期（终态）：过期治理推进 */
    EXPIRED(true),

    /** 已取消（终态）：风控或人工处置 */
    CANCEL(true);

    private final boolean terminal;

    RelationStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 终态须释放 {@code active_flag}，非终态恒为 {@code ACTIVE}。 */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 能否由当前状态迁移到目标状态。
     *
     * <p>终态无出边 —— 已完成的关系不能被改回进行中，否则同一徒弟会触发第二次双向发奖。
     */
    public boolean canTransitTo(RelationStatus target) {
        if (terminal) {
            return false;
        }
        if (target == EXPIRED || target == CANCEL) {
            // 任何非终态都可被过期治理或风控终结
            return true;
        }
        return switch (this) {
            case INVITED -> target == CONNECTED || target == JOINED;
            case CONNECTED -> target == JOINED;
            case JOINED -> target == DONE;
            default -> false;
        };
    }
}
