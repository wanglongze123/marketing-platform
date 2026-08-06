package com.mp.benefit.lock;

import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * L3 冲突计数，服务于《分阶段方案》§5.7 退出标准 15。
 *
 * <p><b>为什么必须有它</b>：k6 跑通不能证明 Redisson 生效 —— DB 原子扣减单独就满足 0 超卖， 删掉锁正确性结果不变。锁的价值只能从<b>L3
 * 的负载侧</b>读出：锁生效时并发被串行化在锁上， 走到唯一索引与条件更新的冲突应当更少。
 *
 * <p><b>只看锁自身的竞争计数没有意义</b> —— 移除锁后该指标必然为 0，那只说明锁代码没运行， 而非「没有冲突」。故这里记的是 L3 侧的三类事件，两组压测各跑一轮后比对：
 *
 * <ul>
 *   <li>{@code duplicateKey} —— 建单撞 {@code uk_idempotent}（并发下同一幂等键同时插入）
 *   <li>{@code conditionalUpdateMiss} —— 主单条件更新 {@code affected_rows = 0}（乱序或重复推进）
 *   <li>{@code stockInsufficient} —— 库存预占 {@code affected_rows = 0}（余量不足）
 * </ul>
 *
 * <p><b>用 {@link LongAdder} 而非 {@code AtomicLong}</b>：500 VU 并发写同一计数器时前者的争用 显著更低，而我们只在压测结束后读一次总值。
 *
 * <p><b>进程内、无持久化、可清零</b>：它是压测观测设施，不是业务指标。真实环境的对应物是 Micrometer 计数器（技术方案 §8.2），V3 接入 Prometheus
 * 时替换，本类届时删除。
 */
@Component
public class ContentionMetrics {

    private final LongAdder duplicateKey = new LongAdder();
    private final LongAdder conditionalUpdateMiss = new LongAdder();
    private final LongAdder stockInsufficient = new LongAdder();

    /** 建单撞唯一索引。并发下同一幂等键同时插入，第二个走幂等出口 */
    public void onDuplicateKey() {
        duplicateKey.increment();
    }

    /** 主单条件更新未命中。乱序通知、重复推进都会走到这里 */
    public void onConditionalUpdateMiss() {
        conditionalUpdateMiss.increment();
    }

    /** 库存预占未命中，即余量不足 */
    public void onStockInsufficient() {
        stockInsufficient.increment();
    }

    public long duplicateKeyCount() {
        return duplicateKey.sum();
    }

    public long conditionalUpdateMissCount() {
        return conditionalUpdateMiss.sum();
    }

    public long stockInsufficientCount() {
        return stockInsufficient.sum();
    }

    /** 压测开始前清零，使两组的数字可比。 */
    public void reset() {
        duplicateKey.reset();
        conditionalUpdateMiss.reset();
        stockInsufficient.reset();
    }
}
