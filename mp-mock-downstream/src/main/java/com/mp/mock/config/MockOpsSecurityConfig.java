package com.mp.mock.config;

import com.mp.mock.controller.OpsTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * mock 侧运维端点令牌校验的装配。V4 第 9 项。
 *
 * <p>与 gateway 侧同构，见 {@code com.mp.gateway.config.OpsSecurityConfig}。
 *
 * <p><b>类名带 Mock 前缀而非与 gateway 侧同名</b>：单进程形态下 {@code GatewayApplication} 扫 {@code com.mp}，两个同名
 * {@code @Configuration} 会撞 —— Spring 报 {@code ConflictingBeanDefinitionException}，因为默认 bean
 * 名取的是类的短名。
 *
 * <p><b>两处各自装配而非抽进公共模块</b>：{@code mp-common} 是纯工具库，不带 Spring 也不带 servlet ——
 * 它现在能被任何模块无副作用地依赖，正是因为什么框架都不带。为一个 filter 给它引入 web 依赖会改变那个模块的定位，代价大于两份七十行的重复。
 */
@Configuration
public class MockOpsSecurityConfig {

    @Bean
    public FilterRegistrationBean<OpsTokenFilter> mockOpsTokenFilter(
            @Value("${mp.ops.token}") String token) {
        FilterRegistrationBean<OpsTokenFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OpsTokenFilter(token));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
