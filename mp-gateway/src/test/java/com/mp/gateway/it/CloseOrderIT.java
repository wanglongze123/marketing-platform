package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.StockStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 订单关闭与 {@code CLOSING} 收敛，对应《分阶段方案》§5.7 退出标准 5、6、7。
 *
 * <p><b>关单的难点不在「怎么关」，在「结果未定时怎么办」。</b> 关单是外部 RPC，同样返回四分类：
 *
 * <ul>
 *   <li>判「关成了」→ 释放库存，而钱可能已经收了 —— 用户付了款却没有订单
 *   <li>判「没关成」→ 一直重试，库存被永久占着
 * </ul>
 *
 * <p>正确处置是进 {@code CLOSING} 中间态、<b>不释放任何东西</b>、由查单收敛。而中间态本身必须有出口， 否则它是个只进不出的黑洞 —— 这正是本类要验的东西。
 */
class CloseOrderIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private PayLedger payLedger;

    private static final int TOTAL_STOCK = 100;

    @BeforeEach
    void resetState() {
        injector.reset();
        payLedger.clear();
        benefitJdbc.update(
                "UPDATE marketing_stock SET total = ?, locked = 0, consumed = 0"
                        + " WHERE stock_key = ?",
                TOTAL_STOCK,
                "sku:" + SKU_ID);
        benefitJdbc.update("DELETE FROM user_purchase_quota");
    }

    @AfterEach
    void cleanUp() {
        injector.reset();
        payLedger.clear();
    }

    // ------------------------------------------------------------------
    // 正向：关单成功
    // ------------------------------------------------------------------

    /** 关单成功：置 {@code CLOSED}，同事务落释放任务，驱动后库存归还。 */
    @Test
    void closeSuccessReleasesStockAndQuota() {
        String bizNo = createOrder("closeOk");
        assertThat(lockedOf()).isEqualTo(1);

        assertThat(benefitOrderService.closeOrder(bizNo, "")).isEqualTo(RetStatus.SUCCESS);
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSED.name());

        // 释放任务与状态推进同事务 —— 置了 CLOSED 却没落任务，库存就永远占着
        assertThat(taskStatusOf(bizNo, TaskType.STOCK_RELEASE)).isEqualTo("PENDING");

        runScheduler();
        assertThat(lockedOf()).as("关单后应释放预占").isZero();
        assertThat(orderField("stock_status", bizNo)).isEqualTo(StockStatus.RELEASED.name());
    }

    /** 重复关单幂等（BR-B-18）：第二次直接返回，不再打扰支付方，也不重复释放。 */
    @Test
    void repeatedCloseIsIdempotent() {
        String bizNo = createOrder("closeTwice");

        benefitOrderService.closeOrder(bizNo, "");
        runScheduler();
        assertThat(lockedOf()).isZero();

        // 第二次关单
        benefitOrderService.closeOrder(bizNo, "");
        runScheduler();

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSED.name());
        assertThat(lockedOf()).as("重复关单不得重复释放").isZero();
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_RELEASE.name()))
                .as("释放任务只应有一条")
                .isEqualTo(1);
    }

    /**
     * <b>已支付的单拒绝关闭</b>（BR-B-16、错误码 {@code 1741}）。
     *
     * <p>关键在于「已支付」这个事实<b>只存在于支付方账本里</b> —— 平台的 {@code pay_status} 还是 {@code
     * WAIT_PAY}（通知尚未到达）。若关单不问支付方、只看平台状态，这单会被关掉，而钱已经收了。
     */
    @Test
    void paidOrderCannotBeClosed() {
        String bizNo = createOrder("paidClose");

        // 用户在收银台付了款：支付方账本变了，平台还不知道
        payLedger.markPaid(bizNo);
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());

        assertThatThrownBy(() -> benefitOrderService.closeOrder(bizNo, ""))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.ORDER_ALREADY_PAID);

        // 状态不变、库存不释放 —— 钱收了，这单还得等支付通知来推进
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(lockedOf()).as("已支付的单不得释放库存").isEqualTo(1);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_RELEASE.name()))
                .as("拒绝关闭时不得落释放任务")
                .isZero();
    }

    // ------------------------------------------------------------------
    // 退出标准 7：关单 UNKNOWN 进 CLOSING，且不释放库存
    // ------------------------------------------------------------------

    /**
     * <b>关单 RPC 返回 {@code UNKNOWN} 时进 {@code CLOSING}，此时不得释放库存与额度。</b>
     *
     * <p>结果未定就释放，等于把额度让给别人，而钱可能已经收了。这是 {@code CLOSING} 这个中间态 存在的全部理由 —— 若允许「不确定时先释放」，就根本不需要它。
     */
    @Test
    void closeUnknownEntersClosingWithoutReleasingStock() {
        String bizNo = createOrder("closeUnknown");
        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);

        assertThat(benefitOrderService.closeOrder(bizNo, "")).isEqualTo(RetStatus.UNKNOWN);

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSING.name());
        assertThat(lockedOf()).as("CLOSING 期间不得释放库存").isEqualTo(1);
        assertThat(orderField("stock_status", bizNo))
                .as("库存态仍为 LOCKED")
                .isEqualTo(StockStatus.LOCKED.name());

        // 查单任务与状态推进同事务 —— 没有它，这单永远停在 CLOSING
        assertThat(taskStatusOf(bizNo, TaskType.QUERY_CLOSE)).isEqualTo("PENDING");
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_RELEASE.name()))
                .as("CLOSING 期间不得落释放任务")
                .isZero();
    }

    /** {@code CLOSING} 查单确认未支付 → {@code CLOSED}，此时才释放。 */
    @Test
    void closingConvergesToClosedThenReleases() {
        String bizNo = createOrder("closingToClosed");
        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        benefitOrderService.closeOrder(bizNo, "");
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSING.name());

        // 支付方恢复正常，查单确认未支付
        injector.setPayMode(FaultMode.SUCCESS);
        makeAllDue(bizNo);
        runScheduler();

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSED.name());

        // 收敛后才落释放任务，再驱动一轮执行它
        makeAllDue(bizNo);
        runScheduler();
        assertThat(lockedOf()).as("确认未支付后才释放").isZero();
    }

    /**
     * <b>{@code CLOSING} 查单发现其实已支付 → {@code PAY_SUCCESS}，补建履约任务。</b>
     *
     * <p>「关单受理后用户其实付款成功了」必须能收敛。停在 {@code CLOSING} 的后果是已收款、订单挂着、 履约不发起 —— 且这单不在任何终态里，对账扫 {@code
     * PAY_SUCCESS} 与 {@code CLOSED} 都找不到它。
     */
    @Test
    void closingConvergesToPaySuccessWhenActuallyPaid() {
        String bizNo = createOrder("closingToPaid");
        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        benefitOrderService.closeOrder(bizNo, "");
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSING.name());

        // 用户在这期间付款成功
        payLedger.markPaid(bizNo);
        injector.setPayMode(FaultMode.SUCCESS);
        makeAllDue(bizNo);
        runScheduler();

        assertThat(orderField("pay_status", bizNo))
                .as("确认已支付应收敛到 PAY_SUCCESS，而非停在 CLOSING")
                .isEqualTo(PayStatus.PAY_SUCCESS.name());

        // 补建的是与支付回调完全相同的两条任务 —— 发生的是同一件事
        assertThat(
                        benefitJdbc.queryForList(
                                "SELECT task_type FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type IN (?, ?)",
                                String.class,
                                bizNo,
                                TaskType.GRANT.name(),
                                TaskType.STOCK_CONSUME.name()))
                .containsExactlyInAnyOrder(TaskType.GRANT.name(), TaskType.STOCK_CONSUME.name());
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_RELEASE.name()))
                .as("钱收了，库存该转消耗而非归还")
                .isZero();

        // 驱动到底：履约完成、库存转消耗
        makeAllDue(bizNo);
        runScheduler();
        assertThat(consumedOf()).as("应转为已消耗").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 退出标准 6：CLOSING 期间支付成功通知到达
    // ------------------------------------------------------------------

    /**
     * <b>关单受理后支付成功通知到达，必须放行。</b>
     *
     * <p>这是 V2 新增路径中<b>唯一「拦截了反而资损」</b>的一条。若 {@code PAY_SUCCESS} 的入边只认 {@code WAIT_PAY}，这条通知会被当成乱序
     * ACK 丢弃 —— 已收款、订单永停 {@code CLOSING}、履约任务不建、库存不转消耗，且对账前十三项无一覆盖。
     *
     * <p>乱序用例（下一条）验的是「该拦的拦住」，本条验的是「不该拦的放行」，缺任一侧都不完整。
     */
    @Test
    void paySuccessNotificationIsAcceptedWhileClosing() {
        String bizNo = createOrder("closingPaid");
        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        benefitOrderService.closeOrder(bizNo, "");
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSING.name());

        injector.setPayMode(FaultMode.SUCCESS);
        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_closingPaid_1", "SUCCESS"));

        assertThat(orderField("pay_status", bizNo))
                .as("CLOSING 期间的支付成功必须放行，否则钱已收而订单被关")
                .isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(taskStatusOf(bizNo, TaskType.GRANT)).isNotNull();
        assertThat(taskStatusOf(bizNo, TaskType.STOCK_CONSUME)).isNotNull();
    }

    // ------------------------------------------------------------------
    // 退出标准 5：支付回调乱序
    // ------------------------------------------------------------------

    /**
     * <b>先 {@code SUCCESS} 后 {@code CLOSED} 的乱序通知：迟到的 {@code CLOSED} 被条件更新拒绝。</b>
     *
     * <p>拦截靠的是条件更新而非幂等键 —— 两条通知携带不同 {@code notifySeq}，各自成键、各自留痕， 唯一索引本来就挡不住它们。
     *
     * <p>{@code CLOSED} 在参数校验处被放开（V2 PR-6），否则它在入口就被拒，执行不到条件更新，第二条 通知也不留痕。
     */
    @Test
    void lateClosedNotificationIsRejectedAfterPaySuccess() {
        String bizNo = createOrder("outOfOrder");

        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_ooo_1", "SUCCESS"));
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());

        // 迟到的 CLOSED
        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_ooo_2", "CLOSED"));

        assertThat(orderField("pay_status", bizNo))
                .as("已支付订单不得被迟到的 CLOSED 改回")
                .isEqualTo(PayStatus.PAY_SUCCESS.name());
        // 两条通知各自留痕：op_seq 取 notifySeq
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK")).as("两条通知都应留痕，即便第二条未推进状态").isEqualTo(2);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.STOCK_RELEASE.name()))
                .as("被拒的 CLOSED 不得落释放任务")
                .isZero();
    }

    /** {@code CLOSED} 通知正常到达（未支付的单）：置 CLOSED 并释放库存。 */
    @Test
    void closedNotificationOnWaitingOrderReleasesStock() {
        String bizNo = createOrder("closedNotify");

        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_cn_1", "CLOSED"));

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSED.name());
        runScheduler();
        assertThat(lockedOf()).as("关闭通知同样要释放库存").isZero();
    }

    /**
     * <b>转消耗与释放同时入队时，库存只被处置一次。</b>
     *
     * <p>这个场景在 PR-6 引入 {@code CLOSED} 的释放分支后才成立：{@code STOCK_CONSUME} 与 {@code STOCK_RELEASE} 的
     * {@code op_no} 不同，{@code uk_biz_type_op} 各挡各的，挡不住「两条都入队」。
     *
     * <p>真正拦住它的是 {@code stock_status} 的条件更新（PR-5）：两者都要求前置 {@code LOCKED}， 谁先执行谁推进，另一个 {@code
     * affected_rows = 0} 跳过。<b>若少了这道闸，同一单会先转消耗 再被释放，可售余量凭空多一份。</b>
     *
     * <p>用条件更新直接把主单打成「已支付」来构造：正常链路下支付态只能被推进一次，两条分支不会 同时落任务 —— 但那是<b>当前</b>状态机的性质，不是库存层的保证。这里验的是库存层。
     */
    @Test
    void consumeAndReleaseTasksTogetherSettleStockOnce() {
        String bizNo = createOrder("bothTasks");
        assertThat(lockedOf()).isEqualTo(1);

        // 两条任务同时入队：绕开状态机直接落，模拟「状态机日后放宽了入边」
        benefitJdbc.update(
                "INSERT INTO benefit_task (task_no, biz_no, task_type, op_no, status, next_time,"
                        + " retry_count, payload) VALUES (?, ?, ?, ?, 'PENDING', NOW(3), 0, '{}')",
                "TK_both_consume",
                bizNo,
                TaskType.STOCK_CONSUME.name(),
                bizNo + "_" + TaskType.STOCK_CONSUME.name());
        benefitJdbc.update(
                "INSERT INTO benefit_task (task_no, biz_no, task_type, op_no, status, next_time,"
                        + " retry_count, payload) VALUES (?, ?, ?, ?, 'PENDING', NOW(3), 0, '{}')",
                "TK_both_release",
                bizNo,
                TaskType.STOCK_RELEASE.name(),
                bizNo + "_" + TaskType.STOCK_RELEASE.name());

        runScheduler();
        runScheduler();

        // 预占一定被解掉了（两条任务都会解），关键是「解到哪去」只能有一个去向
        assertThat(lockedOf()).isZero();
        assertThat(orderField("stock_status", bizNo))
                .as("库存态是终态之一，且只推进过一次")
                .isIn(StockStatus.CONSUMED.name(), StockStatus.RELEASED.name());

        // 转消耗则 consumed=1、余量少一件；释放则 consumed=0、余量满。
        // 两者必居其一 —— 若两条任务都执行了实际的库存 UPDATE，会是 consumed=1 且余量也满，
        // 那一件库存凭空多出来了
        if (StockStatus.CONSUMED.name().equals(orderField("stock_status", bizNo))) {
            assertThat(consumedOf()).isEqualTo(1);
            assertThat(availableOf()).as("已卖掉的那件不得回到可售").isEqualTo(TOTAL_STOCK - 1);
        } else {
            assertThat(consumedOf()).isZero();
            assertThat(availableOf()).isEqualTo(TOTAL_STOCK);
        }
    }

    // ------------------------------------------------------------------
    // CLOSE_ORDER 任务
    // ------------------------------------------------------------------

    /**
     * 建单即落 {@code CLOSE_ORDER} 任务，且 {@code next_time} 落在未来。
     *
     * <p>与建单同事务 —— 分开则存在「单建了、关单任务没发出去」的缺口，那笔单永远不会关闭。
     */
    @Test
    void createOrderEnqueuesCloseTaskInTheFuture() {
        String bizNo = createOrder("closeTask");

        assertThat(taskStatusOf(bizNo, TaskType.CLOSE_ORDER)).isEqualTo("PENDING");
        // next_time 应在未来（支付有效期后），否则调度器立刻就把未付款的单关掉了
        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_time) FROM benefit_task"
                                        + " WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.CLOSE_ORDER.name()))
                .as("关单任务应在支付有效期后才到期")
                .isGreaterThan(60);

        // 未到期时调度器不该领它 —— 领了就是把刚下单的单关掉
        runScheduler();
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());
    }

    /** 到期后 {@code CLOSE_ORDER} 任务执行，订单关闭、库存释放。 */
    @Test
    void dueCloseTaskClosesTheOrder() {
        String bizNo = createOrder("closeDue");

        makeAllDue(bizNo);
        runScheduler();

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.CLOSED.name());
        makeAllDue(bizNo);
        runScheduler();
        assertThat(lockedOf()).isZero();
    }

    /** 已支付的单，到期的关单任务不得把它关掉。 */
    @Test
    void dueCloseTaskDoesNotClosePaidOrder() {
        String bizNo = createOrder("closeDuePaid");
        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_cdp_1", "SUCCESS"));

        makeAllDue(bizNo);
        runScheduler();

        assertThat(orderField("pay_status", bizNo))
                .as("已支付的单不得被超时任务关闭")
                .isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(consumedOf()).as("库存应转消耗而非释放").isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private String createOrder(String tag) {
        CreateTradeReq req = newTradeReq(tag);
        return benefitOrderService.createTrade(req).getBizNo();
    }

    private void makeAllDue(String bizNo) {
        benefitJdbc.update(
                "UPDATE benefit_task SET next_time = NOW(3) WHERE biz_no = ? AND status ="
                        + " 'PENDING'",
                bizNo);
    }

    private String taskStatusOf(String bizNo, TaskType type) {
        return benefitJdbc
                .queryForList(
                        "SELECT status FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                        String.class,
                        bizNo,
                        type.name())
                .stream()
                .findFirst()
                .orElse(null);
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

    private int consumedOf() {
        return num(
                benefitJdbc,
                "SELECT consumed FROM marketing_stock WHERE stock_key = ?",
                "sku:" + SKU_ID);
    }
}
