package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.fission.dto.GroupQueryResp;
import com.mp.api.fission.dto.SponsorQueryReq;
import com.mp.api.fission.dto.SponsorQueryResp;
import com.mp.api.fission.service.FissionService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.QualifyReason;
import com.mp.common.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 师傅进场与轮次管理（FR-F01、FR-F02）。
 *
 * <p>进场链路的第一步就是调公共能力层的资格决策 —— 玩法层不重复实现人群、城市、风控，两处判据 迟早漂移，而漂移的表现是「咨询说能参与，进场说不能」。
 */
class FissionSponsorEntryIT extends AbstractMySqlIT {

    /** 裂变活动，见 {@link AbstractMySqlIT#FISSION_ACTIVITY_ID}。 */
    private static final String ACT = FISSION_ACTIVITY_ID;

    @Autowired private FissionService fissionService;
    @Autowired private com.mp.fission.repository.FissionGroupMapper groupMapper;

    @AfterEach
    void restoreActivity() {
        activityJdbc.update(
                "UPDATE marketing_activity SET status = 'ONLINE', city_scope = NULL,"
                        + " risk_rule = NULL WHERE activity_id = ?",
                ACT);
    }

    private static SponsorQueryReq req(String sponsorId) {
        SponsorQueryReq r = new SponsorQueryReq();
        r.setSponsorId(sponsorId);
        r.setActivityId(ACT);
        r.setScene("FISSION_DEMO");
        r.setCity("SH");
        r.setChannel("APP");
        r.setDeviceId("DEV_1");
        r.setClientIp("10.0.0.1");
        return r;
    }

