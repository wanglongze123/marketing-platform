package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 库存 0 超卖与限购，对应《分阶段方案》§5.7 退出标准 17、18。
 *
 * <p><b>0 超卖的判据是「售出数 == 库存总量」，不是「没报错」。</b> 超卖的失效形态恰恰是全部成功 ——
 * 每个请求都拿到订单、都没有异常，只是卖出去的比有的多。故每个并发用例都同时断言三处：成功数、 {@code locked + consumed} 的终值、以及订单表实际行数。
 *
 * <p>并发用例用真实线程池压同一行，不是串行调用 N 次：串行永远测不出「两个线程同时读到还剩 1」。
 */
class StockAndQuotaIT extends AbstractMySqlIT {

    /** seed 库存总量，与 V2190 一致 */
    private static final int TOTAL_STOCK = 100;

    /** seed 限购数量，与 V2190 一致 */
    private static final int LIMIT_QTY = 2;

    /**
     * 每个用例开始前重置库存，<b>并清掉残留的库存类任务</b>。
     *
     * <p>本类是唯一断言全局单行绝对值的测试类，故隔离要求比别的类高。两处污染源：
     *
     * <ol>
     *   <li><b>库存行是全局的</b>：其余 IT 类下的单也在占它，它们不关心库存、跑完不清理。只在 {@code @AfterEach} 清则本类第一个用例读到的就是别人留下的
     *       {@code locked}
     *   <li><b>{@code runScheduler()} 是全局的</b>：它捞<b>所有</b>待执行任务，不限于本用例的。某个用例 落了 {@code
     *       STOCK_CONSUME} 却不驱动（如 {@code stockTaskIsEnqueuedOnlyOncePerOrder}）， 那条任务就会被后面某个用例的
     *       {@code runScheduler()} 捞走执行，把库存改掉
     * </ol>
     *
     * <p>两处都是首次运行时实际红了才发现的 —— 断言绝对值的代价就是必须先把全局状态清干净。
     */
    @BeforeEach
    void resetStock() {
        // 先清任务再清库存：顺序反了则清库存之后残留任务仍可能被本用例的 runScheduler 执行
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE task_type IN ('STOCK_CONSUME', 'STOCK_RELEASE',"
                        + " 'QUOTA_RELEASE')");
        restoreStock();
    }

    @AfterEach
    void restoreStock() {
        benefitJdbc.update(
                "UPDATE marketing_stock SET total = ?, locked = 0, consumed = 0"
                        + " WHERE stock_key = ?",
                TOTAL_STOCK,
                stockKey());
        benefitJdbc.update("DELETE FROM user_purchase_quota");
    }

    // ------------------------------------------------------------------
    // 退出标准 17：并发下 0 超卖
    // ------------------------------------------------------------------

