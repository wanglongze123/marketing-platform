package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
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
 * <p><b>0 超卖的判据是「售出数 == 库存总量」，不是「没报错」。</b> 超卖的失效形态是全部成功 ——
 * 每个请求都拿到订单、都没有异常，只是卖出去的比有的多。故每个并发用例都同时断言三处：成功数、 {@code locked + consumed} 的终值、以及订单表实际行数。
 *
 * <p>并发用例用真实线程池压同一行，不是串行调用 N 次：串行永远测不出「两个线程同时读到还剩 1」。
 */
class StockAndQuotaIT extends AbstractMySqlIT {

    @org.springframework.beans.factory.annotation.Autowired private PayLedger payLedger;

    @org.springframework.beans.factory.annotation.Autowired private ProviderLedger providerLedger;

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
        // 限额也要还原：额度用例会临时改它，不还原则后续用例（含别的测试类）读到的是被改过的值
        benefitJdbc.update(
                "UPDATE benefit_sku SET purchase_limit_qty = ? WHERE sku_id = ?",
                LIMIT_QTY,
                SKU_ID);
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
     * <p>转消耗只是把「占着」变成「卖掉了」，不释放余量。若实现成 {@code locked -= n} 而忘了 {@code consumed += n}，可售余量会多出一份 ——
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
     * 派生同 {@code clientReqNo}，第二个类跑到时命中幂等返回原单，根本不会扣库存，断言随之失准。 <b>tag 在整个 IT 套件里必须唯一</b>，不只是在本类里。
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
     * 单占用仍大于 0，谓词照常通过，结果是可售余量多出一份 —— 直接超卖。
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
        assertThat(availableOf()).as("可售余量不得增加").isEqualTo(TOTAL_STOCK - 1);
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

