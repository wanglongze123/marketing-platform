package com.mp.gateway.config;

import com.mp.gateway.filter.OpsTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 运维端点令牌校验的装配。V4 第 9 项。
 *
 * <p><b>顺序排在 {@code TraceIdFilter} 之后</b>（它是 {@code @Order(1)}）：被拒的请求也要有 traceId，
 * 否则「谁在什么时候被挡了」这条线索在日志里断掉 —— 而运维端点被拒恰恰是最需要追查的一类事件。
 *
 * <p><b>类名与 {@code @Bean} 方法名都带前缀</b>：单进程形态下 gateway 扫 {@code com.mp}，与 mock 侧的同名类、同名方法都会撞 —— 默认
 * bean 名取类的短名与方法名，Spring 分别报 {@code ConflictingBeanDefinitionException} 与 {@code
 * BeanDefinitionOverrideException}。
 *
 * <p><b>令牌无代码内默认值</b>：缺配置即启动失败，比用一个「大家都知道的默认值」跑起来更安全。 后者的失效形态是校验照常通过，而任何读过代码的人都能绕过 —— 与咨询凭证密钥同样的处置
 * （见 {@code ConsultTokenConfig} 与 {@code ShapeFreezeTest} 的对应断言）。
 */
@Configuration
public class GatewayOpsSecurityConfig {

    @Bean
    public FilterRegistrationBean<OpsTokenFilter> gatewayOpsTokenFilter(
            @Value("${mp.ops.token}") String token) {
        FilterRegistrationBean<OpsTokenFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new OpsTokenFilter(token));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
