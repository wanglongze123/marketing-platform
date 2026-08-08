package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.fission.dto.FollowerDoneReq;
import com.mp.api.fission.dto.FollowerJoinReq;
import com.mp.api.fission.service.FissionService;
import com.mp.common.enums.RelationStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.task.FissionTaskScheduler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 裂变侧对账（技术方案 §6.8 第 7、10、12、13 项）。PR-10 后置 review 补。
 *
 * <p><b>本类此前不存在，因为被测的东西根本没接线</b>：{@code FissionReconcileService} 早已实现，但全仓 没有任何调用方 —— 四项从未运行过，其中第 7
 * 项（师傅奖漏发）与第 13 项（发奖在途标志超时）都是 资损哨兵。一条对账项写好了却没接线，与没写的区别只在代码行数上。
 *
 * <p><b>第 7 项的键派生此前也是错的</b>：读 {@code relation.out_biz_no} 当 {@code outFlowNo} 用，而技术方案 §4.1
 * 把两者定义为不同的东西 —— 一次关系下可发生多次操作（加入与确权各带各的 {@code OutFlowNo}）， 关系行存的是加入时那次的 {@code
 * OutBizNo}。补出的键与主链路那把不同 → 唯一键不冲突 → 下游当成 一笔全新的发放 → 师傅奖发两次。<b>这一项本是「漏发」的兜底，那样写反而造出重发。</b>
 *
 * <p>{@code stale-seconds=0} 的理由同 {@link ReconcileIT}：压的是判据的时间维度，不是判据本身。
 */
@TestPropertySource(properties = "mp.reconcile.stale-seconds=0")
class FissionReconcileIT extends AbstractMySqlIT {

    private static final String ACT = "ACT_DEMO_001";

    @Autowired private FissionService fissionService;
    @Autowired private FissionTaskScheduler fissionScheduler;

    /** 开轮 + 加入 + 确权，走完主链路，返回 relationId。 */
    private String settledRelation(String tag) {
        String sponsorId = "U_sp_" + tag;
        String followerId = "U_fo_" + tag;
        String groupId = fissionService.openGroup(ACT, sponsorId);

        FollowerJoinReq join = new FollowerJoinReq();
        join.setGroupId(groupId);
        join.setFollowerId(followerId);
        join.setOutBizNo("OB_" + tag);
        join.setOutFlowNo("OF_JOIN_" + tag);
        fissionService.followerJoin(join);

        FollowerDoneReq done = new FollowerDoneReq();
        done.setGroupId(groupId);
        done.setFollowerId(followerId);
        done.setOutBizNo("OB_" + tag);
        done.setOutFlowNo("OF_DONE_" + tag);
        fissionService.followerDone(done);

        String relationId = relationIdOf(groupId, followerId);
        assertThat(relationStatus(relationId)).isEqualTo(RelationStatus.DONE.name());
        return relationId;
    }

    // ------------------------------------------------------------------
    // 第 7 项：徒弟已发师傅未返
    // ------------------------------------------------------------------

    /**
     * <b>师傅返奖任务缺失 → 检出并补建</b>。
     *
     * <p>删掉 {@code SPONSOR_REWARD} 任务，模拟「那次四写事务只成了一半」—— 关系已 {@code DONE} 而返奖 任务不存在，师傅奖永久漏发且无重试载体。
     *
     * <p>断言两件事：差异被检出、且补建的任务真能把师傅奖发出去。只断检出的话，一个「扫出来但什么 都没做」的实现照样绿。
     */
    @Test
    void detectsAndRepairsMissingSponsorReward() {
        String relationId = settledRelation("rec7");
        fissionJdbc.update(
                "DELETE FROM fission_task WHERE biz_no = ? AND task_type = ?",
                relationId,
                TaskType.SPONSOR_REWARD.name());

        Map<String, Integer> diffs = fissionService.reconcile();

        assertThat(diffs.get("SPONSOR_NOT_REWARDED")).as("师傅返奖任务缺失须被检出").isPositive();
        assertThat(sponsorTaskCount(relationId)).as("须补建返奖任务").isEqualTo(1);

        // 补建后真的能把奖发出去，才叫自愈
        fissionScheduler.runOnce();
        assertThat(sponsorTaskStatus(relationId)).isEqualTo("DONE");
    }

