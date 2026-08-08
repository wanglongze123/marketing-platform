package com.mp.reward.config;

import com.mp.common.event.GrantResultPublisher;
import com.mp.common.security.ProviderNotifySigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 发奖结果事件的装配与供应方通知签名器。V3 PR-9。
 *
 * <p><b>V3 的传输是进程内事件总线</b>（《分阶段方案》§6.1「V3 仍为单进程，V4 才拆」）。V4 拆分布式后 换成 RocketMQ 普通消息 —— 换的是本类里的一个
 * {@code @Bean}，{@code reward} 的业务代码一行不动， 这正是 {@link GrantResultPublisher} 这层接口存在的理由。
 *
 * <p><b>{@code mp.event.enabled=false} 关掉发布</b>：退出标准第 17 条要求证明「事件丢失不产生资损，
 * 只退化收敛时间」。事件正常工作时，收敛究竟由谁完成无法区分——必须能把它关掉，才能证明查单 这条 lower bound 真的在。这与去锁对照组（V2 第 21 条）是同一种验证手段。
 */
@Configuration
public class RewardEventConfig {

    private static final Logger log = LoggerFactory.getLogger(RewardEventConfig.class);

    /**
     * 供应方通知签名器。<b>密钥独立于咨询凭证与支付通知</b>。
     *
     * <p>三者是三条不同的信任边界，共用一把会让任一方的密钥轮换牵连其余两条链路。理由详见 {@link ProviderNotifySigner} 的类注释。
     */
    @Bean
    public ProviderNotifySigner providerNotifySigner(
            @Value("${mp.provider-notify.secret}") String secret) {
        return new ProviderNotifySigner(secret);
    }

    /**
     * 进程内事件总线实现。
     *
     * <p><b>发布失败吞掉并记 WARN，不向上抛</b>：事件是加速手段，没送到只意味着收敛退回查单周期。 抛给调用方会让一次已经成功的回调处理被判为失败进而重试 ——
     * 那是用更强的一致性要求去保护 一条本就允许丢的通路，且重试会再发一次事件，反而放大问题。
     */
    @Bean
    public GrantResultPublisher grantResultPublisher(
            ApplicationEventPublisher springPublisher,
            @Value("${mp.event.enabled:true}") boolean enabled) {
        if (!enabled) {
            // 打 WARN 而非 INFO：这是对照组配置，误留到正式环境会让收敛全靠查单，
            // 表现为「能收敛但慢」——不报错，故必须在启动日志里显眼
            log.warn("grant result event DISABLED — 仅用于退出标准 17 的对照组，收敛退化为查单周期");
            return event -> log.info("event publishing disabled, skip, opNo={}", event.opNo());
        }
        return event -> {
            try {
                springPublisher.publishEvent(event);
            } catch (Exception e) {
                log.warn(
                        "publish grant result event failed, convergence falls back to query,"
                                + " opNo={}",
                        event.opNo(),
                        e);
            }
        };
    }
}
