package com.mp.gateway.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sentinel 流控。V4 第 4 项，技术方案 §2.3。
 *
 * <p><b>只在 gateway 设防</b>：它是唯一的入口，拦在这里等于拦住全部外部流量。后段各服务再各自 设限只会让「到底是谁拒的」变难查，而它们本就只接受来自 gateway 的调用。
 *
 * <p><b>阈值取 §8.3 目标的 1.5 倍，不是等于</b>。压测目标是「这个量级下要达标」，流控阈值是 「超过多少就该拒」——
 * 两者取同一个数会让压测在达标边缘被自己的流控拦掉，测出来的是 流控阈值而非系统能力。留 50% 余量。
 *
 * <p><b>被拒时返回 1799 而非 5xxx</b>：限流是<b>预期内</b>的业务拒绝，客户端应当退避重试； 归到 5xxx 会让「系统故障率」指标包含正常的流控，而 §8.4 的 P1
 * 告警正是按 5xxx 错误率设的 —— 一次正常的削峰会触发一片告警。
 *
 * <p><b>规则写死在代码里，不接 Nacos 动态配置</b>：动态规则要处理「规则推送失败时用哪一份」
 * 「本地缓存与远端不一致」这些问题，而本阶段的目标是证明流控这条防线存在。写死的规则在重启时 生效，对演示足够；生产要动态调整时换 {@code FlowRuleManager}
 * 的数据源即可，拦截逻辑不动。
 */
@Configuration
@ConditionalOnProperty(name = "mp.sentinel.enabled", havingValue = "true", matchIfMissing = true)
public class SentinelConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SentinelConfig.class);

    /** 倍率：阈值 = 压测目标 × 该值。见类注释 */
    private static final double HEADROOM = 1.5;

    private final int consultQps;
    private final int tradeQps;
    private final int payNotifyQps;

    public SentinelConfig(
            @Value("${mp.sentinel.consult-qps:1000}") int consultQps,
            @Value("${mp.sentinel.trade-qps:300}") int tradeQps,
            @Value("${mp.sentinel.pay-notify-qps:500}") int payNotifyQps) {
        this.consultQps = consultQps;
        this.tradeQps = tradeQps;
        this.payNotifyQps = payNotifyQps;
    }

    @PostConstruct
    public void loadRules() {
        List<FlowRule> rules =
                List.of(
                        rule("/api/benefit/consult", consultQps),
                        rule("/api/benefit/trade", tradeQps),
                        rule("/api/benefit/pay-callback", payNotifyQps));
        FlowRuleManager.loadRules(rules);
        rules.forEach(
                r -> log.info("sentinel rule: {} -> {} QPS", r.getResource(), (int) r.getCount()));
    }

    private FlowRule rule(String resource, int baseQps) {
        FlowRule r = new FlowRule();
        r.setResource(resource);
        r.setGrade(RuleConstant.FLOW_GRADE_QPS);
        r.setCount(baseQps * HEADROOM);
        // 直接拒绝而非排队等待：排队会把请求压在网关的线程上，虚拟线程下不至于耗尽，
        // 但用户等到的仍是超时。明确拒绝让客户端能立刻退避重试
        r.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        return r;
    }

    @Bean
    public SentinelWebInterceptor sentinelWebInterceptor(BlockExceptionHandler handler) {
        SentinelWebMvcConfig config = new SentinelWebMvcConfig();
        config.setBlockExceptionHandler(handler);
        // 资源名取 URL 而非「HTTP 方法 + URL」：规则按接口配，同一路径的不同方法
        // 在本项目里不存在（写用 POST、读用 GET，路径本身就不同）
        config.setHttpMethodSpecify(false);
        return new SentinelWebInterceptor(config);
    }

    /** 被限流时的响应。返回 1799（业务拒绝区段），不返回 5xxx —— 理由见类注释。 */
    @Bean
    public BlockExceptionHandler blockExceptionHandler() {
        return (request, response, resourceName, ex) -> {
            log.warn("sentinel blocked, resource={}", resourceName);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write(
                            "{\"code\":1799,\"message\":\"请求过于频繁，请稍后重试\","
                                    + "\"data\":null,\"traceId\":\""
                                    + com.mp.common.web.TraceIdHolder.get()
                                    + "\"}");
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sentinelWebInterceptor(blockExceptionHandler()))
                .addPathPatterns("/api/**");
    }
}