    /**
     * <b>并发抢购：售出恰好等于库存总量。</b>
     *
     * <p>PRD AC-03 的集成测试版（k6 500VU 版属 PR-7）。库存压到 10，20 个线程各抢一件， 断言恰好 10 单成功、10 单被拒。
     *
     * <p><b>三处同时断言</b>：成功计数只反映「接口返回了什么」，库存终值只反映「数算对了没」， 订单行数才反映「实际卖出去多少」。三者任一单独成立都不足以证明没超卖 ——
     * 譬如「扣减写成读-改-写」会让库存终值正确而订单多出几行。
     */
    @Test
    void concurrentPurchaseSellsExactlyTheAvailableStock() throws Exception {
        int stock = 10;
        int threads = 20;
        setStock(stock);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(
                threads,
                i -> {
                    try {
                        // 每线程一个用户：限购不参与本用例，否则分不清拒绝来自库存还是限购
                        benefitOrderService.createTrade(newTradeReq("rush" + i));
                        ok.incrementAndGet();
                    } catch (BizException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
                        rejected.incrementAndGet();
                    }
                    return null;
                });

        assertThat(ok.get()).as("成功数应恰好等于库存").isEqualTo(stock);
        assertThat(rejected.get()).isEqualTo(threads - stock);

        assertThat(lockedOf()).as("预占应等于售出数").isEqualTo(stock);
        assertThat(availableOf()).as("可售余量应归零，且不得为负").isZero();

        // 订单行数才是「实际卖出去多少」—— 前两条断言都可能在扣减写错时仍然成立
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE sku_id = ?"
                                        + " AND client_req_no LIKE 'REQ_rush%'",
                                SKU_ID))
                .as("订单数应等于售出数")
                .isEqualTo(stock);
    }

    /** 库存为 0 时直接拒绝，且不留任何单据 —— 与 PR-4 的校验失败同一口径。 */
    @Test
    void soldOutRejectsWithoutCreatingOrder() {
        setStock(0);

        CreateTradeReq req = newTradeReq("soldout");
        assertThatThrownBy(() -> benefitOrderService.createTrade(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE user_id = ?",
                                req.getUserId()))
                .as("库存不足时不得建单")
                .isZero();
        assertThat(lockedOf()).as("拒绝的请求不得留下预占").isZero();
    }

    /**
     * <b>幂等重试不重复预占</b>（PRD BR-B-06）。
     *
     * <p>同一 {@code clientReqNo} 调三次，只应占一份库存。预占若被提到事务外，这里会占三份 —— 而多占的那两份没有任何机制会还回去，可售余量永久少两件。
     */
    @Test
    void idempotentRetryLocksStockOnlyOnce() {
        CreateTradeReq req = newTradeReq("idem");

        String first = benefitOrderService.createTrade(req).getBizNo();
        String second = benefitOrderService.createTrade(req).getBizNo();
        String third = benefitOrderService.createTrade(req).getBizNo();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(lockedOf()).as("幂等重试只占一份库存").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 退出标准 18：限购
    // ------------------------------------------------------------------

    /** 同一用户买到限额为止，第 N+1 件拒绝，且不影响库存。 */
    @Test
    void purchaseLimitRejectsBeyondQuota() {
        String userId = "U_limit";

        for (int i = 1; i <= LIMIT_QTY; i++) {
            CreateTradeReq req = newTradeReq("limit" + i);
            req.setUserId(userId);
            req.setConsultToken(consultToken(userId, ACTIVITY_ID, SKU_ID));
            assertThat(benefitOrderService.createTrade(req).getBizNo())
                    .as("第 %s 件在限额内，应放行", i)
                    .isNotBlank();
        }

        CreateTradeReq beyond = newTradeReq("limitX");
        beyond.setUserId(userId);
        beyond.setConsultToken(consultToken(userId, ACTIVITY_ID, SKU_ID));
        assertThatThrownBy(() -> benefitOrderService.createTrade(beyond))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED);

        assertThat(usedQtyOf(userId)).isEqualTo(LIMIT_QTY);
        // 限购拒绝必须整体回滚：库存不能被这次失败的尝试占掉
        assertThat(lockedOf()).as("限购拒绝时库存预占须回滚").isEqualTo(LIMIT_QTY);
    }

    /**
     * <b>并发下限购不被突破。</b>
     *
     * <p>同一用户 10 个线程同时下单，限额 2 —— 只能成 2 单。串行版（上一个用例）测不出这个： 「先查 used_qty 再更新」在串行下完全正确，并发下 10 个线程会同时查到
     * 0 然后各自加一。
     */
    @Test
    void concurrentPurchaseDoesNotBreachTheLimit() throws Exception {
        String userId = "U_limitRace";
        AtomicInteger ok = new AtomicInteger();

        runConcurrently(
                10,
                i -> {
                    try {
                        CreateTradeReq req = newTradeReq("race" + i);
                        req.setUserId(userId);
                        req.setConsultToken(consultToken(userId, ACTIVITY_ID, SKU_ID));
                        benefitOrderService.createTrade(req);
                        ok.incrementAndGet();
                    } catch (BizException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
                    }
                    return null;
                });

        assertThat(ok.get()).as("并发下不得突破限购").isEqualTo(LIMIT_QTY);
        assertThat(usedQtyOf(userId)).isEqualTo(LIMIT_QTY);
        assertThat(lockedOf()).as("库存占用应与成功单数一致").isEqualTo(LIMIT_QTY);
    }

    // ------------------------------------------------------------------
    // 预占 → 消耗 / 释放
    // ------------------------------------------------------------------

    /**
     * 支付成功后预占转消耗，<b>可售余量不变</b>。
     *
     * <p>转消耗只是把「占着」变成「卖掉了」，不释放余量。若实现成 {@code locked -= n} 而忘了 {@code consumed += n}，可售余量会凭空多一份 ——
     * 这正是「断言余量」而非只断言 {@code locked} 的理由。
     */
    @Test
    void paySuccessTurnsLockIntoConsumeWithoutFreeingStock() {
        String bizNo = payFor("consume", "SUCCESS");
        long availableBefore = availableOf();

        runScheduler();

        assertThat(lockedOf()).as("预占应已转出").isZero();
        assertThat(consumedOf()).as("应记为已消耗").isEqualTo(1);
        assertThat(availableOf()).as("转消耗不改变可售余量").isEqualTo(availableBefore);

        assertTaskDone(bizNo, TaskType.STOCK_CONSUME);
    }

    /**
     * 支付失败释放库存与额度，可售余量回升。
     *
     * <p>tag 取 {@code stockPayFail} 而非 {@code payFail}：后者与 {@code BranchRejectionIT} 撞车 —— 同 tag
     * 派生同 {@code clientReqNo}，第二个类跑到时命中幂等返回原单，压根不会扣库存，断言随之失准。 <b>tag 在整个 IT 套件里必须唯一</b>，不只是在本类里。
     */
    @Test
    void payFailureReleasesStockAndQuota() {
        String userId = "U_stockPayFail";
        CreateTradeReq req = newTradeReq("stockPayFail");
        req.setUserId(userId);
        req.setConsultToken(consultToken(userId, ACTIVITY_ID, SKU_ID));

        String bizNo = benefitOrderService.createTrade(req).getBizNo();
        assertThat(lockedOf()).isEqualTo(1);
        assertThat(usedQtyOf(userId)).isEqualTo(1);

        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_stockPayFail_1", "FAILED"));
        runScheduler();

        assertThat(lockedOf()).as("支付失败应释放预占").isZero();
        assertThat(consumedOf()).as("未成交不得计入消耗").isZero();
        assertThat(availableOf()).as("可售余量应回到总量").isEqualTo(TOTAL_STOCK);
        // 交易未成立 → 额度返还（技术方案 §3.4 的口径表）
        assertThat(usedQtyOf(userId)).as("交易未成立应返还限购额度").isZero();

        // 库存与额度由 STOCK_RELEASE 一条任务承接，不拆成两条 —— 二者需要同一道幂等闸
        assertTaskDone(bizNo, TaskType.STOCK_RELEASE);
    }

    /**
     * <b>库存任务重复执行不会多减</b>。
     *
     * <p>把已完成的释放任务重置为待执行再跑一轮，模拟「调度器重复领取」。真实防线是 {@code uk_biz_type_op} 挡在入队处，此用例验的是万一它被绕过（如人工重置），SQL
     * 下界不会让 {@code locked} 变成负数。
     *
     * <p>两者防的不是同一件事：唯一键防「同一单重复入队」，下界防「总数被减成负值」。 下界<b>提供不了</b>每单幂等 —— {@code locked} 是所有订单共享的计数器，A
     * 单重复释放时它 因别的订单占用仍大于 0，会释放掉别人的预占。
     */
    @Test
    void repeatedStockTaskDoesNotDoubleDecrement() {
        String bizNo = payFor("dup", "FAILED");
        runScheduler();
        assertThat(lockedOf()).isZero();

        // 人工把任务打回待执行，再跑一轮
        benefitJdbc.update(
                "UPDATE benefit_task SET status = 'PENDING', lease_owner = NULL,"
                        + " next_time = NOW(3) WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.STOCK_RELEASE.name());
        runScheduler();

        assertThat(lockedOf()).as("重复释放不得把预占减成负数").isZero();
        assertThat(availableOf()).as("可售余量不得超过总量").isEqualTo(TOTAL_STOCK);
    }

    /**
     * <b>重复释放不得动到别人的预占</b> —— 下界拦不住这一类。
     *
     * <p>与上一个用例的区别只有一处：这里<b>另有一笔单占着库存</b>。上一个用例释放后 {@code locked} 恰好为 0，第二次释放被 {@code WHERE locked
     * >= qty} 挡下 —— 于是它验的是「下界生效」，而不是 「A 单不会释放掉 B 单的预占」。
     *
     * <p>后者才是真正的风险，且<b>下界完全拦不住</b>：{@code locked} 是该 {@code stock_key} 下所有订单共享的 计数器，A 单重复释放时它因 B
     * 单占用仍大于 0，谓词照常通过，结果是可售余量凭空多一份 —— 直接超卖。
     *
     * <p>拦住它的只能是 {@code benefit_task.uk_biz_type_op}：同一单同类任务只入队一条。本用例通过 「人工把已完成的任务打回
     * PENDING」模拟唯一键被绕过（真实场景是调度器重复领取），断言即便如此 也不会多减 —— 因为任务只有一条，重跑的还是它自己。
     */
    @Test
    void repeatedReleaseDoesNotStealAnotherOrdersLock() {
        // B 单：占着库存不动，全程不支付
        CreateTradeReq holder = newTradeReq("holder");
        benefitOrderService.createTrade(holder);
        assertThat(lockedOf()).isEqualTo(1);

        // A 单：支付失败并释放
        String bizNo = payFor("stealer", "FAILED");
        assertThat(lockedOf()).as("此刻两单各占一份").isEqualTo(2);
        runScheduler();
        assertThat(lockedOf()).as("A 释放后应只剩 B 的那份").isEqualTo(1);

        // 把 A 的释放任务打回重跑。此时 locked=1（B 的），下界 locked >= 1 照常通过 ——
        // 若每单幂等没落实，这一下就会把 B 的预占也释放掉
        benefitJdbc.update(
                "UPDATE benefit_task SET status = 'PENDING', lease_owner = NULL,"
                        + " next_time = NOW(3) WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.STOCK_RELEASE.name());
        runScheduler();

        assertThat(lockedOf()).as("B 单的预占不得被 A 的重复释放动到").isEqualTo(1);
        assertThat(availableOf()).as("可售余量不得凭空增加").isEqualTo(TOTAL_STOCK - 1);
    }

    /** 同一单同类库存任务只入队一条，由 {@code uk_biz_type_op} 保证。 */
    @Test
    void stockTaskIsEnqueuedOnlyOncePerOrder() {
        String bizNo = payFor("once", "SUCCESS");

        // 重复投递同一条支付通知
        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_once_2", "SUCCESS"));

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_CONSUME.name()))
                .as("同一单同类库存任务只应有一条")
                .isEqualTo(1);

        // op_no 必须是确定性键而非空串 —— 空串时唯一索引形同虚设
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_CONSUME.name()))
                .isEqualTo(bizNo + "_" + TaskType.STOCK_CONSUME.name());
    }

    // ------------------------------------------------------------------

    private String payFor(String tag, String payStatus) {
        CreateTradeReq req = newTradeReq(tag);
        String bizNo = benefitOrderService.createTrade(req).getBizNo();
        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_" + tag + "_1", payStatus));
        return bizNo;
    }

    private void assertTaskDone(String bizNo, TaskType type) {
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT status FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                type.name()))
                .as("%s 任务应已完成", type)
                .isEqualTo("DONE");
    }

    /** N 个线程同时执行同一段逻辑，全部跑完才返回。 */
    private void runConcurrently(int threads, ThrowingIntFunction body) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                int idx = i;
                tasks.add(() -> body.apply(idx));
            }
            List<Future<Void>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            // 逐个 get：invokeAll 不抛出任务内的异常，不 get 则断言失败会被静默吞掉
            for (Future<Void> f : futures) {
                f.get();
            }
        }
    }

    private interface ThrowingIntFunction {
        Void apply(int i) throws Exception;
    }

    private void setStock(int total) {
        benefitJdbc.update(
                "UPDATE marketing_stock SET total = ?, locked = 0, consumed = 0"
                        + " WHERE stock_key = ?",
                total,
                stockKey());
    }

    private static String stockKey() {
        return "sku:" + SKU_ID;
    }

    private int lockedOf() {
        return num(
                benefitJdbc, "SELECT locked FROM marketing_stock WHERE stock_key = ?", stockKey());
    }

    private int consumedOf() {
        return num(
                benefitJdbc,
                "SELECT consumed FROM marketing_stock WHERE stock_key = ?",
                stockKey());
    }

    private int availableOf() {
        return num(
                benefitJdbc,
                "SELECT total - locked - consumed FROM marketing_stock WHERE stock_key = ?",
                stockKey());
    }

    private int usedQtyOf(String userId) {
        Integer used =
                benefitJdbc.queryForObject(
                        "SELECT COALESCE(SUM(used_qty), 0) FROM user_purchase_quota"
                                + " WHERE user_id = ? AND sku_id = ?",
                        Integer.class,
                        userId,
                        SKU_ID);
        return used == null ? 0 : used;
    }
}
