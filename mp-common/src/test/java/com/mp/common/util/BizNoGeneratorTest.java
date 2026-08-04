package com.mp.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 业务单号必须<b>全局唯一，每次生成不同</b> —— 规则与幂等键相反。
 *
 * <p>用 UUIDv7 而非「时间戳 + 8 位随机」：后者随机空间仅 32 bit，同一秒约 7.7 万次调用 即有 50% 碰撞概率，压测场景下不是理论风险。
 */
class BizNoGeneratorTest {

    private static final int N = 100_000;

    @Test
    void bizNoIsUniqueAcrossManyCalls() {
        Set<String> seen = new HashSet<>(N * 2);
        for (int i = 0; i < N; i++) {
            assertThat(seen.add(BizNoGenerator.bizNo())).as("第 %d 次生成撞号", i).isTrue();
        }
    }

    @Test
    void fulfillmentNoIsUniqueAcrossManyCalls() {
        Set<String> seen = new HashSet<>(N * 2);
        for (int i = 0; i < N; i++) {
            assertThat(seen.add(BizNoGenerator.fulfillmentNo())).isTrue();
        }
    }

    /** 前缀用于人工排查时快速判断单号属于哪类对象。 */
    @Test
    void prefixIdentifiesObjectType() {
        assertThat(BizNoGenerator.bizNo()).startsWith("BZ");
        assertThat(BizNoGenerator.fulfillmentNo()).startsWith("FF");
        assertThat(BizNoGenerator.fissionRelationNo()).startsWith("FR");
    }

    /** UUIDv7 前 48 bit 是毫秒时间戳，故先后生成的单号字典序近似递增（降低 B+ 树页分裂）。 */
    @Test
    void bizNoIsRoughlyTimeOrdered() throws InterruptedException {
        String earlier = BizNoGenerator.bizNo();
        Thread.sleep(2);
        assertThat(BizNoGenerator.bizNo()).isGreaterThan(earlier);
    }
}
