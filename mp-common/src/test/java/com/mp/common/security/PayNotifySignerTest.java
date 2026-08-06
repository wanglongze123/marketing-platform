package com.mp.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 支付通知签名的单元测试。
 *
 * <p>验的是<b>篡改必被发现</b>。一个只测正常路径的实现，把 {@code verify} 写成 {@code return true} 照样全绿。
 */
class PayNotifySignerTest {

    private static final String SECRET = "unit-pay-secret";

    private final PayNotifySigner signer = new PayNotifySigner(SECRET);

    private static Map<String, String> notifyFields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("outTradeNo", "BZ001");
        f.put("tradeNo", "PAY_BZ001");
        f.put("notifySeq", "NS1");
        f.put("payStatus", "SUCCESS");
        f.put("payAmount", "9900");
        f.put("currency", "CNY");
        f.put("merchantId", "MCH_DEMO");
        return f;
    }

    @Test
    void signedNotifyVerifies() {
        Map<String, String> fields = notifyFields();
        assertThat(signer.verify(fields, signer.sign(fields))).isTrue();
    }

    /**
     * <b>改金额必被发现</b> —— 验签存在的首要理由。
     *
     * <p>金额校验（{@code 1731}）挡的是「金额与本单应付不一致」，挡不住「照着真实订单金额伪造 一条通知」；验签挡的才是后者。
     */
    @Test
    void tamperedAmountIsRejected() {
        Map<String, String> fields = notifyFields();
        String sign = signer.sign(fields);

        fields.put("payAmount", "1");
        assertThat(signer.verify(fields, sign)).isFalse();
    }

    /**
     * <b>改订单号必被发现。</b>
     *
     * <p>这条是「签名必须覆盖全部字段而不只是金额」的判据：只签金额的话，攻击者可以拿一条真实 通知改掉 {@code outTradeNo}，把 A 单的付款算到 B 单头上 ——
     * 金额没变，签名照样对。
     */
    @Test
    void tamperedOrderNoIsRejected() {
        Map<String, String> fields = notifyFields();
        String sign = signer.sign(fields);

        fields.put("outTradeNo", "BZ_SOMEONE_ELSE");
        assertThat(signer.verify(fields, sign)).isFalse();
    }

    /** 改支付结果必被发现：把 FAILED 改成 SUCCESS 是最直接的伪造收款。 */
    @Test
    void tamperedPayStatusIsRejected() {
        Map<String, String> fields = notifyFields();
        fields.put("payStatus", "FAILED");
        String sign = signer.sign(fields);

        fields.put("payStatus", "SUCCESS");
        assertThat(signer.verify(fields, sign)).isFalse();
    }

    /** 换密钥签的通知不被接受 —— 否则任何人都能自签自用。 */
    @Test
    void notifySignedWithAnotherKeyIsRejected() {
        Map<String, String> fields = notifyFields();
        String foreign = new PayNotifySigner("another-secret").sign(fields);

        assertThat(signer.verify(fields, foreign)).isFalse();
    }

    /** 无签名、空签名一律拒绝，不抛异常 —— 调用方据返回值判断即可。 */
    @Test
    void missingSignatureIsRejected() {
        Map<String, String> fields = notifyFields();
        assertThat(signer.verify(fields, null)).isFalse();
        assertThat(signer.verify(fields, "")).isFalse();
        assertThat(signer.verify(fields, "   ")).isFalse();
        assertThat(signer.verify(fields, "not-a-real-signature")).isFalse();
    }

    /**
     * 字段顺序不影响签名：按名称排序后拼接。
     *
     * <p>签发侧与验签侧只要字段集合一致就能对上，不必约定传参顺序 —— 否则加一个字段就要两边 同步改拼接代码，而漏改的表现是「验签一直失败」。
     */
    @Test
    void fieldOrderDoesNotAffectTheSignature() {
        Map<String, String> ordered = notifyFields();
        Map<String, String> shuffled = new LinkedHashMap<>();
        shuffled.put("merchantId", "MCH_DEMO");
        shuffled.put("payAmount", "9900");
        shuffled.put("outTradeNo", "BZ001");
        shuffled.put("currency", "CNY");
        shuffled.put("notifySeq", "NS1");
        shuffled.put("tradeNo", "PAY_BZ001");
        shuffled.put("payStatus", "SUCCESS");

        assertThat(signer.sign(shuffled)).isEqualTo(signer.sign(ordered));
    }

    /**
     * <b>少一个字段就是另一个签名。</b>
     *
     * <p>防的是「签发侧漏签某字段而没人发现」：若缺字段与该字段为空算同一个签名，漏签就会静默 通过，那个字段从此不受保护。
     */
    @Test
    void droppingAFieldChangesTheSignature() {
        Map<String, String> full = notifyFields();
        Map<String, String> missing = notifyFields();
        missing.remove("merchantId");

        assertThat(signer.sign(missing)).isNotEqualTo(signer.sign(full));
    }

    /** 空密钥直接拒绝构造：跑起来再发现签名恒定，比启动失败危险得多。 */
    @Test
    void blankSecretIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new PayNotifySigner(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayNotifySigner(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
