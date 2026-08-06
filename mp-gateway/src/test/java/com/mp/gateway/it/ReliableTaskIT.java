package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.task.TaskClaimService;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.TaskStatus;
import com.mp.common.enums.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 可靠任务表与调度器，对应《分阶段方案》§5.7 退出标准 8、9。
 *
 * <p>V2 单进程无法 {@code kill -9}，但抢占、接管、fencing 全是 SQL 语义，与部署形态无关 —— 多线程各持不同 {@code lease_owner}
 * 即可在单进程内验证。不在 V2 覆盖的话，这段代码 从写下到 V3 之间不会被执行过一次。
 */
class ReliableTaskIT extends AbstractMySqlIT {

    @Autowired private BenefitTaskMapper taskMapper;
    @Autowired private TaskClaimService claimService;

    /**
     * 标准 8：并发领取，每条任务只被一个 {@code lease_owner} 领走。
     *
     * <p>{@code SKIP LOCKED} 的作用就在这里：不加它，N 个线程会在同一批行上互相阻塞（串行化， 但不重复）；写成 {@code UPDATE ... WHERE id
     * IN (SELECT ... FOR UPDATE SKIP LOCKED)} 的派生表 形式则更糟 —— 内层被物化成临时表，锁子句失效，多个线程领到同一批任务重复发奖。
     */
    @Test
    void concurrentClaimGivesEachTaskToExactlyOneOwner() throws Exception {
        int taskCount = 30;
        int threads = 6;
        String tag = "claim";
        for (int i = 0; i < taskCount; i++) {
            enqueueBare(tag + "_" + i, TaskType.STOCK_CONSUME);
        }

        // taskNo -> 领到它的 owner 列表。同一个 taskNo 出现两次即重复领取
        Map<String, List<String>> claimedBy = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String owner = "owner-" + t;
                futures.add(
                        pool.submit(
                                () -> {
                                    await(start);
                                    // 每个线程多领几轮，确保把 30 条全部领完
                                    for (int round = 0; round < 5; round++) {
                                        for (BenefitTask task :
                                                claimService.claimPending(owner, 10, 30)) {
                                            claimedBy
                                                    .computeIfAbsent(
                                                            task.getTaskNo(),
                                                            k ->
                                                                    java.util.Collections
                                                                            .synchronizedList(
                                                                                    new ArrayList<>()))
                                                    .add(owner);
                                        }
                                    }
                                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimedBy).as("30 条任务应全部被领走").hasSize(taskCount);
        assertThat(claimedBy)
                .allSatisfy(
                        (taskNo, owners) ->
                                assertThat(owners)
                                        .as("%s 被多个实例领走，SKIP LOCKED 未生效", taskNo)
                                        .hasSize(1));

        // 库里的 lease_owner 与领取记录一致 —— 只断言内存里的领取结果，漏掉「打租约时被覆盖」
        for (Map.Entry<String, List<String>> e : claimedBy.entrySet()) {
            assertThat(
                            str(
                                    benefitJdbc,
                                    "SELECT lease_owner FROM benefit_task WHERE task_no = ?",
                                    e.getKey()))
                    .isEqualTo(e.getValue().get(0));
        }
    }

    /**
     * 标准 9：租约过期的 {@code DOING} 任务被接管，且原持有者的写回一律被拒。
     *
     * <p>这是租约机制的残余竞争：A 持有任务但执行变慢，租约过期，B 依租约语义正当接管并完成； A 随后苏醒。若 A 的写回不拦下，接管就只在「A 真死了」时正确，而租约过期恰恰无法区分
     * 「死了」与「慢了」（《分阶段方案》§5.6 ③）。
     */
    @Test
    void expiredLeaseIsTakenOverAndStaleOwnerCannotWriteBack() {
        String taskNo = enqueueBare("fencing", TaskType.STOCK_CONSUME);

        // A 领走
        List<BenefitTask> byA = claimService.claimPending("ownerA", 10, 30);
        assertThat(byA).extracting(BenefitTask::getTaskNo).contains(taskNo);
        Long id =
                num(benefitJdbc, "SELECT id FROM benefit_task WHERE task_no = ?", taskNo)
                        .longValue();

        // 租约置为过去，模拟 A 变慢
        benefitJdbc.update(
                "UPDATE benefit_task SET lease_expire = DATE_SUB(NOW(3), INTERVAL 1 SECOND)"
                        + " WHERE id = ?",
                id);

        // B 依租约语义正当接管
        List<BenefitTask> byB = claimService.claimExpired("ownerB", 10, 30);
        assertThat(byB).extracting(BenefitTask::getTaskNo).contains(taskNo);
        assertThat(str(benefitJdbc, "SELECT lease_owner FROM benefit_task WHERE id = ?", id))
                .isEqualTo("ownerB");

        // A 苏醒，四类写回全部被 fencing 拒绝
        assertThat(taskMapper.markDone(id, "ownerA")).as("完成").isZero();
        assertThat(taskMapper.markRetry(id, "ownerA", 1000L)).as("失败重排").isZero();
        assertThat(taskMapper.renewLease(id, "ownerA", 30)).as("续租").isZero();
        assertThat(taskMapper.markDead(id, "ownerA")).as("死信").isZero();

        // 任务仍归 B，状态未被 A 覆盖
        assertThat(str(benefitJdbc, "SELECT status FROM benefit_task WHERE id = ?", id))
                .isEqualTo(TaskStatus.DOING.name());
        assertThat(str(benefitJdbc, "SELECT lease_owner FROM benefit_task WHERE id = ?", id))
                .isEqualTo("ownerB");

        // B 的写回正常生效 —— 否则上面四条为零可能只是因为 SQL 本身写错了
        assertThat(taskMapper.markDone(id, "ownerB")).isEqualTo(1);
        assertThat(str(benefitJdbc, "SELECT status FROM benefit_task WHERE id = ?", id))
                .isEqualTo(TaskStatus.DONE.name());
    }

    /** 重复入队被 {@code uk_biz_type_op} 挡下，不产生第二条任务。 */
    @Test
    void enqueueIsIdempotentOnBizTypeAndOpNo() {
        String bizNo = "BZ_IT_taskDup";
        taskMapper.enqueue("TK_1", bizNo, TaskType.GRANT.name(), bizNo + "_GRANT", 0, "{}");
        taskMapper.enqueue("TK_2", bizNo, TaskType.GRANT.name(), bizNo + "_GRANT", 0, "{}");

        assertThat(count(benefitJdbc, "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?", bizNo))
                .isEqualTo(1);
        // 保留首次入队的任务号，不被第二次覆盖
        assertThat(str(benefitJdbc, "SELECT task_no FROM benefit_task WHERE biz_no = ?", bizNo))
                .isEqualTo("TK_1");
    }

    /**
     * 收敛端点透出操作记录与任务快照，且两者分列。
     *
     * <p>端点是退出标准 1、3 的观察手段本身 —— 它若不可靠，那两条标准的断言就没有依据。
     */
    @Test
    void convergenceEndpointExposesOpRecordsAndTasks() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("conv"));
        String bizNo = created.getBizNo();
        benefitOrderService.payCallback(
                newPayCallback(bizNo, created.getTradeNo(), "NS_conv_1", "SUCCESS"));

        // 驱动前：GRANT 与 STOCK_CONSUME 两条待执行，主单发放态未启动。
        // 支付成功落两条任务而非一条 —— 库存转消耗自 PR-5 起也从回调事务中移出（技术方案 §7.4）。
        // 另有一条 CLOSE_ORDER 是建单时落的（PR-6），next_time 在支付有效期后，本轮不参与
        ConvergenceResp before = benefitOrderService.queryConvergence(bizNo);
        assertThat(before.getGrantStatus()).isEqualTo(GrantStatus.NOT_START.name());
        assertThat(before.getTasks())
                .extracting(ConvergenceResp.TaskSnapshot::getTaskType)
                .containsExactlyInAnyOrder(
                        TaskType.GRANT.name(),
                        TaskType.STOCK_CONSUME.name(),
                        TaskType.CLOSE_ORDER.name());
        assertThat(before.getTasks())
                .filteredOn(t -> TaskType.GRANT.name().equals(t.getTaskType()))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING.name());
                            assertThat(t.getRetryCount()).isZero();
                            assertThat(t.getNextTime()).isNotBlank();
                            // 未被领取时无持有者 —— 有值即说明租约没在完成时清干净
                            assertThat(t.getLeaseOwner()).isNull();
                        });
        assertThat(before.getOpRecords())
                .extracting(ConvergenceResp.OpRecordSnapshot::getOpType)
                .containsExactly("CREATE_TRADE", "PAY_CALLBACK");

        runScheduler();

        ConvergenceResp after = benefitOrderService.queryConvergence(bizNo);
        assertThat(after.getGrantStatus()).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        // CLOSE_ORDER 未到期，本轮不该被领取 —— 领了就是把刚支付成功的单关掉
        assertThat(after.getTasks())
                .filteredOn(t -> !TaskType.CLOSE_ORDER.name().equals(t.getTaskType()))
                .allSatisfy(
                        t -> {
                            assertThat(t.getStatus()).isEqualTo(TaskStatus.DONE.name());
                            // 完成时清空租约，否则僵尸回收会把已完成的任务当成过期任务捞回来
                            assertThat(t.getLeaseOwner()).isNull();
                            assertThat(t.getLeaseExpire()).isNull();
                        });
        assertThat(after.getTasks())
                .filteredOn(t -> TaskType.CLOSE_ORDER.name().equals(t.getTaskType()))
                .singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING.name()));
        // GRANT_BENEFIT 的本地执行态与下游四分类分列两栏
        assertThat(after.getOpRecords())
                .filteredOn(o -> "GRANT_BENEFIT".equals(o.getOpType()))
                .singleElement()
                .satisfies(
                        o -> {
                            assertThat(o.getStatus()).isEqualTo("SUCCESS");
                            assertThat(o.getDownstreamResult()).isEqualTo("SUCCESS");
                        });
    }

    /**
     * 退避与死信：任务连续失败时 {@code retry_count} 递增、{@code next_time} 后推，超阈进 {@code DEAD}。
     *
     * <p><b>这是 PR-2 里唯一没有真实业务路径能触发的分支</b> —— GRANT 在 mock 下恒成功，故障注入要到 PR-3 才有。用尚无处理器的 {@code
     * STOCK_CONSUME}（PR-5 才接入处理器）驱动：走「无处理器」分支，同样退避重排。
     *
     * <p><b>断言的是 {@code next_time} 相对本轮之前严格后移，而不是「它在将来」</b>：IT 里退避基数被压到 毫秒级（首档
     * 1ms），断言执行时那一毫秒早已过去，「在将来」恒不成立。后移才是退避要保证的性质， 且与基数取值无关。绝对值由 {@code BackoffPolicyTest} 覆盖。
     *
     * <p>每轮之间显式把 {@code next_time} 置为当前时刻使其可领 —— 用 sleep 等到期会让测试时长取决于 退避基数，且掩盖「根本没退避」这种情况。
     */
    /** 短退避序列按 {@code mp.task.backoff-scale=0.02} 压缩后的理论值：1s/5s/30s → 20/100/600ms。 */
    private static final long[] EXPECTED_BACKOFF_MILLIS = {20, 100, 600};

    @Test
    void repeatedFailureIncrementsRetryCountAndPushesNextTimeForward() {
        String taskNo = "TK_IT_backoff";
        // 用确实无处理器的类型。这个选择随实现推进要跟着换：QUERY_GRANT 自 PR-3 起有了处理器、
        // STOCK_CONSUME 自 PR-5 起也有了 —— 每次都表现为本用例变红，因为任务被真的执行掉了，
        // 走不到「无处理器」分支。REFUND 属 V3 范围，届时同样要换
        taskMapper.enqueue(
                taskNo, "BZ_IT_backoff", TaskType.REFUND.name(), "OP_IT_backoff", 0, "{}");

        List<Long> pushes = new ArrayList<>();
        for (int round = 1; round <= 3; round++) {
            makeDue(taskNo);

            scheduler.runOnce();

            assertThat(
                            num(
                                    benefitJdbc,
                                    "SELECT retry_count FROM benefit_task WHERE task_no = ?",
                                    taskNo))
                    .as("第 %s 轮失败后 retry_count 应为 %s", round, round)
                    .isEqualTo(round);

            // 退避量随重试次数增长：短退避序列按 scale=0.02 压缩后为 20 / 100 / 600ms。
            // 只断言「next_time 后移」是不够的 —— 退避取 0 时它等于 NOW(3)，而每轮之间墙钟本就在
            // 前进，断言照样通过（已实测确认）。要验的是退避本身，就必须验后推量
            long pushedMillis = backoffMillis(taskNo);
            // 断言下界取该轮理论值的一半：实测三轮为 24 / 106 / 604 ms（理论 20 / 100 / 600，
            // 余量是调度器每轮自身耗时）。取「不小于前一轮」是不够的 —— 退避取 0 时三轮都是几毫秒
            // 的墙钟噪声，逐轮不减照样成立（已实测确认）
            long expectedMillis = EXPECTED_BACKOFF_MILLIS[round - 1];
            assertThat(pushedMillis)
                    .as("第 %s 轮退避应约 %sms，实际 %sms", round, expectedMillis, pushedMillis)
                    .isGreaterThan(expectedMillis / 2);
            pushes.add(pushedMillis);

            // 无处理器不判死信：补上处理器后任务应能继续跑，而不是已经被判死
            assertThat(
                            str(
                                    benefitJdbc,
                                    "SELECT status FROM benefit_task WHERE task_no = ?",
                                    taskNo))
                    .isEqualTo(TaskStatus.PENDING.name());
            // 租约每轮都还回去，否则要等租约过期撞上僵尸回收才捞得回来
            assertThat(
                            str(
                                    benefitJdbc,
                                    "SELECT lease_owner FROM benefit_task WHERE task_no = ?",
                                    taskNo))
                    .isNull();
        }

        // 末轮显著大于首轮：验的是序列在增长，而非三轮恰好都落在同一噪声区间
        assertThat(pushes.get(2)).as("末轮退避量应远大于首轮，实际 %s", pushes).isGreaterThan(pushes.get(0) * 5);
    }

    /**
     * 处理器持续返回 {@code UNKNOWN} 时超阈进死信，并停止重试。
     *
     * <p>死信是「停止重试并等人工处置」，不是「丢弃」：状态置 {@code DEAD} 留在表里，后续对账与人工 修复以它为入口。若超阈后仍留在 {@code
     * PENDING}，坏任务会无限重试拖垮调度。
     */
    @Test
    void taskStopsRetryingOnceItReachesTheDeadLetterThreshold() {
        String taskNo = "TK_IT_deadGrant";
        String bizNo = "BZ_IT_deadGrant";
        // GRANT 有处理器，但该订单不存在 —— grantBenefit 抛 BizException，按 UNKNOWN 处置
        taskMapper.enqueue(taskNo, bizNo, TaskType.GRANT.name(), bizNo + "_GRANT", 0, "{}");

        int maxRetry = TaskType.GRANT.getMaxRetry();
        List<Long> pushes = new ArrayList<>();
        for (int round = 0; round < maxRetry; round++) {
            makeDue(taskNo);

            scheduler.runOnce();

            // 进死信前每轮都退避。这条路径（writeBack 的 PROCESSING/UNKNOWN 分支）与上一个用例
            // 走的「无处理器」分支是两处独立的 markRetry 调用，各自都要验 —— 只验其一时，另一处
            // 改坏了测试不会红
            if (str(benefitJdbc, "SELECT status FROM benefit_task WHERE task_no = ?", taskNo)
                    .equals(TaskStatus.PENDING.name())) {
                pushes.add(backoffMillis(taskNo));
            }
        }

        // 退避随重试增长：首轮约 20ms、末轮约 600ms（scale=0.02）。
        // 断言末轮跨过 300ms 这条线即可 —— 不比较首末倍数，那要求首轮测得准，
        // 而首轮量级最小、最容易被调度抖动干扰
        assertThat(pushes).as("进死信前应有多轮退避").hasSizeGreaterThan(2);
        assertThat(pushes.get(pushes.size() - 1))
                .as("末轮退避应达到长档（约 600ms），实际 %s", pushes)
                .isGreaterThan(300);

        assertThat(str(benefitJdbc, "SELECT status FROM benefit_task WHERE task_no = ?", taskNo))
                .as("连续 %s 次未成功应进死信", maxRetry)
                .isEqualTo(TaskStatus.DEAD.name());
        // 死信后租约已释放
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT lease_owner FROM benefit_task WHERE task_no = ?",
                                taskNo))
                .isNull();

        // 死信是终态：主扫描只看 PENDING，不会再领它
        Integer retryAtDeath =
                num(benefitJdbc, "SELECT retry_count FROM benefit_task WHERE task_no = ?", taskNo);
        makeDue(taskNo);
        scheduler.runOnce();
        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT retry_count FROM benefit_task WHERE task_no = ?",
                                taskNo))
                .as("进死信后不应再被领取重试")
                .isEqualTo(retryAtDeath);
    }

    /**
     * 任务当前的退避量（毫秒）：{@code next_time} 距数据库当前时刻。
     *
     * <p><b>两端都取数据库时钟</b>，不用「本轮开始到结束的墙钟差」—— 后者把调度器单轮自身的耗时 算了进去，CI 机器上那部分是几百毫秒，远超被测的退避量（本地 20ms 的退避在
     * CI 上量到 414ms）， 断言随机失败。
     */
    private long backoffMillis(String taskNo) {
        Integer ms =
                num(
                        benefitJdbc,
                        "SELECT TIMESTAMPDIFF(MICROSECOND, NOW(3), next_time) DIV 1000"
                                + " FROM benefit_task WHERE task_no = ?",
                        taskNo);
        return ms == null ? 0 : ms;
    }

    /** 把任务的 next_time 置为当前时刻，使其立即可领 —— 绕开退避等待，不改退避本身。 */
    private void makeDue(String taskNo) {
        benefitJdbc.update("UPDATE benefit_task SET next_time = NOW(3) WHERE task_no = ?", taskNo);
    }

    /** 无待执行任务时调度器空转返回 0，不抛异常。 */
    @Test
    void schedulerRunsCleanWhenNothingIsPending() {
        // 先把现存任务清空，避免受其他用例残留影响
        benefitJdbc.update("UPDATE benefit_task SET status = 'DONE' WHERE status = 'PENDING'");
        assertThat(scheduler.runOnce()).isZero();
    }

    /** 直接入队一条任务，不经业务链路 —— 本类验的是调度机制，不必每次都走下单支付。 */
    private String enqueueBare(String tag, TaskType type) {
        String taskNo = "TK_IT_" + tag;
        taskMapper.enqueue(taskNo, "BZ_IT_" + tag, type.name(), "OP_IT_" + tag, 0, "{}");
        return taskNo;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
