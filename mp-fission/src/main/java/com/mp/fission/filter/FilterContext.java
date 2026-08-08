package com.mp.fission.filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次过滤请求的上下文与累积结果。
 *
 * <p><b>单请求内只构建一次</b>（BR-F-10 后半句）：活动上下文、实验上下文、关系上下文在整次过滤 中不变，逐页重建会让「构建一次上下文」的成本乘以页数 —— 而那些构建本身也是
 * IO。
 *
 * <p><b>拒绝按「第一条命中的规则」记账，不累计</b>：过滤器按序短路，一个人被账户状态拒绝后 不再参与后续规则。记全部命中规则要求每条规则都对全量候选人跑一遍，与短路矛盾 —— 而运营
 * 需要的是「为什么这个人不能被邀请」，第一条原因足够。
 */
public class FilterContext {

    private final String groupId;
    private final String activityId;
    private final String sponsorId;
    private final int configVersion;

    /** 影响力阈值：粉丝量超过它即拒（BR-F-07-e）。V3 取固定值，运营配置化后由活动快照填充 */
    private final long influenceThreshold;

    /** 被拒绝者 → 原因，保持插入序便于阅读与断言 */
    private final Map<String, String> rejected = new LinkedHashMap<>();

    /** 本次发生降级的规则，按发生顺序 */
    private final List<FilterRule> degraded = new ArrayList<>();

    public FilterContext(
            String groupId,
            String activityId,
            String sponsorId,
            int configVersion,
            long influenceThreshold) {
        this.groupId = groupId;
        this.activityId = activityId;
        this.sponsorId = sponsorId;
        this.configVersion = configVersion;
        this.influenceThreshold = influenceThreshold;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getActivityId() {
        return activityId;
    }

    public String getSponsorId() {
        return sponsorId;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public long getInfluenceThreshold() {
        return influenceThreshold;
    }

    /** 记一条拒绝。已被前一条规则拒过的人不覆盖 —— 短路语义下第一条原因才是生效的那条。 */
    public void reject(String userId, String reason) {
        rejected.putIfAbsent(userId, reason);
    }

    /**
     * 记一次降级。
     *
     * <p>按规则去重：同一条规则在多个分页各失败一次，对调用方是同一件事（「这条规则本次不可靠」）， 逐页记会让清单长度取决于候选人数。
     */
    public void markDegraded(FilterRule rule) {
        if (!degraded.contains(rule)) {
            degraded.add(rule);
        }
    }

    public Map<String, String> getRejected() {
        return rejected;
    }

    public List<FilterRule> getDegraded() {
        return degraded;
    }

    public List<String> degradedRuleNames() {
        return degraded.stream().map(Enum::name).toList();
    }
}
