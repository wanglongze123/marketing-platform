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

    /**
     * 订单已支付，拒绝关闭（PRD FR-B04、BR-B-16）。V2 PR-6 引入。
     *
     * <p>归 1xxx 而非 5xxx：这是<b>确定的业务拒绝</b> —— 支付方明确回报「已收款」，关不掉。判 5xxx 会让调用方按「结果未知」去查单重试，而这件事早已有确定答案。
     */
    public static final String ORDER_ALREADY_PAID = "1741";

    // ---- 4xxx 入参/凭证非法 ----

    /** 必填参数缺失或取值非法 */
    public static final String INVALID_PARAM = "4001";

    /** 凭证签名非法或已过期。V2 引入 */
    public static final String INVALID_TOKEN = "4003";

    /**
     * 支付通知验签失败（PRD FR-B03、BR-B-12）。V2 PR-6b 引入。
     *
     * <p>归 4xxx 而非 5xxx：验签失败是<b>确定的拒绝</b>，重试没有意义 —— 同一条通知重发多少次， 签名还是不对。判 5xxx 会让支付方按「平台系统故障」不断重投。
     *
     * <p>BR-B-12：未通过验签的通知<b>不得更新任何业务状态</b>，连操作记录都不留 —— 留痕等于给 攻击者一个无需密钥就能写库的入口。
     */
    public static final String PAY_NOTIFY_SIGN_INVALID = "4731";

    // ---- 5xxx 系统异常 ----

    /** 下游超时/未知，映射为 {@code RetStatus.UNKNOWN} */
    public static final String DOWNSTREAM_UNKNOWN = "5001";

    /**
     * 同一业务对象正在被并发处理，请稍后重试。V2 PR-7 引入。
     *
     * <p>归 5xxx 而非 1xxx：这<b>不是业务规则拒绝</b>，而是「现在不知道结果，等会儿再来」——
     * 上游应当按未知态重试，而不是把它当成终态失败展示给用户。抢不到锁不代表这笔业务不成立。
     */
    public static final String CONCURRENT_CONFLICT = "5002";
}
