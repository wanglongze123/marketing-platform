package com.mp.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 签名 SDK 的单元测试。
 *
 * <p>验的是<b>篡改必被发现</b>，而不是「正常流程能跑通」。后者由 {@code TokenAndPricingIT} 覆盖 —— 一个只测正常路径的签名实现，把 {@code
 * verify} 写成 {@code return deserialize(body)} 照样全绿。
 */
class ConsultTokenSignerTest {

    private static final String SECRET = "unit-test-secret";
    private static final long TTL = 900;

    /** 固定时钟：过期判定要可复现，不受执行耗时影响。 */
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ConsultTokenSigner signer = signerAt(NOW);

    private static ConsultTokenSigner signerAt(Instant instant) {
        return new ConsultTokenSigner(SECRET, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void signedTokenRoundTripsWithEveryFieldPreserved() {
        String token = signer.sign("U1", "ACT1", "SKU1", 9900L, 2, 3, TTL);

        ConsultTokenPayload p = signer.verify(token);
        assertThat(p.userId()).isEqualTo("U1");
        assertThat(p.activityId()).isEqualTo("ACT1");
        assertThat(p.skuId()).isEqualTo("SKU1");
        assertThat(p.dealPrice()).isEqualTo(9900L);
        // 两个版本号刻意取不同值：同值时把它们的顺序写反，没有任何用例会红
        assertThat(p.packageVersion()).isEqualTo(2);
        assertThat(p.configVersion()).isEqualTo(3);
        assertThat(p.expireAtEpochMilli()).isEqualTo(NOW.plusSeconds(TTL).toEpochMilli());
    }

    /**
     * 改价必被发现 —— 签名存在的全部理由。
     *
     * <p>不改签名只改载荷：这是最朴素的攻击，把 19900 改成 1，签名对不上即被拒。
     */
    @Test
    void tamperedPriceIsRejected() {
        String token = signer.sign("U1", "ACT1", "SKU1", 19900L, 1, 1, TTL);
        String forged = replaceInBody(token, "19900", "1");

        // 前置：确实改动了，否则下面的断言在「什么都没改」时也成立
        assertThat(forged).isNotEqualTo(token);

        assertThatThrownBy(() -> signer.verify(forged))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    /**
     * 延长有效期同样被发现。
     *
     * <p>过期时刻在签名覆盖范围内 —— 若把它放到签名之外（如另作一个查询参数），持有者可以自行 续期，凭证的时效就形同虚设。
     */
    @Test
    void tamperedExpiryIsRejected() {
        String token = signer.sign("U1", "ACT1", "SKU1", 9900L, 1, 1, TTL);
        long real = NOW.plusSeconds(TTL).toEpochMilli();
        String forged =
                replaceInBody(token, String.valueOf(real), String.valueOf(real + 86400_000L));

        assertThat(forged).isNotEqualTo(token);
        assertThatThrownBy(() -> signer.verify(forged)).isInstanceOf(BizException.class);
    }

    /**
     * 改权益包版本同样被发现。
     *
     * <p>该字段决定权益包的内容，而换版时价格可以一分不动 —— 只签价格挡不住「按月卡+券咨询、 按只剩券的新版履约」。它必须在签名覆盖范围内。
     */
    @Test
    void tamperedPackageVersionIsRejected() {
        String token = signer.sign("U1", "ACT1", "SKU1", 9900L, 7, 1, TTL);
        String forged = replaceInBody(token, "7", "8");

        assertThat(forged).isNotEqualTo(token);
        assertThatThrownBy(() -> signer.verify(forged)).isInstanceOf(BizException.class);
    }

    /** 换密钥重签的凭证不被接受 —— 否则任何人都能自签自用。 */
    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String foreign =
                new ConsultTokenSigner("another-secret", Clock.fixed(NOW, ZoneOffset.UTC))
                        .sign("U1", "ACT1", "SKU1", 9900L, 1, 1, TTL);

        assertThatThrownBy(() -> signer.verify(foreign)).isInstanceOf(BizException.class);
    }

    /** 过期即拒。时钟往后拨一秒越过有效期，不靠等待。 */
    @Test
    void expiredTokenIsRejected() {
        String token = signer.sign("U1", "ACT1", "SKU1", 9900L, 1, 1, TTL);

        ConsultTokenSigner later = signerAt(NOW.plusSeconds(TTL).plusSeconds(1));
        assertThatThrownBy(() -> later.verify(token))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        // 边界：恰好在过期时刻仍然有效，判据是 now > expireAt 而非 >=
        assertThat(signerAt(NOW.plusSeconds(TTL)).verify(token)).isNotNull();
    }

    /** 负有效期签出一张「出生即过期」的凭证，供集成测试用。此处确认它确实被拒。 */
    @Test
    void negativeTtlProducesAnAlreadyExpiredToken() {
        String token = signer.sign("U1", "ACT1", "SKU1", 9900L, 1, 1, -1);

        assertThatThrownBy(() -> signer.verify(token)).isInstanceOf(BizException.class);
    }

    /**
     * 字段边界不可被挪动。
     *
     * <p>{@code userId="U"} + {@code activityId="1_ACT"} 与 {@code userId="U1_ACT"} + {@code
     * activityId=""} 若用普通字符拼接会得到同一个串、同一个签名，攻击者据此可以把一个字段的 内容挪进另一个字段。分隔符取业务值中不可能出现的控制字符，且签发时拒绝含分隔符的值。
     */
    @Test
    void fieldBoundariesCannotBeShifted() {
        String a = signer.sign("U", "1_ACT", "SKU1", 9900L, 1, 1, TTL);
        String b = signer.sign("U1_ACT", "", "SKU1", 9900L, 1, 1, TTL);
        assertThat(a).isNotEqualTo(b);

        // 值里混入分隔符时签发即拒，而不是等到验签才发现
        assertThatThrownBy(
                        () ->
                                signer.sign(
                                        "U" + (char) 0x1F + "X", "ACT1", "SKU1", 9900L, 1, 1, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 格式垃圾一律 4003，不抛别的异常 —— 网关只把 BizException 映射成业务码。 */
    @Test
    void malformedTokensAllMapToInvalidTokenCode() {
        for (String bad : new String[] {null, "", "   ", "no-dot", ".onlysig", "body.", "!!.@@"}) {
            assertThatThrownBy(() -> signer.verify(bad))
                    .as("输入 [%s] 应判 4003", bad)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }
    }

    /** 空密钥直接拒绝构造：跑起来再发现签名恒定，比启动失败危险得多。 */
    @Test
    void blankSecretIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new ConsultTokenSigner("", Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsultTokenSigner(null, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 有效期由签发侧算定：同一密钥、不同 TTL 签出的过期时刻确实不同。 */
    @Test
    void expiryIsComputedAtSigningTime() {
        long shortLived =
                signer.verify(signer.sign("U1", "A", "S", 1L, 1, 1, 60)).expireAtEpochMilli();
        long longLived =
                signer.verify(signer.sign("U1", "A", "S", 1L, 1, 1, 600)).expireAtEpochMilli();

        assertThat(Duration.ofMillis(longLived - shortLived)).isEqualTo(Duration.ofSeconds(540));
    }

    /** 改载荷但保持长度不变，签名重算后仍需匹配 —— 辅助方法自身必须真的改到载荷里去。 */
    private static String replaceInBody(String token, String from, String to) {
        int dot = token.indexOf('.');
        String body =
                new String(
                        Base64.getUrlDecoder().decode(token.substring(0, dot)),
                        StandardCharsets.UTF_8);
        assertThat(body).as("载荷里应含待替换的 %s", from).contains(from);

        String forgedBody = body.replace(from, to);
        return Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(forgedBody.getBytes(StandardCharsets.UTF_8))
                + token.substring(dot);
    }
}
