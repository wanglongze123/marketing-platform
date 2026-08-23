package com.mp.api.fission.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 好友过滤结果（FR-F04）。
 *
 * <p>四部分缺一不可（PRD FR-F04 的「输出」）：通过集合、<b>按原因归类的</b>拒绝集合、生效配置 版本、本次降级规则清单。
 *
 * <p><b>拒绝集合必须带原因</b>：只回「这 30 人不能分享」时，运营看不出是人群没匹配上（该调 人群包）还是账号被封（正常），而这两件事的处置完全不同。AC-09
 * 要求「可看好友过滤候选数 / 通过数 / 拒绝原因分布」。
 *
 * <p><b>降级清单与拒绝集合是两回事</b>：后者说「谁不能被邀请」，前者说「这个结论是在什么条件下 算出来的」。一次大面积降级与一次正常过滤，若不记降级清单，在调用方看来完全一样。
 */
public class FriendFilterResp implements Serializable {

    /** 最终可分享的好友 */
    private List<String> passed;

    /** 被拒绝的好友 → 原因。同一人只记第一条命中的规则 —— 过滤器按序短路 */
    private Map<String, String> rejected;

    /** 本次生效的活动配置版本（BR-C-05） */
    private int configVersion;

    /**
     * 本次发生降级的规则名。
     *
     * <p>fail-open 与 fail-close 都记：两者的区别是「降级后放行还是阻断」，而不是「要不要记」。
     */
    private List<String> degradedRules;

    /** 候选总数，用于算通过率。{@code passed.size() + rejected.size()} 恒等于它 */
    private int candidateCount;

    public List<String> getPassed() {
        return passed;
    }

    public void setPassed(List<String> passed) {
        this.passed = passed;
    }

    public Map<String, String> getRejected() {
        return rejected;
    }

    public void setRejected(Map<String, String> rejected) {
        this.rejected = rejected;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public List<String> getDegradedRules() {
        return degradedRules;
    }

    public void setDegradedRules(List<String> degradedRules) {
        this.degradedRules = degradedRules;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }
}
