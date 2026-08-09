package com.mp.fission.service.impl;

import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.dto.QualifyReq;
import com.mp.api.activity.dto.QualifyResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.api.fission.dto.FollowerDoneReq;
import com.mp.api.fission.dto.FollowerJoinReq;
import com.mp.api.fission.dto.FriendFilterResp;
import com.mp.api.fission.dto.GetFriendsReq;
import com.mp.api.fission.dto.GroupQueryResp;
import com.mp.api.fission.dto.ShareInviteReq;
import com.mp.api.fission.dto.ShareInviteResp;
import com.mp.api.fission.dto.SponsorQueryReq;
import com.mp.api.fission.dto.SponsorQueryResp;
import com.mp.api.fission.service.FissionService;
import com.mp.api.mock.service.MockSocialService;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RelationStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.exception.BizException;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.entity.FissionGroup;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.filter.FilterContext;
import com.mp.fission.filter.FriendFilterChain;
import com.mp.fission.reconcile.FissionReconcileService;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.repository.FissionOpRecordMapper;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.service.FissionTxService;
import com.mp.fission.service.RewardItemFactory;
import java.util.ArrayList;
import java.util.List;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 裂变进场与轮次管理。
 *
 * <p><b>资格判定调公共能力层</b>，玩法层不重复实现 —— 两处判据迟早漂移，而漂移的表现是「咨询说 能参与，进场说不能」。
 *
 * <p><b>本类不带 {@code @Transactional}</b>：事务边界在 {@link FissionTxService}。
 */
@DubboService
@Service
public class FissionServiceImpl implements FissionService {

    private static final Logger log = LoggerFactory.getLogger(FissionServiceImpl.class);

    /** 轮次有效期：7 天。超出上限判 4602 */
    private static final long DEFAULT_ROUND_TTL_SECONDS = 7 * 24 * 3600L;

    /** 达标所需徒弟数。V3 取固定值，运营配置化后改由活动配置快照填充 */
    private static final int DEFAULT_TARGET_COUNT = 3;

    /** 单号碰撞后的换号重试上限 */
    private static final int ID_RETRY_LIMIT = 3;

    /**
     * 本玩法的 {@code playType}，裂变轮次只能开在这类活动上。
     *
     * <p>取字面量而非枚举：{@code playType} 在 {@code mp-api} 的 DTO 里就是 {@code String}（见 {@code
     * CreateActivityReq}、{@code GrantRewardReq}），为一处校验往冻结的公共契约里加枚举，代价 远大于收益 —— 而契约冻结是 M0 的验收项之一。
     */
    private static final String PLAY_TYPE_FISSION = "FISSION";

    /**
     * 发奖在途豁免窗口：10 分钟（《分阶段方案》§6.4 ③）。
     *
     * <p>须长于「发奖 + 查单收敛」的正常耗时（V2 实测 PROCESSING 收敛 153s），短到不让一行永久 豁免。超时后允许治理接管并告警，对账第 13 项据此扫描。
     */
    private static final long GRANTING_WINDOW_SECONDS = 600;

    /** 好友召回的分页大小，与 §7.1 的 N=200 一致 */
    private static final int RECALL_PAGE_SIZE = 200;

    /** 默认召回页数 */
    private static final int DEFAULT_MAX_PAGES = 5;

    /**
     * 召回页数上限（FR-F04 前置条件「候选集大小在受控上限内」）。
     *
     * <p>上限落在页数而非总人数：分页拉取时超限要么整页丢弃、要么截断成半页，前者浪费一次调用、 后者让「拉了多少」取决于最后一页的大小。按页数限制则每一页都是完整的。
     */
    private static final int MAX_PAGES_LIMIT = 50;

    /** 影响力阈值：粉丝量超过它的用户不作为裂变对象（BR-F-07-e）。V3 取固定值 */
    private static final long INFLUENCE_THRESHOLD = 10000L;

    /** 徒弟奖励类型与配置 id。V3 取固定值，运营配置化后改由活动配置快照填充 */
    private static final String FOLLOWER_REWARD_TYPE = "COUPON";

    private static final String FOLLOWER_REWARD_CONFIG_ID = "FISSION_FOLLOWER_REWARD";

    // V3 单进程用 Spring 注入；V4 拆服务后改为 @DubboReference(protocol="tri")。
    //
    // 与 benefit-order / reward 的既有形态保持一致，不提前用 @DubboReference：injvm 代理会把
    // BizException 包成 RuntimeException，错误码随之丢失 —— 而 1xxx/4xxx 与 5xxx 的分区正是
    // 调度器判「确定拒绝」还是「结果未知」的依据（V2 PR-8）。跨进程的异常传播要在 V4 连同
    // 序列化一并处理，此时提前引入只会让错误码在单进程阶段就已失效。
    @Autowired private ActivityService activityService;
    @Autowired private RewardService rewardService;
    @Autowired private MockSocialService socialService;

    private final FissionGroupMapper groupMapper;
    private final FissionRelationMapper relationMapper;
    private final FissionOpRecordMapper opRecordMapper;
    private final FissionTxService tx;
    private final FriendFilterChain filterChain;
    private final FissionReconcileService reconcileService;

