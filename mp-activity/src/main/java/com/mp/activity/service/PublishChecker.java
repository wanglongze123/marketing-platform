package com.mp.activity.service;

import com.mp.activity.entity.MarketingActivity;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布前六项校验（BR-C-04）。
 *
 * <p><b>六项逐条返回而非首个失败即返回</b>：运营改配置时希望一次看到全部问题，逐个试错要发布 六次。故收集全部不通过项一并回报。
 *
 * <p><b>校验点在发布而非创建</b>：草稿允许不完整（分多次填配置是常态），发布才是「这份配置要开始 对用户生效」的时刻。
 */
public final class PublishChecker {

    private PublishChecker() {}

    /** 活动有效期上限：一年。超过多半是填错了年份 */
    private static final long MAX_PERIOD_DAYS = 366;

    /**
     * 跑六项校验。
     *
     * @return 不通过项的说明；空列表表示全部通过
     */
    public static List<String> check(
            MarketingActivity activity, String playConfig, String rewardConfig) {
        List<String> failures = new ArrayList<>();

        checkPeriod(activity, failures);
        checkRewardConfig(rewardConfig, failures);
        checkPlayConfig(activity, playConfig, failures);
        checkScene(activity, failures);
        checkRules(activity, failures);
        checkAlreadyPublished(activity, failures);

        return failures;
    }

    /** ① 有效期合法：{@code endTime > startTime} 且跨度不超上限。 */
    private static void checkPeriod(MarketingActivity activity, List<String> failures) {
        if (activity.getStartTime() == null || activity.getEndTime() == null) {
            failures.add("有效期未配置");
            return;
        }
        if (!activity.getEndTime().isAfter(activity.getStartTime())) {
            failures.add("结束时间须晚于开始时间");
            return;
        }
        long days =
                java.time.Duration.between(activity.getStartTime(), activity.getEndTime()).toDays();
        if (days > MAX_PERIOD_DAYS) {
            failures.add("活动跨度 " + days + " 天，超出上限 " + MAX_PERIOD_DAYS + " 天");
        }
    }

    /** ② 至少一项可用奖励或商品。 */
    private static void checkRewardConfig(String rewardConfig, List<String> failures) {
        if (isBlankJson(rewardConfig)) {
            failures.add("奖励配置为空，至少需要一项可用奖励或商品");
        }
    }

    /** ③ 玩法私有配置通过各自校验。 */
    private static void checkPlayConfig(
            MarketingActivity activity, String playConfig, List<String> failures) {
        if (isBlankJson(playConfig)) {
            failures.add("玩法配置为空");
            return;
        }
        String playType = activity.getPlayType();
        if (!"FISSION".equals(playType) && !"BENEFIT_SELL".equals(playType)) {
            failures.add("玩法类型非法: " + playType);
        }
    }

    /** ④ 场景路由已配置。 */
    private static void checkScene(MarketingActivity activity, List<String> failures) {
        if (isBlank(activity.getScene())) {
            failures.add("场景路由未配置");
        }
    }

    /**
     * ⑤ 人群/频控/风控规则的<b>结构</b>可用。
     *
     * <p><b>不校验「是不是合法 JSON」</b>：这四列在库里都是 {@code JSON} 类型，非法 JSON 在 {@code createActivity} 落库时就被
     * MySQL 拒了，根本活不到发布。把语法校验放在这里，等于写了 一条永远不会触发的分支 —— 而它看起来还挺像回事，掩盖了「语法其实没人管」这件事。语法由 建单路径把关（{@code
     * 4001}），发布这一关校验的是<b>范围配了却是空集</b>这类结构问题。
     */
    private static void checkRules(MarketingActivity activity, List<String> failures) {
        if (isEmptyArray(activity.getCityScope())) {
            failures.add("城市范围配成了空数组：应留空表示不限，空数组等于谁都不匹配");
        }
        if (isEmptyArray(activity.getChannelScope())) {
            failures.add("渠道范围配成了空数组：应留空表示不限，空数组等于谁都不匹配");
        }
    }

    /**
     * 空数组是配置错误，与「留空」语义相反。
     *
     * <p>留空表示不限（放行全部），空数组表示「范围里一个都没有」（拒绝全部）。运营多半想要前者， 而写出后者不会有任何报错 —— 活动上线后无人能参与，且看不出原因。
     */
    private static boolean isEmptyArray(String json) {
        return json != null && "[]".equals(json.trim());
    }

    /** ⑥ 当前状态允许发布：只有 {@code DRAFT} 可发布。 */
    private static void checkAlreadyPublished(MarketingActivity activity, List<String> failures) {
        if (!"DRAFT".equals(activity.getStatus())) {
            failures.add("仅 DRAFT 状态可发布，当前为 " + activity.getStatus());
        }
    }

    private static boolean isBlankJson(String json) {
        if (isBlank(json)) {
            return true;
        }
        String t = json.trim();
        return "{}".equals(t) || "[]".equals(t);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
