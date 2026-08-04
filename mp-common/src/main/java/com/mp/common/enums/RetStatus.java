package com.mp.common.enums;

/**
 * 下游调用的四分类结果，跨服务传递时不做转换。
 *
 * <p>取值以《技术方案》§4.1 为准 —— 是 {@code FAIL} 不是 {@code FAILED}。本地执行态另有 {@link OpStatus}，两者不可互相赋值。
 *
 * <p><b>UNKNOWN 不等于 FAIL</b>：超时后下游是否已执行无从判断，只有 {@code FAIL} 分支允许走失败处理。 其余三类均须保持中间态，以原幂等键查单收敛。
 */
public enum RetStatus {

    /** 下游确认成功 */
    SUCCESS,

    /** 下游确认失败。<b>只有这一类允许走补偿/退款分支</b> */
    FAIL,

    /** 下游已受理、处理中。长退避查单：30s → 2m → 10m */
    PROCESSING,

    /** 结果未知（超时、连接异常、响应无法解析）。短退避查单：1s → 5s → 30s */
    UNKNOWN
}
