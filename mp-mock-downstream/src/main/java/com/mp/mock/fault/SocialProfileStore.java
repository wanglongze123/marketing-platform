package com.mp.mock.fault;

import com.mp.api.mock.dto.SocialDependency;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * mock 社交侧的数据与故障开关。
 *
 * <p><b>与 {@code ProviderLedger} 同类</b>：它持有平台读不到的数据，测试通过它布置场景（谁被拉黑、 谁是商家号），再从平台侧观察过滤结果 ——
 * 而不是让测试直接改平台的表。
 *
 * <p><b>故障按能力逐项注入，不是全局开关</b>：退出标准第 9 条要「逐个注入该过滤器依赖不可用」， 断言 fail-close 的四项阻断、fail-open
 * 的四项放行。全局开关只能一次全挂，那样八条规则的语义 差异一条也验不出来 —— 全挂时结果是「全被拒」，与「fail-close 生效」表现一致。
 *
 * <p>注入的维度取 {@link SocialDependency}（下游自己的能力清单）而非平台的过滤规则枚举：mock 不依赖 {@code
 * mp-fission}，反向依赖会让下游知道平台怎么用它。
 */
@Component
public class SocialProfileStore {

    /** 每个用户的好友名单，测试按需布置 */
    private final Map<String, List<String>> friends = new ConcurrentHashMap<>();

    private final Map<String, Long> followerCount = new ConcurrentHashMap<>();

    private final Map<String, String> accountStatus = new ConcurrentHashMap<>();

    private final Map<String, String> userRole = new ConcurrentHashMap<>();

    /** {@code sponsorId} → 当天已分享过的对象 */
    private final Map<String, Set<String>> sharedToday = new ConcurrentHashMap<>();

    /** {@code sponsorId} → 拉黑了他的人 */
    private final Map<String, Set<String>> blocked = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> inExperiment = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> inCrowd = new ConcurrentHashMap<>();

    /** 召回本身是否不可用（{@code 5603}），与过滤器的依赖故障是两回事 */
    private volatile boolean recallDown;

    /**
     * 平台发起召回的次数，按 {@code sponsorId} 分别计数。
     *
     * <p><b>与 {@code ProviderLedger.recordGrantAttempt} 同一用途</b>：调用次数只有下游数得准。
     * 平台侧数不出来——它拿到的是合并去重后的候选集，「问了几页」这个信息在返回值里已经不存在。
     *
     * <p>没有它，「不足一页即停止翻页」这条只能靠读代码确认：多问一次下游拿到空页，最终候选集 与不多问完全一样。
     */
    private final Map<String, AtomicInteger> recallCalls = new ConcurrentHashMap<>();

    /** 依赖不可用的规则集合，逐条注入 */
    private final Set<SocialDependency> downRules = ConcurrentHashMap.newKeySet();

    // ---- 场景布置 ----

    public void putFriends(String userId, List<String> ids) {
        friends.put(userId, List.copyOf(ids));
    }

    public void putFollowerCount(String userId, long count) {
        followerCount.put(userId, count);
    }

    public void putAccountStatus(String userId, String status) {
        accountStatus.put(userId, status);
    }

    public void putUserRole(String userId, String role) {
        userRole.put(userId, role);
    }

    public void markSharedToday(String sponsorId, String followerId) {
        sharedToday.computeIfAbsent(sponsorId, k -> ConcurrentHashMap.newKeySet()).add(followerId);
    }

    public void markBlocked(String sponsorId, String followerId) {
        blocked.computeIfAbsent(sponsorId, k -> ConcurrentHashMap.newKeySet()).add(followerId);
    }