    /** 进场自动开轮，返回轮次信息与邀请凭证。 */
    @Test
    void sponsorEntryOpensRoundAutomatically() {
        SponsorQueryResp resp = fissionService.sponsorQuery(req("U_entry"));

        assertThat(resp.isAvailable()).isTrue();
        assertThat(resp.getGroupId()).isNotBlank();
        assertThat(resp.getRoundNo()).isEqualTo(1);
        assertThat(resp.getProgress()).isZero();
        assertThat(resp.getInviteToken()).isNotBlank();

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                "U_entry"))
                .isEqualTo(1);
    }

    /** 再次进场复用同一轮，不新开 —— 否则师傅每刷新一次页面就多一轮。 */
    @Test
    void secondEntryReusesRunningRound() {
        String first = fissionService.sponsorQuery(req("U_reuse")).getGroupId();
        String second = fissionService.sponsorQuery(req("U_reuse")).getGroupId();

        assertThat(second).isEqualTo(first);
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                "U_reuse"))
                .isEqualTo(1);
    }

    /**
     * <b>并发进场不产生重复轮次</b>（BR-F-02）。
     *
     * <p>N 个线程同时进场，{@code uk_activity_sponsor_round} 保证只建出一轮 —— 这是 L3 兜底，不依赖 锁：锁一旦失效即破防，唯一索引托底。
     *
     * <p>必须用真实线程池压同一个 {@code (activityId, sponsorId)}：串行调用永远测不出「两个线程同时 读到无进行中轮次」，而那正是要防的。
     */
    @Test
    void concurrentEntryCreatesExactlyOneRound() throws Exception {
        String sponsorId = "U_concurrent";
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> groupIds = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    await(start);
                                    groupIds.add(
                                            fissionService
                                                    .sponsorQuery(req(sponsorId))
                                                    .getGroupId());
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
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                sponsorId))
                .as("并发进场只应建出一轮")
                .isEqualTo(1);
        assertThat(groupIds).as("全部线程应拿到同一个裂变组").containsOnly(groupIds.get(0));
    }

    /** 资格不通过时返回业务结果而非抛异常（BR-F-01）。 */
    @Test
    void notQualifiedReturnsBusinessResultInsteadOfThrowing() {
        activityJdbc.update(
                "UPDATE marketing_activity SET city_scope = ? WHERE activity_id = ?",
                "[\"BJ\"]",
                ACT);

        SponsorQueryResp resp = fissionService.sponsorQuery(req("U_nocity"));

        assertThat(resp.isAvailable()).as("无可参与活动是正常业务结果").isFalse();
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.CITY_NOT_MATCH.name());
        assertThat(resp.getGroupId()).as("未通过资格不应开轮").isNull();
        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                "U_nocity"))
                .isZero();
    }

    /** 手动开轮：已有进行中轮次时拒绝（BR-F-04）。 */
    @Test
    void manualOpenIsRejectedWhenRoundAlreadyRunning() {
        String sponsorId = "U_manual";
        fissionService.openGroup(ACT, sponsorId);

        assertThatThrownBy(() -> fissionService.openGroup(ACT, sponsorId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_ALREADY_RUNNING);

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                sponsorId))
                .isEqualTo(1);
    }

    /**
     * 上一轮终结后可开新轮，轮次号递增。
     *
     * <p>经 {@code terminate} 终结 —— 它同时释放 {@code active_flag}。若只改 {@code status}， {@code
     * uk_activity_sponsor_active} 仍被旧行占着，开新轮撞唯一键：该师傅从此开不了下一轮， 且报的是键冲突而非业务提示。
     */
    @Test
    void newRoundCanBeOpenedAfterPreviousOneEnds() {
        String sponsorId = "U_round2";
        String first = fissionService.openGroup(ACT, sponsorId);

        assertThat(groupMapper.terminate(first, "RUNNING", "DONE")).isEqualTo(1);
        assertThat(
                        str(
                                fissionJdbc,
                                "SELECT active_flag FROM fission_group WHERE group_id = ?",
                                first))
                .as("终结须释放 active_flag，否则该师傅永远开不了下一轮")
                .isEqualTo(first);

        String second = fissionService.openGroup(ACT, sponsorId);
        assertThat(second).isNotEqualTo(first);
        assertThat(
                        num(
                                fissionJdbc,
                                "SELECT round_no FROM fission_group WHERE group_id = ?",
                                second))
                .isEqualTo(2);
    }

    /**
     * <b>裂变轮次不能开在权益售卖活动上</b>。
     *
     * <p>{@code ACT_DEMO_001} 是 V1090 建的 {@code play_type = 'BENEFIT_SELL'} 活动。只判「活动存在」时
     * 它照样能开轮，而两个玩法的配置版本与奖励快照各不相同 —— PR-4 的双向发奖按 {@code group.config_version}
     * 读裂变奖励配置，挂错活动时读到的是一份根本不含裂变奖励的快照。
     *
     * <p>本用例同时是「测试固化错误行为」的回归点：修正前裂变的全部用例都借用这个活动，绿得 心安理得。
     */
    @Test
    void roundCannotBeOpenedOnNonFissionActivity() {
        assertThatThrownBy(() -> fissionService.openGroup("ACT_DEMO_001", "U_wrong_play"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                "U_wrong_play"))
                .as("拒绝须与不建轮同时成立")
                .isZero();
    }

    /**
     * <b>不可用的活动不能开轮</b>（1601）。
     *
     * <p>可用性由库判定状态与时间窗，与 {@code decideQualification} 同一口径。不判则已下线、已结束、 尚未开始的活动照样能开轮 ——
     * 而轮次一旦建出来，后续的分享与加入都会接受它。
     *
     * <p>走 {@code openGroup} 而非 {@code sponsorQuery}：后者会先被资格决策的活动可用性那一维挡下， 测不到 {@code createRound}
     * 自己这道闸。<b>两条路都要能挡</b> —— 判据放在 {@code createRound} 正是因为它是两条路的交汇处。
     */
    @Test
    void roundCannotBeOpenedOnUnavailableActivity() {
        activityJdbc.update(
                "UPDATE marketing_activity SET status = 'ENDED' WHERE activity_id = ?", ACT);

        assertThatThrownBy(() -> fissionService.openGroup(ACT, "U_offline"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_ACTIVITY);

        assertThat(
                        count(
                                fissionJdbc,
                                "SELECT COUNT(*) FROM fission_group WHERE sponsor_id = ?",
                                "U_offline"))
                .isZero();
    }

    /** 轮次查询的历史开关默认关闭（BR-F-05）。 */
    @Test
    void historySwitchIsOffByDefault() {
        String sponsorId = "U_query";
        fissionService.openGroup(ACT, sponsorId);

        GroupQueryResp off = fissionService.queryGroup(ACT, sponsorId, false);
        assertThat(off.getCurrent()).isNotNull();
        assertThat(off.getHistory()).as("默认不返回历史轮次").isNull();

        GroupQueryResp on = fissionService.queryGroup(ACT, sponsorId, true);
        assertThat(on.getHistory()).isNotEmpty();
    }

    /** 栅栏等待，使全部线程尽量同时进入被测方法。 */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 已过期的轮次不被当作可复用的「进行中」，但它仍占着 {@code active_flag}。
     *
     * <p>两件事必须分开看：
     *
     * <ul>
     *   <li><b>不复用</b> —— {@code selectRunning} 带 {@code expire_time > NOW(3)}，师傅不会被塞进
     *       一个已经结不了的轮次继续拉人
     *   <li><b>仍占键</b> —— {@code uk_activity_sponsor_active} 只认 {@code active_flag}，不看有效期。
     *       过期轮次必须<b>先被治理终结</b>才能开下一轮
     * </ul>
     *
     * <p>这不是缺陷而是有意的：若允许绕过去直接开新轮，那条过期轮次会永远停在 {@code RUNNING}， 既没人推进也没人清理 —— 僵尸行由此产生。轮次过期治理随关系过期治理在
     * PR-5 落地， 届时它把状态推到 {@code EXPIRED} 并释放键，本用例的第二段即可去掉手工终结。
     */
    @Test
    void expiredRoundIsNotReusableButStillHoldsTheKey() {
        String sponsorId = "U_expired";
        String first = fissionService.openGroup(ACT, sponsorId);

        // 过期判定用库时钟，与 expire_time 同侧
        fissionJdbc.update(
                "UPDATE fission_group SET expire_time = DATE_SUB(NOW(3), INTERVAL 1 SECOND)"
                        + " WHERE group_id = ?",
                first);

        // 不复用：进场不会把师傅塞回这个已过期的轮次
        assertThat(groupMapper.selectRunning(ACT, sponsorId)).as("已过期的轮次不算进行中").isNull();

        // 但键还占着：未经治理即开新轮，应得到业务提示而非唯一键异常
        assertThatThrownBy(() -> fissionService.openGroup(ACT, sponsorId))
                .as("过期轮次未终结前不应能开新轮，否则留下永远 RUNNING 的僵尸行")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.GROUP_ALREADY_RUNNING);

        // 治理终结后释放键，新轮正常开出
        assertThat(groupMapper.terminate(first, "RUNNING", "EXPIRED")).isEqualTo(1);
        String second = fissionService.openGroup(ACT, sponsorId);
        assertThat(second).isNotEqualTo(first);
    }
}
