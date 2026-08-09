package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.fission.service.FissionService;
import com.mp.common.enums.RelationStatus;
import com.mp.common.enums.TaskStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.service.RelationExpireService;
import com.mp.fission.task.ExpireShard;
import com.mp.fission.task.FissionExpireSeeder;
import com.mp.fission.task.FissionTaskScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 关系与轮次过期治理（FR-F09），对应《分阶段方案》§6.5 退出标准 6、7、24。
 *
 * <p>治理的批量语句有三个组成部分，<b>缺一不可</b>（技术方案 §3.3）：分片区间、排除发奖在途、 释放 {@code active_flag}。三者各自有用例。
 *
 * <p><b>第三部分是最容易漏的</b>：漏了它，被治理的行 {@code status} 已是 {@code EXPIRED}，看起来完全 正确 —— 但仍占着 {@code
 * (group_id, follower_id, 'ACTIVE')}，该师徒下一轮分享被静默吞掉。 故判据取「治理后能否建出新行」，不取「状态对不对」。
 */
class FissionExpireGovernanceIT extends AbstractMySqlIT {

    /** 裂变活动，见 {@link AbstractMySqlIT#FISSION_ACTIVITY_ID}。 */
    private static final String ACT = FISSION_ACTIVITY_ID;

    /** 已过期：造数据时直接写进 {@code expire_time} */
    private static final String PAST = "2020-01-01 00:00:00.000";

    /** 未过期 */
    private static final String FUTURE = "2030-12-31 23:59:59.999";

    @Autowired private FissionService fissionService;
    @Autowired private FissionRelationMapper relationMapper;
    @Autowired private FissionGroupMapper groupMapper;
    @Autowired private RelationExpireService expireService;
    @Autowired private FissionExpireSeeder seeder;
    @Autowired private FissionTaskScheduler scheduler;

    /** V3 单进程恒为单分片，与生产一致。 */
    private static final ExpireShard SHARD = new ExpireShard(0, 1);

    /**
     * 造一条关系。
     *
     * <p>直接走 mapper 而非业务接口：业务接口的关系有效期取自轮次，无法造出「已过期」的行 —— 而过期正是本类要测的前提。
     */
    private String relation(String groupId, String followerId, RelationStatus status, String exp) {
        String relationId = BizNoGenerator.fissionRelationNo();
        relationMapper.insertActive(
                relationId, groupId, ACT, "U_sp_exp", followerId, "", status.name(), "IM", exp);
        return relationId;
    }

    private String statusOf(String relationId) {
        return str(
                fissionJdbc,
                "SELECT status FROM fission_relation WHERE relation_id = ?",
                relationId);
    }

    private String activeFlagOf(String relationId) {
        return str(
                fissionJdbc,
                "SELECT active_flag FROM fission_relation WHERE relation_id = ?",
                relationId);
    }

    // ------------------------------------------------------------------
    // 标准 6：发奖在途豁免
    // ------------------------------------------------------------------

    /**
     * 标准 6：{@code granting_until} 未到期的关系<b>不被治理</b>；置为过去后再驱动即转 {@code EXPIRED}。
     *
     * <p><b>两半必须在同一个用例里</b>：只测前半段（跳过）的话，「治理语句根本没扫到这一行」 与「扫到了但正确跳过」表现一致 —— 而前者在分片区间写错时就会发生。后半段用同一条关系
     * 证明它确实在扫描范围内。
     *
     * <p>这也是 BR-F-26 的两半：豁免（正面）与超时兜底（防止行永生）。
     */
    @Test
    void grantingRelationIsSkippedUntilItsWindowExpires() {
        String groupId = "FG_EXP_GRANTING";
        String relationId = relation(groupId, "U_granting", RelationStatus.JOINED, PAST);
        // 发奖在途：窗口 10 分钟，远未到期
        relationMapper.markGranting(relationId, 600);

        expireService.sweep(SHARD);

        assertThat(statusOf(relationId)).as("发奖在途的关系不得被治理（BR-F-26）").isEqualTo("JOINED");
        assertThat(activeFlagOf(relationId)).as("未被治理则仍占唯一键").isEqualTo("ACTIVE");

        // 窗口置为过去：发奖进程崩溃的形态。豁免必须自带超时，否则这行永生
        fissionJdbc.update(
                "UPDATE fission_relation SET granting_until = ? WHERE relation_id = ?",
                PAST,
                relationId);

        expireService.sweep(SHARD);

        assertThat(statusOf(relationId)).as("豁免超时后治理接管").isEqualTo("EXPIRED");
        assertThat(activeFlagOf(relationId)).isEqualTo(relationId);
    }

