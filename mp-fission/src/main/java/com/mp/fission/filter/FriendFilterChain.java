package com.mp.fission.filter;

import com.mp.api.mock.service.MockSocialService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 好友过滤编排（FR-F04）。
 *
 * <p><b>编排维度是「按过滤器遍历」，不是「按分页遍历」</b>（BR-F-09）。差别不在写法而在 SQL 次数：
 *
 * <pre>
 * 按分页遍历：for page → for filter → 每个 filter 各问一次下游   = page × 8 次调用
 * 按过滤器遍历：for filter → 整批一次问完                        = 8 次调用
 * </pre>
 *
 * <p>本方法接收的是<b>已经取齐的候选集</b>（调用方负责分页召回），故循环只有过滤器这一层。 分页在 {@code getFriends} 那一侧，两者不嵌套——嵌套正是 §7.1
 * 记的病根。
 *
 * <p><b>过滤器按序短路</b>：前一条拒掉的人不进入后一条的候选集。顺序按「先便宜后昂贵」：关系 过滤查本地库最便宜，排最前；四条 fail-open
 * 的投放类规则排最后，它们即使降级也不影响准入。
 *
 * <p><b>每条规则的失败语义由 {@link FilterRule} 自带</b>，编排层只负责「捕获异常 → 查语义 → 按语义处置 → 记降级」这一段。语义写进编排的 if
 * 会让每加一条规则都要回头改这里，且两处一旦漂移， 表现是某条规则在依赖故障时静默换了语义。
 */
@Component
public class FriendFilterChain {

    private static final Logger log = LoggerFactory.getLogger(FriendFilterChain.class);

    private final MockSocialService socialService;
    private final RelationFilter relationFilter;

    public FriendFilterChain(MockSocialService socialService, RelationFilter relationFilter) {
        this.socialService = socialService;
        this.relationFilter = relationFilter;
    }

    /**
     * 过滤一批候选，返回通过的那些；拒绝与降级记进 {@code ctx}。
     *
     * <p>八条规则各调一次下游（关系那条查本地库），<b>与候选人数无关</b>。
     */
    public List<String> filter(FilterContext ctx, List<String> candidates) {
        return filter(ctx, candidates, true);
    }

    /**
     * 过滤一批候选，{@code applyRelationRule} 控制是否启用关系过滤。
     *
     * <p><b>分享侧须传 {@code false}</b>。关系过滤剔除的是「已有进行中关系」的人，而在分享路径上 这批人的正确处置是 BR-F-11
     * 的「重复分享不重复创建，且不作为错误」—— 端上据此把头像标记为 「已邀请」。启用它则重复分享会被判 {@code 1611}（未通过过滤），而那是一个会让端上弹错误提示的 结果，与
     * BR-F-11 直接冲突。
     *
     * <p><b>两条规则并不矛盾，是同一件事在两条路径上的不同呈现</b>：读路径（{@code getFriends}）要
     * 把已邀请的人从可选名单里去掉，免得用户点了没反应；写路径（{@code shareInvite}）要把重复请求
     * 识别为幂等命中。故关系维度在两侧都在起作用，只是一侧记「拒绝」、一侧记「已邀请」。
     *
     * <p>其余七条规则两侧一致：账号注销、被拉黑、人群不匹配，在哪条路径上都是拒绝。
     */
    public List<String> filter(
            FilterContext ctx, List<String> candidates, boolean applyRelationRule) {
        List<String> alive = new ArrayList<>(new LinkedHashSet<>(candidates));

        // ① 关系（fail-close）：查本地库，最便宜，排最前。这条是 §7.1 的优化对象
        if (applyRelationRule) {
            alive =
                    applyExclusion(
                            ctx,
                            FilterRule.RELATION,
                            alive,
                            ids -> relationFilter.findWithActiveRelation(ctx.getGroupId(), ids));
        }

        // ② 分享频控（fail-close）
        alive =
                applyExclusion(
                        ctx,
                        FilterRule.SHARE_FREQUENCY,
                        alive,
                        ids -> socialService.batchSharedToday(ctx.getSponsorId(), ids));

        // ③ 账户状态（fail-close）：注销或禁言
        alive =
                applyExclusion(
                        ctx,
                        FilterRule.ACCOUNT_STATUS,
                        alive,
                        ids ->
                                keysWhere(
                                        socialService.batchAccountStatus(ids),
                                        status -> !"NORMAL".equals(status)));

        // ④ 用户角色（fail-close）：仅普通用户可参与
        alive =
                applyExclusion(
                        ctx,
                        FilterRule.USER_ROLE,
                        alive,
                        ids ->
                                keysWhere(
                                        socialService.batchUserRole(ids),
                                        role -> !"NORMAL".equals(role)));

        // ⑤ 影响力（fail-open）：粉丝量超阈值的大 V 不作为裂变对象
        alive =
                applyExclusion(
                        ctx,
                        FilterRule.INFLUENCE,
                        alive,
                        ids ->
                                keysWhere(
                                        socialService.batchFollowerCount(ids),
                                        count -> count > ctx.getInfluenceThreshold()));

        // ⑥ 社交关系（fail-open）：拉黑了师傅的人
        alive =
                applyExclusion(
                        ctx,
                        FilterRule.SOCIAL,
                        alive,
                        ids -> socialService.batchBlocked(ctx.getSponsorId(), ids));

        // ⑦ 实验分组（fail-open）：不在实验组的排除。注意这条是「保留命中者」，与前几条相反
        alive =
                applyRetention(
                        ctx,
                        FilterRule.EXPERIMENT,
                        alive,
                        ids -> socialService.batchInExperiment(ctx.getActivityId(), ids));

        // ⑧ 营销人群（fail-open）：不匹配活动标签的排除，同样是保留命中者
        alive =
                applyRetention(
                        ctx,
                        FilterRule.CROWD,
                        alive,
                        ids -> socialService.batchInCrowd(ctx.getActivityId(), ids));

        return alive;
    }

