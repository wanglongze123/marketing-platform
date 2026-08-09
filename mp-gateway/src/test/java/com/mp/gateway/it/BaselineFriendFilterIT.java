package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.fission.dto.ShareInviteReq;
import com.mp.api.fission.service.FissionService;
import com.mp.fission.filter.BaselineRelationFilter;
import com.mp.fission.filter.PushdownRelationFilter;
import com.mp.fission.filter.RelationFilter;
import com.mp.fission.repository.FissionRelationMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 关系过滤两套实现的对照，对应《分阶段方案》§6.5 退出标准 8。
 *
 * <p><b>正确性完全一致是性能对照有意义的前提</b>：两个实现若筛出的人不一样，比 P99 是在比两个 不同的功能。这与 V2 去锁对照组（§5.6 ⑫「正确性结果完全一致」）是同一条判据。
 *
 * <p><b>本类把开关切到基线</b>（{@code mp.fission.filter.pushdown=false}），与默认上下文分属两个 Spring 上下文 —— 与 {@code
 * LockDisabledCorrectnessIT} 同一处置。这也是为什么退出标准第 8 条 无法在单个用例里「分别走两套实现」：切换发生在 bean 装配期，不是调用期。
 *
 * <p>故对照由两半构成：本类断言基线实现的结果，{@code FriendFilterIT} 断言下推实现的结果，两者 <b>用同一组场景与同一组断言</b>。此外本类直接注入两个 mapper
 * 方法做逐候选比对 —— 那部分不受 上下文限制，可以在一个用例里跑完。
 */
@TestPropertySource(properties = "mp.fission.filter.pushdown=false")
class BaselineFriendFilterIT extends AbstractMySqlIT {

    /** 裂变活动，见 {@link AbstractMySqlIT#FISSION_ACTIVITY_ID}。 */
    private static final String ACT = FISSION_ACTIVITY_ID;

    @Autowired private FissionService fissionService;
    @Autowired private FissionRelationMapper relationMapper;
    @Autowired private RelationFilter relationFilter;
    @Autowired private ApplicationContext ctx;

    /**
     * 开关切到基线时，装配的是基线实现，<b>下推实现根本不在上下文里</b>。
     *
     * <p>这条看似多余，实则是本类全部断言的前提：若开关名写错、或两个实现的条件注解写反， 本类会在下推实现上跑一遍「基线的断言」并全部通过 —— 对照实验就成了自己跟自己比。
     */
    @Test
    void baselineImplementationIsWiredWhenPushdownIsOff() {
        assertThat(relationFilter.implName()).isEqualTo("baseline");
        assertThat(relationFilter).isInstanceOf(BaselineRelationFilter.class);
        assertThat(ctx.getBeanNamesForType(PushdownRelationFilter.class))
                .as("开关关闭时下推实现不得被装配，否则两个实现会争同一个注入点")
                .isEmpty();
    }

