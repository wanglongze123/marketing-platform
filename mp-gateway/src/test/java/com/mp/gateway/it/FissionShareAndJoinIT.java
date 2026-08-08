package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.fission.dto.FollowerJoinReq;
import com.mp.api.fission.dto.ShareInviteReq;
import com.mp.api.fission.dto.ShareInviteResp;
import com.mp.api.fission.service.FissionService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RelationStatus;
import com.mp.common.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 分享、建联与徒弟加入（FR-F05、FR-F06），对应《分阶段方案》§6.5 退出标准 2。
 *
 * <p>三条链路共用同一个不变量：<b>同组同师徒进行中关系 ≤ 1</b>（NFR-C-05），由 {@code uk_group_follower_active}
 * 兜底。分享、加入两条写路径都要撞得住它。
 */
class FissionShareAndJoinIT extends AbstractMySqlIT {

    private static final String ACT = "ACT_DEMO_001";

    @Autowired private FissionService fissionService;

    /** 开一轮，返回 groupId。 */
    private String newGroup(String sponsorId) {
        return fissionService.openGroup(ACT, sponsorId);
    }

    private static ShareInviteReq shareReq(String groupId, String sponsorId, String... followers) {
        ShareInviteReq req = new ShareInviteReq();
        req.setGroupId(groupId);
        req.setSponsorId(sponsorId);
        req.setFollowerIds(List.of(followers));
        req.setShareMethod("IM");
        return req;
    }

    private static FollowerJoinReq joinReq(
            String groupId, String followerId, String outBizNo, String outFlowNo) {
        FollowerJoinReq req = new FollowerJoinReq();
        req.setGroupId(groupId);
        req.setFollowerId(followerId);
        req.setOutBizNo(outBizNo);
        req.setOutFlowNo(outFlowNo);
        return req;
    }

    /** 分享为每个对象建一条 INVITED。 */
    @Test
    void shareCreatesInvitedRelationForEachFollower() {
        String sponsorId = "U_sp_share";
        String groupId = newGroup(sponsorId);

        ShareInviteResp resp =
                fissionService.shareInvite(shareReq(groupId, sponsorId, "U_f1", "U_f2", "U_f3"));

        assertThat(resp.getInvitedFollowerIds()).containsExactly("U_f1", "U_f2", "U_f3");
        assertThat(resp.getAlreadyInvitedFollowerIds()).isEmpty();
        assertThat(relationCount(groupId, RelationStatus.INVITED)).isEqualTo(3);
    }

    /**
     * BR-F-11：重复分享不重复创建关系，且<b>不作为错误</b>。
     *
     * <p>逐个返回而非只给总数 —— 端上要靠 {@code alreadyInvited} 把那几个头像标记为「已邀请」。
     */
    @Test
    void repeatedShareDoesNotCreateDuplicateRelation() {
        String sponsorId = "U_sp_dup";
        String groupId = newGroup(sponsorId);
        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_a", "U_b"));

        // 第二次分享：U_a 重复，U_c 新增
        ShareInviteResp resp =
                fissionService.shareInvite(shareReq(groupId, sponsorId, "U_a", "U_c"));

        assertThat(resp.getInvitedFollowerIds()).as("只有新对象被创建").containsExactly("U_c");
        assertThat(resp.getAlreadyInvitedFollowerIds())
                .as("重复对象须回报，端上据此标记已邀请")
                .containsExactly("U_a");
        assertThat(relationCount(groupId, RelationStatus.INVITED)).isEqualTo(3);
    }

