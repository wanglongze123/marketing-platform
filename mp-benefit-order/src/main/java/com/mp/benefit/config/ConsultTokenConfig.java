package com.mp.benefit.config;

import com.mp.common.security.ConsultTokenSigner;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 咨询凭证签名器的装配。
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
}
