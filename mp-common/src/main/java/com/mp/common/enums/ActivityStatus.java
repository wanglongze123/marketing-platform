package com.mp.common.enums;

/**
 * 活动状态（PRD §4.1）。
 *
 * <p>流转：{@code DRAFT → SCHEDULED → ONLINE → ENDED}；{@code ONLINE ↔ PAUSED}。
 *
 * <p><b>「可新建单据」与「已有单据处理」是两件事</b>：{@code PAUSED} / {@code ENDED} 不再接受新单，
 * 但存量单据的履约、退款照常推进（BR-C-02）。把两者合成一个判断，会让活动一暂停就卡住所有在途 订单的履约 —— 那是已收款未发奖。
 */
public enum ActivityStatus {

    /** 草稿：可编辑，不可咨询、不可下单 */
    DRAFT,

    /** 待生效：配置已冻结，等定时任务推进到 ONLINE */
    SCHEDULED,

    /** 进行中：唯一可咨询、可新建单据的状态 */
    ONLINE,

    /** 暂停：不接新单，存量单据继续处理 */
    PAUSED,

    /** 已结束：不接新单，存量单据继续处理 */
    ENDED;

    /**
     * 能否由当前状态迁移到目标状态。
     *
     * <p>非法迁移一律拒绝而非静默忽略：静默忽略会让调用方以为改成功了，而运营在后台看到的状态 与他刚才的操作不符，且没有任何错误可查。
     */
    public boolean canTransitTo(ActivityStatus target) {
        return switch (this) {
            case DRAFT -> target == SCHEDULED;
            case SCHEDULED -> target == ONLINE;
            case ONLINE -> target == PAUSED || target == ENDED;
            case PAUSED -> target == ONLINE || target == ENDED;
            case ENDED -> false;
        };
    }

    /** 是否允许新建业务单据（BR-C-01）。 */
    public boolean acceptsNewOrder() {
        return this == ONLINE;
    }
}