    /** 分享给自己拒绝 —— 自邀是刷奖的第一条路径。 */
    @Test
    void sharingToSelfIsRejected() {
        String sponsorId = "U_self";
        String groupId = newGroup(sponsorId);

        assertThatThrownBy(
                        () -> fissionService.shareInvite(shareReq(groupId, sponsorId, sponsorId)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.SPONSOR_IS_FOLLOWER);
    }

    /** BR-F-13：重复点击不重复推进，条件更新命中 0 行。 */
    @Test
    void repeatedConnectDoesNotAdvanceTwice() {
        String sponsorId = "U_sp_conn";
        String groupId = newGroup(sponsorId);
        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_conn"));

        assertThat(fissionService.followerConnect(groupId, "U_conn")).isTrue();
        assertThat(fissionService.followerConnect(groupId, "U_conn")).as("重复点击应命中 0 行").isFalse();

        assertThat(relationCount(groupId, RelationStatus.CONNECTED)).isEqualTo(1);
        assertThat(relationCount(groupId, RelationStatus.INVITED)).isZero();
    }

    /**
     * <b>并发建联只推进一次</b>：条件更新使恰有一个线程拿到 {@code affected_rows = 1}。
     *
     * <p><b>这道闸在 UPDATE 的 {@code status} 谓词上，不在「怎么读」上。</b> 注入自查确认：把实现改成
     * 「先查后判再更新」，本用例与串行那条<b>都不变红</b> —— 因为最终写入仍是条件更新，第二个 线程的 UPDATE 照样命中 0 行。真正让两条同时变红的注入是<b>删掉
     * {@code AND status = #{fromStatus}}</b>。
     *
     * <p>记下这一点是因为它容易反过来理解：并发用例的价值不在于「证明先查后判有问题」，而在于 覆盖「条件更新被删掉」这个失效 —— 串行用例对它同样敏感，但只有并发用例能说明多线程下
     * 的实际行为。
     *
     * <p>判据取「成功计数恰为 1」而非「最终状态是 CONNECTED」：后者在推进两次时同样成立。
     */
    @Test
    void concurrentConnectAdvancesExactlyOnce() throws Exception {
        String sponsorId = "U_sp_cc";
        String groupId = newGroup(sponsorId);
        String followerId = "U_cc";
        fissionService.shareInvite(shareReq(groupId, sponsorId, followerId));

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger advanced =
                new java.util.concurrent.atomic.AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    awaitLatch(start);
                                    if (fissionService.followerConnect(groupId, followerId)) {
                                        advanced.incrementAndGet();
                                    }
                                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(advanced.get()).as("并发建联只应有一个线程推进成功，先查后判会让多个线程都成功").isEqualTo(1);
        assertThat(relationCount(groupId, RelationStatus.CONNECTED)).isEqualTo(1);
    }

    /** 无 INVITED 关系时建联不推进，也不报错 —— 用户点了一个过期链接不该看到异常。 */
    @Test
    void connectWithoutInvitedRelationIsNoop() {
        String groupId = newGroup("U_sp_noinv");

        assertThat(fissionService.followerConnect(groupId, "U_never_invited")).isFalse();
        assertThat(relationCount(groupId, RelationStatus.CONNECTED)).isZero();
    }

    /** 加入：INVITED → JOINED 并回填 outBizNo。 */
    @Test
    void joinAdvancesInvitedRelationAndFillsOutBizNo() {
        String sponsorId = "U_sp_join";
        String groupId = newGroup(sponsorId);
        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_j"));

        String relationId =
                fissionService.followerJoin(joinReq(groupId, "U_j", "OUT_BIZ_1", "OUT_FLOW_1"));

        assertThat(relationId).isNotBlank();
        assertThat(relationCount(groupId, RelationStatus.JOINED)).isEqualTo(1);
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT out_biz_no FROM fission_relation WHERE relation_id = ?",
                                relationId))
                .isEqualTo("OUT_BIZ_1");
        assertThat(opRecordCount("OUT_BIZ_1")).as("加入须留操作记录").isEqualTo(1);
    }

    /** 无关系时直建 JOINED —— 二维码/口令分享的徒弟没有事先建立的 INVITED。 */
    @Test
    void joinWithoutPriorRelationCreatesJoinedDirectly() {
        String groupId = newGroup("U_sp_direct");

        String relationId =
                fissionService.followerJoin(
                        joinReq(groupId, "U_direct", "OUT_BIZ_D", "OUT_FLOW_D"));

        assertThat(relationId).isNotBlank();
        assertThat(relationCount(groupId, RelationStatus.JOINED)).isEqualTo(1);
    }

    /**
     * BR-F-15：{@code outFlowNo} 标识本次操作，重传幂等命中，不产生第二条操作记录。
     *
     * <p>与 {@code outBizNo} 分开：后者标识一次师徒关系，须与后续完成操作一致。把两者当成一个 字段，会让完成操作被当成加入的重传而静默命中幂等。
     */
    @Test
    void joinIsIdempotentOnOutFlowNo() {
        String sponsorId = "U_sp_idem";
        String groupId = newGroup(sponsorId);

        String first =
                fissionService.followerJoin(joinReq(groupId, "U_i", "OUT_BIZ_I", "OUT_FLOW_I"));
        String second =
                fissionService.followerJoin(joinReq(groupId, "U_i", "OUT_BIZ_I", "OUT_FLOW_I"));

        assertThat(second).isEqualTo(first);
        assertThat(relationCount(groupId, RelationStatus.JOINED)).isEqualTo(1);
        assertThat(opRecordCount("OUT_BIZ_I")).as("重传不应产生第二条操作记录").isEqualTo(1);
    }

    /** 师徒同人拒绝（1614）—— 自己邀请自己即可无限触发双向发奖。 */
    @Test
    void joinBySponsorHimselfIsRejected() {
        String sponsorId = "U_sp_same";
        String groupId = newGroup(sponsorId);

        assertThatThrownBy(
                        () ->
                                fissionService.followerJoin(
                                        joinReq(groupId, sponsorId, "OUT_BIZ_S", "OUT_FLOW_S")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.SPONSOR_IS_FOLLOWER);

        assertThat(relationCount(groupId, RelationStatus.JOINED)).isZero();
    }

    /**
     * <b>BR-F-16：同一徒弟并发加入只产生一条有效关系。</b>
     *
     * <p>N 个线程各持不同的 {@code outFlowNo}（模拟端上重复提交生成了新流水），压同一个 {@code (groupId, followerId)}。幂等键各不相同，故
     * {@code uk_idempotent} 挡不住 —— 挡住的是 {@code
     * uk_group_follower_active}，这正是「唯一索引保护的是幂等键的唯一性，不是业务动作的 唯一性」那条分界线（技术方案 §3.3）。
     *
     * <p>必须用真实线程池：串行调用永远测不出「两个线程同时读到无关系」。
     */
    @Test
    void concurrentJoinProducesExactlyOneRelation() throws Exception {
        String sponsorId = "U_sp_conc";
        String groupId = newGroup(sponsorId);
        String followerId = "U_conc";
        int threads = 8;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> relationIds = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String flowNo = "OUT_FLOW_C" + i;
                futures.add(
                        pool.submit(
                                () -> {
                                    awaitLatch(start);
                                    relationIds.add(
                                            fissionService.followerJoin(
                                                    joinReq(
                                                            groupId,
                                                            followerId,
                                                            "OUT_BIZ_C",
                                                            flowNo)));
                                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ?"
                                        + " AND follower_id = ?",
                                groupId,
                                followerId))
                .as("并发加入只应产生一条关系")
                .isEqualTo(1);
        assertThat(relationIds).as("全部线程应拿到同一条关系").containsOnly(relationIds.get(0));
    }

    /** 已终结的裂变组不能再拉人 —— 往已结束的轮次里加徒弟，奖发给谁都不对。 */
    @Test
    void joinIntoTerminatedGroupIsRejected() {
        String sponsorId = "U_sp_term";
        String groupId = newGroup(sponsorId);
        fissionJdbc.update(
                "UPDATE fission_group SET status = 'DONE', active_flag = group_id"
                        + " WHERE group_id = ?",
                groupId);

        assertThatThrownBy(
                        () ->
                                fissionService.followerJoin(
                                        joinReq(groupId, "U_late", "OUT_BIZ_T", "OUT_FLOW_T")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_NOT_RUNNING);
    }

    // ---- 辅助 ----

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int relationCount(String groupId, RelationStatus status) {
        return count(
                fissionJdbc,
                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ? AND status = ?",
                groupId,
                status.name());
    }

    private int opRecordCount(String outBizNo) {
        return count(
                fissionJdbc,
                "SELECT COUNT(*) FROM fission_op_record WHERE out_biz_no = ?"
                        + " AND op_type = 'FOLLOWER_JOIN'",
                outBizNo);
    }
}
