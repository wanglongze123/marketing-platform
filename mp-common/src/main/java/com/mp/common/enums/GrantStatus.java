package com.mp.common.enums;

/**
 * 主单<b>汇总</b>发放态，落 {@code play_biz_record.grant_status}。
 *
 * <p><b>终态带 GRANT_ 前缀</b>，与履约明细的单项态 {@link ItemGrantStatus} 区分 —— 两者字段同名但取值不同，
 * 用两个枚举类型隔开，使串用变成编译错误而非运行时脏数据。
 */
public enum GrantStatus {

    /** 建单默认 */
    NOT_START,

    /** 支付成功后履约启动 */
    GRANTING,

    /** 全部权益项发放成功 */
    GRANT_SUCCESS,

    /** 发放确定失败，无权益在外，可直接退款 */
    GRANT_FAILED,

    /** 发奖 RPC 超时/未知。<b>禁止直接判失败</b>，须以原幂等号查单收敛 */
    GRANT_UNKNOWN
}
