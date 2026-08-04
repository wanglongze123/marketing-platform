package com.mp.common.enums;

/**
 * 错误码，取自《技术方案》§4.1，本项目不新造码。
 *
 * <p>分区决定处置方式：
 *
 * <ul>
 *   <li>{@code 1xxx} 业务规则拒绝 —— 终态失败，不重试，可展示给用户
 *   <li>{@code 4xxx} 入参/凭证非法 —— 终态失败，不重试
 *   <li>{@code 5xxx} 系统异常 —— <b>结果未知，按 UNKNOWN 查单收敛，不得判失败</b>
 * </ul>
 *
 * <p>资格决策必须区分 1xxx 与 5xxx：前者是「不符合条件」，后者是「系统故障」，前端展示与重试策略完全不同。
 */
public final class ErrorCode {

    private ErrorCode() {}

    // ---- 1xxx 业务规则拒绝 ----

    /** 价格不一致：凭证成交价 ≠ 服务端重算价。V2 引入比价后使用 */
    public static final String PRICE_MISMATCH = "1711";

    /** 库存不足。V2 引入 */
    public static final String STOCK_NOT_ENOUGH = "1712";

    /** 超出限购额度。V2 引入 */
    public static final String QUOTA_EXCEEDED = "1713";

    /** 支付金额/币种/商户不一致，触发 P0 告警且不改任何状态 */
    public static final String PAY_AMOUNT_MISMATCH = "1731";

    // ---- 4xxx 入参/凭证非法 ----

    /** 必填参数缺失或取值非法 */
    public static final String INVALID_PARAM = "4001";

    /** 凭证签名非法或已过期。V2 引入 */
    public static final String INVALID_TOKEN = "4003";

    // ---- 5xxx 系统异常 ----

    /** 下游超时/未知，映射为 {@code RetStatus.UNKNOWN} */
    public static final String DOWNSTREAM_UNKNOWN = "5001";
}
