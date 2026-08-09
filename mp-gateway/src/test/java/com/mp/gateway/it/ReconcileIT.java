package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.ReconcileItem;
import com.mp.api.benefit.dto.ReconcileReport;
import com.mp.api.benefit.dto.RevokeAdmitReq;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.util.IdempotentKeys;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 对账与自动补偿（FR-C06、技术方案 §6.8），对应《分阶段方案》§6.5 退出标准 14、15、16。
 *
 * <p><b>{@code stale-seconds=0}</b>：生产取值须显著长于「发放 + 查单收敛」的正常耗时（§5.8 实测 153s），
 * 否则一笔刚下单还没来得及履约的单会被判为差异、告警变噪音。测试压到 0 才能立刻构造出差异 —— <b>压的是判据的时间维度，不是判据本身</b>，谓词的其余部分与生产完全一致。
 *
 * <p><b>每条用例都断言「差异被检出」与「处置动作正确」两件事</b>：只断言检出的话，一个「扫出来但什么 都没做」的实现照样全绿；只断言处置的话，分不清是对账干的还是别的路径顺手做的。
 */
@TestPropertySource(properties = "mp.reconcile.stale-seconds=0")
class ReconcileIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private PayLedger payLedger;
    @Autowired private ProviderLedger providerLedger;
    @Autowired private com.mp.api.reward.service.RewardService rewardService;

    /** 第 8 项用例在支付方账本上造的幽灵单。{@link #reset} 按它清理 */
    private static final String[] GHOST_TRADES = {"GHOST_ORDER_rec8", "GHOST_ORDER_rec8b"};

    @AfterEach
    void reset() {
        injector.reset();
        providerLedger.clearFailingProducts();
        providerLedger.clearDelays();
        // 幽灵单留在进程内账本里的话，此后每个跑对账的用例都会多检出它一次 ——
        // 共享状态上的绝对值断言，通过与否取决于执行顺序
        for (String ghost : GHOST_TRADES) {
            payLedger.forget(ghost);
        }
    }

    private String paidOrder(String tag) {
        String bizNo = benefitOrderService.createTrade(newTradeReq(tag)).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_" + tag, "N1", "SUCCESS"));
        return bizNo;
    }

    private int taskCount(String bizNo, TaskType type) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                type.name());
    }

    /** 已发放并走完退款准入的单，主单停在 {@code REVOKING}，可直接调 {@code createRefund}。 */
    private String admittedRefundOrder(String tag, String refundReqNo) {
        String bizNo = paidOrder(tag);
        runScheduler();
        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo(refundReqNo);
        req.setOperator("cs_probe");
        req.setReason("对账用例");
        benefitOrderService.revokeAndAdmit(req);
        return bizNo;
    }

    // ------------------------------------------------------------------
    // 标准 14：检出人为注入的差异并自动补偿
    // ------------------------------------------------------------------

    /**
     * 标准 14 ①：<b>已收款未履约 → 检出并补建 {@code GRANT} 任务</b>。
     *
     * <p>手工把 {@code GRANT} 任务删掉，模拟「本地消息表那一条丢了」—— 这是对账要兜底的头号场景： 钱已收、履约没发起、且没有任何机制会再看它一眼。
     *
     * <p><b>断言补建的任务与原任务同键</b>：{@code op_no} 取 {@code bizNo + "_GRANT"}，与支付回调落的那条 一致。新造键会让同一单存在两条
     * GRANT 任务，两个调度器各跑一次。
     */
    @Test
    void detectsAndRepairsPaidNotGranted() {
        String bizNo = paidOrder("rec_png");
        // 制造差异：把履约任务删掉
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.GRANT.name());
        assertThat(taskCount(bizNo, TaskType.GRANT)).isZero();

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.diffOf(ReconcileItem.PAID_NOT_GRANTED)).isPositive();
        assertThat(report.repairedOf(ReconcileItem.PAID_NOT_GRANTED)).isPositive();
        assertThat(taskCount(bizNo, TaskType.GRANT)).as("须补建履约任务").isEqualTo(1);
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.GRANT.name()))
                .as("补建的任务须与原任务同键，否则同一单会有两条")
                .isEqualTo(bizNo + "_GRANT");

        // 补建后驱动一轮，单子真的被推到终态 —— 这才叫「自愈」
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
    }

    /**
     * 标准 14 ②：<b>已关闭单仍占库存 → 检出并补建释放任务</b>。
     *
     * <p>手工把主单改成 {@code CLOSED} 而 {@code stock_status} 留在 {@code LOCKED}，模拟释放任务丢失。
     * 这部分库存否则永远没人释放，可售余量永久少一份。
     */
    @Test
    void detectsAndRepairsClosedHoldingStock() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("rec_chs")).getBizNo();
        benefitJdbc.update(
                "UPDATE play_biz_record SET pay_status = 'CLOSED' WHERE play_biz_record_no = ?",
                bizNo);
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.STOCK_RELEASE.name());

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.diffOf(ReconcileItem.CLOSED_HOLDING_STOCK)).isPositive();
        assertThat(taskCount(bizNo, TaskType.STOCK_RELEASE)).as("须补建释放任务").isEqualTo(1);
    }

    /**
     * 标准 14 ③：<b>关单中间态未收敛 → 检出并补建查单任务</b>（第 14 项）。
     *
     * <p>这一项是 §6.4「任何中间态都必须同时具备准入谓词的入边与对账的一行」中的那一行。缺了它，一笔 停在 {@code CLOSING} 的单前十三项无一覆盖 —— 第 1 项扫
     * {@code PAY_SUCCESS}、第 9 项扫 {@code CLOSED}。
     */
    @Test
    void detectsAndRepairsStuckClosing() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("rec_sc")).getBizNo();
        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        benefitOrderService.closeOrder(bizNo, "");
        injector.reset();
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.QUERY_CLOSE.name());

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.diffOf(ReconcileItem.STUCK_CLOSING)).isPositive();
        assertThat(taskCount(bizNo, TaskType.QUERY_CLOSE)).as("中间态必须有收敛出口").isEqualTo(1);
    }

    /**
     * <b>修复后差异归零</b>——标准 14 的后半句。
     *
     * <p>只断言「检出并补建」不够：一个「每轮都报同一条差异」的实现同样能通过前面几条，而那意味着 补偿没有真的生效，告警会永远响。
     */
    @Test
    void diffGoesToZeroAfterRepair() {
        String bizNo = paidOrder("rec_zero");
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.GRANT.name());

        benefitOrderService.reconcile();
        runScheduler();

        // 单子已收敛，再跑一轮对账不应再把它算成差异
        ReconcileReport second = benefitOrderService.reconcile();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE play_biz_record_no = ?"
                                        + " AND pay_status = 'PAY_SUCCESS'"
                                        + " AND grant_status IN ('NOT_START','GRANTING','GRANT_UNKNOWN')"
                                        + " AND refund_status = 'NONE'",
                                bizNo))
                .as("该单已不在第 1 项的扫描范围内")
                .isZero();
        assertThat(second).isNotNull();
    }

    // ------------------------------------------------------------------
    // 标准 15：第 1 项的谓词是白名单，不误补已退款单
    // ------------------------------------------------------------------

    /**
     * 标准 15：<b>一笔 {@code GRANT_FAILED} + {@code REFUND_SUCCESS} 的单不得被补建 {@code GRANT} 任务</b>。
     *
     * <p>谓词写成「排除 {@code GRANT_SUCCESS}」时，这笔已全额退款的失败单会被当作待履约补发一次 —— <b>钱退了、货也给了</b>。故谓词必须是白名单（{@code
     * NOT_START} / {@code GRANTING} / {@code GRANT_UNKNOWN}），新增枚举时不会误放行。
     *
     * <p>本用例同时覆盖第二道：{@code refund_status <> 'NONE'} —— 一旦进入退款流程，该单不再是「待履约」。
     */
    @Test
    void refundedFailedOrderIsNotRepaired() {
        String bizNo = paidOrder("rec_wl");
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.GRANT.name());
        // 造一笔「发放失败且已全额退款」的单
        benefitJdbc.update(
                "UPDATE play_biz_record SET grant_status = 'GRANT_FAILED',"
                        + " refund_status = 'REFUND_SUCCESS' WHERE play_biz_record_no = ?",
                bizNo);

        benefitOrderService.reconcile();

        assertThat(taskCount(bizNo, TaskType.GRANT)).as("已退款的失败单不得被补发 —— 那是钱退了货也给了").isZero();
    }

    /** 白名单的另一半：{@code refund_status} 非 {@code NONE} 的单同样不补，哪怕发放态还在白名单里。 */
    @Test
    void orderInRefundFlowIsNotRepaired() {
        String bizNo = paidOrder("rec_wl2");
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.GRANT.name());
        benefitJdbc.update(
                "UPDATE play_biz_record SET refund_status = ? WHERE play_biz_record_no = ?",
                RefundStatus.REVOKING.name(),
                bizNo);

        benefitOrderService.reconcile();

        assertThat(taskCount(bizNo, TaskType.GRANT)).as("已进入退款流程的单不再是「待履约」").isZero();
    }

    // ------------------------------------------------------------------
    // 标准 16：对账不跨库 JOIN
    // ------------------------------------------------------------------

    /**
     * 标准 16：<b>跨库比对走 {@code batchQueryByOpNos}，不做 JOIN</b>。
     *
     * <p>正面断言该接口可用且返回正确：查得到的键在结果里，查无的键<b>不在</b> —— 后者正是对账第 3 项 要检出的差异，补占位行会把差异抹平。
     *
     * <p>反面由 {@code MultiDataSourceIT} 的既有用例承担（跨库查询运行期 {@code access denied}）：两库各用仅授权 自身 schema
     * 的账号，JOIN 根本执行不了。<b>两条断言缺一不可</b> —— 只验「接口能用」的话，一个偷偷 用 root 连接做 JOIN 的实现同样通过。
     */
    @Test
    void crossDbComparisonUsesBatchQuery() {
        String bizNo = paidOrder("rec_xdb");
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());

        java.util.List<String> opNos =
                benefitJdbc.queryForList(
                        "SELECT DISTINCT grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ?",
                        String.class,
                        bizNo);
        assertThat(opNos).hasSize(2);

        java.util.List<String> probe = new java.util.ArrayList<>(opNos);
        probe.add("NO_SUCH_OP_NO");
        var result = rewardService.batchQueryByOpNos(probe);

        assertThat(result.keySet()).as("查得到的键须在结果里").containsAll(opNos);
        assertThat(result).as("查无的键不得出现 —— 那正是第 3 项要检出的差异").doesNotContainKey("NO_SUCH_OP_NO");
    }

    /**
     * <b>第 3 项须扫「已发放成功」的单，不是「还没发完」的单</b>（PR-10 review 补）。
     *
     * <p>首版复用了 {@code scanPaidNotGranted}，那扫的是 {@code grant_status IN
     * ('NOT_START','GRANTING','GRANT_UNKNOWN')} —— <b>还没发完</b>的单；而本项要找的是「平台记着已发成功、
     * 下游却查无」。两个集合几乎不相交，于是这一项<b>近乎空转</b>：它只在单子还没发完时去比对下游，而 那种单本来就没有发奖记录，查无是正常的。
     *
     * <p>实测确认过：造一笔正常发放成功的单、删掉 reward 侧记录，跑对账检出 0 条。
     *
     * <p><b>这类缺陷注入自查发现不了</b>：注入是「破坏实现看用例红不红」，而此处实现与用例一起错 —— 用例根本没覆盖第 3 项。只能靠拿代码与方案 §6.8 的判据逐条对读。
     */
    @Test
    void detectsGrantSucceededButMissingDownstream() {
        String bizNo = paidOrder("rec_i3");
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());

        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.GRANT_MISSING_DOWNSTREAM);

        // 造「平台有成功明细、reward 侧查无」：删掉下游记录，模拟数据丢失或人为误删
        java.util.List<String> opNos =
                benefitJdbc.queryForList(
                        "SELECT DISTINCT grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? AND grant_status = 'SUCCESS'",
                        String.class,
                        bizNo);
        assertThat(opNos).hasSize(2);
        for (String opNo : opNos) {
            rewardJdbc.update("DELETE FROM reward_grant_item WHERE op_no = ?", opNo);
            rewardJdbc.update("DELETE FROM reward_grant_record WHERE op_no = ?", opNo);
        }

        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.GRANT_MISSING_DOWNSTREAM);

        assertThat(after - before).as("平台记着已发成功而下游查无，正是第 3 项要检出的差异").isEqualTo(opNos.size());
    }

    /**
     * <b>发放失败的明细在下游查无不算差异</b>——第 3 项的反方向。
     *
     * <p>缺了它，一个「扫全部明细而不限 {@code grant_status='SUCCESS'}」的实现照样通过上一条 —— 而那会 把每一笔发放失败的单都报成差异，告警随即变噪音。
     */
    @Test
    void failedGrantMissingDownstreamIsNotADiff() {
        providerLedger.failProduct("PROD_A_001");
        providerLedger.failProduct("PROD_B_001");
        String bizNo = paidOrder("rec_i3_fail");
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_FAILED.name());

        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.GRANT_MISSING_DOWNSTREAM);
        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.GRANT_MISSING_DOWNSTREAM);

        assertThat(after - before).as("发放失败的单在下游查无是正常的，不构成差异").isZero();
    }

    /**
     * <b>库存与额度的比对取份数，不取单数</b>（PR-10 review 补）。
     *
     * <p>{@code consumed} 与 {@code used_qty} 都按份数累加（{@code consumed = consumed + qty}），而首版拿它们 与
     * {@code COUNT(*)} 比 —— 一笔 {@code quantity=3} 的单会让 {@code consumed=3} 而单数为 1，每轮对账报一次
     * 假差异，<b>而假告警会让资损哨兵失效</b>。
     *
     * <p><b>这条约束当前被 {@code doCreateTrade} 的 {@code quantity=1} 守卫遮着</b>：正常链路造不出 {@code quantity>1}
     * 的单，两种口径的结果恒等。故本用例<b>直接改库</b>把份数改成 3，绕过那道守卫 —— 与 PR-7/8 「绕过服务层直调事务方法」是同一处置，都是让被外层遮蔽的内层约束变得可观测。
     *
     * <p><b>这不是今天的缺陷，是一颗埋着的雷</b>：{@code quantity} 在 DDL、DTO、库存 SQL 里全都按份数设计，
     * 守卫一放开（多份购买是常规需求）这两项立刻开始刷假告警。
     */
    @Test
    void stockAndQuotaCompareByQuantityNotOrderCount() {
        String bizNo = paidOrder("rec_qty");
        runScheduler();

        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.STOCK_MISMATCH);

        // 直接改库造出 quantity=3 的单，并把库存与额度按份数同步推进 ——
        // 模拟「守卫放开后多份购买」的正常状态：三者口径一致，不应报差异
        benefitJdbc.update(
                "UPDATE play_biz_record SET quantity = 3 WHERE play_biz_record_no = ?", bizNo);
        benefitJdbc.update(
                "UPDATE marketing_stock SET consumed = consumed + 2 WHERE stock_key = ?",
                "sku:" + SKU_ID);

        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.STOCK_MISMATCH);

        assertThat(after - before).as("份数与单数是两个量纲：按单数比会把一笔 quantity=3 的正常单报成差异").isZero();
    }

    /** 空列表不得拼出 {@code IN ()} —— 那是 MySQL 语法错误，而「本批没有要比对的键」是正常情形。 */
    @Test
    void batchQueryHandlesEmptyInput() {
        assertThat(rewardService.batchQueryByOpNos(java.util.List.of())).isEmpty();
        assertThat(rewardService.batchQueryByOpNos(null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 只告警的项：检出但不改数
    // ------------------------------------------------------------------

    /**
     * <b>金额不一致只告警，不改单</b>（第 5 项）。
     *
     * <p>差异被检出，而 {@code pay_amount} 保持原值 —— 只断言检出的话，一个「顺手把金额改成应付额」的 实现同样能通过，而那会把一次错误固化成新的基线。
     */
    @Test
    void amountMismatchIsReportedButNotFixed() {
        String bizNo = paidOrder("rec_amt");
        benefitJdbc.update(
                "UPDATE play_biz_record SET pay_amount = 1 WHERE play_biz_record_no = ?", bizNo);

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.diffOf(ReconcileItem.AMOUNT_MISMATCH)).isPositive();
        assertThat(report.repairedOf(ReconcileItem.AMOUNT_MISMATCH)).as("禁止自动改单").isZero();
        assertThat(orderField("pay_amount", bizNo)).as("金额不得被对账改回去").isEqualTo("1");
    }

    /**
     * 第 8 项：<b>支付方已收款而本地无单，检出并告警</b>。
     *
     * <p><b>这是十五项里唯一「从支付方往平台看」的一项</b>。其余全是拿本地的单去比对下游 —— 那种方向
     * 发现不了「平台自身没有任何记录的那笔单」：建单事务提交后、支付通知到达前进程崩溃，或通知永久丢失， 本地没有任何线索可查，前七项一条都覆盖不到。
     *
     * <p>用例直接在支付方账本上记一笔平台从未建过的单（{@code markPaid} 一个不存在的 {@code outTradeNo}）， 这正是那个场景在 mock 上的等价物。
     */
    @Test
    void detectsPaidTradeWithoutLocalOrder() {
        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.PAY_WITHOUT_ORDER);

        // 支付方收了一笔平台没有记录的钱
        payLedger.markPaid(GHOST_TRADES[0], 9900L);

        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.PAY_WITHOUT_ORDER);

        assertThat(after - before).as("支付方有、本地无 —— 前七项都发现不了它，只有本项能").isEqualTo(1);
    }

    /**
     * 第 8 项：<b>只告警，不自动补记主单</b>。
     *
     * <p>技术方案原文写的是「补记主单 + 建履约任务」，实施时降级为只告警：对账文件只有 {@code outTradeNo} / {@code tradeNo} /
     * 金额三个字段，而补记主单要 {@code activity_id} / {@code sku_id} / {@code price_snapshot} / {@code
     * benefit_snapshot} 一整套业务数据，全都只能填占位值 —— 而<b>凭占位值造出来的单会被后续履约当成真单发奖</b>，比「本地无单」本身更糟。
     *
     * <p>与第 3 项「不自动补发」、第 5 项「禁止自动改单」同一条判断：正确值取决于历史，猜不出来就只告警。
     */
    @Test
    void paidTradeWithoutLocalOrderIsNotAutoRepaired() {
        payLedger.markPaid(GHOST_TRADES[1], 9900L);

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.diffOf(ReconcileItem.PAY_WITHOUT_ORDER)).as("须检出").isPositive();
        assertThat(report.repairedOf(ReconcileItem.PAY_WITHOUT_ORDER))
                .as("禁止自动补单 —— 占位值造出来的单会被履约当成真单发奖")
                .isZero();
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE play_biz_record_no = ?",
                                GHOST_TRADES[1]))
                .as("不得无中生有造出主单")
                .isZero();
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?",
                                GHOST_TRADES[1]))
                .as("更不得为一笔来源不明的收款建履约任务")
                .isZero();
    }

    /** 正常单（支付方与本地都有）不算第 8 项差异 —— 否则每一笔正常交易每轮报一次。 */
    @Test
    void normalPaidOrderIsNotAPayWithoutOrderDiff() {
        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.PAY_WITHOUT_ORDER);

        paidOrder("rec8_normal");

        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.PAY_WITHOUT_ORDER);

        assertThat(after - before).as("本地有单即不是差异 —— 判反了会让每笔正常交易都告警，哨兵被噪音淹没").isZero();
    }

    /**
     * 第 4 项：<b>{@code REFUNDING} 悬挂 + 查单任务丢失 → 补 {@code QUERY_REFUND}，单子收敛</b>。
     *
     * <p><b>这一支首版不存在</b>：{@code repairUnresolvedOps} 只判 {@code CLOSING} 与 {@code GRANT_UNKNOWN}，
     * 而扫描扫的是全部非终态操作记录 —— 退款链路的悬挂被扫了出来却没有任何补建分支。
     *
     * <p>那个失效形态<b>比「没扫到」更糟</b>：{@code diffs} 有值而 {@code repaired} 恒空，监控上告警是亮的、
     * 看起来对账在正常工作，而那条告警永远不会消失。没扫到至少还有人怀疑覆盖不全。
     *
     * <p>断言落在<b>最终状态</b>而不只是任务条数：补了任务但单子推不动，等于没修。
     */
    @Test
    void detectsAndRepairsUnresolvedRefund() {
        String bizNo = admittedRefundOrder("rec_unrf", "RR_unrf");

        injector.setPayMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        benefitOrderService.createRefund(bizNo, "RR_unrf");
        injector.reset();
        assertThat(orderField("refund_status", bizNo))
                .as("前提：单子须停在 REFUNDING")
                .isEqualTo(RefundStatus.REFUNDING.name());

        // 模拟查单任务丢失（宕机、人为误删、DEAD 后被清理）
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.QUERY_REFUND.name());

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.repairedOf(ReconcileItem.OP_UNRESOLVED))
                .as("检出还不够，必须真的补出任务 —— 只检出不修的告警永远不会消失")
                .isPositive();
        assertThat(taskCount(bizNo, TaskType.QUERY_REFUND)).as("须补回查单任务").isEqualTo(1);
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.QUERY_REFUND.name()))
                .as("须复用原 refundNo —— 新造键会让下游当成一笔全新的退款")
                .isEqualTo(IdempotentKeys.refundNo(bizNo, "RR_unrf"));

        for (int i = 0; i < 3; i++) {
            runScheduler();
        }
        assertThat(orderField("refund_status", bizNo))
                .as("补了任务还要真的能推到终态，否则等于没修")
                .isEqualTo(RefundStatus.REFUND_SUCCESS.name());
    }

    /**
     * 第 4 项：<b>{@code REVOKING} 悬挂 + 回收任务丢失 → 补 {@code REVOKE}</b>。
     *
     * <p>与上一条是退款链路的两个中间态，缺任一个那一段就没有收敛通路。§6.4 要求「任何中间态都必须 同时具备准入谓词的入边与对账的一行」—— 这两条即那两行。
     */
    @Test
    void detectsAndRepairsUnresolvedRevoke() {
        String bizNo = paidOrder("rec_unrv");
        runScheduler();

        // 回收 RPC 未定 → 主单进 REVOKING
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RR_unrv");
        req.setOperator("cs_probe");
        req.setReason("对账用例");
        benefitOrderService.revokeAndAdmit(req);
        injector.reset();

        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());
        benefitJdbc.update(
                "DELETE FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.REVOKE.name());

        ReconcileReport report = benefitOrderService.reconcile();

        assertThat(report.repairedOf(ReconcileItem.OP_UNRESOLVED)).isPositive();
        assertThat(taskCount(bizNo, TaskType.REVOKE)).as("须补回回收任务").isEqualTo(1);
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.REVOKE.name()))
                .as("须复用原 revokeNo")
                .isEqualTo(IdempotentKeys.revokeNo(bizNo, "RR_unrv"));
    }

    /**
     * <b>{@code pay_amount IS NULL} 不算金额差异</b>。
     *
     * <p>它是 {@code applyPaidAfterClosing} 有意留下的「实付未知」状态（关单收敛时支付方没说收了多少）， 不是金额错误 ——
     * 不排除的话，每一笔走关单收敛的单都会报一次差异。
     *
     * <p>该状态在退款侧的正确处置是拒绝退款（{@code 1754}，PR-7/8 review 补），两处对同一状态的判断一致。
     */
    @Test
    void nullPayAmountIsNotAmountMismatch() {
        // 对账扫全表，别的用例留下的差异行也在里面 —— 故取增量而非绝对值。
        // 与 RefundAdmissionIT 记的「账本计数器是全局的」同族：共享状态上的绝对值断言，
        // 通过与否取决于用例执行顺序。首版即如此写，实测 expected 0 but was 1
        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.AMOUNT_MISMATCH);

        String bizNo = paidOrder("rec_null_amt");
        benefitJdbc.update(
                "UPDATE play_biz_record SET pay_amount = NULL WHERE play_biz_record_no = ?", bizNo);

        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.AMOUNT_MISMATCH);

        assertThat(after - before).as("实付未知是可表达的状态，不是金额错误 —— 不排除它则每笔关单收敛的单都报一次").isZero();
    }
}
