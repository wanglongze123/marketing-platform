package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.fission.dto.FriendFilterResp;
import com.mp.api.fission.dto.GetFriendsReq;
import com.mp.api.fission.dto.ShareInviteReq;
import com.mp.api.fission.dto.ShareInviteResp;
import com.mp.api.fission.service.FissionService;
import com.mp.api.mock.dto.SocialDependency;
import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import com.mp.fission.filter.FilterRule;
import com.mp.mock.fault.SocialProfileStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 好友获取与过滤（FR-F03、FR-F04），对应《分阶段方案》§6.5 退出标准 9。
 *
 * <p><b>八条规则的失败语义各自独立</b>（BR-F-07-a~h）：四条 fail-close（关系、频控、账户状态、 用户角色）依赖挂掉即阻断，四条
 * fail-open（影响力、社交、实验、人群）依赖挂掉即放行。判据 是「影响资损或合规的取 fail-close」（BR-F-08）。
 *
 * <p><b>逐条注入而非一次全挂</b>：全挂时结果是「全被拒」，与「fail-close 生效」表现一致 —— 四条 fail-open 规则的语义一条也验不出来。
 *
 * <p>退出标准第 8 条（两套关系过滤实现结果一致）在 {@link BaselineFriendFilterIT}，那里要另起 一个上下文。
 */
class FriendFilterIT extends AbstractMySqlIT {

    /**
     * 裂变活动，由 {@code V3091__seed_fission_activity.sql} 初始化。
     *
     * <p><b>不用 {@code ACT_DEMO_001}</b>：那是 {@code play_type = 'BENEFIT_SELL'} 的权益售卖活动。 {@code
     * createRound} 校验 {@code playType} 后，裂变轮次开不到它上面 —— 而本类的每个用例都要先开一轮。
     */
    private static final String ACT = "ACT_FISSION_001";

    @Autowired private FissionService fissionService;
    @Autowired private SocialProfileStore social;

    @AfterEach
    void resetSocial() {
        social.reset();
    }