    public FissionServiceImpl(
            FissionGroupMapper groupMapper,
            FissionRelationMapper relationMapper,
            FissionOpRecordMapper opRecordMapper,
            FissionTxService tx,
            FriendFilterChain filterChain,
            FissionReconcileService reconcileService) {
        this.groupMapper = groupMapper;
        this.relationMapper = relationMapper;
        this.opRecordMapper = opRecordMapper;
        this.tx = tx;
        this.filterChain = filterChain;
        this.reconcileService = reconcileService;
    }

    // ------------------------------------------------------------------
    // ① 师傅进场
    // ------------------------------------------------------------------

    @Override
    public SponsorQueryResp sponsorQuery(SponsorQueryReq req) {
        requireText(req.getSponsorId(), "sponsorId");
        requireText(req.getActivityId(), "activityId");

        SponsorQueryResp resp = new SponsorQueryResp();
        resp.setActivityId(req.getActivityId());

        // 资格决策：人群、城市、渠道、风控四维，由公共能力层判定
        QualifyResp qualify = activityService.decideQualification(toQualifyReq(req));
        if (!qualify.isPass()) {
            // 无可参与活动是正常业务结果，不抛异常（BR-F-01）—— 抛异常会让「今天没活动」
            // 与「资格服务挂了」在调用方看来一样
            resp.setAvailable(false);
            resp.setReasonCode(qualify.getReasonCode());
            log.info(
                    "sponsorQuery not qualified, sponsorId={}, reason={}",
                    req.getSponsorId(),
                    qualify.getReasonCode());
            return resp;
        }

        resp.setAvailable(true);
        resp.setReasonCode(qualify.getReasonCode());

        // 复用进行中轮次；无则自动开轮
        FissionGroup running = groupMapper.selectRunning(req.getActivityId(), req.getSponsorId());
        if (running == null) {
            running = createRound(req.getActivityId(), req.getSponsorId());
        }

        fillRound(resp, running);
        resp.setInviteToken(inviteToken(running, req.getSponsorId()));
        return resp;
    }

    // ------------------------------------------------------------------
    // ② 手动开轮
    // ------------------------------------------------------------------

    @Override
    public String openGroup(String activityId, String sponsorId) {
        requireText(activityId, "activityId");
        requireText(sponsorId, "sponsorId");

        // 已有轮次占着 active_flag 时拒绝（BR-F-04）：一个师傅在一个活动下同时只能有一轮，
        // 否则邀请来的徒弟不知道该记进哪一轮。
        //
        // 判据取 selectActive（只认 active_flag）而非 selectRunning（带 expire_time 过滤）：
        // 二者对「已过期但未被治理」的轮次给出相反答案 —— 用后者会放行开新轮，而唯一键
        // 挡下它，异常从业务提示退化成 DuplicateKeyException；更糟的是若被 createRound 的
        // 撞键分支捕获，调用方会拿到那条过期轮次，看起来「开轮成功」而实际什么也没发生。
        //
        // 过期轮次须先由治理推到终态并释放键，才允许开下一轮。
        FissionGroup occupying = groupMapper.selectActive(activityId, sponsorId);
        if (occupying != null) {
            throw new BizException(
                    ErrorCode.GROUP_ALREADY_RUNNING, "已存在未终结的轮次: " + occupying.getGroupId());
        }

        return createRound(activityId, sponsorId).getGroupId();
    }

    /**
     * 建一轮，轮次号取历史最大值 +1。
     *
     * <p><b>并发兜底靠 {@code uk_activity_sponsor_active} 而非 {@code uk_activity_sponsor_round}</b>：
     * 轮次号由「读 MAX 再 +1」算出，并发时线程 A 建轮 1 提交后，线程 B 读到 1 并算出轮 2 —— 两条记录的 {@code round_no}
     * 不同，轮次号唯一键根本不冲突。挡住「同时两轮进行中」的是 {@code active_flag} 那道部分唯一键（BR-F-04）。
     *
     * <p>撞键后回查已建好的那一轮返回，<b>不重试建新轮</b>：重试会算出 +1 的轮次号，把「并发进场」 变成「开了两轮」—— 那正是这道闸要挡的。
     */
    private FissionGroup createRound(String activityId, String sponsorId) {
        ActivityConfResp conf = activityService.queryActivityConf(activityId);
        if (conf == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "活动不存在: " + activityId);
        }

        // 「存在」不等于「可以在它上面开裂变轮次」。ActivityConfResp 带着 available 与 playType
        // 两个字段，只判 null 等于把它们丢掉：
        //
        // - available：由库判定时间窗与状态（与 decideQualification 同一口径）。不判则已下线、
        //   已结束、尚未开始的活动照样能开轮，而轮次一旦建出来就会被后续的分享、加入接受
        // - playType：不判则裂变轮次能挂在权益售卖活动上。两个玩法的配置版本、奖励快照各不
        //   相同，PR-4 的双向发奖按 group.config_version 读裂变奖励配置，挂错活动时读到的是
        //   一份根本不含裂变奖励的快照
        //
        // 判在 createRound 而非各调用点：sponsorQuery 与 openGroup 两条路都要经过这里，
        // 放调用点则漏一处就是一个绕过口
        if (!conf.isAvailable()) {
            throw new BizException(
                    ErrorCode.NO_AVAILABLE_ACTIVITY,
                    "活动不可用: " + activityId + ", status=" + conf.getStatus());
        }
        if (!PLAY_TYPE_FISSION.equals(conf.getPlayType())) {
            throw new BizException(
                    ErrorCode.INVALID_PARAM,
                    "活动玩法不是裂变: " + activityId + ", playType=" + conf.getPlayType());
        }

