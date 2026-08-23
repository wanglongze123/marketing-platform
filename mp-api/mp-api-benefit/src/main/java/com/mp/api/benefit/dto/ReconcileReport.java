package com.mp.api.benefit.dto;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一轮对账的结果（技术方案 §6.8）。V3 PR-10 引入。
 *
 * <p><b>差异数按项分列，不汇总成一个总数</b>：十五项的处置动作完全不同 —— 前一批补建任务自愈，后一批 只告警等人工。汇总成一个数会让「有 3
 * 条差异」无法回答「要不要叫人」。这也是指标 {@code reconcile_diff_total{type}} 带 {@code type} 标签的理由。
 *
 * <p><b>{@code repaired} 与 {@code diffs} 分列</b>：检出与修复是两件事。一条差异被检出但没能修复（如金额 不一致，按规定禁止自动改单），在这里表现为
 * {@code diffs} 有而 {@code repaired} 无 —— 合成一个数则「已 自愈」与「等人工」分不开，而后者才需要告警。
 */
public class ReconcileReport implements Serializable {

    /** 各项检出的差异条数，键为 {@link ReconcileItem#name()} */
    private final Map<String, Integer> diffs = new LinkedHashMap<>();

    /** 各项实际修复（补建任务）的条数 */
    private final Map<String, Integer> repaired = new LinkedHashMap<>();

    public void addDiff(ReconcileItem item, int count) {
        if (count > 0) {
            diffs.merge(item.name(), count, Integer::sum);
        }
    }

    public void addRepaired(ReconcileItem item, int count) {
        if (count > 0) {
            repaired.merge(item.name(), count, Integer::sum);
        }
    }

    public Map<String, Integer> getDiffs() {
        return diffs;
    }

    public Map<String, Integer> getRepaired() {
        return repaired;
    }

    public int diffOf(ReconcileItem item) {
        return diffs.getOrDefault(item.name(), 0);
    }

    public int repairedOf(ReconcileItem item) {
        return repaired.getOrDefault(item.name(), 0);
    }

    /** 差异总数，供日志与「本轮是否干净」的快速判断。 */
    public int totalDiff() {
        return diffs.values().stream().mapToInt(Integer::intValue).sum();
    }
}
