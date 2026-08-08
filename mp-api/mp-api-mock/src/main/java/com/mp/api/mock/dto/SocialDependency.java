package com.mp.api.mock.dto;

/**
 * mock 社交侧的各项能力，供逐项注入「依赖不可用」。
 *
 * <p><b>这是下游自己的词汇，不是平台的过滤规则清单</b>。裂变的 {@code FilterRule}（八条过滤规则 及其 fail-open / fail-close
 * 语义）是平台侧概念，放在 {@code mp-fission}；本枚举列的是「社交 系统提供哪几项查询」。两者一一对应是因为每条过滤规则各问一项数据，但它们的归属不同 —— mock 依赖
 * {@code mp-api-mock}，不依赖 {@code mp-fission}，把平台的规则枚举拿到下游来用是反向依赖。
 *
 * <p><b>逐项注入而非一个全局开关</b>：退出标准第 9 条要「逐个注入该过滤器依赖不可用」，断言 fail-close 的四项阻断、fail-open
 * 的四项放行。全局开关一次全挂，八条规则的语义差异一条也 验不出来 —— 全挂时结果是「全被拒」，与「fail-close 生效」表现一致。
 */
public enum SocialDependency {

    /** 粉丝量查询 */
    FOLLOWER_COUNT,

    /** 账户状态查询 */
    ACCOUNT_STATUS,

    /** 用户角色查询 */
    USER_ROLE,

    /** 分享频控查询 */
    SHARE_FREQUENCY,

    /** 拉黑关系查询 */
    BLOCK_RELATION,

    /** 实验分组查询 */
    EXPERIMENT,

    /** 人群标签查询 */
    CROWD
}