    /**
     * <b>补建的返奖键必须与主链路同键</b> —— 这是本次 review 修掉的那个缺陷的守卫。
     *
     * <p>两把发奖键都派生自 {@code outFlowNo}（{@code _FL} / {@code _SP}），而关系行上根本没有这个值。 首版读 {@code
     * out_biz_no} 当 {@code outFlowNo} 用，派生出的是一把<b>与主链路不同的键</b>：
     *
     * <pre>
     * 主链路：  sponsorFlowNo("OF_DONE_x") = "OF_DONE_x_SP"
     * 错误补建：sponsorFlowNo("OB_x")      = "OB_x_SP"      ← 新键
     * </pre>
     *
     * <p>于是 {@code uk_biz_type_op} 不冲突、任务照常入队、下游按新 {@code opNo} 当成一笔全新的发放 —— <b>师傅奖发两次，直接资损</b>。
     *
     * <p><b>本用例刻意让 {@code outBizNo} 与 {@code outFlowNo} 取不同值</b>（{@code OB_rec7key} 与 {@code
     * OF_DONE_rec7key}）：两者同值时错误实现照样能派生出正确的键，这条守卫就形同虚设 —— 而 §4.1 明确 二者不天然相等。
     */
    @Test
    void repairedSponsorKeyMatchesMainPath() {
        String relationId = settledRelation("rec7key");
        String expected = IdempotentKeys.sponsorFlowNo("OF_DONE_rec7key");
        String wrongKey = IdempotentKeys.sponsorFlowNo("OB_rec7key");
        assertThat(expected).as("用例前提：两把键必须不同，否则守卫无效").isNotEqualTo(wrongKey);

        fissionJdbc.update(
                "DELETE FROM fission_task WHERE biz_no = ? AND task_type = ?",
                relationId,
                TaskType.SPONSOR_REWARD.name());

        fissionService.reconcile();

        assertThat(sponsorTaskOpNo(relationId))
                .as("补建的键须与主链路一致 —— 新造键即让下游当成第二笔发放")
                .isEqualTo(expected);
        assertThat(sponsorTaskCount(relationId)).as("同键不产生第二条任务").isEqualTo(1);
    }

    /**
     * <b>补不出正确的键时宁可不补</b>——查单任务缺失即无从反推 {@code outFlowNo}。
     *
     * <p>猜一把键的代价是重复发奖，不可逆；不补的代价只是这一条继续挂着等人工，可逆。故这一支 只告警不入队。
     *
     * <p>缺了这条守卫，一个「取不到就拿 {@code out_biz_no} 兜底」的实现照样通过上一条 —— 而那正是被修掉的 那个错误换了个位置回来。
     */
    @Test
    void skipsRepairWhenKeyCannotBeDerived() {
        String relationId = settledRelation("rec7nokey");
        fissionJdbc.update(
                "DELETE FROM fission_task WHERE biz_no = ? AND task_type IN (?, ?)",
                relationId,
                TaskType.SPONSOR_REWARD.name(),
                TaskType.QUERY_GRANT.name());

        fissionService.reconcile();

        assertThat(sponsorTaskCount(relationId)).as("反推不出原键时不得猜一个 —— 猜错即重复发奖").isZero();
    }

    // ------------------------------------------------------------------
    // 第 13 项：发奖在途标志超时
    // ------------------------------------------------------------------

