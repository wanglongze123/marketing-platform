package com.mp.gateway.config;

import com.mp.common.security.PayNotifySigner;
import com.mp.common.security.ProviderNotifySigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gateway 自己的签名器装配。V4 新增，仅在分布式形态下生效。
 *
 * <p><b>为什么需要它</b>：{@code FaultInjectionController} 的两个签名端点留在 gateway（它们不碰 进程内状态，见该类注释），但签名器的
 * {@code @Bean} 定义分别在 {@code ConsultTokenConfig}（benefit 模块）与 {@code RewardEventConfig}（reward
 * 模块）里。 单进程形态下这两个模块与 gateway 同处一个容器，拿得到；拆服务后它们的 jar 已从 gateway 的 fat jar 里排除，于是启动时报 {@code
 * PayNotifySigner that could not be found}。
 *
 * <p><b>{@code @ConditionalOnMissingBean} 不可省</b>：单进程形态下 benefit 与 reward 的配置类照常 装配这两个
 * bean，本类若无条件地再造一份，Spring 会因同类型多实例而拒绝启动 —— 那等于用一个 分布式形态的修复弄坏了单进程形态。有它则本类只在「别人没提供」时补位。
 *
 * <p><b>密钥取值必须与对应服务一致</b>：gateway 签、benefit 或 reward 验，两边用不同的密钥时 表现为「签名端点正常返回，回调一律 4731」，
 * 而两边的日志各自都看不出问题。故 dist 配置里三处 都指向同一个环境变量默认值。
 */
@Configuration
@ConditionalOnProperty(name = "mp.dubbo.remote", havingValue = "true")
public class GatewaySignerConfig {

    @Bean
    @ConditionalOnMissingBean
    public PayNotifySigner payNotifySigner(@Value("${mp.pay-notify.secret}") String secret) {
        return new PayNotifySigner(secret);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderNotifySigner providerNotifySigner(
            @Value("${mp.provider-notify.secret}") String secret) {
        return new ProviderNotifySigner(secret);
    }
}
