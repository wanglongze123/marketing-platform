package com.mp.benefit.task;

import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.common.web.TraceIdHolder;
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
        // 每条任务一个 traceId，在此设置而非每轮一个：一轮领 50 条，共用一个 traceId 会把
        // 50 笔无关业务的日志串成一条链。调度器不经过 TraceIdFilter，不设则全程为空 ——
        // 而履约、关单、查单收敛正是最需要按链路排查的部分
        TraceIdHolder.newTrace();
        try {
            doExecute(task);
        } finally {
            // 线程池的线程会被复用，不清理则下一条任务继承本条的 traceId
            TraceIdHolder.clear();
        }
    }

    private void doExecute(BenefitTask task) {
        TaskType type = TaskType.valueOf(task.getTaskType());
        TaskHandler handler = handlers.get(type);
        if (handler == null) {
            // 无处理器不判死信：补上处理器后任务应能继续执行。租约必须归还，直接 return 会让
            // 任务留在 DOING 并持有租约，须等租约过期再撞上每 N 轮一次的僵尸回收才捞得回来。
            //
            // 不计入 retry_count：这不是执行失败，而是本实例没有能力处理它。计入则计数在等待
            // 处理器接入期间无上限累积，处理器注册后首次返回非终态即达阈值直接进死信
            log.error("no handler for task type {}, taskNo={}", type, task.getTaskNo());
            // 退避取固定长档而非按 retry_count 索引：计数既然不增长，索引恒为首档，任务会以
            // 最短间隔被反复领取。处理器要等到下次发版才会出现，高频轮询纯属浪费
            int rows =
                    taskMapper.releaseWithoutRetry(
                            task.getId(), owner, backoff.maxBackoffMicros(RetStatus.UNKNOWN));
            logWriteBack(task, "PENDING(no handler)", rows);
            return;
        }

        RetStatus result;
        long startNanos = System.nanoTime();
        try {
            result = handler.handle(task);
        } catch (BizException e) {
            // 业务规则拒绝是**确定的答案**，重试拿到的还是同一个答案（PR-8 补）。
            //
            // 不这样分的话，每一笔正常成交的订单都会贡献一条死信：建单时落的 CLOSE_ORDER 任务
            // 在支付有效期后到期，此时订单早已 PAY_SUCCESS，关单判 1741 —— 按 UNKNOWN 重试
            // 五轮进 DEAD。死信本是「停止重试并等人工处置」的入口，被正常业务填满就没人看了。
            //
            // 只认 1xxx / 4xxx：5xxx 是系统异常，其语义恰恰是「结果未知」，必须继续按 UNKNOWN
            // 收敛。这与 ErrorCode 的分区口径是同一条 —— 判据取错误码而非异常类型，因为
            // BizException 同时承载着这三个分区
            if (isDeterministicRejection(e.getCode())) {
                log.info(
                        "task rejected by business rule, no retry, taskNo={}, type={}, code={},"
                                + " msg={}",
                        task.getTaskNo(),
                        type,
                        e.getCode(),
                        e.getMessage());
                result = RetStatus.FAIL;
            } else {
                log.error("task threw system-level BizException, taskNo={}", task.getTaskNo(), e);
                result = RetStatus.UNKNOWN;
            }
        } catch (Exception e) {
            // 未预期异常映射为 UNKNOWN 而非 FAIL：异常可能发生在 RPC 发出之后，下游未必没执行。
            // 判为 FAIL 等于替下游断言「没做」，而这个断言没有依据
            log.error("task handler threw, taskNo={}, type={}", task.getTaskNo(), type, e);
            result = RetStatus.UNKNOWN;
        }

        renewIfSlow(task, startNanos);
        writeBack(task, type, result);
    }

    /**
     * 该错误码是否表示「确定的业务拒绝」——重试不会改变答案。
     *
     * <p>按 {@code ErrorCode} 的分区判：{@code 1xxx} 业务规则拒绝、{@code 4xxx} 入参非法，两者都是终态； {@code 5xxx}
     * 是系统异常，语义为「结果未知」，必须继续重试收敛。
     *
     * <p>取码的首字符而非枚举整体比对：错误码是《技术方案》§4.1 定义的常量集，分区语义写在 号段上，逐个列举会在加码时漏改。
     */
    private static boolean isDeterministicRejection(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        char zone = code.charAt(0);
        return zone == '1' || zone == '4';
    }

    /**
     * 执行耗时超过阈值则尝试延长租约，并在超时已不可挽回时告警（《分阶段方案》§5.6 ④）。
     *
     * <p>阈值取租约的三分之二：租约 30 秒时超 20 秒。
     *
     * <p><b>这是事后续租，对「单次长 RPC」无保护作用，不要把它当成长任务的解法。</b> 本方法在 {@code handler.handle()}
     * <b>返回之后</b>才拿得到耗时 —— 而执行期间无并发点可插， 无处发起续租。于是：
     *
     * <ul>
     *   <li>耗时在阈值与租约之间（20~30s）：续租成功，确实起了作用 —— 它保护的是<b>写回</b>， 不是执行
     *   <li>耗时超过租约（&gt;30s）：租约已过期甚至已被另一实例接管，{@code renewLease} 带 fencing， {@code affected_rows =
     *       0}；紧随其后的 {@code writeBack} 同样被 fencing 拒绝。 这次执行完全白跑，结果被丢弃，任务由接管者重跑
     * </ul>
     *
     * <p><b>故 V2 的契约是：单次任务耗时必须短于租约。</b> 依据是任务形态 —— 单次 RPC + 两个短事务， 正常百毫秒级，比 30 秒租约低两个数量级。<b>该契约当前没有
     * RPC 超时兜底</b>：injvm 下 调用等同本地方法，Dubbo 超时不生效，V4 拆 tri 协议时须显式配置 provider/consumer timeout，
     * 否则一个卡死的下游调用会无限期占住调度线程。
     *
     * <p>契约被打破时<b>必须看得见</b>：续租失败单独打 error 并写明后果，而不是混在一条 {@code renewed=false} 里 ——
     * 后者读起来像「续租没必要」，实际是「这次执行白跑了」。 真正的长任务出现时，改法是执行前另起守护线程定期续租，不是调大阈值。
     */
    private void renewIfSlow(BenefitTask task, long startNanos) {
        long elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        if (elapsedSeconds < leaseSeconds * 2L / 3) {
            return;
        }
        int rows = taskMapper.renewLease(task.getId(), owner, leaseSeconds);
        if (rows > 0) {
            log.warn(
                    "task ran {}s (lease {}s), lease renewed, taskNo={}",
                    elapsedSeconds,
                    leaseSeconds,
                    task.getTaskNo());
            return;
        }
        // 续租被 fencing 拒绝：租约已过期或已易主。本次执行的结果即将被 writeBack 一并丢弃 ——
        // 这不是「续租没必要」，而是「单次执行超出了租约」，即上面那条契约被打破
        log.error(
                "task ran {}s, exceeded lease {}s and could not renew, this run is discarded and"
                        + " the task will be re-executed by whoever holds the lease now;"
                        + " taskNo={}, type={}, bizNo={}",
                elapsedSeconds,
                leaseSeconds,
                task.getTaskNo(),
                task.getTaskType(),
                task.getBizNo());
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
                // 两个来源，处置相同：下游明确失败（业务分支已由 handler 处置完毕），
                // 或 handler 抛出确定的业务拒绝（1xxx/4xxx）。都是终态答案，重试没有意义。
                //
                // 置 DONE 而非 DEAD：DEAD 的语义是「重试到死也没成功，等人工」，而这里
                // 事情已有确定结论 —— 关不掉是因为已支付，这是正确结果，不需要人来看
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
