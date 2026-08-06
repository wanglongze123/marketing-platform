package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.common.enums.PayStatus;
import com.mp.common.exception.BizException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * <b>去锁对照组</b>，对应《分阶段方案》§5.7 退出标准 15。
 *
 * <p>本类与 {@code ConcurrentIdempotencyIT} 压的是同一批场景，唯一差别是 {@code mp.lock.enabled=false}。 它要证明的命题只有一个：
 *
 * <blockquote>
 *
 * <b>去掉 L2 锁之后，正确性结果完全不变。</b>
 *
 * </blockquote>
 *
 * <p>这不是「锁没用」，而是技术方案 §6.1 的实证：<b>L2 是性能优化，L3 才是正确性兜底</b>。锁的价值 在于减少并发走到 L3 的冲突（条件更新 {@code
 * affected_rows=0}、唯一键冲突），而不是保证结果对。
 *
 * <p><b>反过来说，若本类变红，说明代码里有地方错把锁当成了正确性依据</b> —— 那才是真正的问题：
 * 锁会因超时、宕机、时钟漂移而失效，唯一索引不会。任何「靠锁保证不重复」的写法，在锁失效的那一刻 就会产生重复数据。
 *
 * <p><b>为什么必须是独立的测试类</b>：{@code mp.lock.enabled} 由 Spring 上下文读取，命令行 {@code -D} 传不进
 * {@code @DynamicPropertySource} 构造的上下文。用 {@code @TestPropertySource} 覆盖 会让 Spring
 * 另建一个上下文缓存条目，两组因此能在同一次 {@code mvn verify} 里各跑各的。
 *
 * <p>代价是多一个 Spring 上下文（启动约 1 秒）。收益是这条对照关系每次 CI 都被验证，而不是只在 手工压测时看一眼。
 */
@TestPropertySource(properties = "mp.lock.enabled=false")
class LockDisabledCorrectnessIT extends AbstractMySqlIT {

    private static final int THREADS = 12;

    @BeforeEach
    void resetStock() {
        benefitJdbc.update(
                "UPDATE marketing_stock SET total = 1000, locked = 0, consumed = 0"
                        + " WHERE stock_key = ?",
                "sku:" + SKU_ID);
        benefitJdbc.update("DELETE FROM user_purchase_quota");
    }

    /** 无锁时并发建单仍只产生一笔 —— 靠的是 {@code uk_idempotent} 与 {@code DuplicateKeyException} 分流。 */
    @Test
    void concurrentCreateTradeStillProducesOneOrderWithoutLock() throws Exception {
        List<String> bizNos = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(
                THREADS,
                () -> {
                    // 无锁时不应出现 5002 —— 没有锁就没有锁冲突。若这里抛了，
                    // 说明 mp.lock.enabled=false 没生效，本类验的就不是对照组
                    bizNos.add(
                            benefitOrderService
                                    .createTrade(newTradeReq("noLockCreate"))
                                    .getBizNo());
                });

        assertThat(bizNos).hasSize(THREADS);
        assertThat(bizNos.stream().distinct().toList()).as("无锁时唯一索引仍保证只建一笔单").hasSize(1);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE client_req_no = ?",
                                "REQ_noLockCreate"))
                .isEqualTo(1);
        assertThat(lockedOf()).as("无锁时库存仍只占一份").isEqualTo(1);
    }

    /** 无锁时并发支付通知仍只推进一次 —— 靠的是 {@code uk_biz_op} 与主单条件更新。 */
    @Test
    void concurrentPayCallbackStillAdvancesOnceWithoutLock() throws Exception {
        String bizNo = benefitOrderService.createTrade(newTradeReq("noLockPay")).getBizNo();
        String tradeNo = "PAY1_" + bizNo;

        runConcurrently(
                THREADS,
                () -> {
                    PayCallbackReq cb = newPayCallback(bizNo, tradeNo, "NS_noLock", "SUCCESS");
                    try {
                        benefitOrderService.payCallback(cb);
                    } catch (BizException e) {
                        // 无锁时唯一索引冲突可能以异常形式出现在某些实现里 —— 只要最终状态唯一即可。
                        // 这里刻意宽容：本类断言的是「结果」，不是「过程中谁赢了」
                    }
                });

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK"))
                .as("无锁时 uk_biz_op 仍保证只留一条操作记录")
                .isEqualTo(1);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = 'GRANT'",
                                bizNo))
                .as("无锁时 GRANT 任务仍只落一条")
                .isEqualTo(1);
    }

    /**
     * <b>无锁时并发抢库存仍不超卖</b> —— 这是最有说服力的一条。
     *
     * <p>库存扣减<b>从来就没有锁</b>（技术方案 §7.4 明确「不用分布式锁」），靠的是 {@code UPDATE ... WHERE 余量 >= n}
     * 的行锁串行化。此用例把这一点显式化：把锁全关掉，20 个线程抢 5 件， 结果与开锁时一模一样。
     */
    @Test
    void concurrentPurchaseStillDoesNotOversellWithoutLock() throws Exception {
        benefitJdbc.update(
                "UPDATE marketing_stock SET total = 5, locked = 0, consumed = 0"
                        + " WHERE stock_key = ?",
                "sku:" + SKU_ID);

        List<String> ok = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int idx = i;
                tasks.add(
                        () -> {
                            try {
                                ok.add(
                                        benefitOrderService
                                                .createTrade(newTradeReq("noLockRush" + idx))
                                                .getBizNo());
                            } catch (BizException ignored) {
                                // 库存不足
                            }
                            return null;
                        });
            }
            for (Future<Void> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                f.get();
            }
        }

        assertThat(ok).as("无锁时售出仍恰好等于库存").hasSize(5);
        assertThat(lockedOf()).isEqualTo(5);
        assertThat(availableOf()).as("可售余量不得为负").isZero();
    }

    // ------------------------------------------------------------------

    private void runConcurrently(int threads, ThrowingRunnable body) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(
                        () -> {
                            body.run();
                            return null;
                        });
            }
            for (Future<Void> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                f.get();
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private int lockedOf() {
        return num(
                benefitJdbc,
                "SELECT locked FROM marketing_stock WHERE stock_key = ?",
                "sku:" + SKU_ID);
    }

    private int availableOf() {
        return num(
                benefitJdbc,
                "SELECT total - locked - consumed FROM marketing_stock WHERE stock_key = ?",
                "sku:" + SKU_ID);
    }
}