        // op_no 必须是确定性键而非空串 —— 空串时唯一索引不起作用
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_CONSUME.name()))
                .isEqualTo(bizNo + "_" + TaskType.STOCK_CONSUME.name());
    }

    // ------------------------------------------------------------------
    // 额度的每单幂等（PR-8）
    // ------------------------------------------------------------------

    /**
     * <b>从未占用额度的单，释放时不得动到同用户另一笔单的额度</b>（PR-8 补，补之前是真实缺陷）。
     *
     * <p>与 {@code repeatedReleaseDoesNotStealAnotherOrdersLock} 是同一类缺陷的另一半：那条验库存的 {@code
     * locked}，这条验额度的 {@code used_qty}。两个计数器同构 —— 都按聚合维度共享，都有一个 拦不住跨单误还的下界。
     *
     * <p><b>触发条件是「限额中途变过」</b>，这也是它此前没被发现的原因：既有用例里限额恒为 2，每一单
     * 都占额度，于是「无条件返还」与「按本单是否占用返还」表现完全相同。运营调限额是常规动作， 一变就显形 ——
     *
     * <ol>
     *   <li>限额置 0（不限购），A 单下单：库存占了，额度<b>没有</b>行
     *   <li>运营调限额为 2，B 单下单：建额度行，{@code used_qty = 1}
     *   <li>A 单支付失败释放：若无条件调 {@code tryRelease}，下界 {@code used_qty >= 1} 因 B 占着而 照常通过 —— B 的额度被 A
     *       还掉了
     * </ol>
     *
     * <p>修法是给主单加 {@code quota_status}，与 {@code stock_status} 分列。<b>不能共用一列</b>：库存对
     * 每一单都预占，额度只在配了限购时才扣，共用则不限购的单同样以 {@code LOCKED} 进入释放分支。
     */
    @Test
    void releaseOfAnOrderThatNeverHeldQuotaDoesNotStealAnothers() {
        String userId = "U_quotaGhost";

        // ① 限额置 0 —— A 单不占额度
        setPurchaseLimit(0);
        String bizA = createOrderFor(userId, "quotaGhostA");
        assertThat(usedQtyOf(userId)).as("不限购时不该建额度行").isZero();
        assertThat(quotaStatusOf(bizA)).as("没占额度的单，额度态应为 NONE").isEqualTo("NONE");

        // ② 运营调高限额 —— B 单建行并占用
        setPurchaseLimit(LIMIT_QTY);
        createOrderFor(userId, "quotaGhostB");
        assertThat(usedQtyOf(userId)).isEqualTo(1);

        // ③ A 单支付失败并释放
        benefitOrderService.payCallback(
                newPayCallback(bizA, "PAY1_" + bizA, "NS_quotaGhost_1", "FAILED"));
        runScheduler();

        assertThat(usedQtyOf(userId)).as("A 从未占用额度，释放不得动到 B 的那一份").isEqualTo(1);
        assertThat(quotaStatusOf(bizA)).as("跳过返还后额度态不变").isEqualTo("NONE");
        // 库存那一半照常释放 —— 两道闸各判各的，A 确实占了库存
        assertThat(stockStatusOf(bizA)).isEqualTo("RELEASED");
        assertThat(lockedOf()).as("A 的库存应已释放，只剩 B 的那份").isEqualTo(1);
    }

    /** 占过额度的单重复释放，同样不得多还 —— 每单幂等对额度侧一并成立。 */
    @Test
    void repeatedReleaseReturnsQuotaOnlyOnce() {
        String userId = "U_quotaTwice";
        String bizNo = createOrderFor(userId, "quotaTwiceA");
        // 同用户另一笔单占着额度，使下界 used_qty >= 1 无法充当闸
        createOrderFor(userId, "quotaTwiceB");
        assertThat(usedQtyOf(userId)).isEqualTo(2);

        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_quotaTwice_1", "FAILED"));
        runScheduler();
        assertThat(usedQtyOf(userId)).as("A 释放后应只剩 B 占的那份").isEqualTo(1);

        // 把释放任务打回重跑
        benefitJdbc.update(
                "UPDATE benefit_task SET status = 'PENDING', lease_owner = NULL,"
                        + " next_time = NOW(3) WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.STOCK_RELEASE.name());
        runScheduler();

        assertThat(usedQtyOf(userId)).as("重复释放不得吃掉 B 的额度").isEqualTo(1);
        assertThat(quotaStatusOf(bizNo)).isEqualTo("RELEASED");
    }

    /** 支付成功时额度不返还，{@code quota_status} 停在 {@code LOCKED}（技术方案 §3.4 的不对称）。 */
    @Test
    void paySuccessDoesNotReturnQuota() {
        String userId = "U_quotaKeep";
        String bizNo = createOrderFor(userId, "quotaKeep");
        assertThat(usedQtyOf(userId)).isEqualTo(1);

        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_quotaKeep_1", "SUCCESS"));
        runScheduler();

        assertThat(usedQtyOf(userId)).as("买了就算用掉，成交不返还额度").isEqualTo(1);
        assertThat(quotaStatusOf(bizNo)).as("成交后额度态停在 LOCKED —— 这一份不会归还").isEqualTo("LOCKED");
        assertThat(stockStatusOf(bizNo)).isEqualTo("CONSUMED");
    }

    // ------------------------------------------------------------------

    private String createOrderFor(String userId, String tag) {
        CreateTradeReq req = newTradeReq(tag);
        req.setUserId(userId);
        req.setConsultToken(consultToken(userId, ACTIVITY_ID, SKU_ID));
        return benefitOrderService.createTrade(req).getBizNo();
    }

    private void setPurchaseLimit(int qty) {
        benefitJdbc.update(
                "UPDATE benefit_sku SET purchase_limit_qty = ? WHERE sku_id = ?", qty, SKU_ID);
    }

    private String quotaStatusOf(String bizNo) {
        return str(
                benefitJdbc,
                "SELECT quota_status FROM play_biz_record WHERE play_biz_record_no = ?",
                bizNo);
    }

    private String stockStatusOf(String bizNo) {
        return str(
                benefitJdbc,
                "SELECT stock_status FROM play_biz_record WHERE play_biz_record_no = ?",
                bizNo);
    }

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

    // ------------------------------------------------------------------
    // 多份购买（放开 quantity=1 守卫）
    // ------------------------------------------------------------------

    /**
     * <b>买 N 份收 N 份钱</b>。
     *
     * <p>这是放开 {@code quantity=1} 守卫后最危险的一处：{@code order_amount} 若不乘份数，就是买 3 份 收 1
     * 份钱，<b>而没有任何下游能发现</b> —— 支付方按 {@code order_amount} 收款，金额校验拿 {@code pay_amount} 与它比，两边一致；对账第 5
     * 项比的也是这两个数。库存与限购却按 3 份扣， 于是货发 3 份、钱收 1 份，账面处处自洽。
     *
     * <p><b>单价从 seed 读，不写死</b>：写死则改 seed 时本条静默失效。
     */
    @Test
    void orderAmountMultipliesByQuantity() {
        long unitPrice =
                num(benefitJdbc, "SELECT sale_price FROM benefit_sku WHERE sku_id = ?", SKU_ID);
        relaxLimitTo(10);

        String bizNo = benefitOrderService.createTrade(newTradeReq("mq_amount", 3)).getBizNo();

        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT order_amount FROM play_biz_record"
                                        + " WHERE play_biz_record_no = ?",
                                bizNo))
                .as("应付须为单价 × 份数 —— 漏乘即买 3 份收 1 份钱，且账面自洽无人发现")
                .isEqualTo(unitPrice * 3);
        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT quantity FROM play_biz_record"
                                        + " WHERE play_biz_record_no = ?",
                                bizNo))
                .isEqualTo(3);
    }

    /**
     * <b>买 N 份，库存与限购各扣 N</b>，且支付后转消耗也是 N。
     *
     * <p>库存四处（预占、转消耗、释放、回补）本就传 {@code quantity}，本条是回归保护 —— 它们与金额
     * 那处曾处于相反状态：一边按份数、一边按单数，而<b>两边都不报错</b>。
     */
    @Test
    void stockAndQuotaDeductByQuantity() {
        relaxLimitTo(10);
        int before = availableOf();

        String bizNo = benefitOrderService.createTrade(newTradeReq("mq_stock", 3)).getBizNo();

        assertThat(lockedOf()).as("预占须按份数").isEqualTo(3);
        assertThat(availableOf()).as("可售余量须减 3").isEqualTo(before - 3);
        assertThat(usedQtyOf("U_mq_stock")).as("限购额度须按份数扣").isEqualTo(3);

        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_mq", "N1", "SUCCESS"));
        runScheduler();

        assertThat(consumedOf()).as("转消耗须按份数").isEqualTo(3);
        assertThat(lockedOf()).as("预占须已归还").isZero();
    }

    /**
     * <b>限购按份数判，不按单数</b>：限 2 份时，一单买 3 份直接被拒。
     *
     * <p>这条确定了 {@code purchase_limit_qty} 的语义 —— 它是「最多买几份」而非「最多下几单」。 两种口径下 {@code used_qty}
     * 的累加方式相反，而对账第 15 项（{@code SUM(quantity)}）已按前者实现。
     *
     * <p>拒绝时须<b>不留痕</b>：额度未扣、库存未占。只断言抛异常的话，一个「先占后判」的实现照样通过， 而那会让一次被拒的下单永久占着库存。
     */
    @Test
    void quotaLimitCountsSharesNotOrders() {
        int availableBefore = availableOf();

        assertThatThrownBy(() -> benefitOrderService.createTrade(newTradeReq("mq_overlimit", 3)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("限 2 份时一单买 3 份须拒 —— 按单数判则它会被放行")
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED);

        assertThat(usedQtyOf("U_mq_overlimit")).as("被拒时不得扣额度").isZero();
        assertThat(availableOf()).as("被拒时不得占库存 —— 先占后判会让被拒的单永久占着").isEqualTo(availableBefore);
    }

    /** 份数下界与上界：0 / 负数 / 超过上限一律拒，且不建单。 */
    @Test
    void quantityOutOfRangeIsRejected() {
        for (int bad : new int[] {0, -1, 100}) {
            assertThatThrownBy(
                            () -> benefitOrderService.createTrade(newTradeReq("mq_bad" + bad, bad)))
                    .as("份数 %s 须被拒", bad)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_PARAM);
        }
        assertThat(availableOf()).as("非法份数不得占库存").isEqualTo(TOTAL_STOCK);
    }

    /**
     * <b>买 N 份，供应方收到的份数也是 N</b>。
     *
     * <p><b>判据取供应方账本，不取平台记录</b>：平台侧的履约明细只有状态没有数量，对账十五项也不 比对这个维度 —— 于是一个把 {@code qty} 写死成 1
     * 的实现，在平台侧看来处处正常（明细 {@code SUCCESS}、账本一条、金额还收足了 3 份的钱），<b>只有供应方数得清到底发了几份</b>。 这与「重复发奖 = 0
     * 取下游账本」是同一条理由。
     *
     * <p>它与 {@link #orderAmountMultipliesByQuantity} 构成一对：那条防「收少了」，这条防「发少了」。
     * <b>两条缺任一条，另一条都会让缺陷看起来「账平了」</b> —— 收 1 份钱发 1 份货是自洽的， 收 3 份钱发 3 份货也是自洽的，只有收 3 发 1
     * 才是资损，而它需要两条一起才测得出来。
     */
    @Test
    void providerReceivesOrderQuantity() {
        relaxLimitTo(10);
        String bizNo = benefitOrderService.createTrade(newTradeReq("mq_grant", 3)).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_mqg", "N1", "SUCCESS"));
        runScheduler();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());

        // 逐供应方核对：一次调用一把 opNo，份数应为订单份数
        List<String> opNos =
                benefitJdbc.queryForList(
                        "SELECT DISTINCT grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? AND grant_op_no IS NOT NULL",
                        String.class,
                        bizNo);
        assertThat(opNos).as("前提：该单须已产生发放调用").isNotEmpty();
        for (String opNo : opNos) {
            assertThat(providerLedger.grantedQty(opNo))
                    .as("供应方 %s 收到的份数须等于订单份数 —— 写死 1 即收 3 份钱发 1 份货", opNo)
                    .isEqualTo(3);
        }
    }

    /**
     * 把限购放宽到够本用例买 N 份。
     *
     * <p>seed 限的是 2 份，而多份用例要买 3 份 —— <b>不放宽则它们撞的是限购，测不到金额与库存</b> （首版即如此，实测两条同时报「超出限购额度」）。{@code
     * restoreStock} 会在每个用例后还原。
     */
    private void relaxLimitTo(int limitQty) {
        benefitJdbc.update(
                "UPDATE benefit_sku SET purchase_limit_qty = ? WHERE sku_id = ?", limitQty, SKU_ID);
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