    /**
     * 「命中即拒绝」类规则：下游返回的是应被剔除的集合。
     *
     * <p>与 {@link #applyRetention} 拆开而非用一个布尔参数：两者在<b>降级时的行为</b>不同 —— fail-open
     * 降级时前者「一个都不拒」，后者「一个都不留」若照搬 fail-open 的字面意思就全拒了。 分开写让两处各自的降级分支都必须显式写出来。
     */
    private List<String> applyExclusion(
            FilterContext ctx,
            FilterRule rule,
            List<String> alive,
            Function<List<String>, Set<String>> queryRejected) {
        if (alive.isEmpty()) {
            // 上一条已把人拒光：不再调下游。省的是一次真实的外部调用，不是几行 CPU
            return alive;
        }
        Set<String> rejected;
        try {
            rejected = queryRejected.apply(alive);
        } catch (Exception e) {
            return handleDependencyFailure(ctx, rule, alive, e);
        }
        List<String> kept = new ArrayList<>(alive.size());
        for (String id : alive) {
            if (rejected.contains(id)) {
                ctx.reject(id, rule.getRejectReason());
            } else {
                kept.add(id);
            }
        }
        return kept;
    }

    /**
     * 「命中才保留」类规则：下游返回的是应被留下的集合（实验分组、人群标签）。
     *
     * <p><b>降级时的正确行为是全部放行，不是全部拒绝</b>。这两条规则的下游返回「谁在组里」， 依赖挂掉时返回的是空集——若按正常路径处理，空集意味着「没人在组里」，结果是全员被拒。
     * 而它们是 fail-open 规则，语义要求放行。
     *
     * <p><b>这正是把两类规则拆成两个方法的理由</b>：合成一个方法加布尔参数时，降级分支只有一处， 「fail-open 就放行」这句话对 exclusion
     * 类恰好等于「不拒任何人」（正确），对 retention 类却会 走进「保留空集」（全拒，与语义相反）。而两类规则的正常路径长得几乎一样，合并的诱惑很大。
     */
    private List<String> applyRetention(
            FilterContext ctx,
            FilterRule rule,
            List<String> alive,
            Function<List<String>, Set<String>> queryKept) {
        if (alive.isEmpty()) {
            return alive;
        }
        Set<String> kept;
        try {
            kept = queryKept.apply(alive);
        } catch (Exception e) {
            return handleDependencyFailure(ctx, rule, alive, e);
        }
        if (kept == null) {
            // 该活动没有配这条规则（没做实验、没圈人群）—— 规则不适用，全员放行。
            //
            // 这与「配了但一个都没命中」（空集，全员拒绝）必须分开。合并的后果由
            // BaselineFriendFilterIT 实测发现：未配置实验的活动分享名单恒为空，且不报错。
            // 而多数活动本来就不做实验分组
            log.debug("filter {} not configured for activity {}, skip", rule, ctx.getActivityId());
            return alive;
        }
        List<String> result = new ArrayList<>(alive.size());
        for (String id : alive) {
            if (kept.contains(id)) {
                result.add(id);
            } else {
                ctx.reject(id, rule.getRejectReason());
            }
        }
        return result;
    }

    /**
     * 依赖不可用时按规则自带的语义处置，<b>两类语义都记降级清单</b>。
     *
     * <p>fail-close 阻断的是<b>当前存活的全部候选人</b>，不是「这条规则本该拒的那些」——依赖挂了， 本该拒谁根本无从得知。这是 fail-close
     * 的字面含义：问不到就一律不放行。
     *
     * @return fail-open 时原样返回存活集合，fail-close 时返回空集
     */
    private List<String> handleDependencyFailure(
            FilterContext ctx, FilterRule rule, List<String> alive, Exception e) {
        ctx.markDegraded(rule);
        if (rule.isFailOpen()) {
            log.warn(
                    "filter {} degraded, fail-open, pass {} candidates, cause={}",
                    rule,
                    alive.size(),
                    e.toString());
            return alive;
        }
        log.warn(
                "filter {} degraded, fail-close, reject {} candidates, cause={}",
                rule,
                alive.size(),
                e.toString());
        for (String id : alive) {
            ctx.reject(id, rule.getDegradedReason());
        }
        return List.of();
    }

    /** 取满足条件的键，保持入参顺序。 */
    private static <T> Set<String> keysWhere(
            Map<String, T> source, Function<T, Boolean> predicate) {
        Set<String> hit = new LinkedHashSet<>();
        for (Map.Entry<String, T> e : source.entrySet()) {
            if (Boolean.TRUE.equals(predicate.apply(e.getValue()))) {
                hit.add(e.getKey());
            }
        }
        return hit;
    }

    /** 当前生效的关系过滤实现，写进日志与对照实验记录。 */
    public String relationFilterImpl() {
        return relationFilter.implName();
    }
}
