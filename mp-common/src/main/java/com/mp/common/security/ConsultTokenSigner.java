package com.mp.common.security;

import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 咨询凭证的签发与验签，L1 防线（技术方案 §6.2）。
 *
 * <p>放在 {@code mp-common} 而非 {@code mp-activity}：技术方案 §4.2 明确「Token 解密不放在 activity」——
 * 解密结果含裂变组等玩法私有概念，放公共能力层就成了 <b>公共层依赖玩法层</b>，方向反了。故做成公共 SDK，各玩法自行调用。
 *
 * <p><b>HMAC 而非加密</b>：载荷里没有秘密 —— 用户自己的 ID、他正在看的商品、他看到的价格，都是他
 * 已经知道的。要防的是<b>篡改</b>，不是窥视。用加密解决完整性问题会带来一种错觉，以为「解得开 就是真的」，而实际上密文被截断或重放同样解得开。
 *
 * <p><b>验签之后仍须逐字段比对</b>。签名只证明「这张凭证是平台签发的、没被改过」，不证明「它是发给 当前这个请求的」。用户 A 的合法凭证在用户 B 的请求里同样验签通过 ——
 * 拿它下单就是越权。比对由 调用方做（{@code createTrade}），本类只负责让载荷可信。
 */
public final class ConsultTokenSigner {

    /** 载荷字段分隔符。取 ASCII 单元分隔符：出现在业务值里的概率远低于 {@code |} 或 {@code ,} */
    private static final String SEP = String.valueOf((char) 0x1F);

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;

    public ConsultTokenSigner(String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("签名密钥不得为空");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /**
     * 签发。
     *
     * @param ttlSeconds 有效期。<b>过期时刻在签发时算定并进签名</b>，不由消费侧传入 —— 否则持有者可以 自行延长有效期，凭证就失去了时效意义
     */
    public String sign(
            String userId,
            String activityId,
            String skuId,
            long dealPrice,
            int configVersion,
            long ttlSeconds) {
        ConsultTokenPayload payload =
                new ConsultTokenPayload(
                        userId,
                        activityId,
                        skuId,
                        dealPrice,
                        configVersion,
                        clock.millis() + ttlSeconds * 1000L);
        String body = serialize(payload);
        return ENC.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + mac(body);
    }

    /**
     * 验签 + 验时效，通过则返回载荷。
     *
     * <p>失败一律抛 {@code 4003}，<b>不区分「签名不对」与「已过期」</b>：两者对调用方是同一处置 （重新咨询），而区分开来会告诉攻击者「签名算法猜对了，只是时间不对」。
     *
     * <p>不做任何字段级判断 —— 那是调用方的事，见类注释。
     */
    public ConsultTokenPayload verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证缺失");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证格式非法");
        }

        String body;
        try {
            body = new String(DEC.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证格式非法");
        }

        // 定长比较，不用 String.equals —— 后者比到第一个不等的字节就返回，
        // 逐字节的耗时差可被用来把签名一位一位试出来
        byte[] expected = mac(body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = token.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证签名非法");
        }

        ConsultTokenPayload payload = deserialize(body);
        if (clock.millis() > payload.expireAtEpochMilli()) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证已过期");
        }
        return payload;
    }

    /**
     * 载荷序列化：定长字段顺序拼接。
     *
     * <p><b>值内不得含分隔符</b>：若 {@code userId} 是 {@code "ab"}、{@code activityId} 是 {@code "c"}，与 {@code
     * userId="a"}、{@code activityId="bc"} 会拼出同一个串、算出同一个签名 —— 攻击者可以 借此把字段边界挪走。签发时即拒绝，而不是等验签时发现。
     */
    private static String serialize(ConsultTokenPayload p) {
        reject(p.userId(), "userId");
        reject(p.activityId(), "activityId");
        reject(p.skuId(), "skuId");
        return String.join(
                SEP,
                p.userId(),
                p.activityId(),
                p.skuId(),
                String.valueOf(p.dealPrice()),
                String.valueOf(p.configVersion()),
                String.valueOf(p.expireAtEpochMilli()));
    }

    private static void reject(String value, String field) {
        if (value == null || value.contains(SEP)) {
            throw new IllegalArgumentException(field + " 为空或含分隔符，无法签发凭证");
        }
    }

    private static ConsultTokenPayload deserialize(String body) {
        // -1：末尾空字段也要保留，否则被篡改成尾部截断的载荷会解析成字段更少的合法对象
        String[] f = body.split(SEP, -1);
        if (f.length != 6) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证载荷非法");
        }
        try {
            return new ConsultTokenPayload(
                    f[0],
                    f[1],
                    f[2],
                    Long.parseLong(f[3]),
                    Integer.parseInt(f[4]),
                    Long.parseLong(f[5]));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.INVALID_TOKEN, "咨询凭证载荷非法");
        }
    }

    private String mac(String body) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return ENC.encodeToString(hmac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 密钥或算法不可用是配置问题，不是业务失败。异常里不带 body，它含用户标识
            throw new IllegalStateException("凭证签名计算失败", e);
        }
    }
}
