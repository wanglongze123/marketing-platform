package com.mp.benefit.reconcile;

import com.mp.api.benefit.dto.ReconcileItem;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 资损哨兵指标（技术方案 §8.2）。V3 PR-10。
 *
 * <p><b>三个「应恒为 0」的指标一律由对账任务产出，不是请求路径埋点</b>。这一点决定了指标是否可信：
 *
 * <ul>
 *   <li>请求路径上埋「我有没有重复发奖」，埋的是<b>代码自己的判断</b> —— 而如果那段判断本身有缺陷， 它同样会认为自己没重复。指标与缺陷同源，恒为 0 说明不了任何事
 *   <li>对账直接问库里的数（{@code SELECT count(*) ... WHERE locked+consumed > total}），它<b>不依赖
 *       任何业务代码的判断</b>。这才是「哨兵」的意思
 * </ul>
 *
 * <p><b>{@code reconcileDiff} 按项分列，不合成总数</b>：十五项的处置动作不同，合成一个数就无法回答 「要不要叫人」—— 而这正是告警要回答的唯一问题。对应
 * Prometheus 的 {@code reconcile_diff_total{type}}。
 *
 * <p><b>进程内、可清零，与 {@code ContentionMetrics} 同属观测设施</b>：V3 单进程无 Prometheus，接入时本类 换成 Micrometer
 * 计数器，形状不变 —— 它现在的方法签名就是按 Counter 的形状写的（只增不减、按标签分列）。
 */
@Component
public class ReconcileMetrics {

    /** 对账检出的重复发奖数。目标恒为 0 */
    private final LongAdder rewardDuplicate = new LongAdder();

    /** 对账检出的超卖行数。目标恒为 0 */
    private final LongAdder stockOversold = new LongAdder();

    /** 对账检出的重复退款数。目标恒为 0 */
    private final LongAdder refundDuplicate = new LongAdder();

    /** 按对账项分列的差异数 */
    private final Map<String, LongAdder> reconcileDiff = new ConcurrentHashMap<>();

    /** 人工处置次数，按动作分列 */
    private final Map<String, LongAdder> manualRepair = new ConcurrentHashMap<>();

    public void onRewardDuplicate(int n) {
        rewardDuplicate.add(n);
    }

    public void onStockOversold(int n) {
        stockOversold.add(n);
    }

    public void onRefundDuplicate(int n) {
        refundDuplicate.add(n);
    }

    public void onDiff(ReconcileItem item, int n) {
        if (n > 0) {
            reconcileDiff.computeIfAbsent(item.name(), k -> new LongAdder()).add(n);
        }
    }

    public void onManualRepair(String action) {
        manualRepair.computeIfAbsent(action, k -> new LongAdder()).increment();
    }

    public long rewardDuplicateCount() {
        return rewardDuplicate.sum();
    }

    public long stockOversoldCount() {
        return stockOversold.sum();
    }

    public long refundDuplicateCount() {
        return refundDuplicate.sum();
    }

    public long diffCount(ReconcileItem item) {
        LongAdder a = reconcileDiff.get(item.name());
        return a == null ? 0 : a.sum();
    }

    public long manualRepairCount(String action) {
        LongAdder a = manualRepair.get(action);
        return a == null ? 0 : a.sum();
    }

    /** 三个哨兵指标的总和。压测与演示时看这一个数即可 —— 非 0 即须查。 */
    public long sentinelTotal() {
        return rewardDuplicate.sum() + stockOversold.sum() + refundDuplicate.sum();
    }

    public void reset() {
        rewardDuplicate.reset();
        stockOversold.reset();
        refundDuplicate.reset();
        reconcileDiff.clear();
        manualRepair.clear();
    }
}
