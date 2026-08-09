package com.mp.fission.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发器，与 {@link FissionTaskScheduler} 分开。
 *
 * <p><b>为什么拆成两个类</b>：集成测试要显式驱动 {@code runOnce()} 并在每轮之间读快照断言退避序列， 定时器在旁边跑会把任务提前领走，测试观察到的序列取决于线程调度
 * —— 断言随机失败，且失败时 无法区分是被定时器抢了还是逻辑真的错了。测试关掉本 bean（{@code mp.fission.task.timer.enabled=false}），
 * 调度逻辑本身不受影响。
 */
@Component
@ConditionalOnProperty(
        name = "mp.fission.task.timer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FissionTaskSchedulerTrigger {

    private static final Logger log = LoggerFactory.getLogger(FissionTaskSchedulerTrigger.class);

    private final FissionTaskScheduler scheduler;

    public FissionTaskSchedulerTrigger(FissionTaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${mp.fission.task.interval-millis:1000}")
    public void tick() {
        try {
            scheduler.runOnce();
        } catch (Exception e) {
            // 调度线程不能死：单轮异常记录后继续，下一轮照常。吞掉的是本轮的异常，
            // 任务本身仍持有租约，到期后由僵尸回收捞回
            log.error("scheduler round failed", e);
        }
    }
}
