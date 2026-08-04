package com.mp.common.enums;

/** 退款子状态，落 {@code play_biz_record.refund_status}。V3 引入逆向链路后使用。 */
public enum RefundStatus {

    /** 建单默认 */
    NONE,

    /** 退款准入通过且需回收 */
    REVOKING,

    /** 回收失败，可人工重试 */
    REVOKE_FAILED,

    /** 退款受理成功 */
    REFUNDING,

    /** 退款到账。终态 */
    REFUND_SUCCESS,

    /** 退款失败，可人工重试 */
    REFUND_FAILED
}
