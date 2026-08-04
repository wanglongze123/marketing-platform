package com.mp.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 幂等键必须<b>确定性可重算</b>：同一操作重试必须得到同一个值。
 *
 * <p>这是 L3 唯一索引成立的前提 —— 若键不可重算，唯一索引只挡得住网络重传， 挡不住业务重入（同一动作重试两次得到两个键，两条都能插入）。
 */
class IdempotentKeysTest {

    @Test
    void grantOpNoIsDeterministic() {
        String a = IdempotentKeys.grantOpNo("BZ001", "PROVIDER_A");
        String b = IdempotentKeys.grantOpNo("BZ001", "PROVIDER_A");
        assertThat(a).isEqualTo(b).isEqualTo("BZ001_G_PROVIDER_A");
    }

    /** 一次调用 = 一个供应方：不同供应方必须派生不同的键，否则两组发放会互相顶替。 */
    @Test
    void grantOpNoDiffersPerProvider() {
        assertThat(IdempotentKeys.grantOpNo("BZ001", "PROVIDER_A"))
                .isNotEqualTo(IdempotentKeys.grantOpNo("BZ001", "PROVIDER_B"));
    }

    @Test
    void payCallbackKeyIsDeterministic() {
        String a = IdempotentKeys.payCallback("T001", "SEQ1");
        String b = IdempotentKeys.payCallback("T001", "SEQ1");
        assertThat(a).isEqualTo(b).isEqualTo("T001_SEQ1");
    }

    /** 分隔符不可省略：若拼成 tradeNo + notifySeq，("T00","12") 与 ("T001","2") 会撞成同一个键。 */
    @Test
    void payCallbackKeySeparatorPreventsCollision() {
        assertThat(IdempotentKeys.payCallback("T00", "12"))
                .isNotEqualTo(IdempotentKeys.payCallback("T001", "2"));
    }

    /** payStatus 不参与键：入了键，乱序的 SUCCESS 与 CLOSED 会成为两个键，两条都能插入。 */
    @Test
    void payCallbackKeyExcludesStatus() {
        assertThat(IdempotentKeys.payCallback("T001", "SEQ1")).doesNotContain("SUCCESS", "CLOSED");
    }
}
