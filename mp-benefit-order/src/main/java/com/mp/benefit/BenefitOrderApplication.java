package com.mp.benefit;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * benefit-order 服务的独立启动入口。V4 新增。
 *
 * <p>与 {@code GatewayApplication} 并存，理由见 {@code ActivityApplication} 的类注释。
 *
 * <p><b>{@code @EnableScheduling} 不可省</b>。V3 之前它只在 {@code GatewayApplication} 上，单进程形态下 由 gateway
 * 统一驱动全部 {@code @Scheduled}；拆服务后本模块的 {@code TaskSchedulerTrigger} 随 benefit-order 走，而它是 {@code
 * GRANT} / {@code QUERY_GRANT} / {@code CLOSE_ORDER} / {@code STOCK_RELEASE} 等全部异步动作的唯一驱动。
 *
 * <p>漏掉的后果是<b>任务永远停在 {@code PENDING} 且不报任何错</b>：接口照常返回成功、单据照常落库、
 * 日志一行异常都没有，只是发放永远不发生。这类失败没有任何现成信号能提示，只能靠「下单后等半天 没到账」被人发现 —— 故在此显式记一笔。
 */
@SpringBootApplication(scanBasePackages = {"com.mp.benefit", "com.mp.common"})
@EnableDubbo(scanBasePackages = "com.mp.benefit")
@EnableScheduling
public class BenefitOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BenefitOrderApplication.class, args);
    }
}
