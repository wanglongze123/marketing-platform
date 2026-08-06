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
            enqueueBare(tag + "_" + i, TaskType.QUERY_GRANT);
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
        String taskNo = enqueueBare("fencing", TaskType.QUERY_GRANT);

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

        // 驱动前：GRANT 任务待执行，主单发放态未启动
        ConvergenceResp before = benefitOrderService.queryConvergence(bizNo);
        assertThat(before.getGrantStatus()).isEqualTo(GrantStatus.NOT_START.name());
        assertThat(before.getTasks())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.getTaskType()).isEqualTo(TaskType.GRANT.name());
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
        assertThat(after.getTasks())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.getStatus()).isEqualTo(TaskStatus.DONE.name());
                            // 完成时清空租约，否则僵尸回收会把已完成的任务当成过期任务捞回来
                            assertThat(t.getLeaseOwner()).isNull();
                            assertThat(t.getLeaseExpire()).isNull();
                        });
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
