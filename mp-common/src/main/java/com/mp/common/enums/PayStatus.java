package com.mp.common.enums;

/**
 * 支付子状态，落 {@code play_biz_record.pay_status}。
 *
 * <p>三条子状态线独立推进（{@link PayStatus} / {@link GrantStatus} / {@link RefundStatus}）， 展示用的 biz_status
 * 由三者派生、不落库 —— 「支付成功且退款中」这类组合无法由单一枚举表达。
 */
public enum PayStatus {

    /** 建单默认。出边：PAY_SUCCESS / PAY_FAILED / CLOSING / CLOSED */
    WAIT_PAY,

    /** 关单 RPC 返回 UNKNOWN。V2 引入 —— 此状态下<b>不得释放库存与额度</b> */
    CLOSING,

    /** 支付回调金额校验通过。终态 */
    PAY_SUCCESS,

    /** 支付失败通知 */
    PAY_FAILED,

    /** 订单关闭。终态 */
    CLOSED
}
