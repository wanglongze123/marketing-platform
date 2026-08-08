package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.fission.dto.FollowerDoneReq;
import com.mp.api.fission.dto.FollowerJoinReq;
import com.mp.api.fission.service.FissionService;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.RelationStatus;
import com.mp.common.enums.TaskStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.task.FissionTaskScheduler;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.ProviderLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 徒弟完成与双向发奖（FR-F07），对应《分阶段方案》§6.5 退出标准 1、4、5。
 *
 * <p><b>本类是 §1.2 论断的验收</b>：{@code FissionFollowerDone} 与 {@code payCallback} 占据同一 架构位置 —— 都是「确权事件 →
 * 触发公共能力层发放」。裂变原样调用为权益售卖设计的 {@code reward.grantReward}，接口签名零改动。
 */
class FissionBidirectionalGrantIT extends AbstractMySqlIT {

    private static final String ACT = "ACT_DEMO_001";

    @Autowired private FissionService fissionService;
    @Autowired private FissionTaskScheduler scheduler;
    @Autowired private FaultInjector injector;
    @Autowired private ProviderLedger ledger;

    @AfterEach
    void resetInjection() {
        injector.reset();
    }

    /** 开轮 + 加入，返回 groupId。确权的前置。 */
    private String joinedGroup(String sponsorId, String followerId, String tag) {
        String groupId = fissionService.openGroup(ACT, sponsorId);
        FollowerJoinReq join = new FollowerJoinReq();
        join.setGroupId(groupId);
        join.setFollowerId(followerId);
        join.setOutBizNo("OB_" + tag);
        join.setOutFlowNo("OF_JOIN_" + tag);
        fissionService.followerJoin(join);
        return groupId;
    }

    private static FollowerDoneReq doneReq(String groupId, String followerId, String tag) {
        FollowerDoneReq req = new FollowerDoneReq();
        req.setGroupId(groupId);
        req.setFollowerId(followerId);
        req.setOutBizNo("OB_" + tag);
        req.setOutFlowNo("OF_DONE_" + tag);
        return req;
    }

