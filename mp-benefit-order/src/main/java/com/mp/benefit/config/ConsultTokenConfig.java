package com.mp.benefit.config;

import com.mp.common.security.ConsultTokenSigner;
import com.mp.common.security.PayNotifySigner;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 签名器的装配：咨询凭证（PR-4）与支付通知（PR-6b）。
 *
 * <p><b>密钥只从配置读，不写死、不入库、不打日志</b>（技术方案 §6.2）。默认值仅供本地与测试 —— V3 上真实环境时改为从密钥管理服务注入，届时本类的形状不变，只换取值来源。
 *
 * <p><b>{@code Clock} 显式注入而非用 {@code System.currentTimeMillis()}</b>：过期判定要可测。 用系统时钟的话，「过期凭证被拒」这条只能靠
 * sleep 等真实时间流逝，而有效期取几秒则测试变慢、 取几毫秒则与执行耗时同量级 —— 两头都不可靠。注入 {@code Clock} 后测试直接把时钟拨过去。
 */
@Configuration
public class ConsultTokenConfig {

    @Bean
    public Clock consultTokenClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public ConsultTokenSigner consultTokenSigner(
            @Value("${mp.consult-token.secret}") String secret, Clock consultTokenClock) {
        return new ConsultTokenSigner(secret, consultTokenClock);
    }

    /**
     * 支付通知签名器。<b>密钥与咨询凭证分开</b>（V2 PR-6b）。
     *
     * <p>两者是不同的信任边界：凭证密钥平台自持、自签自验，泄露只影响下单校验；支付密钥与支付方 共享，泄露可伪造收款通知直接资损。共用一把还会让「轮换支付方密钥」被迫连带作废所有在途凭证。
     *
     * <p>同样不给代码内默认值 —— 缺配置就启动失败，比用一把仓库里人人可见的密钥跑起来安全。
     */
    @Bean
    public PayNotifySigner payNotifySigner(@Value("${mp.pay-notify.secret}") String secret) {
        return new PayNotifySigner(secret);
    }
}