    /**
     * <b>{@code granting_until} 超时 → 清空豁免，让过期治理能接管</b>。
     *
     * <p>这是四项里唯一直接改字段的处置，理由是<b>不改它就没有任何机制能再动这行</b>：豁免未到期时 过期治理跳过该关系，而超时意味着发奖早该收敛却没有 ——
     * 关系既不被治理接管，也没人推进它， 永久悬挂。
     *
     * <p>清的是豁免标志而非业务结果，故仍属「把单子推回既有通路」。
     */
    @Test
    void clearsExpiredGrantingFlag() {
        String sponsorId = "U_sp_rec13";
        String followerId = "U_fo_rec13";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        FollowerJoinReq join = new FollowerJoinReq();
        join.setGroupId(groupId);
        join.setFollowerId(followerId);
        join.setOutBizNo("OB_rec13");
        join.setOutFlowNo("OF_JOIN_rec13");
        fissionService.followerJoin(join);

        String relationId = relationIdOf(groupId, followerId);
        // 造一个已超时的在途标志：正常链路下它由 markGranting 置成未来时刻
        fissionJdbc.update(
                "UPDATE fission_relation SET granting_until = DATE_SUB(NOW(3), INTERVAL 1 HOUR)"
                        + " WHERE relation_id = ?",
                relationId);

        Map<String, Integer> diffs = fissionService.reconcile();

        assertThat(diffs.get("GRANTING_UNTIL_EXPIRED")).as("超时的在途标志须被检出").isPositive();
        assertThat(
                        fissionJdbc.queryForObject(
                                "SELECT granting_until FROM fission_relation"
                                        + " WHERE relation_id = ?",
                                Object.class,
                                relationId))
                .as("超时后须清空豁免，否则这行既不推进也不被治理 —— 永生")
                .isNull();
    }

