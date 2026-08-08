package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.mock.dto.FaultMode;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.task.BenefitTaskScheduler;
import com.mp.benefit.task.TaskClaimService;
import com.mp.benefit.task.TaskHandler;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.web.TraceIdHolder;
import com.mp.mock.fault.FaultInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
    @Autowired private FaultInjector injector;

    @AfterEach
    void resetInjection() {
        injector.reset();
    }

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

    /** 短退避序列按 {@code mp.task.backoff-scale=0.02} 压缩后的理论值：1s/5s/30s → 20/100/600ms。 */
    private static final long[] EXPECTED_BACKOFF_MILLIS = {20, 100, 600};

    /**
     * 任务类型尚无处理器时：归还租约、退避重排、<b>不计重试次数</b>、不判死信。
     *
     * <p>不计重试是关键。该情形不是执行失败，而是本实例没有能力处理它 —— 计入则 {@code retry_count}
     * 在等待处理器接入期间无上限累积，待处理器真正注册后，首次返回非终态即达阈值直接进死信。
     *
     * <p>退避取固定末档而非按次数递增：计数既然不增长，按它索引序列会恒取首档，任务将以最短间隔 被反复领取。递增序列由 {@link
     * #taskStopsRetryingOnceItReachesTheDeadLetterThreshold} 经真实 GRANT 链路覆盖，绝对值由 {@code
     * BackoffPolicyTest} 覆盖。
     *
     * <p>每轮之间显式把 {@code next_time} 置为当前时刻使其可领 —— 用 sleep 等到期会让测试时长取决于 退避基数，且掩盖「根本没退避」这种情况。
     */
    @Test
    void taskWithoutHandlerIsRescheduledWithoutCountingAsRetry() {
        String taskNo = "TK_IT_backoff";
        // 用确实无处理器的类型。这个选择随实现推进要跟着换：QUERY_GRANT 自 PR-3 起有了处理器、
        // STOCK_CONSUME 自 PR-5 起也有了 —— 每次都表现为本用例变红，因为任务被真的执行掉了，
        // 走不到「无处理器」分支。REFUND 属 V3 范围，届时同样要换
        taskMapper.enqueue(
                taskNo, "BZ_IT_backoff", TaskType.REFUND.name(), "OP_IT_backoff", 0, "{}");

        // 末档理论值 600ms（短退避 30s × scale=0.02）
        long expectedMillis = EXPECTED_BACKOFF_MILLIS[EXPECTED_BACKOFF_MILLIS.length - 1];

        for (int round = 1; round <= 3; round++) {
            makeDue(taskNo);

            scheduler.runOnce();

            assertThat(
                            num(
                                    benefitJdbc,
                                    "SELECT retry_count FROM benefit_task WHERE task_no = ?",
                                    taskNo))
                    .as("第 %s 轮：无处理器不计重试，retry_count 应恒为 0", round)
                    .isZero();

            // 断言下界取理论值的一半，余量是调度器每轮自身耗时。只断言「next_time 后移」不够 ——
            // 退避取 0 时它等于 NOW(3)，而每轮之间墙钟本就在前进，断言照样通过（已实测确认）
            long pushedMillis = backoffMillis(taskNo);
            assertThat(pushedMillis)
                    .as("第 %s 轮退避应取末档约 %sms，实际 %sms", round, expectedMillis, pushedMillis)
                    .isGreaterThan(expectedMillis / 2);

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
    }

    /**
     * 处理器持续返回 {@code UNKNOWN} 时超阈进死信，并停止重试。
     *
     * <p>死信是「停止重试并等人工处置」，不是「丢弃」：状态置 {@code DEAD} 留在表里，后续对账与人工 修复以它为入口。若超阈后仍留在 {@code
     * PENDING}，坏任务会无限重试拖垮调度。
     *
     * <p><b>构造方式取「下游持续超时」而非「订单不存在」</b>（PR-8 改）。原先借的是后者 —— 主单查不到 抛 {@code 4001}，被调度器按 {@code
     * UNKNOWN} 重试到死信。但那是个<b>确定的答案</b>：单都没了，重试 多少次也变不出来，{@code StockTaskHandler} 一直就是判 {@code FAIL}
     * 的。PR-8 让调度器对 {@code 1xxx}/{@code 4xxx} 一律判 {@code FAIL} 后，这个构造器不再成立。
     *
     * <p>换成注入超时才是本用例真正要的形态：结果<b>未知</b>，每一轮重试都可能成功，故必须退避重试， 直到阈值才放弃。这也是死信本来的语义
     * ——「试到最后仍不知道」，而非「已知做不到」。
     *
     * <p><b>载体取 {@code QUERY_CLOSE} 而非 {@code GRANT}</b>：后者在落了查单任务之后即把职责移交出去、 自身终结（见 {@code
     * GrantTaskHandler}），根本走不到重试至阈值那条路 —— 用它构造出的「死信」 恰恰是被判定为缺陷的那个现象。{@code QUERY_CLOSE}
     * 是查单类，没有可移交的对象，下游持续 不可达时只能自己退避重试到阈值，正是死信语义的形态。
     */
    @Test
    void taskStopsRetryingOnceItReachesTheDeadLetterThreshold() {
        // 建单要调支付方下单，故先正常建单，再注入 —— 一上来就注入的话 createTrade 自己就抛了
        String bizNo = benefitOrderService.createTrade(newTradeReq("deadQuery")).getBizNo();

        // 支付方持续超时：关单结果未知，每一轮重试都可能拿到答案，故应退避重试而非一次判死
        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);

        // 先把主单推进到 CLOSING —— reconcileClose 只对该状态做实事，其余状态视为「已被别的
        // 路径收敛」直接返回 SUCCESS，任务一轮即 DONE，压不到重试分支
        benefitOrderService.closeOrder(bizNo, "");
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT pay_status FROM play_biz_record WHERE"
                                        + " play_biz_record_no = ?",
                                bizNo))
                .isEqualTo(PayStatus.CLOSING.name());

        // 用 closeOrder 自己落的那条任务，不另行入队：op_no 同为 bizNo + "_QUERY_CLOSE"，
        // 重复入队会命中 uk_biz_type_op 而保留原 task_no，后续按自造的号查库将一行也读不到
        String taskNo =
                str(
                        benefitJdbc,
                        "SELECT task_no FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                        bizNo,
                        TaskType.QUERY_CLOSE.name());
        assertThat(taskNo).as("关单受理应同事务落下查单任务").isNotNull();

        int maxRetry = TaskType.QUERY_CLOSE.getMaxRetry();
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

    /**
     * 任务执行期间 MDC 里有 traceId，且每条任务各不相同、执行后清理。
     *
     * <p>调度器由 {@code @Scheduled} 驱动，不经过 {@code TraceIdFilter}。不设则履约、关单、查单收敛 这些最需要按链路排查的日志全部不带
     * traceId，只能靠 bizNo 逐行 grep。
     *
     * <p>断言「每条任务各不相同」而非只断言「非空」：一轮领 50 条，若在轮级别设置一次，50 笔无关 业务的日志会被串成一条链。断言「执行后清理」则是因为线程会被复用 ——
     * 不清理则下一条任务 继承上一条的 traceId。
     *
     * <p>取 MDC 而非解析日志输出：验的是「执行期间 MDC 是否被正确设置」，日志格式是另一件事。
     */
    @Test
    void schedulerAssignsDistinctTraceIdPerTaskAndClearsAfterwards() {
        // GRANT 无对应主单会抛 4001，被调度器判为确定拒绝 —— 与本用例无关，
        // 此处只关心 handler 被调用时 MDC 的内容
        String taskA = enqueueBare("traceA", TaskType.GRANT);
        String taskB = enqueueBare("traceB", TaskType.GRANT);

        // 按 taskNo 记录，只取本用例入队的两条。
        //
        // 不能断言「本轮恰好执行 2 条」：调度器领的是全库所有到期的 PENDING 任务，同一个
        // Spring 上下文里其他用例残留的任务会一并被领走。本地按某个执行顺序恰好没有残留，
        // CI 上顺序不同即多出一条，断言 hasSize(2) 随之失败（实测 CI 上为 3）。
        // 用例之间不共享状态是理想情况，但调度器的语义本就是「领走所有到期的」，
        // 断言应当收窄到自己关心的那部分，而不是要求全局清净
        Map<String, String> seen = new ConcurrentHashMap<>();
        TraceCapturingHandler probe = new TraceCapturingHandler(seen);
        BenefitTaskScheduler probed =
                new BenefitTaskScheduler(taskMapper, claimService, List.of(probe), 30, 0.02);

        probed.runOnce();

        assertThat(seen).as("本用例的两条任务都应被执行").containsKeys(taskA, taskB);
        // 断言非空串而非非 null：handler 把缺失的 traceId 存成空串（Map 不接受 null 值），
        // 用 isNotNull 会让「执行了但没有 traceId」这一失败形态照常通过
        assertThat(seen.get(taskA)).as("任务 A 执行期间应有 traceId").isNotEmpty();
        assertThat(seen.get(taskB)).as("任务 B 执行期间应有 traceId").isNotEmpty();
        assertThat(seen.get(taskA))
                .as("两条任务的 traceId 不应相同 —— 按轮设置会把多笔无关业务串成一条链")
                .isNotEqualTo(seen.get(taskB));
        assertThat(MDC.get(TraceIdHolder.KEY)).as("执行完毕后必须清理，否则线程复用时会串链路").isNull();
    }

    /** 记录各任务执行时 MDC 中的 traceId，键为 taskNo。 */
    private record TraceCapturingHandler(Map<String, String> seen) implements TaskHandler {

        @Override
        public TaskType taskType() {
            return TaskType.GRANT;
        }

        @Override
        public RetStatus handle(BenefitTask task) {
            String traceId = MDC.get(TraceIdHolder.KEY);
            // traceId 为空时用占位串：ConcurrentHashMap 不接受 null 值，而「执行了但没有
            // traceId」正是本用例要抓的失败形态，不能因存不进去而丢失
            seen.put(task.getTaskNo(), traceId == null ? "" : traceId);
            return RetStatus.SUCCESS;
        }
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