    public void markInExperiment(String activityId, String userId) {
        inExperiment.computeIfAbsent(activityId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    public void markInCrowd(String activityId, String userId) {
        inCrowd.computeIfAbsent(activityId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    // ---- 故障注入 ----

    public void setRecallDown(boolean down) {
        this.recallDown = down;
    }

    public void setRuleDown(SocialDependency rule, boolean down) {
        if (down) {
            downRules.add(rule);
        } else {
            downRules.remove(rule);
        }
    }

    public boolean isRecallDown() {
        return recallDown;
    }

    /** 记一次召回到达。所有模式都算，包括抛不可用的那次 —— 请求确实发出了。 */
    public void recordRecallCall(String sponsorId) {
        recallCalls.computeIfAbsent(sponsorId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 平台向该师傅发起过几次召回，即翻了几页。 */
    public int recallCallCount(String sponsorId) {
        AtomicInteger n = recallCalls.get(sponsorId);
        return n == null ? 0 : n.get();
    }

    /** 该规则的依赖是否不可用。调用点据此抛异常，模拟「问不到人」。 */
    public void failIfDown(SocialDependency rule) {
        if (downRules.contains(rule)) {
            throw new IllegalStateException("模拟依赖不可用: " + rule);
        }
    }

    /** 全部复位。测试之间互不影响，靠的是每个用例自己布置场景而非依赖顺序。 */
    public void reset() {
        friends.clear();
        followerCount.clear();
        accountStatus.clear();
        userRole.clear();
        sharedToday.clear();
        blocked.clear();
        inExperiment.clear();
        inCrowd.clear();
        downRules.clear();
        recallCalls.clear();
        recallDown = false;
    }

    // ---- 读取 ----

    public List<String> friendsOf(String userId) {
        return friends.getOrDefault(userId, List.of());
    }

    /**
     * 取一批用户的某个标量画像。
     *
     * <p>返回 {@link LinkedHashMap} 保持入参顺序 —— 过滤结果的顺序影响拒绝集合的可读性，而 {@code HashMap}
     * 的迭代序随哈希值变化，会让同一批入参在不同 JVM 上产出不同顺序的日志。
     */
    public <T> Map<String, T> pick(Map<String, T> source, List<String> ids, T defaultValue) {
        Map<String, T> out = new LinkedHashMap<>();
        for (String id : ids) {
            T v = source.get(id);
            out.put(id, v == null ? defaultValue : v);
        }
        return out;
    }

    public Map<String, Long> followerCounts(List<String> ids) {
        return pick(followerCount, ids, 0L);
    }

    public Map<String, String> accountStatuses(List<String> ids) {
        return pick(accountStatus, ids, "NORMAL");
    }

    public Map<String, String> userRoles(List<String> ids) {
        return pick(userRole, ids, "NORMAL");
    }

    public Set<String> intersect(Map<String, Set<String>> source, String key, List<String> ids) {
        Set<String> all = source.getOrDefault(key, Set.of());
        List<String> hit = new ArrayList<>();
        for (String id : ids) {
            if (all.contains(id)) {
                hit.add(id);
            }
        }
        return Set.copyOf(hit);
    }

    public Set<String> sharedToday(String sponsorId, List<String> ids) {
        return intersect(sharedToday, sponsorId, ids);
    }

    public Set<String> blocked(String sponsorId, List<String> ids) {
        return intersect(blocked, sponsorId, ids);
    }

    /**
     * 该活动的实验命中集合；<b>活动未配置实验时返回 {@code null}</b>。
     *
     * <p>与「配了但一个都没命中」（空集）严格分开：合并会让未配置实验的活动把全部候选人拒光， 而多数活动本来就不做实验。失败形态是分享名单恒为空，且不报任何错。
     */
    public Set<String> inExperiment(String activityId, List<String> ids) {
        if (!inExperiment.containsKey(activityId)) {
            return null;
        }
        return intersect(inExperiment, activityId, ids);
    }

    /** 该活动的人群命中集合；未配置人群包时返回 {@code null}，理由同 {@link #inExperiment}。 */
    public Set<String> inCrowd(String activityId, List<String> ids) {
        if (!inCrowd.containsKey(activityId)) {
            return null;
        }
        return intersect(inCrowd, activityId, ids);
    }
}
