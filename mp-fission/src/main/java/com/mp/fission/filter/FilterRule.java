package com.mp.fission.filter;

/**
 * 八个好友过滤规则及其<b>失败语义</b>（PRD BR-F-07-a~h）。
 *
 * <p><b>失败语义是规则自带的属性，不是编排层的策略</b>：判据为「影响资损或合规的取 fail-close」 （BR-F-08）。挂在枚举上而非写进编排的 if ——
 * 后者每加一条规则就要回头改编排，且两处一旦漂移， 表现是某条规则在依赖故障时静默换了语义。
 *
 * <p><b>两类失败语义的分界</b>：
 *
 * <ul>
 *   <li>{@code fail-close}（依赖挂了就拒绝）：关系、频控、账户状态、用户角色。放行的代价是重复 建关系、刷分享、给注销账号发奖 —— 都是资损或合规问题
 *   <li>{@code fail-open}（依赖挂了就放行）：影响力、社交关系、实验分组、营销人群。这四条是 <b>投放优化</b>而非准入门槛，拒绝的代价是活动可用性 ——
 *       一个推荐服务抖动不该让全部用户 分享不出去
 * </ul>
 *
 * <p>无论哪种语义，依赖失败都<b>计入降级清单</b>（{@code degradedRules}）：结果对不对是一回事，
 * 「这次的结果是在什么条件下算出来的」是另一回事。不记的话，一次大面积降级与一次正常过滤在 调用方看来完全一样。
 */
public enum FilterRule {

    /** BR-F-07-a 关系：是否已有进行中关系。<b>下推 DB 的就是这一条</b>（§7.1 的优化对象） */
    RELATION(false, "已有进行中关系"),

    /** BR-F-07-b 分享频控：当天是否已收到同类分享 */
    SHARE_FREQUENCY(false, "当日已收到同类分享"),

    /** BR-F-07-c 账户状态：是否注销或禁言 */
    ACCOUNT_STATUS(false, "账户状态异常"),

    /** BR-F-07-d 用户角色：是否为允许的用户类型 */
    USER_ROLE(false, "用户类型不允许"),

    /** BR-F-07-e 影响力：是否超过粉丝量阈值 */
    INFLUENCE(true, "影响力超阈值"),

    /** BR-F-07-f 社交关系：是否存在拉黑 */
    SOCIAL(true, "存在拉黑关系"),

    /** BR-F-07-g 实验分组：是否在指定实验组 */
    EXPERIMENT(true, "不在实验组"),

    /** BR-F-07-h 营销人群：是否匹配活动标签 */
    CROWD(true, "人群标签不匹配");

    private final boolean failOpen;
    private final String rejectReason;

    FilterRule(boolean failOpen, String rejectReason) {
        this.failOpen = failOpen;
        this.rejectReason = rejectReason;
    }

    /** 依赖不可用时放行（{@code true}）还是阻断（{@code false}）。 */
    public boolean isFailOpen() {
        return failOpen;
    }

    /** 被本规则拒绝时的原因，回给调用方按原因归类的拒绝集合。 */
    public String getRejectReason() {
        return rejectReason;
    }

    /** 依赖不可用时的原因，与正常拒绝<b>区分开</b> —— 两者对运营的含义不同。 */
    public String getDegradedReason() {
        return rejectReason + "（依赖不可用，fail-close 阻断）";
    }
}
