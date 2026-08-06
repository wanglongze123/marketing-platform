package com.mp.benefit.task;

import com.mp.common.enums.RetStatus;
import java.time.Duration;
import java.util.List;

/**
 * 退避序列。<b>{@code PROCESSING} 与 {@code UNKNOWN} 用不同的序列，这不是调优</b>。
 *
 * <p>{@code PROCESSING} 是下游已受理、正在处理，结果迟早会来，频繁查单只是徒增负载，故长退避； {@code UNKNOWN}
 * 是连请求是否到达都不知道，本单可能卡在中间态占着库存与额度， 必须尽快查证，故短退避（技术方案 §6.6）。两者用同一序列，等于把「已受理」和「不知道」当成 同一回事 ——
 * 而这正是四分类要拆开的东西。
 *
 * <p><b>基数可配</b>：生产长退避跑满需 12.5 分钟，而集成测试全套不足 8 秒。测试注入毫秒级基数，
 * 断言的是两条序列的<b>倍率关系与彼此不相等</b>，不是绝对值（《分阶段方案》§5.7）。
 */
public final class BackoffPolicy {

    /** 短退避 1s → 5s → 30s，之后维持 30s */
    private static final List<Duration> SHORT =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));

    /** 长退避 30s → 2m → 10m，之后维持 10m */
    private static final List<Duration> LONG =
            List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));

    /**
     * 缩放系数，1.0 为生产取值。
     *
     * <p>测试注入极小值把整条序列压进毫秒级 —— 不改序列本身，倍率关系保持不变，测试断言的正是 这个关系。若测试改用另一套序列常量，验的就不是生产跑的那套逻辑了。
     */
    private final double scale;

    public BackoffPolicy(double scale) {
        if (scale <= 0) {
            throw new IllegalArgumentException("退避缩放系数须为正数，实际 " + scale);
        }
        this.scale = scale;
    }

    /**
     * 按下游结果选序列，返回本次重试应等待的微秒数。
     *
     * <p>{@code FAIL} 不在此处出现：失败是终态，走失败分支而非重试（否则等于替下游反复确认一件 它已经明确回答过的事）。{@code SUCCESS} 同理不需要退避。
     */
    public long nextBackoffMicros(RetStatus downstream, int retryCount) {
        List<Duration> sequence = downstream == RetStatus.PROCESSING ? LONG : SHORT;
        Duration base = sequence.get(Math.min(Math.max(retryCount, 0), sequence.size() - 1));
        return Math.max(1L, (long) (base.toNanos() / 1000.0 * scale));
    }

    /** 生产取值下本次重试的等待时长，供日志与文档核对用。 */
    public Duration nextBackoff(RetStatus downstream, int retryCount) {
        return Duration.ofNanos(nextBackoffMicros(downstream, retryCount) * 1000);
    }
}