    /** 布置 n 个好友，全部为正常账号。 */
    private List<String> friendsOf(String sponsorId, int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(sponsorId + "_f" + i);
        }
        social.putFriends(sponsorId, ids);
        // 实验与人群是「命中才保留」，默认全部命中 —— 否则每个用例都要先布置这两项
        for (String id : ids) {
            social.markInExperiment(ACT, id);
            social.markInCrowd(ACT, id);
        }
        return ids;
    }

    private FriendFilterResp getFriends(String groupId, String sponsorId) {
        GetFriendsReq req = new GetFriendsReq();
        req.setGroupId(groupId);
        req.setSponsorId(sponsorId);
        return fissionService.getFriends(req);
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    /** 全部正常时全员通过，且返回四部分俱全。 */
    @Test
    void allNormalCandidatesPass() {
        String sponsorId = "U_sp_ff_all";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> friends = friendsOf(sponsorId, 5);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed()).containsExactlyElementsOf(friends);
        assertThat(resp.getRejected()).isEmpty();
        assertThat(resp.getDegradedRules()).as("无依赖故障时降级清单为空").isEmpty();
        assertThat(resp.getCandidateCount()).isEqualTo(5);
        assertThat(resp.getConfigVersion()).as("须回报生效配置版本（BR-C-05）").isPositive();
    }

    /**
     * <b>通过集合与拒绝集合的并集恒等于候选总数</b>。
     *
     * <p>这条不变量挡的是「人被静默丢弃」：某条规则若既不放行也不记拒绝，那个人就从结果里消失了 —— 端上看到的是「我的好友少了几个」，而没有任何错误。逐条断言各集合的内容发现不了它，
     * 因为每条断言都只看自己关心的那几个人。
     */
    @Test
    void passedPlusRejectedAlwaysCoversAllCandidates() {
        String sponsorId = "U_sp_ff_sum";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> friends = friendsOf(sponsorId, 6);
        social.putAccountStatus(friends.get(0), "CLOSED");
        social.putUserRole(friends.get(1), "MERCHANT");
        social.putFollowerCount(friends.get(2), 999999L);
        social.markBlocked(sponsorId, friends.get(3));

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed().size() + resp.getRejected().size())
                .as("通过 + 拒绝须覆盖全部候选，缺口即有人被静默丢弃")
                .isEqualTo(resp.getCandidateCount());
        assertThat(resp.getPassed()).containsExactly(friends.get(4), friends.get(5));
    }

    /** 逐条规则的正常拒绝，各带自己的原因 —— 运营据此看拒绝原因分布（AC-09）。 */
    @Test
    void eachRuleRejectsWithItsOwnReason() {
        String sponsorId = "U_sp_ff_reason";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> f = friendsOf(sponsorId, 6);
        social.markSharedToday(sponsorId, f.get(0));
        social.putAccountStatus(f.get(1), "MUTED");
        social.putUserRole(f.get(2), "STAFF");
        social.putFollowerCount(f.get(3), 20000L);
        social.markBlocked(sponsorId, f.get(4));

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getRejected())
                .containsEntry(f.get(0), FilterRule.SHARE_FREQUENCY.getRejectReason())
                .containsEntry(f.get(1), FilterRule.ACCOUNT_STATUS.getRejectReason())
                .containsEntry(f.get(2), FilterRule.USER_ROLE.getRejectReason())
                .containsEntry(f.get(3), FilterRule.INFLUENCE.getRejectReason())
                .containsEntry(f.get(4), FilterRule.SOCIAL.getRejectReason());
        assertThat(resp.getPassed()).containsExactly(f.get(5));
    }

    /**
     * 「命中才保留」的两条规则（实验、人群）拒绝未命中者。
     *
     * <p>它们与其余六条方向相反：下游返回的是应<b>保留</b>的集合。方向搞反的表现是「全员被拒」 或「全员通过」，而两者都不会报错。
     */
    @Test
    void retentionRulesRejectNonMatchingUsers() {
        String sponsorId = "U_sp_ff_retain";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> ids = List.of(sponsorId + "_a", sponsorId + "_b", sponsorId + "_c");
        social.putFriends(sponsorId, ids);
        // a 两项都命中，b 只在实验组，c 只在人群
        social.markInExperiment(ACT, ids.get(0));
        social.markInCrowd(ACT, ids.get(0));
        social.markInExperiment(ACT, ids.get(1));
        social.markInCrowd(ACT, ids.get(2));

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed()).containsExactly(ids.get(0));
        assertThat(resp.getRejected())
                .containsEntry(ids.get(1), FilterRule.CROWD.getRejectReason())
                .containsEntry(ids.get(2), FilterRule.EXPERIMENT.getRejectReason());
    }

    /**
     * <b>「活动没配这条规则」与「配了但一个都没命中」必须分开</b>。
     *
     * <p>实验分组与人群标签这两条是「命中才保留」，下游返回的是应保留的集合。未配置时若返回空集 并按正常路径处理，空集意味着「没人在组里」，结果是<b>全员被拒</b> ——
     * 而多数活动本来就不做 实验分组。失败形态是分享名单恒为空，且不报任何错。
     *
     * <p>本用例由 {@code BaselineFriendFilterIT} 实测暴露：那两条用例不布置实验与人群（它们只关心 关系过滤），结果全部候选人被判「不在实验组」，{@code
     * shareInvite} 抛 {@code 1611}。
     *
     * <p><b>这类缺陷在「每个用例都先把场景布置齐全」的写法下永远不会显形</b> —— 而生产环境里 「没配」才是常态。
     */
    @Test
    void unconfiguredRetentionRuleDoesNotRejectEveryone() {
        String sponsorId = "U_sp_ff_unconf";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        // 刻意不调 markInExperiment / markInCrowd：这个活动没做实验、没圈人群
        List<String> ids = List.of(sponsorId + "_x", sponsorId + "_y");
        social.putFriends(sponsorId, ids);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed()).as("未配置的规则须跳过，不得拒人").containsExactlyElementsOf(ids);
        assertThat(resp.getRejected()).isEmpty();
        assertThat(resp.getDegradedRules()).as("规则不适用不是降级，两者含义不同").isEmpty();
    }

    /** 配了人群但无人命中时，全员被拒 —— 与上一条构成同一判据的两侧。 */
    @Test
    void configuredRetentionRuleWithNoMatchRejectsEveryone() {
        String sponsorId = "U_sp_ff_nomatch";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> ids = List.of(sponsorId + "_p", sponsorId + "_q");
        social.putFriends(sponsorId, ids);
        // 配了人群包，但圈的是别人 —— 这批候选人一个都不在里面
        social.markInCrowd(ACT, "U_someone_else");

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed()).as("配了人群包却无人命中，须全员拒绝").isEmpty();
        assertThat(resp.getRejected()).hasSize(2);
    }

    /** 已有进行中关系的好友被关系过滤剔除（BR-F-07-a）。 */
    @Test
    void followersWithActiveRelationAreFilteredOut() {
        String sponsorId = "U_sp_ff_rel";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> f = friendsOf(sponsorId, 3);
        fissionService.shareInvite(shareReq(groupId, sponsorId, f.get(0)));

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed()).containsExactly(f.get(1), f.get(2));
        assertThat(resp.getRejected())
                .containsEntry(f.get(0), FilterRule.RELATION.getRejectReason());
    }

    // ------------------------------------------------------------------
    // 标准 9：逐条注入依赖故障，验各自的失败语义
    // ------------------------------------------------------------------

    /**
     * 标准 9 上半：<b>四条 fail-close 规则的依赖挂掉即阻断全部候选</b>，并记入降级清单。
     *
     * <p>阻断的是<b>当前存活的全部候选人</b>，不是「这条规则本该拒的那些」—— 依赖挂了，本该拒谁 根本无从得知。这是 fail-close 的字面含义。
     *
     * <p>关系那条不在此列：它查平台自己的库，注入「依赖不可用」无处可注 —— 库挂了整个请求都不成立。
     */
    @Test
    void failCloseRulesBlockEverythingWhenDependencyIsDown() {
        record Case(SocialDependency dep, FilterRule rule) {}

        Case[] cases = {
            new Case(SocialDependency.SHARE_FREQUENCY, FilterRule.SHARE_FREQUENCY),
            new Case(SocialDependency.ACCOUNT_STATUS, FilterRule.ACCOUNT_STATUS),
            new Case(SocialDependency.USER_ROLE, FilterRule.USER_ROLE),
        };

        for (Case c : cases) {
            String sponsorId = "U_sp_fc_" + c.rule();
            String groupId = fissionService.openGroup(ACT, sponsorId);
            friendsOf(sponsorId, 3);
            social.setRuleDown(c.dep(), true);

            FriendFilterResp resp = getFriends(groupId, sponsorId);

            assertThat(resp.getPassed()).as("%s 是 fail-close，须阻断全部", c.rule()).isEmpty();
            assertThat(resp.getRejected()).as("被阻断的人须逐个记原因").hasSize(3);
            assertThat(resp.getDegradedRules())
                    .as("无论 fail-open 还是 fail-close，降级都要记清单")
                    .contains(c.rule().name());
            social.setRuleDown(c.dep(), false);
        }
    }

    /**
     * 标准 9 下半：<b>四条 fail-open 规则的依赖挂掉即放行</b>，并记入降级清单。
     *
     * <p>这四条是投放优化而非准入门槛，拒绝的代价是活动可用性 —— 一个推荐服务抖动不该让全部 用户分享不出去。
     *
     * <p><b>实验与人群这两条尤其容易写错</b>：它们的下游返回「谁在组里」，依赖挂掉时若按正常路径 处理，空集意味着「没人在组里」，结果是全员被拒 —— 与 fail-open
     * 语义相反，且不会报任何错。
     */
    @Test
    void failOpenRulesPassEverythingWhenDependencyIsDown() {
        record Case(SocialDependency dep, FilterRule rule) {}

        Case[] cases = {
            new Case(SocialDependency.FOLLOWER_COUNT, FilterRule.INFLUENCE),
            new Case(SocialDependency.BLOCK_RELATION, FilterRule.SOCIAL),
            new Case(SocialDependency.EXPERIMENT, FilterRule.EXPERIMENT),
            new Case(SocialDependency.CROWD, FilterRule.CROWD),
        };

        for (Case c : cases) {
            String sponsorId = "U_sp_fo_" + c.rule();
            String groupId = fissionService.openGroup(ACT, sponsorId);
            List<String> friends = friendsOf(sponsorId, 3);
            social.setRuleDown(c.dep(), true);

            FriendFilterResp resp = getFriends(groupId, sponsorId);

            assertThat(resp.getPassed())
                    .as("%s 是 fail-open，依赖挂掉须放行", c.rule())
                    .containsExactlyElementsOf(friends);
            assertThat(resp.getRejected()).isEmpty();
            assertThat(resp.getDegradedRules()).contains(c.rule().name());
            social.setRuleDown(c.dep(), false);
        }
    }

    /**
     * <b>降级清单按规则去重</b>：一条规则不因候选人多而被记多次。
     *
     * <p>清单长度若随候选人数增长，调用方无从判断「降了几条规则」—— 而那正是这个字段要回答的。
     */
    @Test
    void degradedRulesAreDeduplicated() {
        String sponsorId = "U_sp_ff_dedup";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        friendsOf(sponsorId, 10);
        social.setRuleDown(SocialDependency.CROWD, true);
        social.setRuleDown(SocialDependency.EXPERIMENT, true);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getDegradedRules())
                .as("两条规则各记一次，与候选人数无关")
                .containsExactlyInAnyOrder(FilterRule.CROWD.name(), FilterRule.EXPERIMENT.name());
    }

    // ------------------------------------------------------------------
    // 召回不可用（5603）
    // ------------------------------------------------------------------

    /**
     * <b>召回不可用抛 {@code 5603}，不降级为空列表</b>。
     *
     * <p>这与过滤器的 fail-open 不是同一类判断：召回失败时手上没有任何可放行的对象。返回空列表 会让端上显示一个看起来正常的空页面 —— 用户以为自己没有好友，而故障无人察觉。
     */
    @Test
    void recallFailureRaisesSystemErrorNotEmptyList() {
        String sponsorId = "U_sp_ff_recall";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        friendsOf(sponsorId, 3);
        social.setRecallDown(true);

        assertThatThrownBy(() -> getFriends(groupId, sponsorId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("召回故障归 5xxx —— 结果未知，重试可能成功")
                .isEqualTo(ErrorCode.FRIEND_RECALL_UNAVAILABLE);
    }

    /** 好友为空是正常结果，不是故障 —— 与上一条构成 1xxx / 5xxx 分界的两侧。 */
    @Test
    void emptyFriendListIsNotAnError() {
        String sponsorId = "U_sp_ff_empty";
        String groupId = fissionService.openGroup(ACT, sponsorId);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getPassed()).isEmpty();
        assertThat(resp.getCandidateCount()).isZero();
        assertThat(resp.getDegradedRules()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 召回分页（FR-F03）
    // ------------------------------------------------------------------
    //
    // 本节由 PR-6 合入后的复审补入：注入「只召回第一页」「不去重」「去掉页数上限」
    // 三处，此前的 20 条用例全部保持绿色 —— 因为每条用例的好友数都在 10 以内，
    // 而一页是 200，分页整段从未被执行过。
    //
    // 这与 PR-5 的「约束在单分片下不可观测」同类：代码正确，但没有任何用例能区分
    // 它在与不在。区别是那处要构造特殊入参才可观测，此处只要把数据量提到一页以上。

    /** 一页的大小，与 {@code FissionServiceImpl.RECALL_PAGE_SIZE} 一致 */
    private static final int PAGE_SIZE = 200;

    /**
     * <b>候选集跨页时，后续页的好友必须也被拉到</b>。
     *
     * <p>注入「只召回第一页」时此前全部用例仍绿 —— 判据必须是「第 2 页的人在不在结果里」， 而这要求候选集真的超过一页。用 {@code PAGE_SIZE + 50}
     * 人：跨一次页边界，且末页不满。
     */
    @Test
    void recallPagesThroughUntilLastPartialPage() {
        String sponsorId = "U_sp_page";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        int total = PAGE_SIZE + 50;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            ids.add(sponsorId + "_p" + i);
        }
        social.putFriends(sponsorId, ids);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getCandidateCount()).as("跨页的好友须全部拉到").isEqualTo(total);
        assertThat(resp.getPassed()).as("第 2 页的人须在结果里").contains(ids.get(total - 1));
        assertThat(social.recallCallCount(sponsorId)).as("末页不满一页即停止翻页，不再多问一次拿空页").isEqualTo(2);
    }

    /**
     * <b>末页恰好满一页时要多问一次</b>，否则无从知道后面还有没有。
     *
     * <p>与上一条构成边界的两侧：{@code batch.size() < PAGE_SIZE} 这个提前退出条件，在「恰好整除」 时不成立 —— 若写成 {@code
     * <=}，最后一页会被丢弃。
     */
    @Test
    void recallAsksOneMorePageWhenLastPageIsExactlyFull() {
        String sponsorId = "U_sp_page_exact";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE; i++) {
            ids.add(sponsorId + "_e" + i);
        }
        social.putFriends(sponsorId, ids);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getCandidateCount()).isEqualTo(PAGE_SIZE);
        assertThat(social.recallCallCount(sponsorId)).as("恰好一页时须再问一次才能确认没有下一页").isEqualTo(2);
    }

    /**
     * <b>跨页重复的好友只算一次</b>（分页期间名单变动是召回侧的常态）。
     *
     * <p>去重放在召回而非过滤链：过滤链的入参契约是「一批互不相同的候选人」，让它自己去重等于 把上游的毛病带进下游。
     *
     * <p>失败形态是同一个人被重复过滤、重复计数 —— 而 {@code passed} 里出现两个相同 id 时，端上 会显示两个一样的头像。
     */
    @Test
    void duplicatesAcrossPagesAreCountedOnce() {
        String sponsorId = "U_sp_page_dup";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE; i++) {
            ids.add(sponsorId + "_d" + i);
        }
        // 第 2 页重复第 1 页的前 10 个，另有 5 个新人
        for (int i = 0; i < 10; i++) {
            ids.add(sponsorId + "_d" + i);
        }
        for (int i = 0; i < 5; i++) {
            ids.add(sponsorId + "_new" + i);
        }
        social.putFriends(sponsorId, ids);

        FriendFilterResp resp = getFriends(groupId, sponsorId);

        assertThat(resp.getCandidateCount())
                .as("重复的 10 个只算一次：200 + 5 = 205")
                .isEqualTo(PAGE_SIZE + 5);
        assertThat(resp.getPassed()).doesNotHaveDuplicates();
    }

    /**
     * <b>页数上限生效</b>（FR-F04 前置条件「候选集大小在受控上限内」）。
     *
     * <p>超限抛 {@code 4001} 而非静默截断：截断后调用方拿到的是一份不完整的名单，而它看起来 与完整名单没有区别。
     */
    @Test
    void maxPagesBeyondLimitIsRejected() {
        String sponsorId = "U_sp_page_limit";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        GetFriendsReq req = new GetFriendsReq();
        req.setGroupId(groupId);
        req.setSponsorId(sponsorId);
        req.setMaxPages(51);

        assertThatThrownBy(() -> fissionService.getFriends(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
    }

    /**
     * {@code maxPages} 限制实际拉取的页数，<b>不是只做参数校验</b>。
     *
     * <p>传 1 时只问一页 —— 校验通过但循环不受它约束的实现，会把三页全拉回来。
     */
    @Test
    void maxPagesCapsHowManyPagesAreActuallyFetched() {
        String sponsorId = "U_sp_page_cap";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE * 3; i++) {
            ids.add(sponsorId + "_c" + i);
        }
        social.putFriends(sponsorId, ids);

        GetFriendsReq req = new GetFriendsReq();
        req.setGroupId(groupId);
        req.setSponsorId(sponsorId);
        req.setMaxPages(1);
        FriendFilterResp resp = fissionService.getFriends(req);

        assertThat(social.recallCallCount(sponsorId)).as("maxPages=1 时只问一页").isEqualTo(1);
        assertThat(resp.getCandidateCount()).isEqualTo(PAGE_SIZE);
    }

    /**
     * <b>翻页途中失败同样抛 {@code 5603}，不返回已拉到的半份名单</b>。
     *
     * <p>半份名单与完整名单在调用方看来没有区别 —— 用户会以为自己的好友就这些，而运营看到的是 一次「成功」的召回。这与首页就失败是同一处置：召回要么给出完整候选集，要么明确报错。
     */
    @Test
    void failureOnLaterPageAlsoRaisesRecallError() {
        String sponsorId = "U_sp_page_midfail";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE * 2; i++) {
            ids.add(sponsorId + "_m" + i);
        }
        social.putFriends(sponsorId, ids);

        // 第一页正常拉回，随后置为不可用 —— 模拟翻页途中下游挂掉
        GetFriendsReq warmUp = new GetFriendsReq();
        warmUp.setGroupId(groupId);
        warmUp.setSponsorId(sponsorId);
        warmUp.setMaxPages(1);
        assertThat(fissionService.getFriends(warmUp).getCandidateCount()).isEqualTo(PAGE_SIZE);

        social.setRecallDown(true);
        assertThatThrownBy(() -> getFriends(groupId, sponsorId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("拉到一半失败须报错，不得返回半份名单")
                .isEqualTo(ErrorCode.FRIEND_RECALL_UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // BR-F-12：分享侧独立把关
    // ------------------------------------------------------------------

    /**
     * BR-F-12：<b>分享侧独立过滤一次，不信任 {@code getFriends} 的结果</b>。
     *
     * <p>客户端可以根本不调 {@code getFriends} 而直接构造一批 id —— 分享是写路径，把关只在读路径上 做等于没做。
     */
    @Test
    void shareIndependentlyFiltersItsTargets() {
        String sponsorId = "U_sp_share_filter";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> f = friendsOf(sponsorId, 3);
        social.putAccountStatus(f.get(0), "CLOSED");

        ShareInviteResp resp =
                fissionService.shareInvite(
                        shareReq(groupId, sponsorId, f.get(0), f.get(1), f.get(2)));

        assertThat(resp.getInvitedFollowerIds()).containsExactly(f.get(1), f.get(2));
        assertThat(resp.getFilteredFollowerIds())
                .as("未通过过滤的对象须回报，端上据此置灰")
                .containsEntry(f.get(0), FilterRule.ACCOUNT_STATUS.getRejectReason());
        assertThat(relationCount(groupId, com.mp.common.enums.RelationStatus.INVITED))
                .as("被过滤的对象不得建关系")
                .isEqualTo(2);
    }

    /** 全员未通过时抛 {@code 1611}，且不建任何关系。 */
    @Test
    void shareRejectsWhenNoTargetPassesFilter() {
        String sponsorId = "U_sp_share_none";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> f = friendsOf(sponsorId, 2);
        social.putAccountStatus(f.get(0), "CLOSED");
        social.putAccountStatus(f.get(1), "MUTED");

        assertThatThrownBy(
                        () ->
                                fissionService.shareInvite(
                                        shareReq(groupId, sponsorId, f.get(0), f.get(1))))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.FOLLOWER_FILTERED);

        assertThat(relationCount(groupId, com.mp.common.enums.RelationStatus.INVITED)).isZero();
    }

    /**
     * <b>重复分享仍走 BR-F-11 的「已邀请」，不被关系过滤判为 {@code 1611}</b>。
     *
     * <p>两条规则并不矛盾，是同一件事在两条路径上的不同呈现：读路径要把已邀请的人从可选名单里 去掉（免得用户点了没反应），写路径要把重复请求识别为幂等命中。故关系过滤在分享侧关闭 ——
     * 启用它则重复分享会弹一个错误提示，与 BR-F-11「不作为错误」直接冲突。
     */
    @Test
    void repeatedShareIsStillIdempotentNotFiltered() {
        String sponsorId = "U_sp_share_dup";
        String groupId = fissionService.openGroup(ACT, sponsorId);
        List<String> f = friendsOf(sponsorId, 2);
        fissionService.shareInvite(shareReq(groupId, sponsorId, f.get(0)));

        ShareInviteResp resp =
                fissionService.shareInvite(shareReq(groupId, sponsorId, f.get(0), f.get(1)));

        assertThat(resp.getAlreadyInvitedFollowerIds())
                .as("重复分享是幂等命中，不是过滤拒绝")
                .containsExactly(f.get(0));
        assertThat(resp.getInvitedFollowerIds()).containsExactly(f.get(1));
        assertThat(resp.getFilteredFollowerIds()).isEmpty();
    }

    private static ShareInviteReq shareReq(String groupId, String sponsorId, String... followers) {
        ShareInviteReq req = new ShareInviteReq();
        req.setGroupId(groupId);
        req.setSponsorId(sponsorId);
        req.setFollowerIds(List.of(followers));
        req.setShareMethod("IM");
        return req;
    }

    private int relationCount(String groupId, com.mp.common.enums.RelationStatus status) {
        return count(
                fissionJdbc,
                "SELECT COUNT(*) FROM fission_relation WHERE group_id = ? AND status = ?",
                groupId,
                status.name());
    }
}