    /**
     * 标准 8：<b>两套实现对同一候选集给出完全相同的结果</b>。
     *
     * <p>直接调两个 mapper 方法而非走接口：切换在装配期，一个上下文里只有一个实现。而这两条 SQL
     * 正是两套实现各自的全部内容——下推版一次问一页，基线版一次问一个人，比对它们等于比对 两套实现。
     *
     * <p><b>候选集必须同时含命中与未命中</b>：全命中时「返回全部候选」的错误实现也能通过；全未 命中时「恒返回空集」同样通过。
     */
    @Test
    void twoImplementationsAgreeOnEveryCandidate() {
        String sponsorId = "U_sp_cmp";
        String groupId = fissionService.openGroup(ACT, sponsorId);

        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(sponsorId + "_c" + i);
        }
        // 偶数号建关系（命中），奇数号不建（未命中）
        List<String> invited = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i += 2) {
            invited.add(candidates.get(i));
        }
        ShareInviteReq share = new ShareInviteReq();
        share.setGroupId(groupId);
        share.setSponsorId(sponsorId);
        share.setFollowerIds(invited);
        share.setShareMethod("IM");
        fissionService.shareInvite(share);

        // 下推：整页一次 IN 查询
        Set<String> pushdown =
                Set.copyOf(relationMapper.selectActiveFollowerIdsIn(groupId, candidates));

        // 基线：逐用户各查一次
        List<String> baseline = new ArrayList<>();
        for (String id : candidates) {
            if (relationMapper.countActiveRelation(groupId, id) > 0) {
                baseline.add(id);
            }
        }

        assertThat(pushdown)
                .as("两套实现筛出的集合须逐项相等，否则比 P99 是在比两个不同的功能")
                .containsExactlyInAnyOrderElementsOf(baseline);
        assertThat(pushdown).containsExactlyInAnyOrderElementsOf(invited);
        assertThat(pushdown).as("候选集须同时含命中与未命中").hasSize(10);
    }

    /**
     * 两套实现对<b>终态关系</b>的判定一致：已 {@code DONE} / {@code EXPIRED} 的不算「进行中」。
     *
     * <p>两条 SQL 各写了一遍 {@code status IN ('INVITED','CONNECTED','JOINED')}，漏在任一侧都会让 该实现把历史关系当成进行中 ——
     * 表现是「这个好友邀请过一次后就再也点不了」。
     */
    @Test
    void bothImplementationsIgnoreTerminalRelations() {
        String sponsorId = "U_sp_cmp_term";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        String follower = sponsorId + "_t0";

        ShareInviteReq share = new ShareInviteReq();
        share.setGroupId(groupId);
        share.setSponsorId(sponsorId);
        share.setFollowerIds(List.of(follower));
        share.setShareMethod("IM");
        fissionService.shareInvite(share);

        List<String> candidates = List.of(follower);
        assertThat(relationMapper.selectActiveFollowerIdsIn(groupId, candidates))
                .containsExactly(follower);
        assertThat(relationMapper.countActiveRelation(groupId, follower)).isEqualTo(1);

        // 推到终态：两侧都应不再认为它是进行中
        String relationId =
                str(
                        fissionJdbc,
                        "SELECT relation_id FROM fission_relation WHERE group_id = ?"
                                + " AND follower_id = ?",
                        groupId,
                        follower);
        relationMapper.terminate(relationId, "INVITED", "EXPIRED");

        assertThat(relationMapper.selectActiveFollowerIdsIn(groupId, candidates))
                .as("下推实现须忽略终态关系")
                .isEmpty();
        assertThat(relationMapper.countActiveRelation(groupId, follower))
                .as("基线实现须忽略终态关系")
                .isZero();
    }

    /**
     * <b>SQL 次数对照</b>：下推一页一条，基线一人一条（§7.1 收益的第一笔）。
     *
     * <p>次数取自 MySQL 的 {@code Com_select} 会话计数器，<b>不是按代码结构推断的</b>。这是退出标准 第 2 条要的三组对照数据里唯一能在 CI
     * 里稳定测出的一组 —— 扫描行数与 P99 依赖百万行灌数与 k6，属压测范围（§6.6 已记）。
     *
     * <p><b>取全局计数器而非会话计数器</b>：{@code SHOW SESSION STATUS} 只统计当前连接，而 mapper 调用
     * 从连接池借连接执行，与测试自己持有的那条不是同一个 —— 首次写成会话计数器时两次测量的差值 都是 0（实测）。全局计数器跨连接聚合，正是这里需要的口径。
     *
     * <p>全局计数器会被同容器内的其他活动干扰，故断言取<b>相对关系</b>（基线 ≥ 候选人数、下推不足 基线的十分之一）而非绝对值。本类用例串行执行，量级差异（50 比
     * 1）远大于噪声。
     *
     * <p><b>收益不与页数解耦</b>：候选集本身是分页拉取的，每页仍要执行一次下推查询。本用例只测 一页内的差异 —— 那正是收益的来源（消除页内逐用户查询）。
     */
    @Test
    void pushdownIssuesOneSqlPerPageWhileBaselineIssuesOnePerCandidate() throws Exception {
        String sponsorId = "U_sp_sqlcount";
        String groupId = fissionService.openGroup(ACT, sponsorId);

        int n = 50;
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            candidates.add(sponsorId + "_s" + i);
        }

        try (java.sql.Connection conn = fissionJdbc.getDataSource().getConnection()) {
            long before = comSelect(conn);
            relationMapper.selectActiveFollowerIdsIn(groupId, candidates);
            long pushdownSqls = comSelect(conn) - before;

            before = comSelect(conn);
            for (String id : candidates) {
                relationMapper.countActiveRelation(groupId, id);
            }
            long baselineSqls = comSelect(conn) - before;

            // 计数器本身由 SHOW STATUS 读取，每次读也算一条 select，故断言取相对关系而非绝对值
            assertThat(baselineSqls).as("基线一人一条 SQL，须约等于候选人数 %d", n).isGreaterThanOrEqualTo(n);
            assertThat(pushdownSqls).as("下推整页一条，须远小于基线").isLessThan(baselineSqls / 10);
        }
    }

    /** 实例累计执行的 {@code SELECT} 条数，跨连接聚合。 */
    private static long comSelect(java.sql.Connection conn) throws Exception {
        try (java.sql.Statement st = conn.createStatement();
                java.sql.ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS LIKE 'Com_select'")) {
            rs.next();
            return Long.parseLong(rs.getString(2));
        }
    }

    /**
     * 下推查询走 {@code idx_group_follower_status}，不是 {@code (group_id, status)}。
     *
     * <p><b>索引选错时下推不会报错，只是白做</b>：若走 {@code idx_group_status_id}，{@code follower_id}
     * 只能在回表后逐行过滤，扫描行数仍等于该师傅当前轮次的全部进行中关系（千级）—— §7.1 的 收益第二笔（单次扫描行数降 R/h 倍）完全消失，而功能用例全绿。
     */
    @Test
    void pushdownQueryUsesTheFollowerPrefixedIndex() {
        String plan =
                str(
                        fissionJdbc,
                        "EXPLAIN FORMAT=JSON SELECT follower_id FROM fission_relation"
                                + " WHERE group_id = 'FG_X'"
                                + " AND follower_id IN ('a', 'b', 'c')"
                                + " AND status IN ('INVITED', 'CONNECTED', 'JOINED')");

        assertThat(plan)
                .as("须走 idx_group_follower_status，否则下推的第二笔收益不存在")
                .contains("idx_group_follower_status");
    }
}