        Integer maxRound = groupMapper.selectMaxRound(activityId, sponsorId);
        int roundNo = maxRound == null ? 1 : maxRound + 1;

        for (int attempt = 0; attempt < ID_RETRY_LIMIT; attempt++) {
            String groupId = BizNoGenerator.fissionGroupNo();
            try {
                tx.openGroup(
                        groupId,
                        activityId,
                        sponsorId,
                        roundNo,
                        DEFAULT_TARGET_COUNT,
                        conf.getCurVersion(),
                        DEFAULT_ROUND_TTL_SECONDS);
                log.info(
                        "fission round opened, groupId={}, sponsorId={}, roundNo={}",
                        groupId,
                        sponsorId,
                        roundNo);
                return groupMapper.selectByGroupId(groupId);
            } catch (DuplicateKeyException e) {
                // 撞 uk_activity_sponsor_active 或 uk_activity_sponsor_round：并发进场已建轮，回查它。
                //
                // 回查用 selectActive 而非 selectRunning：后者带 expire_time > NOW(3)，与唯一键
                // 的判据不一致 —— 唯一键只认 active_flag，不看有效期。两者口径不同时，
                // 「撞了键却查不到」会让本方法误判为组号碰撞并换号重试，最终抛碰撞异常
                FissionGroup existing = groupMapper.selectActive(activityId, sponsorId);
                if (existing != null) {
                    log.info(
                            "concurrent round creation, reuse existing groupId={}",
                            existing.getGroupId());
                    return existing;
                }
                // 撞 uk_group_id：组号碰撞，换号重试
                log.warn("groupId collision, retry, attempt={}", attempt + 1);
            }
        }
        throw new BizException(ErrorCode.FISSION_QUERY_ERROR, "裂变组号连续碰撞，请重试");
    }

    // ------------------------------------------------------------------
    // ③ 轮次查询
    // ------------------------------------------------------------------

    @Override
    public GroupQueryResp queryGroup(String activityId, String sponsorId, boolean includeHistory) {
        requireText(activityId, "activityId");
        requireText(sponsorId, "sponsorId");

        GroupQueryResp resp = new GroupQueryResp();
        resp.setActivityId(activityId);
        resp.setSponsorId(sponsorId);

        FissionGroup running = groupMapper.selectRunning(activityId, sponsorId);
        if (running != null) {
            resp.setCurrent(toRoundInfo(running));
        }

        // 开关默认关闭（BR-F-05）：默认全开会让每次查询都拉膨胀表明细，
        // 而多数调用方只要当前轮的进度
        if (includeHistory) {
            List<GroupQueryResp.RoundInfo> history = new ArrayList<>();
            for (FissionGroup g : groupMapper.selectHistory(activityId, sponsorId)) {
                history.add(toRoundInfo(g));
            }
            resp.setHistory(history);
        }
        return resp;
    }

    // ------------------------------------------------------------------
    // ④ 好友获取与过滤
    // ------------------------------------------------------------------

    /**
     * 召回 + 过滤，一次请求的两段。
     *
     * <p><b>先把全部页召回，再整批过滤</b>，而不是逐页召回逐页过滤。两者的差别是下游调用次数：
     *
     * <pre>
     * 逐页过滤：page 页 × 8 条规则 = page × 8 次调用
     * 整批过滤：8 次调用
     * </pre>
     *
     * <p>这正是 BR-F-09「编排维度为按过滤器遍历，非按分页遍历」的落点 —— 逐页写法在代码上更 自然（召回一页顺手过滤一页），而它把过滤器循环嵌进了分页循环里。
     *
     * <p>代价是候选集要整批驻留内存，故 {@code maxPages} 有上限（FR-F04 的前置条件「候选集大小 在受控上限内」）。
     */
    @Override
    public FriendFilterResp getFriends(GetFriendsReq req) {
        requireText(req.getGroupId(), "groupId");
        requireText(req.getSponsorId(), "sponsorId");

        FissionGroup group = requireRunningGroup(req.getGroupId());

        int pages = req.getMaxPages() <= 0 ? DEFAULT_MAX_PAGES : req.getMaxPages();
        if (pages > MAX_PAGES_LIMIT) {
            throw new BizException(
                    ErrorCode.INVALID_PARAM, "候选页数超上限 " + MAX_PAGES_LIMIT + ": " + pages);
        }

        List<String> candidates = recall(req.getSponsorId(), pages);

        FilterContext ctx =
                new FilterContext(
                        req.getGroupId(),
                        group.getActivityId(),
                        req.getSponsorId(),
                        group.getConfigVersion(),
                        INFLUENCE_THRESHOLD);
        List<String> passed = filterChain.filter(ctx, candidates);

        FriendFilterResp resp = new FriendFilterResp();
        resp.setPassed(passed);
        resp.setRejected(ctx.getRejected());
        resp.setConfigVersion(group.getConfigVersion());
        resp.setDegradedRules(ctx.degradedRuleNames());
        resp.setCandidateCount(candidates.size());

        // 只记数量与原因分布，不打印完整用户列表（FR-F04 的日志要求）：一次请求可达数百人，
        // 全打出来会淹没日志，且好友关系本身是敏感数据
        log.info(
                "getFriends done, groupId={}, impl={}, candidates={}, passed={}, rejected={},"
                        + " degraded={}",
                req.getGroupId(),
                filterChain.relationFilterImpl(),
                candidates.size(),
                passed.size(),
                ctx.getRejected().size(),
                ctx.degradedRuleNames());
        return resp;
    }

    /**
     * 逐页召回，拉满 {@code maxPages} 或下游返回空页为止。
     *
     * <p>召回不可用抛 {@code 5603}，<b>不吞成空列表</b>：空列表与「这个人没有好友」不可区分。
     *
     * <p><b>去重在这里做，不在过滤链里</b>：不同页返回同一个人是召回侧的问题（分页期间名单变动）， 而过滤链的入参契约是「一批互不相同的候选人」——
     * 让它自己去重等于把上游的毛病带进下游。
     */
    private List<String> recall(String sponsorId, int maxPages) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (int page = 0; page < maxPages; page++) {
            List<String> batch;
            try {
                batch = socialService.recallFriends(sponsorId, page, RECALL_PAGE_SIZE);
            } catch (Exception e) {
                log.warn("friend recall failed, sponsorId={}, page={}", sponsorId, page, e);
                throw new BizException(ErrorCode.FRIEND_RECALL_UNAVAILABLE, "好友召回不可用");
            }
            if (batch == null || batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < RECALL_PAGE_SIZE) {
                // 不足一页即最后一页，不必再问下游一次拿空页
                break;
            }
        }
        return new ArrayList<>(all);
    }

    // ------------------------------------------------------------------
    // ⑤ 分享
    // ------------------------------------------------------------------

    @Override
    public ShareInviteResp shareInvite(ShareInviteReq req) {
        requireText(req.getGroupId(), "groupId");
        requireText(req.getSponsorId(), "sponsorId");
        if (req.getFollowerIds() == null || req.getFollowerIds().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "followerIds 不能为空");
        }

        FissionGroup group = requireRunningGroup(req.getGroupId());

        // 请求里的 sponsorId 只用于校验，此后一律取轮次上的值（BR-C-06 的同一条原则：
        // 客户端声明仅作提示，以服务端上下文为准）。
        //
        // 不校验就直接用请求值时，知道他人 groupId 即可往那一轮里塞一条 sponsor_id 是自己的
        // 关系 —— 同组关系带着两个不同的师傅，而师傅返奖按关系上的 sponsor_id 发，奖直接
        // 落到伪造者头上。这是控制面契约没有落进执行面的典型：followerJoin 用的已经是
        // group.getSponsorId()，只有本方法信了请求。
        //
        // 校验须先于过滤：FilterContext 也带 sponsorId，它决定「已有关系」「已是师傅」这些
        // 规则按谁来判 —— 传请求值等于让攻击者指定过滤基准，而过滤结果直接决定建哪些关系。
        String sponsorId = group.getSponsorId();
        if (!sponsorId.equals(req.getSponsorId())) {
            log.warn(
                    "shareInvite sponsor mismatch, groupId={}, claimed={}, actual={}",
                    req.getGroupId(),
                    req.getSponsorId(),
                    sponsorId);
            throw new BizException(ErrorCode.SPONSOR_NOT_GROUP_OWNER, "分享者不是该轮次的师傅");
        }

        List<String> targets = new ArrayList<>();
        for (String followerId : req.getFollowerIds()) {
            if (followerId == null || followerId.isBlank()) {
                continue;
            }
            // 师徒非同人：分享给自己不成立。先于过滤判 —— 自邀是刷奖入口，不该混在
            // 「未通过过滤」这个较软的结论里
            if (followerId.equals(sponsorId)) {
                throw new BizException(ErrorCode.SPONSOR_IS_FOLLOWER, "师徒不能为同一人");
            }
            targets.add(followerId);
        }

        // BR-F-12：被分享对象须通过过滤。分享侧独立校验一次，不信任 getFriends 的结果 ——
        // 两次调用之间对方可能已注销、已被拉黑，或客户端根本没调过 getFriends 而直接构造了
        // 一批 id。分享是写路径，把关只在读路径上做等于没做
        //
        // 关系那条规则在此关闭：它剔除的是「已有进行中关系」的人，而在分享路径上这批人的
        // 正确处置是 BR-F-11 的「重复分享不重复创建，且不作为错误」（下方由撞键分支承接）。
        // 启用它会让重复分享被判 1611，与 BR-F-11 直接冲突
        FilterContext ctx =
                new FilterContext(
                        req.getGroupId(),
                        group.getActivityId(),
                        sponsorId,
                        group.getConfigVersion(),
                        INFLUENCE_THRESHOLD);
        List<String> passed = filterChain.filter(ctx, targets, false);
        if (passed.isEmpty() && !targets.isEmpty()) {
            // 一个都没通过：这是确定的业务拒绝，不建任何关系
            throw new BizException(
                    ErrorCode.FOLLOWER_FILTERED, "被分享对象均未通过过滤: " + ctx.getRejected());
        }

        List<String> invited = new ArrayList<>();
        List<String> already = new ArrayList<>();

        for (String followerId : passed) {
            try {
                tx.createInvitedRelation(
                        req.getGroupId(),
                        group.getActivityId(),
                        sponsorId,
                        followerId,
                        req.getShareMethod(),
                        relationExpireOf(group));
                invited.add(followerId);
            } catch (DuplicateKeyException e) {
                // 撞 uk_group_follower_active：已有进行中关系。重复分享不重复创建（BR-F-11），
                // 这不是错误 —— 端上据此把该好友标记为「已邀请」
                already.add(followerId);
            }
        }

        ShareInviteResp resp = new ShareInviteResp();
        resp.setInvitedFollowerIds(invited);
        resp.setAlreadyInvitedFollowerIds(already);
        resp.setFilteredFollowerIds(ctx.getRejected());
        log.info(
                "shareInvite done, groupId={}, invited={}, already={}, filtered={}",
                req.getGroupId(),
                invited.size(),
                already.size(),
                ctx.getRejected().size());
        return resp;
    }

    // ------------------------------------------------------------------
    // ⑤ 建联
    // ------------------------------------------------------------------

    @Override
    public boolean followerConnect(String groupId, String followerId) {
        requireText(groupId, "groupId");
        requireText(followerId, "followerId");

        // 与分享、加入同一道闸：终结或已过期的轮次不得再推进关系。
        //
        // 缺了它，三条写路径对「轮次还能不能动」给出两种答案 —— 分享与加入拒绝，建联放行。
        // 表现是终结轮次里的 INVITED 仍能被推成 CONNECTED，而 PR-4 的双向发奖以关系状态为
        // 判据，一条本该随轮次一起作废的关系就这样进了发奖范围。
        //
        // 幂等性不受影响：本方法的幂等来自下面那条条件更新的 status 谓词，不来自「不校验」
        requireRunningGroup(groupId);

        // 条件更新 WHERE status='INVITED'：重复点击命中 0 行，天然幂等（BR-F-13）。
        // 不先查后判 —— 那在并发下会两个线程都读到 INVITED 各推进一次
        int rows =
                relationMapper.advanceStatusByGroupFollower(
                        groupId,
                        followerId,
                        RelationStatus.INVITED.name(),
                        RelationStatus.CONNECTED.name());
        log.info(
                "followerConnect groupId={}, followerId={}, advanced={}",
                groupId,
                followerId,
                rows > 0);
        return rows > 0;
    }

    // ------------------------------------------------------------------
    // ⑥ 徒弟加入
    // ------------------------------------------------------------------

    @Override
    public String followerJoin(FollowerJoinReq req) {
        requireText(req.getGroupId(), "groupId");
        requireText(req.getFollowerId(), "followerId");
        requireText(req.getOutBizNo(), "outBizNo");
        requireText(req.getOutFlowNo(), "outFlowNo");

        FissionGroup group = requireRunningGroup(req.getGroupId());

        // 师徒非同人（BR-F-14 的前置，1614）：自己邀请自己即可无限刷奖
        if (req.getFollowerId().equals(group.getSponsorId())) {
            throw new BizException(ErrorCode.SPONSOR_IS_FOLLOWER, "师徒不能为同一人");
        }

        // 幂等前置查：outFlowNo 标识本次操作，命中且确属同一件事才返回原关系
        FissionOpRecordMapper.OpRecordBinding hit =
                opRecordMapper.selectBindingByIdempotentKey(req.getOutFlowNo());
        if (hit != null) {
            // 命中只证明「这个流水号用过」，不证明「用在了同一件事上」。
            //
            // uk_idempotent 是全局唯一键，上游若把某次操作的 outFlowNo 复用到另一次加入
            // （不同徒弟、不同关系，甚至不同 op_type），此处仍然命中。若就此回查
            // (groupId, followerId) 的 active 关系并返回，返回的是「当前请求对应的那条关系」——
            // 而它可能仍停在 INVITED，从没被这次调用推进过。调用方拿到一个非空 relationId，
            // 会认为加入已成功。
            //
            // 这类失效是静默的：没有异常、没有错误码，只有一条状态不对的关系。故命中后
            // 必须比对绑定字段，不一致按幂等键冲突（4002）显式拒绝
            requireSameOperation(hit, req);

            FissionRelation r = relationMapper.selectActive(req.getGroupId(), req.getFollowerId());
            log.info(
                    "followerJoin idempotent hit, outFlowNo={}, opNo={}",
                    req.getOutFlowNo(),
                    hit.getOpNo());
            return r == null ? null : r.getRelationId();
        }

        try {
            String relationId =
                    tx.joinRelation(
                            req.getGroupId(),
                            group.getActivityId(),
                            group.getSponsorId(),
                            req.getFollowerId(),
                            req.getOutBizNo(),
                            req.getOutFlowNo(),
                            relationExpireOf(group));
            if (relationId != null) {
                return relationId;
            }
        } catch (DuplicateKeyException e) {
            // 撞 uk_group_follower_active 或 uk_idempotent：并发加入已建好（BR-F-16）
            log.info("followerJoin concurrent, fall back to query, groupId={}", req.getGroupId());
        }

        // 并发已推进：回查那一条。同一徒弟只产生一条关系，这是 L3 的兜底结果
        FissionRelation r = relationMapper.selectActive(req.getGroupId(), req.getFollowerId());
        if (r == null) {
            throw new BizException(ErrorCode.FISSION_QUERY_ERROR, "加入失败且未查到关系");
        }
        return r.getRelationId();
    }

    // ------------------------------------------------------------------
    // ⑦ 徒弟完成与双向发奖
    // ------------------------------------------------------------------

    /**
     * 组件序：关系预处理 → 徒弟发奖 → 关系后处理 → 师傅返奖（异步）。
     *
     * <p><b>与 {@code payCallback} 占据同一架构位置</b>（技术方案 §5.0）：一个「已确认的凭证事件」 触发「公共能力层发奖」。二者上游不同（社交行为 vs
     * 付款），下游收敛到同一套 {@code reward.grantReward} + 可靠任务查单。
     *
     * <p>师傅返奖走本地消息表异步（BR-F-21）：主链路只保证徒弟发奖同步完成，接口响应不返回师傅 返奖状态 —— 一次请求串两次外部发奖会把 RT 拉长一倍，而师傅奖最终一致即可。
     */
    @Override
    public String followerDone(FollowerDoneReq req) {
        requireText(req.getGroupId(), "groupId");
        requireText(req.getFollowerId(), "followerId");
        requireText(req.getOutBizNo(), "outBizNo");
        requireText(req.getOutFlowNo(), "outFlowNo");

        FissionGroup group = requireRunningGroup(req.getGroupId());

        // ① 幂等前置查，必须先于定位关系：确权成功后关系已进 DONE 并释放 active_flag，
        // 此时 selectActive 查不到它 —— 先定位再判幂等的话，重复确权拿到的是「关系不存在」
        // 而非幂等命中。对上游而言那是一个会触发告警的错误，而它本该静默成功
        if (opRecordMapper.selectOpNoByIdempotentKey(req.getOutFlowNo()) != null) {
            // 回查用 selectLatest（不看 active_flag）：确权成功的那条关系已释放唯一键
            FissionRelation done =
                    relationMapper.selectLatest(req.getGroupId(), req.getFollowerId());
            log.info("followerDone idempotent hit, outFlowNo={}", req.getOutFlowNo());
            return done == null ? null : done.getRelationId();
        }

        // ② 关系预处理：定位唯一进行中关系，校验 JOINED
        FissionRelation relation =
                relationMapper.selectActive(req.getGroupId(), req.getFollowerId());
        if (relation == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "关系不存在: " + req.getFollowerId());
        }
        if (!RelationStatus.JOINED.name().equals(relation.getStatus())) {
            throw new BizException(
                    ErrorCode.RELATION_NOT_JOINED, "关系非 JOINED: " + relation.getStatus());
        }

        // 两把发奖键同源派生自 outFlowNo，重试可重算（技术方案 §5.1）
        String followerGrantNo = IdempotentKeys.followerGrantNo(req.getOutFlowNo());
        String sponsorFlowNo = IdempotentKeys.sponsorFlowNo(req.getOutFlowNo());

        // ③ 任务前置：op=PROCESSING + granting_until + 查单任务，同事务。
        // 此后任何时刻宕机，任务都在 —— 这是收敛率 100% 的基础
        boolean accepted =
                tx.prepareDone(
                        relation.getRelationId(),
                        req.getGroupId(),
                        group.getActivityId(),
                        req.getFollowerId(),
                        req.getOutBizNo(),
                        req.getOutFlowNo(),
                        followerGrantNo,
                        GRANTING_WINDOW_SECONDS);
        if (!accepted) {
            // 前置查与本次插入之间的并发窗口：另一线程已插入同一 outFlowNo。
            // 前置查挡的是「重传」，这里挡的是「并发」—— 后者才是真正的兜底（唯一索引）
            log.info("followerDone concurrent idempotent hit, outFlowNo={}", req.getOutFlowNo());
            return relation.getRelationId();
        }

        // ④ 徒弟发奖：调公共能力层
        RetStatus result = grantToFollower(group, relation, req, followerGrantNo);

        switch (result) {
            case SUCCESS -> {
                // ⑤ 关系后处理：四写同事务
                boolean settled =
                        tx.settleDone(
                                relation.getRelationId(),
                                req.getGroupId(),
                                req.getOutBizNo(),
                                req.getOutFlowNo(),
                                followerGrantNo,
                                sponsorFlowNo);
                log.info(
                        "followerDone settled, relationId={}, settled={}",
                        relation.getRelationId(),
                        settled);
            }
            case FAIL -> {
                // 确定失败：关系保持 JOINED，可人工或重试重入
                tx.markDoneFailed(relation.getRelationId(), req.getOutFlowNo());
                log.warn("followerDone grant FAILED, relationId={}", relation.getRelationId());
            }
            default -> {
                // UNKNOWN / PROCESSING：不推进关系，保留查单任务由收敛处置。
                // 判 FAIL 会让一笔可能已发放的奖被当成没发，补发即重复发放
                tx.markDoneUnresolved(req.getOutFlowNo(), result);
                log.info(
                        "followerDone unresolved, keep QUERY_GRANT, relationId={}, result={}",
                        relation.getRelationId(),
                        result);
            }
        }
        return relation.getRelationId();
    }

    /**
     * 徒弟发奖，调 {@code reward.grantReward}。
     *
     * <p><b>这是 §1.2 论断的验收点</b>：裂变能否原样调用为权益售卖设计的接口。六个字段的取值见 《分阶段方案》§6.3 的填表结论；三个对裂变无语义的字段由 {@link
     * RewardItemFactory} 填约定值。
     */
    private RetStatus grantToFollower(
            FissionGroup group,
            FissionRelation relation,
            FollowerDoneReq req,
            String followerGrantNo) {
        GrantRewardReq grantReq = new GrantRewardReq();
        grantReq.setPlayType("FISSION");
        grantReq.setActivityId(group.getActivityId());
        // bizOrderNo 取 relationId 而非 groupId：一个组下 N 条关系各自发奖，取 groupId 会让
        // uk_biz_op 把同组第二个徒弟的发奖当成重复操作挡下
        grantReq.setBizOrderNo(relation.getRelationId());
        grantReq.setOpNo(followerGrantNo);
        grantReq.setReceiverId(req.getFollowerId());
        grantReq.setRewardItems(
                RewardItemFactory.of(FOLLOWER_REWARD_TYPE, List.of(FOLLOWER_REWARD_CONFIG_ID)));

        try {
            return rewardService.grantReward(grantReq).getRetStatus();
        } catch (Exception e) {
            // 异常映射 UNKNOWN 而非 FAIL：异常可能发生在 RPC 发出之后，下游未必没执行
            log.warn("grantReward threw, treat as UNKNOWN, opNo={}", followerGrantNo, e);
            return RetStatus.UNKNOWN;
        }
    }

    // ------------------------------------------------------------------
    // ⑧ 对账
    // ------------------------------------------------------------------

    /**
     * 跑一轮裂变侧对账（技术方案 §6.8 第 7、10、12、13 项）。编排在 {@link FissionReconcileService}， 本方法只是玩法层的对外入口。
     *
     * <p><b>这是 {@code FissionReconcileService} 的唯一触达点</b>：它不被任何调度直接持有，缺了这个入口 那四项一次都不会运行 —— 其中第 7
     * 项（师傅奖漏发）与第 13 项（发奖在途标志超时）都是资损哨兵。
     *
     * <p><b>不加锁</b>：与权益侧同理，对账是旁路只读扫描 + 幂等补建，两个实例同时跑的后果只是补建 两次同一条任务 —— 而 {@code enqueue} 命中 {@code
     * uk_biz_type_op} 不产生第二条。
     */
    @Override
    public java.util.Map<String, Integer> reconcile() {
        return reconcileService.reconcileOnce();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 幂等命中后校验「原操作」与「本次请求」是否同一件事，不是则按幂等键冲突拒绝。
     *
     * <p>比对三项，各挡一类复用：
     *
     * <ul>
     *   <li>{@code op_type} —— 挡「完成操作的流水号被当成加入重传」。二者是不同动作，PRD BR-F-14/15 把 {@code outBizNo} 与
     *       {@code outFlowNo} 分开正是为此
     *   <li>{@code subject_id} —— 挡「甲的加入流水号用在乙的加入上」。它是操作主体，即徒弟
     *   <li>{@code out_biz_no} —— 挡「同一徒弟、不同师徒关系」。它标识一次师徒关系（BR-F-14）
     * </ul>
     *
     * <p><b>判 {@code 4002} 而非静默返回</b>：幂等的语义是「同一请求重复执行结果相同」，前提是 「同一请求」。请求不同却复用了幂等键，是上游的单号生成有问题 ——
     * 静默按命中处理会把 这个问题埋起来，且返回的关系状态未必对。归 4xxx：同一组合重试结果不变，重试无意义。
     *
     * <p>不比 {@code activityId}：请求里没有这个字段，它由 {@code groupId} 推出，已被 {@code out_biz_no} 那一项覆盖。
     */
    private static void requireSameOperation(
            FissionOpRecordMapper.OpRecordBinding hit, FollowerJoinReq req) {
        boolean sameType = "FOLLOWER_JOIN".equals(hit.getOpType());
        boolean sameSubject = req.getFollowerId().equals(hit.getSubjectId());
        boolean sameBiz = req.getOutBizNo().equals(hit.getOutBizNo());
        if (sameType && sameSubject && sameBiz) {
            return;
        }
        log.warn(
                "followerJoin idempotent key reused across operations, outFlowNo={},"
                        + " opType={}/{}, subject={}/{}, outBizNo={}/{}",
                req.getOutFlowNo(),
                hit.getOpType(),
                "FOLLOWER_JOIN",
                hit.getSubjectId(),
                req.getFollowerId(),
                hit.getOutBizNo(),
                req.getOutBizNo());
        throw new BizException(
                ErrorCode.IDEMPOTENT_KEY_CONFLICT, "外部流水号已用于另一次操作: " + req.getOutFlowNo());
    }

    /**
     * 取进行中的裂变组，不存在、已终结或已过期即拒绝 —— 往已结束的轮次里拉人不成立。
     *
     * <p><b>「进行中」的判据必须与 {@code selectRunning} 一致</b>：状态 {@code RUNNING} <b>且</b>未过期。
     * 只判状态时本类会有两套「进行中」定义 —— 进场那条路（走 {@code selectRunning}）认为轮次已经不可用 不再复用它，而分享/加入这条路（走本方法）仍然放行。
     *
     * <p>放行的后果不止是「多了几条关系」：{@link #relationExpireOf} 把关系有效期取自轮次， 于是建出来的关系<b>诞生即过期</b>；而 PR-5
     * 的过期治理尚未落地，没有任何东西会终结这一轮， 这些关系就永远停在那里 —— 既发不了奖，也不会被清理。
     *
     * <p>过期判定<b>用库时钟</b>（{@code selectRunning} 的 {@code expire_time > NOW(3)}）而非在 Java 侧比 {@code
     * LocalDateTime.now()}：{@code expire_time} 由库的 {@code DATE_ADD(NOW(3), ...)} 算出，
     * 两端取自同一个时钟才不会因应用与库的时差产生边界抖动（与《分阶段方案》§5.6 ⑦ 对 {@code next_time} 的处置同源）。
     */
    private FissionGroup requireRunningGroup(String groupId) {
        FissionGroup group = groupMapper.selectByGroupId(groupId);
        if (group == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "裂变组不存在: " + groupId);
        }
        if (!"RUNNING".equals(group.getStatus())) {
            throw new BizException(ErrorCode.GROUP_NOT_RUNNING, "裂变组已终结: " + group.getStatus());
        }
        // 状态为 RUNNING 但已过期：轮次过期治理（PR-5）尚未把它推到 EXPIRED，此处按已终结拒绝
        if (!groupMapper.isUnexpired(groupId)) {
            throw new BizException(
                    ErrorCode.GROUP_NOT_RUNNING,
                    "裂变组已过期: " + groupId + ", expireTime=" + group.getExpireTime());
        }
        return group;
    }

    /**
     * 关系有效期取轮次的有效期。
     *
     * <p>关系不能活得比它所属的轮次久 —— 轮次结算后才完成的关系，奖发给谁都不对。PRD 6.1 也 写明「关系有效期须 ≤ 活动有效期」。
     */
    private static String relationExpireOf(FissionGroup group) {
        return group.getExpireTime().toString().replace('T', ' ');
    }

    private static QualifyReq toQualifyReq(SponsorQueryReq req) {
        QualifyReq q = new QualifyReq();
        q.setUserId(req.getSponsorId());
        q.setActivityId(req.getActivityId());
        q.setScene(req.getScene());
        q.setCity(req.getCity());
        q.setChannel(req.getChannel());
        q.setDeviceId(req.getDeviceId());
        q.setClientIp(req.getClientIp());
        return q;
    }

    private static void fillRound(SponsorQueryResp resp, FissionGroup group) {
        resp.setGroupId(group.getGroupId());
        resp.setRoundNo(group.getRoundNo());
        resp.setProgress(group.getProgress());
        resp.setTargetCount(group.getTargetCount());
    }

    private static GroupQueryResp.RoundInfo toRoundInfo(FissionGroup g) {
        GroupQueryResp.RoundInfo info = new GroupQueryResp.RoundInfo();
        info.setGroupId(g.getGroupId());
        info.setRoundNo(g.getRoundNo());
        info.setStatus(g.getStatus());
        info.setProgress(g.getProgress());
        info.setTargetCount(g.getTargetCount());
        return info;
    }

    /**
     * 邀请凭证。
     *
     * <p>V3 PR-2 取确定性拼接的明文串，PR-3 分享链路接入时改为 {@code ConsultTokenSigner} 签名 —— 该 SDK 落在 {@code
     * mp-common}，正是为了「Token 处理不放在 activity」（技术方案 §4.2）。
     *
     * <p>此处不提前签名：签名要绑定的字段（徒弟分支、奖励快照）在 PR-3 才确定，提前签会签一个 不完整的载荷，而凭证一旦签发就会被端上缓存。
     */
    private static String inviteToken(FissionGroup group, String sponsorId) {
        return group.getGroupId() + "_" + sponsorId + "_" + group.getRoundNo();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, field + " 不能为空");
        }
    }
}
