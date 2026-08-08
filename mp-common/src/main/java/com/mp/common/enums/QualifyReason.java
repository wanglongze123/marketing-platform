package com.mp.common.enums;

/**
 * 资格决策的标准原因码（FR-C02、BR-C-07）。
 *
 * <p><b>「不符合条件」与「系统异常」必须分开</b>：前者是确定的业务结论，前端照常展示、用户不必 重试；后者是依赖故障，重试可能通过。合成一个「不通过」会让风控依赖挂掉时，全部用户被告知
 * 「你不符合条件」—— 业务上是误判，排查时也看不出系统出了故障。
 *
 * <p>对应错误码：业务原因归 {@code 1201}，依赖异常归 {@code 5201}。
 */
public enum QualifyReason {

    /** 通过 */
    PASS(false),

    /** 活动不在可参与状态或时间窗外 */
    ACTIVITY_UNAVAILABLE(false),

    /** 不在活动指定城市范围内 */
    CITY_NOT_MATCH(false),

    /** 不在活动指定渠道范围内 */
    CHANNEL_NOT_MATCH(false),

    /** 不在目标人群内 */
    CROWD_NOT_MATCH(false),

    /** 命中风控规则（黑名单、频控、风险等级） */
    RISK_REJECTED(false),

    /** 上下文构建依赖异常 —— 这一条是系统故障，不是「不符合条件」 */
    CONTEXT_UNAVAILABLE(true);

    private final boolean systemError;

    QualifyReason(boolean systemError) {
        this.systemError = systemError;
    }

    /** 该原因是否属于系统异常。决定错误码取 {@code 5201} 还是 {@code 1201}。 */
    public boolean isSystemError() {
        return systemError;
    }
}
