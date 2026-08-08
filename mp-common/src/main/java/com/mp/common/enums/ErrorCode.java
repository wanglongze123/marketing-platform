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

    /**
     * 不符合资格，附 {@code reasonCode}（PRD FR-C02）。V3 PR-1 引入。
     *
     * <p>与 {@link #QUALIFY_CONTEXT_ERROR} 严格分开：本码表示「确定不符合条件」，前端照常展示、
     * 用户不必重试；后者是依赖故障，重试可能通过（BR-C-07）。
     */
    public static final String NOT_QUALIFIED = "1201";

    /**
     * 无可参与活动（PRD FR-F01、BR-F-01）。V3 PR-2 引入。
     *
     * <p>归 1xxx 且<b>不作为异常抛出</b>：这是正常的业务结果 —— 用户今天确实没有可参与的活动。 与 {@code 5601}（候选检索异常）分开，后者是系统故障。
     */
    public static final String NO_AVAILABLE_ACTIVITY = "1601";

    /** 已存在进行中轮次，不得重复开启（PRD FR-F02、BR-F-04）。V3 PR-2 引入 */
    public static final String GROUP_ALREADY_RUNNING = "1602";

    /** 裂变组已终结，不能再往里拉人。V3 PR-3 引入 */
    public static final String GROUP_NOT_RUNNING = "1603";

    /**
     * 师徒为同一人（PRD FR-F06）。V3 PR-3 引入。
     *
     * <p>这条是刷奖的第一道门：自己邀请自己即可无限触发双向发奖。分享与加入两处都要判 —— 只判其中一处时，另一条路径照样进得来。
     */
    public static final String SPONSOR_IS_FOLLOWER = "1614";

    /**
     * 被分享对象未通过好友过滤（PRD FR-F05、BR-F-12）。V3 PR-6 引入。
     *
     * <p>归 1xxx：确定的业务拒绝 —— 该好友本轮不可被分享，重试结果不变。
     *
     * <p>与 {@link #FRIEND_RECALL_UNAVAILABLE} 严格分开：本码是「这个人不该被邀请」，那条是
     * 「候选名单拉不出来」。合并会让召回方故障时前端显示「你的好友都不符合条件」。
     */
    public static final String FOLLOWER_FILTERED = "1611";

    /**
     * 关系非 {@code JOINED}，不能确权（PRD FR-F07）。V3 PR-4 引入。
     *
     * <p>确权的前置是「徒弟已加入」。对已 {@code DONE} 的关系重复确权同样落到这里 —— 那是重复 发奖的入口，必须拦在发奖之前。
     */
    public static final String RELATION_NOT_JOINED = "1617";

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

    /**
     * 活动发布校验不通过（PRD FR-C01、BR-C-04）。V3 PR-1 引入。
     *
     * <p>归 4xxx 而非 1xxx：配置不完整是<b>提交内容本身不合格</b>，与「规则判定你不能参与」不同。 重试同一份配置结果不变，运营须先改配置。
     */
    public static final String PUBLISH_CHECK_FAILED = "4101";

    /** 活动有效期非法：{@code endTime <= startTime}，或超出上限。V3 PR-1 引入 */
    public static final String INVALID_ACTIVITY_PERIOD = "4102";

    /** 活动状态迁移非法（PRD §4.1 的流转表）。V3 PR-1 引入 */
    public static final String INVALID_STATUS_TRANSITION = "4103";

    /** 轮次有效期超上限（PRD FR-F02）。V3 PR-2 引入 */
    public static final String GROUP_PERIOD_TOO_LONG = "4602";

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
     * 资格决策的上下文构建依赖异常（PRD FR-C02）。V3 PR-1 引入。
     *
     * <p>与 {@link #NOT_QUALIFIED} 是同一个接口的两类结果，<b>不可合并</b>：风控依赖挂掉时若返回 {@code
     * 1201}，全部用户会被告知「你不符合条件」—— 业务上是误判，排查时也看不出系统故障。
     */
    public static final String QUALIFY_CONTEXT_ERROR = "5201";

    /**
     * 裂变候选活动检索异常（PRD FR-F01）。V3 PR-2 引入。
     *
     * <p>与 {@link #NO_AVAILABLE_ACTIVITY} 严格分开：那条是「确实没有活动」，本条是「查不出来」。 合并会让检索依赖故障时全部师傅收到「今天没活动」——
     * 与资格决策的 1201/5201 是同一条分界线。
     */
    public static final String FISSION_QUERY_ERROR = "5601";

    /**
     * 好友召回能力不可用（PRD FR-F03）。V3 PR-6 引入。
     *
     * <p>归 5xxx：候选名单拉不出来是系统故障，重试可能成功。<b>不降级为空列表</b> —— 空列表与 「这个人没有好友」不可区分，端上会显示一个看起来正常的空页面，而故障无人察觉。
     *
     * <p>与过滤器的 fail-open 处置不同：<b>召回失败没有可放行的对象</b>，而过滤器失败时手上有一批 明确的候选人，可按各自的失败语义处置。两者不是同一类判断。
     */
    public static final String FRIEND_RECALL_UNAVAILABLE = "5603";

    /**
     * 同一业务对象正在被并发处理，请稍后重试。V2 PR-7 引入。
     *
     * <p>归 5xxx 而非 1xxx：这<b>不是业务规则拒绝</b>，而是「现在不知道结果，等会儿再来」——
     * 上游应当按未知态重试，而不是把它当成终态失败展示给用户。抢不到锁不代表这笔业务不成立。
     */
    public static final String CONCURRENT_CONFLICT = "5002";
}
