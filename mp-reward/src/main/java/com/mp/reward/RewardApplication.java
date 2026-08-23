package com.mp.reward;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * reward 服务的独立启动入口。V4 新增。
 *
 * <p>与 {@code GatewayApplication} 并存，理由见 {@code ActivityApplication} 的类注释。
 *
 * <p><b>无 {@code @EnableScheduling}</b>：发奖的收敛任务在玩法层（{@code benefit-order} 的 {@code
 * QUERY_GRANT}、{@code fission} 的同名任务），reward 只被动接收调用与供应方回调，自身没有任务表。
 *
 * <p><b>事件发布方在本服务</b>：{@code RewardEventConfig} 装配的 {@code GrantResultPublisher} 由此进程 持有。V4
 * 接入消息队列后换的是那个 {@code @Bean} 的实现，本类不动。
 */
@SpringBootApplication(scanBasePackages = {"com.mp.reward", "com.mp.common"})
@EnableDubbo(scanBasePackages = "com.mp.reward")
public class RewardApplication {

    public static void main(String[] args) {
        SpringApplication.run(RewardApplication.class, args);
    }
}
