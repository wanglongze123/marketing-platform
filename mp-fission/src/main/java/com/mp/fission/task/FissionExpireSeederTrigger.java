package com.mp.fission.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 播种的定时触发器，与 {@link FissionExpireSeeder} 分开。
 *
 * <p>拆开的理由与 {@link FissionTaskSchedulerTrigger} 相同：集成测试关掉定时器，显式调 {@code seed()} 与 {@code runOnce()}
 * 控制两者的先后 —— 定时器在旁边跑会让「播了但还没跑」这个中间状态 观察不到。而播种器本身在测试中必须存在，故条件注解只能打在 trigger 上。
 *
 * <p>与调度器共用 {@code mp.fission.task.timer.enabled} 而非另设开关：两者是同一件事的两半 （播种 +
 * 执行），分开配置只会制造「播了没人跑」或「有人跑但没得播」的组合。
 */
@Component
@ConditionalOnProperty(
        name = "mp.fission.task.timer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FissionExpireSeederTrigger {

    private static final Logger log = LoggerFactory.getLogger(FissionExpireSeederTrigger.class);

    private final FissionExpireSeeder seeder;

    public FissionExpireSeederTrigger(FissionExpireSeeder seeder) {
        this.seeder = seeder;
    }

    @Scheduled(fixedDelayString = "${mp.fission.expire.seed-interval-millis:30000}")
    public void tick() {
        try {
            seeder.seed();
        } catch (Exception e) {
            // 播种线程不能死：本轮失败下轮照常。任务若已存在，缺这一次播种没有后果；
            // 任务若不存在，下一轮补上，代价是治理晚一个播种周期开始
            log.error("expire task seeding failed", e);
        }
    }
}
