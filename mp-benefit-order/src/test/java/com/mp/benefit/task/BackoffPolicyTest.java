package com.mp.benefit.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.common.enums.RetStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 退避序列，对应《分阶段方案》§5.7 退出标准 1、3 断言的那两条序列。
 *
 * <p><b>为什么单独写单测而非只靠 IT</b>：IT 里退避基数被压到毫秒级，断言的是「两条序列不相等」 这个关系；生产取值下的绝对值（1s/5s/30s 与
 * 30s/2m/10m）只有在这里才能验。两者少任何一个， 「改了序列常量但没人发现」都成立。
 */
class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(1.0);

    /** UNKNOWN 走短退避 1s → 5s → 30s。 */
    @Test
    void unknownUsesShortSequence() {
        assertThat(policy.nextBackoff(RetStatus.UNKNOWN, 0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.nextBackoff(RetStatus.UNKNOWN, 1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.nextBackoff(RetStatus.UNKNOWN, 2)).isEqualTo(Duration.ofSeconds(30));
    }

    /** PROCESSING 走长退避 30s → 2m → 10m。 */
    @Test
    void processingUsesLongSequence() {
        assertThat(policy.nextBackoff(RetStatus.PROCESSING, 0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.nextBackoff(RetStatus.PROCESSING, 1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.nextBackoff(RetStatus.PROCESSING, 2)).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * 两条序列逐项不等，且长退避恒长于短退避。
     *
     * <p>这是四分类拆开的直接体现：{@code PROCESSING} 是下游已受理、结果迟早会来，频繁查单徒增负载； {@code UNKNOWN}
     * 是连请求是否到达都不知道，本单可能卡在中间态占着库存与额度，必须尽快查证。 两条序列若取值相同，等于把「已受理」和「不知道」当成同一回事。
     */
    @Test
    void theTwoSequencesNeverCoincide() {
        for (int retry = 0; retry < 5; retry++) {
            Duration shortBackoff = policy.nextBackoff(RetStatus.UNKNOWN, retry);
            Duration longBackoff = policy.nextBackoff(RetStatus.PROCESSING, retry);
            assertThat(longBackoff).as("retry=%s 时长退避应严格长于短退避", retry).isGreaterThan(shortBackoff);
        }
    }

    /** 超出序列长度后维持末位值，不越界也不重新从头开始。 */
    @Test
    void retryCountBeyondSequenceLengthHoldsAtTheLastValue() {
        assertThat(policy.nextBackoff(RetStatus.UNKNOWN, 3)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.nextBackoff(RetStatus.UNKNOWN, 99)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.nextBackoff(RetStatus.PROCESSING, 99)).isEqualTo(Duration.ofMinutes(10));
    }

    /** 负数不应导致越界 —— retry_count 由 SQL 自增，理论上不会为负，但边界不该靠调用方保证。 */
    @Test
    void negativeRetryCountFallsBackToTheFirstValue() {
        assertThat(policy.nextBackoff(RetStatus.UNKNOWN, -1)).isEqualTo(Duration.ofSeconds(1));
    }

    /**
     * 缩放只改基数，不改倍率关系。
     *
     * <p>测试注入极小系数把整条序列压进毫秒级，验的仍是生产跑的那套逻辑 —— 若测试改用另一套 序列常量，验的就不是同一个东西了。
     */
    @Test
    void scalingPreservesTheRatioBetweenSteps() {
        BackoffPolicy scaled = new BackoffPolicy(0.001);

        long first = scaled.nextBackoffMicros(RetStatus.UNKNOWN, 0);
        long second = scaled.nextBackoffMicros(RetStatus.UNKNOWN, 1);
        long third = scaled.nextBackoffMicros(RetStatus.UNKNOWN, 2);

        assertThat(second).isEqualTo(first * 5);
        assertThat(third).isEqualTo(first * 30);
        // 压到毫秒级：整条序列跑满不足 40 毫秒，集成测试等得起
        assertThat(third).isLessThan(40_000L);
    }

    /** 缩放到极小时仍返回正值 —— 返回 0 会让 next_time 不推进，任务在同一轮里被反复领取。 */
    @Test
    void extremeScalingStillYieldsAPositiveDelay() {
        assertThat(new BackoffPolicy(1e-9).nextBackoffMicros(RetStatus.UNKNOWN, 0))
                .isGreaterThan(0);
    }

    @Test
    void nonPositiveScaleIsRejected() {
        assertThatThrownBy(() -> new BackoffPolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
