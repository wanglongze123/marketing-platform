package com.mp.benefit.task;

import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 可靠任务调度器。
 *
 * <p>每轮：领一批 → 逐个执行 → 按四分类写回。<b>写回一律带 {@code lease_owner} fencing</b> —— 租约过期无法区分「持有者死了」与「持有者慢了」，A
 * 变慢被 B 正当接管后苏醒，若能写回就会 覆盖 B 的结果（《分阶段方案》§5.6 ③）。
 *
 * <p><b>{@code runOnce} 是 public 的，供测试显式驱动</b>：退出标准断言的是退避序列与状态迁移，
 * 靠真实定时触发去碰运气既慢又不稳定。测试每驱动一轮读一次快照，序列保存在测试变量里 （《分阶段方案》§5.4）。
 *
 * <p>V2 为单进程，{@code lease_owner} 取进程内随机值；V3 多实例时改为实例标识，逻辑不变 —— 抢占、接管、fencing 都是 SQL 语义，与部署形态无关。
 */
@Component
public class BenefitTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(BenefitTaskScheduler.class);

    /** 每轮领取上限 */
    private static final int BATCH = 50;

    /** 僵尸回收的扫描频率低于主路径：每 N 轮一次 */
    private static final int RECLAIM_EVERY_N_ROUNDS = 10;

    private final BenefitTaskMapper taskMapper;
    private final TaskClaimService claimService;
    private final BackoffPolicy backoff;
    private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);

    /** 租约 30 秒：长于单次任务耗时（百毫秒级）两个数量级，短于可接受的接管延迟 */
    private final int leaseSeconds;

    private final String owner = "inst-" + UUID.randomUUID().toString().substring(0, 8);

    private long round;

    public BenefitTaskScheduler(
            BenefitTaskMapper taskMapper,
            TaskClaimService claimService,
            List<TaskHandler> handlerList,
            @Value("${mp.task.lease-seconds:30}") int leaseSeconds,
            @Value("${mp.task.backoff-scale:1.0}") double backoffScale) {
        this.taskMapper = taskMapper;
        this.claimService = claimService;
        this.leaseSeconds = leaseSeconds;
        this.backoff = new BackoffPolicy(backoffScale);
        for (TaskHandler h : handlerList) {
            TaskHandler prev = handlers.put(h.taskType(), h);
            if (prev != null) {
                throw new IllegalStateException("任务类型 " + h.taskType() + " 注册了多个处理器");
            }
        }
    }

    /**
     * 跑一轮，返回本轮实际执行的任务数。
     *
     * @return 执行数，0 表示无可领任务
     */
    public int runOnce() {
        round++;

        List<BenefitTask> tasks = claimService.claimPending(owner, BATCH, leaseSeconds);
        if (round % RECLAIM_EVERY_N_ROUNDS == 0) {
            List<BenefitTask> zombies = claimService.claimExpired(owner, BATCH, leaseSeconds);
            if (!zombies.isEmpty()) {
                log.warn("reclaimed {} expired tasks, owner={}", zombies.size(), owner);
                tasks = concat(tasks, zombies);
            }
        }

        for (BenefitTask task : tasks) {
            execute(task);
        }
        return tasks.size();
    }

    private void execute(BenefitTask task) {
        TaskType type = TaskType.valueOf(task.getTaskType());
        TaskHandler handler = handlers.get(type);
        if (handler == null) {
            // 无处理器不是任务的错，别让它进死信 —— 补上处理器后应能继续跑
            log.error("no handler for task type {}, taskNo={}", type, task.getTaskNo());
            return;
        }

        RetStatus result;
        try {
            result = handler.handle(task);
        } catch (Exception e) {
            // 未预期异常映射为 UNKNOWN 而非 FAIL：异常可能发生在 RPC 发出之后，下游未必没执行。
            // 判为 FAIL 等于替下游断言「没做」，而这个断言没有依据
            log.error("task handler threw, taskNo={}, type={}", task.getTaskNo(), type, e);
            result = RetStatus.UNKNOWN;
        }

        writeBack(task, type, result);
    }

    /** 按四分类写回。affected_rows=0 一律视为租约已易主，放弃且不重试本次写回。 */
    private void writeBack(BenefitTask task, TaskType type, RetStatus result) {
        Long id = task.getId();
        int rows;

        switch (result) {
            case SUCCESS -> {
                rows = taskMapper.markDone(id, owner);
                logWriteBack(task, "DONE", rows);
            }
            case FAIL -> {
                // 下游明确失败：业务分支已由 handler 处置完毕，任务本身到此为止
                rows = taskMapper.markDone(id, owner);
                logWriteBack(task, "DONE(FAIL branch handled)", rows);
            }
            case PROCESSING, UNKNOWN -> {
                int retried = task.getRetryCount() == null ? 0 : task.getRetryCount();
                if (retried + 1 >= type.getMaxRetry()) {
                    rows = taskMapper.markDead(id, owner);
                    logWriteBack(task, "DEAD", rows);
                    if (rows > 0) {
                        log.error(
                                "task dead after {} retries, taskNo={}, type={}, bizNo={}",
                                retried,
                                task.getTaskNo(),
                                type,
                                task.getBizNo());
                    }
                } else {
                    long micros = backoff.nextBackoffMicros(result, retried);
                    rows = taskMapper.markRetry(id, owner, micros);
                    logWriteBack(task, "PENDING(retry)", rows);
                }
            }
        }
    }

    private void logWriteBack(BenefitTask task, String to, int rows) {
        if (rows == 0) {
            // 不是错误：租约已被正当接管，接管者的结果才作数
            log.warn(
                    "write-back rejected by lease fencing, taskNo={}, owner={}, target={}",
                    task.getTaskNo(),
                    owner,
                    to);
        }
    }

    private static List<BenefitTask> concat(List<BenefitTask> a, List<BenefitTask> b) {
        List<BenefitTask> all = new java.util.ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    /** 本实例标识，测试构造 fencing 场景时需要它。 */
    public String owner() {
        return owner;
    }
}