    /** {@code granting_until IS NULL}（不在发奖流程）的到期关系照常被治理。 */
    @Test
    void relationWithoutGrantingFlagIsExpired() {
        String groupId = "FG_EXP_PLAIN";
        String relationId = relation(groupId, "U_plain", RelationStatus.JOINED, PAST);

        expireService.sweep(SHARD);

        assertThat(statusOf(relationId)).isEqualTo("EXPIRED");
    }

    /** 未到期的关系不被治理 —— 谓词的另一侧。 */
    @Test
    void unexpiredRelationIsUntouched() {
        String groupId = "FG_EXP_FUTURE";
        String relationId = relation(groupId, "U_future", RelationStatus.JOINED, FUTURE);

        expireService.sweep(SHARD);

        assertThat(statusOf(relationId)).isEqualTo("JOINED");
    }

    /**
     * <b>治理释放 {@code active_flag}，该师徒可重新建立关系</b>（标准 3 的第二条路径，由治理实际驱动）。
     *
     * <p>{@code FissionRelationStateIT} 已覆盖「{@code terminate} 释放唯一键」，但那走的是 mapper 方法； 治理走的是另一条 SQL
     * —— 两条语句各写各的，前者正确不蕴含后者正确。这正是「三条终态路径 必须逐条验」的意思。
     *
     * <p>判据是<b>「拿到的是不是新行」</b>：漏掉释放时，第二次分享撞唯一键、被「先插后判」当成 幂等命中，接口返回成功而实际返回的是那条已过期的关系。
     */
    @Test
    void expiredRelationReleasesUniqueKeyAndAllowsRebuild() {
        String sponsorId = "U_sp_rebuild";
        String followerId = "U_fo_rebuild";
        String groupId = fissionService.openGroup(ACT, sponsorId);

        String first = relation(groupId, followerId, RelationStatus.INVITED, PAST);
        expireService.sweep(SHARD);
        assertThat(statusOf(first)).isEqualTo("EXPIRED");

        // 同师徒重新建关系：治理若不释放 active_flag，这一步撞唯一键
        String second = relation(groupId, followerId, RelationStatus.INVITED, FUTURE);

        assertThat(second).as("须是新行，不是被静默返回的旧行").isNotEqualTo(first);
        assertThat(activeFlagOf(second)).isEqualTo("ACTIVE");
        assertThat(activeFlagOf(first)).as("旧行已移出唯一键约束").isEqualTo(first);
    }

    /** 终态关系不被重复治理 —— {@code status IN (...)} 谓词的另一侧。 */
    @Test
    void terminalRelationIsNotReExpired() {
        String groupId = "FG_EXP_DONE";
        String relationId = relation(groupId, "U_done", RelationStatus.JOINED, PAST);
        relationMapper.terminate(
                relationId, RelationStatus.JOINED.name(), RelationStatus.DONE.name());

        expireService.sweep(SHARD);

        assertThat(statusOf(relationId)).as("已 DONE 的关系不得被改成 EXPIRED").isEqualTo("DONE");
    }

    // ------------------------------------------------------------------
    // 标准 7：批量语句走索引、不重复扫描
    // ------------------------------------------------------------------

