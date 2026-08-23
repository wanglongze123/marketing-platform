package com.mp.benefit.reconcile;

import com.mp.api.benefit.dto.ReconcileItem;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * 把 {@link ReconcileMetrics} 的计数桥接到 Micrometer。V4 第 6 项。
 *
 * <p><b>为什么是桥接而不是直接改用 Micrometer 计数器</b>：{@code ReconcileMetrics} 有 318 个测试里的 多处断言依赖它的 {@code
 * xxxCount()} 读数与 {@code reset()}。改掉它等于同时动观测设施与它的测试， 而这两件事失败时的表征相同（数字不对），分不清是桥接错了还是业务错了。
 *
 * <p>桥接用 {@code Gauge} 而非 {@code Counter}：源头是 {@code LongAdder}，它支持 {@code reset()}（压测 分轮次时要清零），而
 * Counter 语义上只增不减，把一个会被清零的值报成 Counter 会让 Prometheus 的 {@code increase()} 在清零点算出一个巨大的负跳变。Gauge
 * 如实反映「当前这一轮的计数」。
 *
 * <p><b>三个哨兵指标的目标恒为 0</b>（技术方案 §8.2）：它们由对账产出，不依赖任何业务代码的自我判断 —— 请求路径上埋「我有没有重复发奖」，埋的是代码自己的判断，与缺陷同源，恒为
 * 0 说明不了任何事。
 */
@Component
@ConditionalOnClass(MeterRegistry.class)
public class ReconcileMetricsBinder {

    public ReconcileMetricsBinder(ReconcileMetrics metrics, MeterRegistry registry) {
        // 三个资损哨兵：Grafana「业务资损大盘」的绿色指标墙就是它们。
        //
        // 名字不带 _total 后缀：Micrometer 把那视为 counter 的命名约定，注册成 gauge 时
        // 会把它剥掉 —— 写 reward_duplicate_total，导出的是 reward_duplicate，而 PromQL
        // 按原名查会返回空。这个不一致不报错，表现为「看板一片空白但服务明明在跑」
        registry.gauge("reward_duplicate_total", metrics, ReconcileMetrics::rewardDuplicateCount);
        registry.gauge("stock_oversold_total", metrics, ReconcileMetrics::stockOversoldCount);
        registry.gauge("refund_duplicate_total", metrics, ReconcileMetrics::refundDuplicateCount);

        // 对账差异按项分列。合成一个总数就无法回答「要不要叫人」——
        // 十五项的处置动作不同，有的补建任务（幂等，自动），有的只告警（转人工）
        for (ReconcileItem item : ReconcileItem.values()) {
            registry.gauge(
                    "reconcile_diff_total",
                    io.micrometer.core.instrument.Tags.of("type", item.name()),
                    metrics,
                    m -> m.diffCount(item));
        }
    }
}
