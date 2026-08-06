package com.mp.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 支付结果通知的签名与验签（PRD FR-B03 ①、BR-B-12）。
 *
 * <p><b>与 {@link ConsultTokenSigner} 是两条不同的信任边界，不可复用同一个类，也不共用密钥：</b>
 *
 * <table>
 *   <tr><th></th><th>咨询凭证</th><th>支付通知</th></tr>
 *   <tr><td>签名方</td><td>平台自己</td><td>支付平台</td></tr>
 *   <tr><td>挡什么</td><td>用户伪造自己的下单请求</td><td>伪造的收款成功通知</td></tr>
 *   <tr><td>误判代价</td><td>价格不对的订单，尚未付款</td><td>认为钱已收到并触发履约，直接资损</td></tr>
 * </table>
 *
 * <p>密钥泄露的后果也不同：凭证密钥泄露只影响下单校验，支付密钥泄露可伪造收款通知。共用一把 会让「轮换支付方密钥」被迫连带作废所有在途凭证。
 *
 * <p><b>签名覆盖全部业务字段，不只是金额。</b> 只签金额则攻击者可以拿一条真实通知改掉 {@code outTradeNo}，把 A 单的付款算到 B 单头上 ——
 * 金额没变，签名照样对。
 *
 * <p><b>字段按名称排序后拼接</b>，不依赖调用方的传参顺序：签发侧与验签侧只要字段集合一致就能对上， 加字段时两边不必同步改拼接代码。真实支付平台（微信、支付宝）用的也是这个形状。
 */
public final class PayNotifySigner {

    private final byte[] key;

    public PayNotifySigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("支付通知签名密钥不得为空");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 签发。供 mock 支付方使用；真实环境里这一步在支付平台那侧。 */
    public String sign(Map<String, String> fields) {
        return mac(canonicalize(fields));
    }

    /**
     * 验签。
     *
     * <p>定长比较，不用 {@code String.equals} —— 后者比到第一个不等的字节就返回，逐字节的耗时差 可被用来把签名一位一位试出来。
     */
    public boolean verify(Map<String, String> fields, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = mac(canonicalize(fields)).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 规范化：按字段名排序，{@code k=v} 以 {@code &} 连接，空值字段跳过。
     *
     * <p>跳过空值而非签成 {@code k=}：真实支付平台的可选字段缺失时不参与签名，签发侧与验签侧对 「缺失」的处理必须一致，否则一个可选字段没传就验不过。
     */
    private static String canonicalize(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private String mac(String payload) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 密钥或算法不可用是配置问题。异常里不带 payload，它含订单号与金额
            throw new IllegalStateException("支付通知签名计算失败", e);
        }
    }
}