    /**
     * 标准 7：灌 N 条到期关系，一轮治理全部推进，<b>总更新数恰为 N</b>。
     *
     * <p>N 取 {@code BATCH + 1}（501）：跨过一次 {@code LIMIT 500} 边界，覆盖「循环执行至 {@code affected_rows <
     * limit}」这条终止条件。取 500 以内则循环只跑一次，多批次的正确性未被触及。
     *
     * <p><b>判据必须是「总更新数恰为 N」，不能是「N 行变成了 EXPIRED」</b>：一行被扫两次，最终状态仍是 {@code EXPIRED} ——
     * 按最终状态断言的话，重复扫描完全隐形。而重复扫描正是这条语句最可能的失效 方式：谓词写漏 {@code status} 时，更新后的行不离开 {@code WHERE}
     * 集合，第二批把第一批再扫 一遍，直到批次上限才停。
     *
     * <p>故<b>先空扫一轮排干存量</b> —— 同一容器里别的用例留下的到期行也会计入返回值，不排干则只能 断言「≥ N」，而那恰好放过了「多扫」这个失效。
     */
    @Test
    void batchExpireCoversAllRowsExactlyOnce() {
        // 排干：本轮之后表内无任何可治理的行，下一轮返回值只反映本用例灌入的数据
        expireService.sweep(SHARD);

        String groupId = "FG_EXP_BULK";
        int n = 501;
        for (int i = 0; i < n; i++) {
            relation(groupId, "U_bulk_" + i, RelationStatus.INVITED, PAST);
        }

        int expired = expireService.sweep(SHARD);

        assertThat(expired).as("总更新数须恰为灌入数：多出即重复扫描，少了即没扫完").isEqualTo(n);
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ?"
                                        + " AND status = 'EXPIRED'",
                                groupId))
                .isEqualTo(n);
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ?"
                                        + " AND status <> 'EXPIRED'",
                                groupId))
                .as("一轮须扫完，不留尾巴")
                .isZero();

        // 再扫一次：全部已终态，无行可动
        assertThat(expireService.sweep(SHARD)).as("幂等：第二次无行可更新（BR-F-25）").isZero();
    }

    /**
     * <b>批量语句必须真的按 {@code id} 区间限定范围</b>。
     *
     * <p>本用例由注入自查补入：删掉 {@code AND id BETWEEN}（改为恒真条件）后，此前的 12 条用例 <b>全部保持绿色</b> —— V3 单进程恒为单分片，区间是
     * {@code [0, Long.MAX_VALUE]}，删不删都一样。 而《分阶段方案》§6.4 ② 明确要求「不因为只有一个分片就省掉 {@code id BETWEEN}」：省了它，
     * V4 加分片时这条会扫百万行的批量语句要重写，届时需重新验证执行计划。
     *
     * <p>故直接调 mapper 传一个<b>刻意排除目标行</b>的窄区间，绕开「单分片」这个使区间不可观测的 前提。这是一处「结论无对应用例」—— 与《分阶段方案》§6.6 记的
     * PR-4、PR-5 教训同类，区别在于 那两处是缺用例，此处是<b>约束在当前形态下不可观测</b>，只能构造出可观测的条件来验。
     */
    @Test
    void batchExpireRespectsShardInterval() {
        String groupId = "FG_EXP_SHARD";
        String relationId = relation(groupId, "U_shard", RelationStatus.INVITED, PAST);
        long id =
                num(
                                fissionJdbc,
                                "SELECT id FROM fission_relation WHERE relation_id = ?",
                                relationId)
                        .longValue();

        // 区间落在目标行之前：该行不得被治理
        assertThat(relationMapper.expireBatch(0, id - 1, 500)).as("区间外的行不得被更新").isZero();
        assertThat(statusOf(relationId)).isEqualTo("INVITED");

        // 区间落在目标行之后：同样不得被治理
        assertThat(relationMapper.expireBatch(id + 1, Long.MAX_VALUE, 500)).isZero();
        assertThat(statusOf(relationId)).isEqualTo("INVITED");

        // 区间覆盖目标行：治理生效 —— 证明前两次的 0 是区间挡住的，不是别的原因
        assertThat(relationMapper.expireBatch(id, id, 500)).isEqualTo(1);
        assertThat(statusOf(relationId)).isEqualTo("EXPIRED");
    }

    /** 轮次治理的分片区间同样生效 —— 两条语句各写各的，前者正确不蕴含后者正确。 */
    @Test
    void groupExpireRespectsShardInterval() {
        String sponsorId = "U_sp_shard_grp";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        fissionJdbc.update(
                "UPDATE fission_group SET expire_time = ? WHERE group_id = ?", PAST, groupId);
        long id =
                num(fissionJdbc, "SELECT id FROM fission_group WHERE group_id = ?", groupId)
                        .longValue();

        assertThat(groupMapper.expireBatch(0, id - 1, 500)).as("区间外的轮次不得被更新").isZero();
        assertThat(str(fissionJdbc, "SELECT status FROM fission_group WHERE group_id = ?", groupId))
                .isEqualTo("RUNNING");

        assertThat(groupMapper.expireBatch(id, id, 500)).isEqualTo(1);
        assertThat(str(fissionJdbc, "SELECT status FROM fission_group WHERE group_id = ?", groupId))
                .isEqualTo("EXPIRED");
    }

    /**
     * 标准 7：批量语句走 {@code idx_expire}，不是全表扫描。
     *
     * <p><b>断言索引名而非「快不快」</b>：性能断言在 CI 上不稳定，而执行计划是确定的。这条语句 在生产要扫百万行的表，选错索引的表现是「慢」而非「错」——
     * 不会有任何功能用例变红。
     */
    @Test
    void batchExpireUsesExpireIndex() {
        String plan =
                str(
                        fissionJdbc,
                        "EXPLAIN FORMAT=JSON UPDATE fission_relation"
                                + " SET status = 'EXPIRED', active_flag = relation_id"
                                + " WHERE status IN ('INVITED', 'CONNECTED', 'JOINED')"
                                + " AND expire_time < NOW(3)"
                                + " AND (granting_until IS NULL OR granting_until < NOW(3))"
                                + " AND id BETWEEN 0 AND 9223372036854775807"
                                + " LIMIT 500");

        assertThat(plan).as("须走 idx_expire，不得全表扫描（BR-F-24）").contains("idx_expire");
    }

    // ------------------------------------------------------------------
    // 轮次治理
    // ------------------------------------------------------------------

    /**
     * <b>轮次过期同样要治理，否则该师傅永远开不了下一轮</b>（《分阶段方案》§6.6 的连带结论）。
     *
     * <p>{@code openGroup} 的判据取 {@code selectActive}（只认 {@code active_flag}），故已过期但未被治理的 轮次仍挡着开新轮 ——
     * 且报的是业务提示「已存在未终结的轮次」，看起来像正常拒绝。
     *
     * <p>判据取「治理后能否开出新轮」，不取轮次的 {@code status} —— 与关系那条同理。
     */
    @Test
    void expiredGroupReleasesUniqueKeyAndAllowsNextRound() {
        String sponsorId = "U_sp_group_exp";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        fissionJdbc.update(
                "UPDATE fission_group SET expire_time = ? WHERE group_id = ?", PAST, groupId);

        expireService.sweep(SHARD);

        assertThat(str(fissionJdbc, "SELECT status FROM fission_group WHERE group_id = ?", groupId))
                .isEqualTo("EXPIRED");
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT active_flag FROM fission_group WHERE group_id = ?",
                                groupId))
                .as("终态须释放 active_flag，否则下一轮开不出来")
                .isEqualTo(groupId);

        String next = fissionService.openGroup(ACT, sponsorId);
        assertThat(next).as("治理后可开下一轮").isNotEqualTo(groupId);
    }

    /** 未到期的轮次不被治理。 */
    @Test
    void unexpiredGroupIsUntouched() {
        String sponsorId = "U_sp_group_alive";
        String groupId = fissionService.openGroup(ACT, sponsorId);

        expireService.sweep(SHARD);

        assertThat(str(fissionJdbc, "SELECT status FROM fission_group WHERE group_id = ?", groupId))
                .isEqualTo("RUNNING");
    }

    // ------------------------------------------------------------------
    // 标准 24：任务终态为 DONE 而非 DEAD
    // ------------------------------------------------------------------

    /**
     * 标准 24（{@code RELATION_EXPIRE} 一项）：治理任务收敛后为 {@code DONE}，不陪着重试到死信。
     *
     * <p><b>「扫完置 DONE + 播种复活」而非「返回 PROCESSING 永不了结」</b>：后者跑得通，但 {@code retry_count} 随每轮累加，5 轮后进
     * {@code DEAD} —— 而那不是失败。死信表被正常运转的 治理任务填满，与 V2 PR-8 修正的第 2 项是同一族失效。
     *
     * <p>驱动 8 轮远超阈值 5：若实现返回 {@code PROCESSING}，此处必红。
     */
    @Test
    void expireTaskEndsAtDoneNotDead() {
        seeder.seed();

        String bizNo = SHARD.bizNo();
        String opNo = IdempotentKeys.expireOpNo(bizNo);
        assertThat(taskStatus(bizNo, opNo)).as("播种后任务待执行").isEqualTo(TaskStatus.PENDING.name());

        for (int i = 0; i < 8; i++) {
            scheduler.runOnce();
        }

        assertThat(taskStatus(bizNo, opNo))
                .as("扫完即 DONE，不得进 DEAD")
                .isEqualTo(TaskStatus.DONE.name());
        assertThat(retryCount(bizNo, opNo)).as("一次成功的扫描不计重试").isZero();
    }

    /**
     * 播种幂等：重复播种不重置在途任务，也不新建第二条。
     *
     * <p>周期性完全由播种提供（任务扫完即 {@code DONE}），故播种必须能把终态任务复活 —— 而在途的 那条不得被动，否则每次播种都把它的 {@code next_time}
     * 提前，退避不起作用。
     */
    @Test
    void seedingIsIdempotentAndRevivesFinishedTask() {
        seeder.seed();
        seeder.seed();

        String bizNo = SHARD.bizNo();
        String opNo = IdempotentKeys.expireOpNo(bizNo);
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_task WHERE biz_no = ?"
                                        + " AND task_type = ? AND op_no = ?",
                                bizNo,
                                TaskType.RELATION_EXPIRE.name(),
                                opNo))
                .as("重复播种不新建第二条")
                .isEqualTo(1);

        scheduler.runOnce();
        assertThat(taskStatus(bizNo, opNo)).isEqualTo(TaskStatus.DONE.name());

        // 下一个播种周期：复活
        seeder.seed();
        assertThat(taskStatus(bizNo, opNo))
                .as("终态任务须被复活，否则治理只跑一次就停")
                .isEqualTo(TaskStatus.PENDING.name());
    }

    /** 治理由任务驱动时与直接调用等效 —— 处理器确实接上了，不是落进「无处理器」分支。 */
    @Test
    void expireTaskActuallySweeps() {
        String groupId = "FG_EXP_VIA_TASK";
        String relationId = relation(groupId, "U_via_task", RelationStatus.JOINED, PAST);

        seeder.seed();
        scheduler.runOnce();

        assertThat(statusOf(relationId)).as("任务驱动的治理须真的推进关系").isEqualTo("EXPIRED");
    }

    private String taskStatus(String bizNo, String opNo) {
        return str(
                fissionJdbc,
                "SELECT status FROM fission_task WHERE biz_no = ? AND task_type = ? AND op_no = ?",
                bizNo,
                TaskType.RELATION_EXPIRE.name(),
                opNo);
    }

    private Integer retryCount(String bizNo, String opNo) {
        return num(
                fissionJdbc,
                "SELECT retry_count FROM fission_task WHERE biz_no = ? AND task_type = ?"
                        + " AND op_no = ?",
                bizNo,
                TaskType.RELATION_EXPIRE.name(),
                opNo);
    }
}
