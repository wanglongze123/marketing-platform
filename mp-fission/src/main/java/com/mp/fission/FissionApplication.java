package com.mp.fission;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * fission 服务的独立启动入口。V4 新增。
 *
 * <p>与 {@code GatewayApplication} 并存，理由见 {@code ActivityApplication} 的类注释。
 *
 * <p><b>{@code @EnableScheduling} 不可省</b>，且本模块有<b>两个</b>定时器依赖它：{@code
 * FissionTaskSchedulerTrigger}（驱动 {@code SPONSOR_REWARD} / {@code RELATION_EXPIRE} / {@code
 * QUERY_GRANT}）与 {@code FissionExpireSeederTrigger}（播种到期关系）。二者共用 {@code
 * mp.fission.task.timer.enabled} 一个开关—— 「播了没人跑」与「有人跑但没得播」都是半截状态。
 *
 * <p>漏掉的失败形态见 {@code BenefitOrderApplication} 的类注释：静默，且没有任何现成信号。裂变侧 更隐蔽一层 —— 师傅返奖不发生时，徒弟那侧看起来一切正常。
 */
@SpringBootApplication(scanBasePackages = {"com.mp.fission", "com.mp.common"})
@EnableDubbo(scanBasePackages = "com.mp.fission")
@EnableScheduling
public class FissionApplication {

    public static void main(String[] args) {
        SpringApplication.run(FissionApplication.class, args);
    }
}