    /**
     * 主链路：徒弟发奖成功 → 关系 DONE → 师傅返奖任务落库 → 调度器驱动返奖。
     *
     * <p><b>四写同事务</b>：{@code op=SUCCESS}、关系 {@code DONE}、查单任务 {@code DONE}、 {@code SPONSOR_REWARD}
     * 任务，缺一即为漏发师傅奖（BR-F-20）。
     */
    @Test
    void followerDoneGrantsBothSidesAndAdvancesRelation() {
        String sponsorId = "U_sp_bi";
        String followerId = "U_fo_bi";
        String groupId = joinedGroup(sponsorId, followerId, "bi");

        fissionService.followerDone(doneReq(groupId, followerId, "bi"));

        // ① 操作记录终态
        assertThat(opStatus("OF_DONE_bi")).isEqualTo(OpStatus.SUCCESS.name());

        // ② 关系 DONE，且释放 active_flag
        String relationId = relationIdOf(groupId, followerId);
        assertThat(relationStatus(relationId)).isEqualTo(RelationStatus.DONE.name());
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT active_flag FROM fission_relation WHERE relation_id = ?",
                                relationId))
                .as("终态须释放 active_flag")
                .isEqualTo(relationId);
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT granting_until FROM fission_relation"
                                        + " WHERE relation_id = ?",
                                relationId))
                .as("关系已终结，发奖在途豁免须清空")
                .isNull();

        // ③ 查单任务已了结
        String followerGrantNo = IdempotentKeys.followerGrantNo("OF_DONE_bi");
        assertThat(taskStatus(relationId, TaskType.QUERY_GRANT, followerGrantNo))
                .isEqualTo(TaskStatus.DONE.name());

        // ④ 师傅返奖任务已落库，且尚未执行
        String sponsorFlowNo = IdempotentKeys.sponsorFlowNo("OF_DONE_bi");
        assertThat(taskStatus(relationId, TaskType.SPONSOR_REWARD, sponsorFlowNo))
                .as("师傅返奖任务须与徒弟发奖同事务落库")
                .isEqualTo(TaskStatus.PENDING.name());

        // 徒弟已发奖，师傅尚未 —— 异步的含义
        assertThat(ledger.contains(followerGrantNo)).as("徒弟奖同步发出").isTrue();
        assertThat(ledger.contains(sponsorFlowNo)).as("师傅奖此刻尚未发出").isFalse();

        // 驱动调度器：师傅返奖执行
        scheduler.runOnce();

        assertThat(ledger.contains(sponsorFlowNo)).as("调度器驱动后师傅奖发出").isTrue();
        assertThat(taskStatus(relationId, TaskType.SPONSOR_REWARD, sponsorFlowNo))
                .isEqualTo(TaskStatus.DONE.name());

        // 两笔发放各 1 条，键不同源不冲突
        assertThat(rewardRecordCount(followerGrantNo)).isEqualTo(1);
        assertThat(rewardRecordCount(sponsorFlowNo)).isEqualTo(1);
    }

    /** <b>两把幂等键必须不同</b>：同键会让师傅返奖被 {@code uk_idempotent} 当成徒弟发奖的重传挡下 —— 师傅永远拿不到奖，且不报错。 */
    @Test
    void followerAndSponsorGrantKeysAreDistinctButSameSourced() {
        String outFlowNo = "OF_KEY";
        String followerKey = IdempotentKeys.followerGrantNo(outFlowNo);
        String sponsorKey = IdempotentKeys.sponsorFlowNo(outFlowNo);

        assertThat(followerKey).isNotEqualTo(sponsorKey);
        // 同源派生：都能从 outFlowNo 重算，重试不产生新键
        assertThat(followerKey).isEqualTo(IdempotentKeys.followerGrantNo(outFlowNo));
        assertThat(sponsorKey).isEqualTo(IdempotentKeys.sponsorFlowNo(outFlowNo));
    }

    /**
     * 标准 5：发奖 {@code UNKNOWN} 时<b>关系不推进到 DONE</b>，查单任务保留，{@code granting_until} 已置。
     *
     * <p>判 {@code FAIL} 会让一笔可能已发放的奖被当成没发 —— 补发即重复发放。这是四分类在裂变侧 的同一条约束。
     */
    @Test
    void unknownGrantKeepsRelationJoinedAndPreservesQueryTask() {
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);

        String sponsorId = "U_sp_unk";
        String followerId = "U_fo_unk";
        String groupId = joinedGroup(sponsorId, followerId, "unk");

        fissionService.followerDone(doneReq(groupId, followerId, "unk"));

        String relationId = relationIdOf(groupId, followerId);
        assertThat(relationStatus(relationId))
                .as("结果未定时关系不得推进到 DONE")
                .isEqualTo(RelationStatus.JOINED.name());
        assertThat(opStatus("OF_DONE_unk")).isEqualTo(OpStatus.UNKNOWN.name());

        String followerGrantNo = IdempotentKeys.followerGrantNo("OF_DONE_unk");
        assertThat(taskStatus(relationId, TaskType.QUERY_GRANT, followerGrantNo))
                .as("查单任务须保留，它是唯一的收敛通路")
                .isEqualTo(TaskStatus.PENDING.name());

        // 未推进即无师傅返奖任务 —— 徒弟都没确认发到，不该给师傅返奖
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                relationId,
                                TaskType.SPONSOR_REWARD.name()))
                .isZero();

        // granting_until 已置：发奖在途期间过期治理须跳过这条关系
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT granting_until FROM fission_relation"
                                        + " WHERE relation_id = ?",
                                relationId))
                .as("发奖在途须置豁免，否则一边发奖一边被推到 EXPIRED")
                .isNotNull();
    }

    /**
     * <b>{@code UNKNOWN} 的查单任务真的会被收敛，而不是无人处理。</b>
     *
     * <p>这一条由逐行审阅补入。原用例只断言查单任务为 {@code PENDING} —— 而「等待收敛」与「没有 处理器、无人处理」<b>都是 {@code
     * PENDING}</b>，两者用同一个断言分不开。缺处理器时任务会在 调度器的「无处理器」分支里 {@code PENDING → DOING → PENDING}
     * 无限循环，{@code retry_count} 因该分支不计数而永不增长，连死信都进不去，关系永久停在 {@code JOINED}。
     *
     * <p>判据是<b>驱动调度器之后关系推进到 {@code DONE}</b>，即收敛真的发生了。
     */
    @Test
    void unknownGrantIsActuallyConvergedByQueryTask() {
        // 下游已记账但调用方未收到结果 —— 查单能查得，收敛为成功
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);

        String sponsorId = "U_sp_conv";
        String followerId = "U_fo_conv";
        String groupId = joinedGroup(sponsorId, followerId, "conv");

        fissionService.followerDone(doneReq(groupId, followerId, "conv"));

        String relationId = relationIdOf(groupId, followerId);
        assertThat(relationStatus(relationId)).isEqualTo(RelationStatus.JOINED.name());

        // 恢复下游，驱动查单任务
        injector.setProviderMode(FaultMode.SUCCESS);
        scheduler.runOnce();

        assertThat(relationStatus(relationId))
                .as("查单须真的把关系收敛到 DONE —— 只断言任务 PENDING 分不出「无人处理」")
                .isEqualTo(RelationStatus.DONE.name());
        assertThat(opStatus("OF_DONE_conv")).isEqualTo(OpStatus.SUCCESS.name());

        // 收敛后同样落师傅返奖任务：两条路径复用同一套四写
        String sponsorFlowNo = IdempotentKeys.sponsorFlowNo("OF_DONE_conv");
        assertThat(taskStatus(relationId, TaskType.SPONSOR_REWARD, sponsorFlowNo))
                .as("查单收敛也要落师傅返奖任务，否则这条路径上师傅永远拿不到奖")
                .isNotNull();

        // 全程只发一次：查单收敛不产生第二笔发放
        assertThat(rewardRecordCount(IdempotentKeys.followerGrantNo("OF_DONE_conv"))).isEqualTo(1);
    }

    /** 发奖确定失败：关系保持 JOINED 可重入，{@code granting_until} 清空。 */
    @Test
    void failedGrantKeepsRelationJoinedAndClearsGrantingFlag() {
        injector.setProviderMode(FaultMode.FAIL);

        String sponsorId = "U_sp_fail";
        String followerId = "U_fo_fail";
        String groupId = joinedGroup(sponsorId, followerId, "fail");

        fissionService.followerDone(doneReq(groupId, followerId, "fail"));

        String relationId = relationIdOf(groupId, followerId);
        assertThat(relationStatus(relationId)).isEqualTo(RelationStatus.JOINED.name());
        assertThat(opStatus("OF_DONE_fail")).isEqualTo(OpStatus.FAILED.name());
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT granting_until FROM fission_relation"
                                        + " WHERE relation_id = ?",
                                relationId))
                .as("发奖已确定失败，豁免须清空否则治理永久跳过这条关系")
                .isNull();
    }

    /** 重复确权幂等：同 {@code outFlowNo} 不重复发奖、不产生第二条操作记录。 */
    @Test
    void repeatedDoneIsIdempotentOnOutFlowNo() {
        String sponsorId = "U_sp_idem2";
        String followerId = "U_fo_idem2";
        String groupId = joinedGroup(sponsorId, followerId, "idem2");

        fissionService.followerDone(doneReq(groupId, followerId, "idem2"));
        fissionService.followerDone(doneReq(groupId, followerId, "idem2"));

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_op_record WHERE idempotent_key = ?",
                                "OF_DONE_idem2"))
                .isEqualTo(1);
        assertThat(rewardRecordCount(IdempotentKeys.followerGrantNo("OF_DONE_idem2"))).isEqualTo(1);
    }

    /** 关系非 JOINED 时拒绝确权（1617）—— 对已 DONE 的关系重复确权是重复发奖的入口。 */
    @Test
    void doneOnNonJoinedRelationIsRejected() {
        String sponsorId = "U_sp_nj";
        String followerId = "U_fo_nj";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        // 只分享不加入：关系停在 INVITED
        com.mp.api.fission.dto.ShareInviteReq share = new com.mp.api.fission.dto.ShareInviteReq();
        share.setGroupId(groupId);
        share.setSponsorId(sponsorId);
        share.setFollowerIds(java.util.List.of(followerId));
        share.setShareMethod("IM");
        fissionService.shareInvite(share);

        assertThatThrownBy(() -> fissionService.followerDone(doneReq(groupId, followerId, "nj")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.RELATION_NOT_JOINED);
    }

    /**
     * 师傅返奖失败不影响徒弟已成功的奖励（BR-F-20）。
     *
     * <p>回滚徒弟奖等于因为师傅没拿到而把已发给徒弟的收回去 —— 对用户是凭空被扣。
     */
    @Test
    void sponsorRewardFailureDoesNotAffectFollowerGrant() {
        String sponsorId = "U_sp_sf";
        String followerId = "U_fo_sf";
        String groupId = joinedGroup(sponsorId, followerId, "sf");

        fissionService.followerDone(doneReq(groupId, followerId, "sf"));
        String followerGrantNo = IdempotentKeys.followerGrantNo("OF_DONE_sf");
        assertThat(ledger.contains(followerGrantNo)).isTrue();

        // 师傅返奖时下游失败
        injector.setProviderMode(FaultMode.FAIL);
        scheduler.runOnce();

        String relationId = relationIdOf(groupId, followerId);
        assertThat(relationStatus(relationId))
                .as("师傅返奖失败不回滚关系终态")
                .isEqualTo(RelationStatus.DONE.name());
        assertThat(ledger.contains(followerGrantNo)).as("徒弟已发的奖不被收回").isTrue();
    }

    // ---- 辅助 ----

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

    private String opStatus(String idempotentKey) {
        return str(
                fissionJdbc,
                "SELECT status FROM fission_op_record WHERE idempotent_key = ?",
                idempotentKey);
    }

    private String taskStatus(String bizNo, TaskType type, String opNo) {
        java.util.List<String> rows =
                fissionJdbc.queryForList(
                        "SELECT status FROM fission_task WHERE biz_no = ? AND task_type = ?"
                                + " AND op_no = ?",
                        String.class,
                        bizNo,
                        type.name(),
                        opNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int rewardRecordCount(String opNo) {
        return count(rewardJdbc, "SELECT COUNT(*) FROM reward_grant_record WHERE op_no = ?", opNo);
    }
}