    /**
     * <b>未超时的在途标志不得被清</b> —— 第 13 项的反方向。
     *
     * <p>缺了它，一个「无条件清空 {@code granting_until}」的实现照样通过上一条 —— 而那会让正在发奖的 关系失去豁免，被过期治理推到 {@code
     * EXPIRED}，一边在发奖一边被判过期（BR-F-26 要挡的正是这个）。
     */
    @Test
    void keepsGrantingFlagWhileStillInWindow() {
        String sponsorId = "U_sp_rec13ok";
        String followerId = "U_fo_rec13ok";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        FollowerJoinReq join = new FollowerJoinReq();
        join.setGroupId(groupId);
        join.setFollowerId(followerId);
        join.setOutBizNo("OB_rec13ok");
        join.setOutFlowNo("OF_JOIN_rec13ok");
        fissionService.followerJoin(join);

        String relationId = relationIdOf(groupId, followerId);
        fissionJdbc.update(
                "UPDATE fission_relation SET granting_until = DATE_ADD(NOW(3), INTERVAL 1 HOUR)"
                        + " WHERE relation_id = ?",
                relationId);

        fissionService.reconcile();

        assertThat(
                        fissionJdbc.queryForObject(
                                "SELECT granting_until FROM fission_relation"
                                        + " WHERE relation_id = ?",
                                Object.class,
                                relationId))
                .as("窗口内的豁免须保留，否则发奖中的关系会被治理推到 EXPIRED")
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // 第 10 项：轮次进度未推进 —— 只告警，不改数
    // ------------------------------------------------------------------

    /**
     * <b>进度落后须被检出，但不得自动改 {@code progress}</b>。
     *
     * <p>它是共享计数器，与库存、限购额度同类 —— 正确值取决于历史上哪些关系完成过，直接改会把 一次错误固化成新基线（§6.8 对第 6、15 项的同一条处置）。
     */
    @Test
    void detectsProgressLagWithoutMutatingIt() {
        String relationId = settledRelation("rec10");
        String groupId =
                str(
                        fissionJdbc,
                        "SELECT group_id FROM fission_relation WHERE relation_id = ?",
                        relationId);
        // 人工把进度抹掉，制造「关系已 DONE 而进度没含它」
        fissionJdbc.update("UPDATE fission_group SET progress = 0 WHERE group_id = ?", groupId);

        Map<String, Integer> diffs = fissionService.reconcile();

        assertThat(diffs.get("RELATION_PROGRESS_LAG")).as("进度落后须被检出").isPositive();
        assertThat(
                        num(
                                fissionJdbc,
                                "SELECT progress FROM fission_group WHERE group_id = ?",
                                groupId))
                .as("禁止自动改数 —— 共享计数器的正确值取决于历史")
                .isZero();
    }

    // ------------------------------------------------------------------
    // 整轮行为
    // ------------------------------------------------------------------

    /**
     * <b>一条链路完整的关系不得贡献任何差异</b>——正常数据不该刷出告警。
     *
     * <p>假告警会让资损哨兵失效，这与 §6.8 给「差异」定义时间下界是同一条理由。
     *
     * <p><b>断言取增量而非全局计数</b>：对账扫的是全库，同类前面的用例会刻意留下补不掉的差异 （{@code skipsRepairWhenKeyCannotBeDerived}
     * 那条按设计就永远补不出键）。断言全局为空等于 要求测试按顺序执行、且彼此不留痕 —— 那是比被测行为更强的假设。
     */
    @Test
    void healthyRelationAddsNoDiff() {
        int sponsorBefore = diffOf(fissionService.reconcile(), "SPONSOR_NOT_REWARDED");
        int grantingBefore = diffOf(fissionService.reconcile(), "GRANTING_UNTIL_EXPIRED");

        settledRelation("rec_ok");

        Map<String, Integer> diffs = fissionService.reconcile();
        assertThat(diffOf(diffs, "SPONSOR_NOT_REWARDED") - sponsorBefore)
                .as("链路完整时不得报师傅奖漏发")
                .isZero();
        assertThat(diffOf(diffs, "GRANTING_UNTIL_EXPIRED") - grantingBefore)
                .as("确权后 granting_until 已清空，不得报超时")
                .isZero();
    }

    /**
     * <b>重复跑对账不产生第二条任务</b>——处置动作必须幂等（BR-C-24）。
     *
     * <p>对账是旁路，可能被运维连点、也可能两个实例同时跑。补建走 {@code enqueue} 的 upsert，命中 {@code uk_biz_type_op} 不新增行。
     */
    @Test
    void repeatedReconcileIsIdempotent() {
        String relationId = settledRelation("rec_idem");
        fissionJdbc.update(
                "DELETE FROM fission_task WHERE biz_no = ? AND task_type = ?",
                relationId,
                TaskType.SPONSOR_REWARD.name());

        fissionService.reconcile();
        fissionService.reconcile();

        assertThat(sponsorTaskCount(relationId)).as("重复补建不得产生第二条任务").isEqualTo(1);
    }

    // ---- 辅助 ----

    /** 取某项的差异数，未出现即 0 —— 无差异的项不进返回。 */
    private static int diffOf(Map<String, Integer> diffs, String item) {
        return diffs.getOrDefault(item, 0);
    }

    private String relationIdOf(String groupId, String followerId) {
        return str(
                fissionJdbc,
                "SELECT relation_id FROM fission_relation WHERE group_id = ? AND follower_id = ?",
                groupId,
                followerId);
    }

    private String relationStatus(String relationId) {
        return str(
                fissionJdbc,
                "SELECT status FROM fission_relation WHERE relation_id = ?",
                relationId);
    }

    private int sponsorTaskCount(String relationId) {
        return count(
                fissionJdbc,
                "SELECT COUNT(*) FROM fission_task WHERE biz_no = ? AND task_type = ?",
                relationId,
                TaskType.SPONSOR_REWARD.name());
    }

    private String sponsorTaskOpNo(String relationId) {
        return str(
                fissionJdbc,
                "SELECT op_no FROM fission_task WHERE biz_no = ? AND task_type = ?",
                relationId,
                TaskType.SPONSOR_REWARD.name());
    }

    private String sponsorTaskStatus(String relationId) {
        return str(
                fissionJdbc,
                "SELECT status FROM fission_task WHERE biz_no = ? AND task_type = ?",
                relationId,
                TaskType.SPONSOR_REWARD.name());
    }
}
