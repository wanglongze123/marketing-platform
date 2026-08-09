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

    /** 裂变活动，见 {@link AbstractMySqlIT#FISSION_ACTIVITY_ID}。 */
    private static final String ACT = FISSION_ACTIVITY_ID;

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

    /**
     * <b>分享者必须是该轮次的师傅</b>（1615）。
     *
     * <p>攻击形态：知道他人 {@code groupId} 即可发起一次 {@code sponsorId} 是自己的分享。若实现信了 请求里的 {@code
     * sponsorId}，同一个组下会出现两个不同的 {@code sponsor_id} —— 而 PR-4 的 师傅返奖按关系上的 {@code sponsor_id}
     * 发，奖直接落到伪造者头上。
     *
     * <p><b>判据取「库里那条关系的 sponsor_id」而非只断言抛异常</b>：一个「先建关系再校验」的实现 同样会抛出预期错误码，只断言异常则它照常全绿 ——
     * 而它已经把脏数据写进去了。这与退出标准 第 16 条「均不建单必须与拒绝了同时断言」是同一处置。
     */
    @Test
    void shareByNonOwnerIsRejectedAndWritesNothing() {
        String owner = "U_sp_owner";
        String groupId = newGroup(owner);
        String attacker = "U_sp_attacker";

        assertThatThrownBy(
                        () -> fissionService.shareInvite(shareReq(groupId, attacker, "U_victim")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.SPONSOR_NOT_GROUP_OWNER);

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ?",
                                groupId))
                .as("越权分享不得留下任何关系")
                .isZero();
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_relation WHERE sponsor_id = ?",
                                attacker))
                .as("伪造的 sponsor_id 不得落库 —— PR-4 的师傅返奖按它发奖")
                .isZero();
    }

    /**
     * 正常分享落库的 {@code sponsor_id} 取自轮次，不取自请求。
     *
     * <p>与上一条互补：上一条验「不一致时拒绝」，本条验「一致时落的是服务端那个值」。 两条一起才排除「校验通过后仍写请求值」的实现 —— 二者当前等价，但把「落哪个值」这件事
     * 钉在服务端，是为了让 PR-4 接双向发奖时不必再回头确认一次数据来源。
     */
    @Test
    void shareStoresServerSideSponsorId() {
        String sponsorId = "U_sp_srv";
        String groupId = newGroup(sponsorId);

        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_srv_f"));

        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT sponsor_id FROM fission_relation WHERE group_id = ?"
                                        + " AND follower_id = ?",
                                groupId,
                                "U_srv_f"))
                .isEqualTo(sponsorId);
    }

    /**
     * <b>已过期的轮次不能再分享、建联或加入</b>。
     *
     * <p>轮次状态仍是 {@code RUNNING}（过期治理属 PR-5，尚未把它推到 {@code EXPIRED}），只有 {@code expire_time}
     * 过了。若「进行中」只判状态，本类的三条写路径都会放行 —— 而 {@code selectRunning} 那一侧早已认为这轮不可用。<b>一个类里两套「进行中」定义</b>。
     *
     * <p>放行的后果不止是多几条关系：关系有效期取自轮次（{@code relationExpireOf}），建出来的关系 <b>诞生即过期</b>，而没有任何机制会再看它一眼。
     *
     * <p>三条路径逐一断言 —— 只验其中一条时，另两条的缺口照样在。
     */
    @Test
    void expiredRoundRejectsShareConnectAndJoin() {
        String sponsorId = "U_sp_exp";
        String groupId = newGroup(sponsorId);
        // 先在未过期时建一条 INVITED，供过期后的建联使用 —— 否则建联返回 false 是因为没关系，
        // 而不是因为轮次过期，用例就测不到它声称的那道闸
        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_exp_f"));

        // 过期判定用库时钟，与 expire_time 同侧
        fissionJdbc.update(
                "UPDATE fission_group SET expire_time = DATE_SUB(NOW(3), INTERVAL 1 SECOND)"
                        + " WHERE group_id = ?",
                groupId);

        assertThatThrownBy(
                        () -> fissionService.shareInvite(shareReq(groupId, sponsorId, "U_exp_g")))
                .as("过期轮次不得再分享")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_NOT_RUNNING);

        assertThatThrownBy(() -> fissionService.followerConnect(groupId, "U_exp_f"))
                .as("过期轮次不得再建联")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_NOT_RUNNING);

        assertThatThrownBy(
                        () ->
                                fissionService.followerJoin(
                                        joinReq(groupId, "U_exp_f", "OUT_BIZ_E", "OUT_FLOW_E")))
                .as("过期轮次不得再加入")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_NOT_RUNNING);

        assertThat(relationCount(groupId, RelationStatus.CONNECTED)).as("过期后不得推进关系").isZero();
        assertThat(relationCount(groupId, RelationStatus.JOINED)).isZero();
        assertThat(relationCount(groupId, RelationStatus.INVITED))
                .as("过期前建立的关系保持原状，等 PR-5 的治理处置")
                .isEqualTo(1);
    }

    /** 已终结的裂变组不能建联 —— 与分享、加入同一道闸，缺了它三条写路径口径不一。 */
    @Test
    void connectIntoTerminatedGroupIsRejected() {
        String sponsorId = "U_sp_conn_term";
        String groupId = newGroup(sponsorId);
        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_conn_term"));
        fissionJdbc.update(
                "UPDATE fission_group SET status = 'DONE', active_flag = group_id"
                        + " WHERE group_id = ?",
                groupId);

        assertThatThrownBy(() -> fissionService.followerConnect(groupId, "U_conn_term"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_NOT_RUNNING);

        assertThat(relationCount(groupId, RelationStatus.CONNECTED))
                .as("终结轮次里的关系不得被推进 —— PR-4 的发奖以关系状态为判据")
                .isZero();
    }

    /**
     * <b>幂等键跨操作复用判 4002，不静默按命中处理</b>。
     *
     * <p>{@code uk_idempotent} 是全局唯一键，命中只证明「这个流水号用过」，不证明「用在了同一件 事上」。此处用甲的 {@code outFlowNo}
     * 去做乙的加入：若实现只看「命中了没有」，就会回查 <b>乙</b>的 active 关系并返回 —— 而乙那条关系仍停在 {@code INVITED}，从没被推进过。
     *
     * <p><b>调用方拿到一个非空 relationId，会认为加入成功。</b> 这类失效没有异常、没有错误码， 只有一条状态不对的关系 —— 正是这套代码一贯规避的静默失效。
     *
     * <p>断言两件事：拒绝了（4002），以及乙的关系<b>仍是 INVITED</b>。只断言异常的话，一个「拒绝前 先推进」的实现照常全绿。
     */
    @Test
    void reusingOutFlowNoAcrossFollowersIsRejected() {
        String sponsorId = "U_sp_reuse";
        String groupId = newGroup(sponsorId);
        fissionService.shareInvite(shareReq(groupId, sponsorId, "U_reuse_a", "U_reuse_b"));

        String sharedFlowNo = "OUT_FLOW_SHARED";
        fissionService.followerJoin(joinReq(groupId, "U_reuse_a", "OUT_BIZ_A", sharedFlowNo));

        // 同一个 outFlowNo 换个徒弟再来一次：这不是重传，是两次不同的操作
        assertThatThrownBy(
                        () ->
                                fissionService.followerJoin(
                                        joinReq(groupId, "U_reuse_b", "OUT_BIZ_B", sharedFlowNo)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_KEY_CONFLICT);

        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT status FROM fission_relation WHERE group_id = ?"
                                        + " AND follower_id = ?",
                                groupId,
                                "U_reuse_b"))
                .as("被冒用流水号的那个徒弟，关系必须仍停在 INVITED —— 静默按命中处理会让调用方以为他加入了")
                .isEqualTo(RelationStatus.INVITED.name());
        assertThat(relationCount(groupId, RelationStatus.JOINED)).as("只有 A 真正加入了").isEqualTo(1);
    }

    /**
     * 同一徒弟、同一流水号、<b>不同 outBizNo</b> 也判 4002。
     *
     * <p>{@code outBizNo} 标识一次师徒关系，{@code outFlowNo} 标识本次操作（BR-F-14/15）。两者换了 一个就不是同一件事 ——
     * 这一条覆盖的是「徒弟对上了但关系对不上」，与上一条的失效形态不同： 上一条错在人，本条错在关系。
     */
    @Test
    void reusingOutFlowNoWithDifferentOutBizNoIsRejected() {
        String sponsorId = "U_sp_reuse2";
        String groupId = newGroup(sponsorId);
        String flowNo = "OUT_FLOW_SAME";

        fissionService.followerJoin(joinReq(groupId, "U_reuse_c", "OUT_BIZ_C1", flowNo));

        assertThatThrownBy(
                        () ->
                                fissionService.followerJoin(
                                        joinReq(groupId, "U_reuse_c", "OUT_BIZ_C2", flowNo)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_KEY_CONFLICT);

        assertThat(opRecordCount("OUT_BIZ_C2")).as("被拒的操作不得留记录").isZero();
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
