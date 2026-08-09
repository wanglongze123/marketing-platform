package com.mp.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 供应方异步通知的签名与验签（FR-B06）。V3 PR-9 引入。
 *
 * <p><b>第三条信任边界，与 {@link ConsultTokenSigner}、{@link PayNotifySigner} 各自独立、各用各的密钥</b>：
 *
 * <table>
 *   <tr><th></th><th>咨询凭证</th><th>支付通知</th><th>供应方通知</th></tr>
 *   <tr><td>签名方</td><td>平台自己</td><td>支付平台</td><td>奖励供应方</td></tr>
 *   <tr><td>挡什么</td><td>伪造下单请求</td><td>伪造收款通知</td><td>伪造发放成功通知</td></tr>
 *   <tr><td>误判代价</td><td>价格不对的订单</td><td>认为钱已收到并履约</td><td>认为奖已发出并置终态</td></tr>
 * </table>
 *
 * <p>第三列的代价值得单列：一条伪造的成功通知会让 {@code reward_grant_record} 进终态，而终态<b>不再被 查单推进</b>（条件更新限定 {@code
 * PROCESSING}）—— 于是这笔发放永远停在「已成功」，实际上供应方 那边什么都没有。它不像重复发奖那样能被对账第 11 项数出来，因为记录数是对的。
 *
 * <p><b>与 {@code PayNotifySigner} 有一处刻意的差别：空值签成 {@code k=} 而非跳过</b>。支付方那侧的规则
 * 由支付平台定（可选字段缺失时不参与签名），平台只能对齐；而供应方通知的两端都在本仓内，可以取 更严的一种 —— 跳过空值会让「字段缺失」与「字段为空」签出同一个值，攻击者可借此删字段。
 */
public final class ProviderNotifySigner {

    private final byte[] key;

    public ProviderNotifySigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("供应方通知签名密钥不得为空");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 签发。供 mock 供应方使用；真实环境里这一步在供应方那侧。 */
    public String sign(Map<String, String> fields) {
        return mac(canonicalize(fields));
    }

    /**
     * 验签。
     *
     * <p>定长比较，理由同 {@link PayNotifySigner#verify}：{@code String.equals} 比到第一个不等的字节就返回，
     * 逐字节的耗时差可被用来把签名试出来。
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
     * 规范化：按字段名排序，{@code k=v} 以 {@code &} 连接，<b>空值签成 {@code k=} 不跳过</b>。
     *
     * <p>排序而非依赖传参顺序：签发侧与验签侧只要字段集合一致就能对上，加字段时两边不必同步改 拼接代码。
     */
    private static String canonicalize(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(fields).entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
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
            // 密钥或算法不可用是配置问题。异常里不带 payload，它含业务单号
            throw new IllegalStateException("供应方通知签名计算失败", e);
        }
    }
}
