package com.mp.fission.filter;

import java.util.List;
import java.util.Set;

/**
 * 关系过滤（BR-F-07-a）：剔除已有进行中关系的好友。
 *
 * <p><b>八条规则里只有它有两套实现</b>，因为只有它查平台自己的表，也就只有它能被下推 DB（§7.1）。 其余七条都要跨出服务边界问社交系统，批量化是接口形态决定的，没有「下推」可言。
 *
 * <p>两套实现由 {@code mp.fission.filter.pushdown} 切换，<b>都进 CI 的正确性用例</b>：退出标准第 8 条
 * 断言两者筛出的通过集合、拒绝集合、拒绝原因逐项相等。
 *
 * <p><b>正确性一致是性能对照有意义的前提</b>：两个实现若筛出的人不一样，比 P99 是在比两个不同的 功能。这与 V2 去锁对照组（§5.6 ⑫）的判据同源。
 */
public interface RelationFilter {

    /**
     * 返回候选页中<b>已有进行中关系</b>的用户，即应被拒绝的那些。
     *
     * @param candidates 当前候选页，长度受页大小约束（≤ 200）
     */
    Set<String> findWithActiveRelation(String groupId, List<String> candidates);

    /** 实现标识，写进日志与对照实验的记录，避免「跑的到底是哪一套」靠猜。 */
    String implName();
}
