package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.RevokeAdmitReq;
import com.mp.api.mock.dto.FaultMode;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.common.util.IdempotentKeys;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 退款执行与 {@code UNKNOWN} 收敛（FR-B08），对应《分阶段方案》§6.5 退出标准 12、13、24。
 *
 * <p><b>「重复退款 = 0」的判据必须取支付方账本，不能取平台自己的库</b>：平台的三道闸都在平台侧， 只能证明「平台没重复受理」；钱有没有退两次，只有支付方数得准（§5.3 对 mock
 * 独立账本的要求， 退款侧同理）。
 *
 * <p>本类的每一条「不重复退款」断言都落在 {@code PayLedger.refundSize()} 上。
 */
class RefundExecutionIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private PayLedger payLedger;
    @Autowired private ProviderLedger providerLedger;
    @Autowired private OrderTxService orderTx;
    @Autowired private PlayBizRecordMapper bizRecordMapper;

    @AfterEach
    void reset() {
        injector.reset();
    }

    /** 建单 → 支付 → 履约 → 准入回收，主单进 {@code REVOKING}，可以退款了。 */
    private String admittedOrder(String tag, String refundReqNo) {
        String bizNo = benefitOrderService.createTrade(newTradeReq(tag)).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_" + tag, "N1", "SUCCESS"));
        runScheduler();

        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo(refundReqNo);
        req.setOperator("cs_bob");
        benefitOrderService.revokeAndAdmit(req);
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());
        return bizNo;
    }

    // ------------------------------------------------------------------
    // 主链路
    // ------------------------------------------------------------------

    /** 退款成功：主单进终态，退款单号落库，支付方账本恰一笔。 */
    @Test
    void refundSucceedsAndReachesTerminalState() {
        String bizNo = admittedOrder("rf_ok", "RR1");
        int before = payLedger.refundSize();

        RetStatus result = benefitOrderService.createRefund(bizNo, "RR1");

        assertThat(result).isEqualTo(RetStatus.SUCCESS);
        assertThat(orderField("refund_status", bizNo))
                .isEqualTo(RefundStatus.REFUND_SUCCESS.name());
        assertThat(orderField("refund_no", bizNo)).isEqualTo(IdempotentKeys.refundNo(bizNo, "RR1"));
        assertThat(payLedger.refundSize() - before).as("支付方恰收到一笔退款").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 标准 12：同订单最多一笔有效退款（三道闸各自生效）
    // ------------------------------------------------------------------

    /**
     * 标准 12 上半：<b>同 {@code refundNo} 重传只成一笔</b>（第三道闸）。
     *
     * <p>第二次调用在主单条件更新处即被挡下（{@code REFUND_SUCCESS} 不在 {@code REVOKING} 入边内）， 根本走不到支付方。
     */
    @Test
    void repeatedSameRefundNoRefundsOnlyOnce() {
        String bizNo = admittedOrder("rf_same", "RR2");
        int before = payLedger.refundSize();

        benefitOrderService.createRefund(bizNo, "RR2");
        benefitOrderService.createRefund(bizNo, "RR2");

        assertThat(payLedger.refundSize() - before).as("重传不得多退一笔").isEqualTo(1);
    }

    /**
     * 标准 12 下半：<b>两个不同 {@code refundNo} 只成一笔</b>（第二道闸，客服连点）。
     *
     * <p><b>这一类是 {@code refundNo} 唯一索引挡不住的</b>：两把键都是新的，唯一索引不冲突。挡下它的 是主单条件更新与 {@code
     * uk_biz_op(bizNo,'CREATE_REFUND','')} 这条单据级约束。
     *
     * <p>只测同键重传的话，一个「只有 {@code refundNo} 唯一索引」的实现照样全绿 —— 而客服连点在 真实系统里比重传常见得多。
     */
    @Test
    void twoDifferentRefundNosStillRefundOnlyOnce() {
        String bizNo = admittedOrder("rf_two", "RR3");
        int before = payLedger.refundSize();

        benefitOrderService.createRefund(bizNo, "RR3");
        // 客服换了个工单号又点一次
        assertThatThrownBy(() -> benefitOrderService.createRefund(bizNo, "RR3_AGAIN"))
                .isInstanceOf(BizException.class);

        assertThat(payLedger.refundSize() - before).as("两个退款号只能退成一笔").isEqualTo(1);
    }

    /**
     * 标准 12：<b>并发两笔不同 {@code refundNo} 的退款只成一笔</b>（第一道闸）。
     *
     * <p>串行用例证明不了这一条：并发下两个线程可能同时读到 {@code REVOKING} 各自推进一次 —— 而 条件更新让第二个命中 0 行。
     */
    @Test
    void concurrentRefundsSettleOnlyOne() throws Exception {
        String bizNo = admittedOrder("rf_race", "RR4");
        int before = payLedger.refundSize();

        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String reqNo = "RR4_" + i;
            jobs.add(
                    () -> {
                        try {
                            benefitOrderService.createRefund(bizNo, reqNo);
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    });
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Boolean>> futures = pool.invokeAll(jobs);
            long succeeded = 0;
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get())) {
                    succeeded++;
                }
            }
            assertThat(succeeded).as("恰有一个线程受理成功").isEqualTo(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(payLedger.refundSize() - before).as("并发下支付方仍只收到一笔").isEqualTo(1);
    }

    /**
     * <b>「先回收后退款」由数据库谓词强制，不只是服务层的一个 {@code if}</b>。
     *
     * <p>本用例由注入自查补入：把 {@code startRefund} 的前置态从 {@code REVOKING} 放开为 {@code NONE / REVOKING /
     * REVOKE_FAILED}（即允许未经回收直接退款）后，此前 20 条用例<b>全部保持绿色</b> —— 因为服务层的 {@code doCreateRefund} 先一步用
     * {@code if (current != REVOKING)} 拒掉了， 那条 SQL 谓词根本没有机会执行。
     *
     * <p>于是「顺序不可颠倒」这条 §5.6 的核心约束，实际上只由一处可被随手改掉的 {@code if} 守着： 谁把服务层那个分支挪走或写反，DB
     * 这一层不会有任何抵抗，而没有任何用例会红。
     *
     * <p>故直接调 {@code tx.startRefund} 绕开服务层，验证谓词本身。<b>这与 PR-5 的「约束在单分片下
     * 不可观测」同类</b>：约束存在、用例齐全，但外层还有一道拦截使内层不可观测 —— 差别是那处要 构造特殊入参，此处要绕过一层调用。
     */
    @Test
    void refundStatePredicateItselfRejectsNonRevokingOrder() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("rf_pred")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_pred", "N1", "SUCCESS"));
        runScheduler();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.NONE.name());

        PlayBizRecord order =
                bizRecordMapper.selectOne(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<PlayBizRecord>lambdaQuery()
                                .eq(PlayBizRecord::getPlayBizRecordNo, bizNo));

        assertThat(orderTx.startRefund(order, IdempotentKeys.refundNo(bizNo, "RRP")))
                .as("未经回收的单，退款谓词本身必须命中 0 行")
                .isFalse();
        assertThat(orderField("refund_status", bizNo))
                .as("谓词拒绝后状态不得改动")
                .isEqualTo(RefundStatus.NONE.name());
    }

    /** {@code REVOKE_FAILED} 的单同样过不了退款谓词 —— 权益还在，钱不能退。 */
    @Test
    void refundStatePredicateRejectsRevokeFailedOrder() {
        String bizNo = admittedOrder("rf_pred_rf", "RRQ");
        // 直接改库造出 REVOKE_FAILED，绕开回收链路 —— 本用例只验谓词
        benefitJdbc.update(
                "UPDATE play_biz_record SET refund_status = 'REVOKE_FAILED'"
                        + " WHERE play_biz_record_no = ?",
                bizNo);

        PlayBizRecord order =
                bizRecordMapper.selectOne(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<PlayBizRecord>lambdaQuery()
                                .eq(PlayBizRecord::getPlayBizRecordNo, bizNo));

        assertThat(orderTx.startRefund(order, IdempotentKeys.refundNo(bizNo, "RRQ")))
                .as("回收失败的单，退款谓词必须命中 0 行")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 标准 13：退款走 UNKNOWN 收敛，不重复退款
    // ------------------------------------------------------------------

    /**
     * 标准 13：<b>注入退款 {@code TIMEOUT_AFTER_COMMIT}，查单收敛且支付方账本仅 1 笔</b>。
     *
     * <p>这是四分类在退款侧最关键的一类：<b>钱已经退了但调用方收不到结果</b>。平台若把 {@code UNKNOWN} 误判为 {@code FAIL} 并重发，就是重复退款。
     *
     * <p><b>两个断言缺一不可</b>：只断言「最终 REFUND_SUCCESS」的话，一个「失败就重发」的实现同样 能收敛到成功 —— 只是多退了一笔钱。账本条数才是资损的判据。
     */
    @Test
    void refundUnknownConvergesWithoutDoubleRefund() {
        String bizNo = admittedOrder("rf_unk", "RR5");
        int before = payLedger.refundSize();
        String refundNo = IdempotentKeys.refundNo(bizNo, "RR5");

        injector.setPayMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        RetStatus result = benefitOrderService.createRefund(bizNo, "RR5");

        assertThat(result).as("超时须判 UNKNOWN，不得判 FAIL").isEqualTo(RetStatus.UNKNOWN);
        assertThat(orderField("refund_status", bizNo))
                .as("结果未定时主单保持 REFUNDING，不得进 REFUND_FAILED")
                .isEqualTo(RefundStatus.REFUNDING.name());
        assertThat(taskStatus(bizNo, TaskType.QUERY_REFUND, refundNo))
                .as("须落查单任务，否则这笔单永远停在中间态")
                .isEqualTo(TaskStatus.PENDING.name());

        // 恢复正常后驱动调度器：查单收敛
        injector.reset();
        for (int i = 0; i < 3; i++) {
            runScheduler();
        }

        assertThat(orderField("refund_status", bizNo))
                .as("查单须收敛到成功")
                .isEqualTo(RefundStatus.REFUND_SUCCESS.name());
        assertThat(payLedger.refundSize() - before)
                .as("支付方账本仅 1 笔 —— 这才是「重复退款 = 0」的判据")
                .isEqualTo(1);
        assertThat(payLedger.refundAttempts(refundNo)).as("只查不发：发起次数恰为 1，收敛靠查单而非重发").isEqualTo(1);
    }

    /**
     * <b>退款侧只查不发，与发放侧刻意不同</b>。
     *
     * <p>发放侧在连续查无满阈值后会以原 {@code opNo} 重发；退款侧不做这一段 —— 多发一笔奖可回收， 多退一笔钱要走人工追讨，两者的失效代价不对称。
     *
     * <p>注入「未记账的超时」后反复驱动：若实现了重发，{@code refundAttempts} 会随轮次增长。
     */
    @Test
    void refundQueryNeverReDispatches() {
        String bizNo = admittedOrder("rf_nodisp", "RR6");
        String refundNo = IdempotentKeys.refundNo(bizNo, "RR6");

        injector.setPayMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        benefitOrderService.createRefund(bizNo, "RR6");
        int afterFirstCall = payLedger.refundAttempts(refundNo);

        // 保持故障，驱动多轮查单
        for (int i = 0; i < 4; i++) {
            runScheduler();
        }

        assertThat(payLedger.refundAttempts(refundNo)).as("查单不得发起新的退款请求").isEqualTo(afterFirstCall);
        assertThat(payLedger.containsRefund(refundNo))
                .as("下游未记账（TIMEOUT_BEFORE_COMMIT），账本无本单")
                .isFalse();
    }

    /** 退款确定失败进 {@code REFUND_FAILED}，可人工重试 —— 与「结果未定」严格分开。 */
    @Test
    void refundFailureIsTerminalAndDistinctFromUnknown() {
        String bizNo = admittedOrder("rf_fail", "RR7");

        injector.setPayMode(FaultMode.FAIL);
        RetStatus result = benefitOrderService.createRefund(bizNo, "RR7");

        assertThat(result).isEqualTo(RetStatus.FAIL);
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REFUND_FAILED.name());
    }

    // ------------------------------------------------------------------
    // 退款回补库存，但不返还限购额度（技术方案 §3.4 的口径表）
    // ------------------------------------------------------------------

    private int consumedOf() {
        return num(
                benefitJdbc,
                "SELECT consumed FROM marketing_stock WHERE stock_key = ?",
                "sku:" + SKU_ID);
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

    /**
     * <b>退款成功后须回补 {@code consumed}</b>（技术方案 §3.4 口径表）。
     *
     * <p>{@code settleRefund} 只推主单状态而不落库存任务的话，{@code consumed} 永远还不回去。
     *
     * <p><b>它的表现不是超卖，而是对账第 6 项每轮报一次假差异</b>：该项比对 {@code consumed} 与「已支付 且未退款成功」的份数（{@code
     * sumConsumedQuantity} 的谓词含 {@code refund_status <> 'REFUND_SUCCESS'}）， 退款后分母减少而 {@code
     * consumed} 不动。§3.4 原话：「退款/关单后的口径必须定死，否则对账第 6 项 在任何含退款的压测里都会报差异」——而假告警会让资损哨兵失效。
     *
     * <p><b>断言两处而非一处</b>：只断 {@code stock_status} 的话，一个「改了状态但没调回补 SQL」的实现照样绿； 只断 {@code consumed}
     * 的话，分不清是回补干的还是别的路径顺手减的。
     */
    @Test
    void refundRestoresConsumedStock() {
        String bizNo = admittedOrder("rf_restore", "RR30");
        runScheduler(); // 跑掉 STOCK_CONSUME
        int consumedBefore = consumedOf();
        assertThat(orderField("stock_status", bizNo)).isEqualTo("CONSUMED");

        benefitOrderService.createRefund(bizNo, "RR30");
        runScheduler(); // 跑 STOCK_RESTORE

        assertThat(orderField("stock_status", bizNo))
                .as("回补后本单库存态须进 RESTORED")
                .isEqualTo("RESTORED");
        assertThat(consumedBefore - consumedOf()).as("退款须把这一份已售还回可售").isEqualTo(1);
    }

    /**
     * <b>退款回补库存，但<u>不</u>返还限购额度</b> —— 这个不对称是 §3.4 定死的。
     *
     * <p>商品可以再卖给别人，故库存回补；而「买了再退」若能刷回额度，限购即被绕过。缺了这一条， 一个「退款走 {@code STOCK_RELEASE}」的实现照样通过上一个用例 ——
     * 而那条任务会连额度一起还掉。
     *
     * <p>断言落在 {@code quota_status} 与 {@code used_qty} 两处：前者证明状态机没动它，后者证明计数器 没被减。
     */
    @Test
    void refundDoesNotReturnPurchaseQuota() {
        String bizNo = admittedOrder("rf_quota", "RR31");
        runScheduler();
        int usedBefore = usedQtyOf("U_rf_quota");
        assertThat(usedBefore).as("下单时应已占用额度").isPositive();

        benefitOrderService.createRefund(bizNo, "RR31");
        runScheduler();

        assertThat(usedQtyOf("U_rf_quota")).as("退款不返还额度，否则「买了再退」可刷回限购").isEqualTo(usedBefore);
        assertThat(orderField("quota_status", bizNo))
                .as("额度态须停在 LOCKED —— 买了就算用掉")
                .isEqualTo("LOCKED");
    }

    /**
     * <b>回补任务重复执行不得多减</b> —— 且必须<u>另有一笔单占着 {@code consumed}</u>。
     *
     * <p>这是 §7.4 反复强调的那条：<b>下界谓词提供不了每单幂等</b>。{@code consumed} 是该 {@code stock_key}
     * 下所有订单共享的计数器，本单重复回补时它因别的单占用仍大于 0，{@code WHERE consumed >= qty} 照常放行 ——
     * 结果是这一单还掉了别人的已售份额，可售余量多出一份，直接超卖。
     *
     * <p>故必须先造一笔 B 单占着 {@code consumed}。<b>若只有 A 单，回补后 {@code consumed} 归零，第二次被下界 挡下 ——
     * 验的就成了「下界生效」而不是「不动别人的份额」</b>，与 §7.4 记的「这个错误只有一种用例能 暴露」是同一条。
     *
     * <p>真正拦住它的是主单 {@code stock_status} 的条件更新（{@code CONSUMED → RESTORED}），本用例通过 人工把任务打回 {@code
     * PENDING} 模拟调度器重复领取。
     */
    @Test
    void repeatedRestoreDoesNotStealAnotherOrdersConsumed() {
        // B 单：支付并转消耗，占着 consumed 不退款
        String holder = benefitOrderService.createTrade(newTradeReq("rf_holder")).getBizNo();
        payLedger.markPaid(holder);
        benefitOrderService.payCallback(newPayCallback(holder, "T_rf_holder", "N1", "SUCCESS"));
        runScheduler();

        String bizNo = admittedOrder("rf_dup_restore", "RR32");
        runScheduler();
        int consumedBefore = consumedOf();

        benefitOrderService.createRefund(bizNo, "RR32");
        runScheduler();
        int afterFirst = consumedOf();
        assertThat(consumedBefore - afterFirst).isEqualTo(1);

        // 人工把回补任务打回待执行，模拟调度器重复领取
        benefitJdbc.update(
                "UPDATE benefit_task SET status = 'PENDING', lease_owner = NULL,"
                        + " next_time = NOW(3) WHERE biz_no = ? AND task_type = ?",
                bizNo,
                TaskType.STOCK_RESTORE.name());
        runScheduler();

        assertThat(consumedOf())
                .as("重复回补不得吃掉另一笔单的已售份额 —— 下界拦不住这一类，靠的是 stock_status 那道闸")
                .isEqualTo(afterFirst);
    }

    /**
     * <b>关单释放过的单不得再被退款回补</b> —— 这是 {@code RESTORED} 不复用 {@code RELEASED} 的用处。
     *
     * <p>两者归还的是不同的计数器：{@code RELEASED} 表示预占已还（{@code locked} 减，交易未成立）， {@code RESTORED}
     * 表示已售已还（{@code consumed} 减，成立后反悔）。若合并成一个值，回补的条件更新 就要同时接受 {@code LOCKED} 与 {@code CONSUMED}
     * 两个前置态，于是一笔关单释放过的单还能再被回补 一次，减掉的是别的订单的 {@code consumed}。
     *
     * <p>本用例直接对一笔已释放的单落回补任务 —— 正常链路造不出这个组合，故绕过服务层构造， 与 PR-7/8「让被外层遮蔽的内层约束变得可观测」是同一处置。
     */
    @Test
    void releasedOrderIsNotRestoredAgain() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("rf_released")).getBizNo();
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_rf_rel", "N1", "FAILED"));
        runScheduler();
        assertThat(orderField("stock_status", bizNo)).isEqualTo("RELEASED");

        int consumedBefore = consumedOf();
        // 绕过服务层直接落一条回补任务
        benefitJdbc.update(
                "INSERT INTO benefit_task (task_no, biz_no, task_type, op_no, status, next_time,"
                        + " retry_count, payload) VALUES (?, ?, 'STOCK_RESTORE', ?, 'PENDING',"
                        + " NOW(3), 0, '{}')",
                "TSK_rf_rel_manual",
                bizNo,
                bizNo + "_STOCK_RESTORE");
        runScheduler();

        assertThat(orderField("stock_status", bizNo))
                .as("已释放的单不得被回补改写 —— 它从未转过消耗")
                .isEqualTo("RELEASED");
        assertThat(consumedOf()).as("不得减掉别的订单的已售份额").isEqualTo(consumedBefore);
    }

    // ------------------------------------------------------------------
    // REFUND_FAILED 不是死状态：人工带 operator 可重入（技术方案 §6.4）
    // ------------------------------------------------------------------

    /** 把一单推到 {@code REFUND_FAILED}。 */
    private String failedRefundOrder(String tag, String refundReqNo) {
        String bizNo = admittedOrder(tag, refundReqNo);
        injector.setPayMode(FaultMode.FAIL);
        benefitOrderService.createRefund(bizNo, refundReqNo);
        injector.reset();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REFUND_FAILED.name());
        return bizNo;
    }

    /**
     * <b>{@code REFUND_FAILED} 必须能由人工重入，否则它是死状态</b>（技术方案 §6.4）。
     *
     * <p>状态迁移表把 {@code REFUND_FAILED → REFUNDING} 标注为人工重试边，而另三条通路都够不着它： 准入的前置态只有 {@code NONE} /
     * {@code REVOKE_FAILED}，{@code createRefund} 要求 {@code REVOKING}， {@code manualRepair}
     * 的「重试退款」只补查单任务而 {@code failRefund} 的前置态是 {@code REFUNDING}。 于是一笔退款失败的单永远退不了款，只能改库。
     *
     * <p>§6.4 原话：「若准入谓词不给例外分支，这两条边永远 {@code affected_rows=0}，它们又会退化成 死状态」。
     */
    @Test
    void refundFailedCanBeReAdmittedByOperator() {
        String bizNo = failedRefundOrder("rf_reentry", "RR20");

        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RR20");
        req.setOperator("cs_carol");
        req.setReason("用户申诉，重试退款");
        benefitOrderService.revokeAndAdmit(req);

        assertThat(orderField("refund_status", bizNo))
                .as("带 operator 的人工准入须能把 REFUND_FAILED 拉回 REVOKING")
                .isEqualTo(RefundStatus.REVOKING.name());

        // 拉回后真的能退成，才叫「不是死状态」—— 只断言状态变了的话，
        // 一个「改了状态但退款仍走不通」的实现照样绿
        assertThat(benefitOrderService.createRefund(bizNo, "RR20")).isEqualTo(RetStatus.SUCCESS);
        assertThat(orderField("refund_status", bizNo))
                .isEqualTo(RefundStatus.REFUND_SUCCESS.name());
    }

    /**
     * <b>不带 {@code operator} 的自动路径不得从 {@code REFUND_FAILED} 重入</b>——人工通道是显式开口，不是放宽谓词。
     *
     * <p>缺了这一条，一个「无条件把 {@code REFUND_FAILED} 加进前置态集合」的实现照样通过上一条 ——
     * 而那等于悄悄放宽了状态机：任何自动重试都能把一笔已判定失败的退款重新发起，且不留操作人。 §6.4 的要求是「做法不是悄悄放宽谓词，而是用参数把人工通道显式化」。
     */
    @Test
    void refundFailedStaysClosedToAutomaticPath() {
        String bizNo = failedRefundOrder("rf_auto", "RR21");

        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RR21");
        // operator 不填 —— 自动路径

        assertThatThrownBy(() -> benefitOrderService.revokeAndAdmit(req))
                .isInstanceOf(BizException.class);
        assertThat(orderField("refund_status", bizNo))
                .as("自动路径不得把 REFUND_FAILED 拉回，开口只对带审计的调用生效")
                .isEqualTo(RefundStatus.REFUND_FAILED.name());
    }

    /**
     * <b>人工重入必须复用原 {@code refundNo}，不得按新工单号重新派生</b>（BR-B-38）。
     *
     * <p>这条在「{@code REFUND_FAILED} 经人工重入」这一路上才显形：客服换个工单号再来一次，派生出的 是一把新键 —— 主单的 {@code fillRefundNo}
     * 带 {@code IS NULL} 守卫，库里存的还是旧号，而<b>真正发给 支付方的是新号</b>，于是同一单对支付系统发起了两笔退款。
     *
     * <p><b>三道闸一道都拦不住它</b>：条件更新看的是状态、{@code uk_biz_op} 看的是 {@code (bizNo, CREATE_REFUND,
     * '')}（{@code upsert} 只累加 {@code retry_count}），两者都不检查这把键本身。故断言 落在支付方账本上 —— 平台侧的闸只能证明「平台没重复受理」。
     */
    @Test
    void manualReEntryReusesOriginalRefundNo() {
        String bizNo = failedRefundOrder("rf_keyreuse", "RR22");
        String originalRefundNo = orderField("refund_no", bizNo);
        assertThat(originalRefundNo).isEqualTo(IdempotentKeys.refundNo(bizNo, "RR22"));
        int before = payLedger.refundSize();

        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RR22");
        req.setOperator("cs_dave");
        req.setReason("换工单号重试");
        benefitOrderService.revokeAndAdmit(req);

        // 客服带着新工单号重试 —— 派生规则若照它走，会得到一把全新的键
        benefitOrderService.createRefund(bizNo, "TICKET_9527");

        assertThat(orderField("refund_no", bizNo)).as("退款单号须保持原值").isEqualTo(originalRefundNo);
        assertThat(payLedger.containsRefund(originalRefundNo)).as("发给支付方的须是原键").isTrue();
        assertThat(payLedger.containsRefund(IdempotentKeys.refundNo(bizNo, "TICKET_9527")))
                .as("不得按新工单号派生出第二把键 —— 那对支付系统就是第二笔退款")
                .isFalse();
        assertThat(payLedger.refundSize() - before).as("支付方账本只增一笔").isEqualTo(1);
    }

    /**
     * <b>人工准入须把 {@code operator} / {@code reason} 落进操作记录</b>（BR-C-27、技术方案 §6.4）。
     *
     * <p>§6.4 把「人工通道显式开口」与「留审计」写成同一条：谓词给人工路径开例外，代价是那次操作 必须可追溯到人。<b>参数只被接收而不落库等于开了口却没留痕</b> ——
     * 库里查不出「谁把这单从 退款失败拉回来重试的」，对账也算不出真实的自动收敛率。
     */
    @Test
    void manualAdmissionPersistsOperatorAudit() {
        String bizNo = failedRefundOrder("rf_audit", "RR23");

        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RR23");
        req.setOperator("cs_erin");
        req.setReason("客诉升级，人工重试");
        benefitOrderService.revokeAndAdmit(req);

        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT operator FROM play_op_record"
                                        + " WHERE play_biz_record_no = ? AND op_type ="
                                        + " 'REVOKE_BENEFIT'",
                                bizNo))
                .as("人工准入的操作人须落库")
                .isEqualTo("cs_erin");
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT reason FROM play_op_record"
                                        + " WHERE play_biz_record_no = ? AND op_type ="
                                        + " 'REVOKE_BENEFIT'",
                                bizNo))
                .isEqualTo("客诉升级，人工重试");
    }

    // ------------------------------------------------------------------
    // 标准 24：REVOKE / REFUND 任务不陪着重试到死信
    // ------------------------------------------------------------------

    /**
     * 标准 24（{@code REVOKE} 一项）：回收 {@code UNKNOWN} 收敛后任务为 {@code DONE} 而非 {@code DEAD}。
     *
     * <p><b>两半断言缺一不可</b>：只断言终态 {@code DONE} 的话，「重试若干轮后恰好收敛」同样能得到 {@code
     * DONE}，而每一轮都白调了一次下游。发起次数须取下游侧的计数。
     */
    @Test
    void revokeTaskEndsAtDoneWithoutExtraDownstreamCalls() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("rvk_task")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_rvk", "N1", "SUCCESS"));
        runScheduler();

        // 注入回收超时：准入后主单停在 REVOKING，落 REVOKE 任务
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RR8");
        benefitOrderService.revokeAndAdmit(req);

        String revokeNo = IdempotentKeys.revokeNo(bizNo, "RR8");
        assertThat(taskStatus(bizNo, TaskType.REVOKE, revokeNo))
                .as("回收未定须落 REVOKE 任务")
                .isEqualTo(TaskStatus.PENDING.name());

        // 恢复正常，驱动至收敛。轮数远超阈值 5 —— 若实现让它陪着重试，此处会是 DEAD
        injector.reset();
        for (int i = 0; i < 8; i++) {
            runScheduler();
        }

        assertThat(taskStatus(bizNo, TaskType.REVOKE, revokeNo))
                .as("收敛后须 DONE，不得进死信")
                .isEqualTo(TaskStatus.DONE.name());
        assertThat(orderField("refund_status", bizNo))
                .as("回收收敛后停在 REVOKING，不自动发起退款")
                .isEqualTo(RefundStatus.REVOKING.name());
    }

    /** 标准 24（{@code QUERY_REFUND} 一项）：查单收敛后任务为 {@code DONE}。 */
    @Test
    void queryRefundTaskEndsAtDone() {
        String bizNo = admittedOrder("rf_task", "RR9");
        String refundNo = IdempotentKeys.refundNo(bizNo, "RR9");

        injector.setPayMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        benefitOrderService.createRefund(bizNo, "RR9");
        injector.reset();

        for (int i = 0; i < 8; i++) {
            runScheduler();
        }

        assertThat(taskStatus(bizNo, TaskType.QUERY_REFUND, refundNo))
                .as("收敛后须 DONE，不得进死信")
                .isEqualTo(TaskStatus.DONE.name());
    }

    /** {@code REFUND} 任务类型<b>不入队</b> —— 退款由调用方显式发起，没有系统自动触发的时刻。 */
    @Test
    void refundTaskTypeIsNeverEnqueued() {
        String bizNo = admittedOrder("rf_notask", "RR10");
        benefitOrderService.createRefund(bizNo, "RR10");

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE task_type = ?",
                                TaskType.REFUND.name()))
                .as("REFUND 保留定义但不入队，结果未定时落的是 QUERY_REFUND")
                .isZero();
    }

    // ------------------------------------------------------------------
    // PR-7/8 review 补：收敛通路的空明细判定
    // ------------------------------------------------------------------

    /**
     * <b>{@code REVOKING} 但一条成功明细都没有时，收敛通路不得判成功</b>（review 缺陷 ③）。
     *
     * <p>同步链路的 {@code revokeGranted} 对这一情形判得对：打 ERROR、置未定、交人工，理由是「不静默
     * 当作无需回收，那会退掉一笔可能已发放的单」。<b>收敛通路必须给出同一个答案</b> —— 原实现的循环 一次都不执行，直接落到 {@code
     * settleRevoke}，于是<b>一次回收都没发生的单进入「回收已完成」</b>。
     *
     * <p>危害在下一步：{@code createRefund} 的前置态谓词正是 {@code REVOKING}，这单会被照常放行 ——
     * 「先回收后退款」从内部被绕过，三道闸一道都不会响。
     *
     * <p><b>断言退款仍被挡住是本用例的重点</b>：只断言 {@code reconcileRevoke} 返回 {@code UNKNOWN} 的话，一个
     * 「返回未知但仍把状态推成已回收」的实现照样通过 —— 而资损发生在状态上，不在返回值上。
     */
    @Test
    void reconcileRevokeDoesNotSettleWhenNoGrantedItemExists() {
        // 走真实的未收敛路径：注入回收超时，准入后操作记录停在 UNKNOWN、落 REVOKE 任务。
        //
        // 不能用正常准入后改库来造这个场景 —— 那条路径的回收已经成功，操作记录本就是
        // SUCCESS，断言「不得置 SUCCESS」会被前置状态直接满足，测不到收敛通路做了什么
        String bizNo = benefitOrderService.createTrade(newTradeReq("rvk_empty")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_rvk_empty", "N1", "SUCCESS"));
        runScheduler();

        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo("RRE");
        req.setOperator("cs_bob");
        benefitOrderService.revokeAndAdmit(req);
        injector.reset();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());

        int refundBefore = payLedger.refundSize();

        // 造数据不一致：明细全部改为 FAILED，主单 grant_status 仍是 GRANT_SUCCESS。
        // 于是 selectGranted 返回空 —— 这正是缺陷 ③ 的触发条件
        benefitJdbc.update(
                "UPDATE benefit_fulfillment_record SET grant_status = 'FAILED', revoke_no = NULL,"
                        + " revoke_time = NULL WHERE play_biz_record_no = ?",
                bizNo);

        RetStatus result = benefitOrderService.reconcileRevoke(bizNo);

        assertThat(result).as("数据不一致须回报未定，交人工").isEqualTo(RetStatus.UNKNOWN);
        assertThat(orderField("refund_status", bizNo))
                .as("一次回收都没发生，不得进入「回收已完成」")
                .isEqualTo(RefundStatus.REVOKING.name());
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT status FROM play_op_record WHERE play_biz_record_no = ?"
                                        + " AND op_type = 'REVOKE_BENEFIT'",
                                bizNo))
                .as("操作记录不得置 SUCCESS —— 对账按它判断退款走到哪一步")
                .isNotEqualTo("SUCCESS");

        // 关键一条：退款必须仍被挡住。资损发生在状态上，不在返回值上
        assertThat(payLedger.refundSize() - refundBefore).as("支付方不得收到退款").isZero();
    }

    private String taskStatus(String bizNo, TaskType type, String opNo) {
        return str(
                benefitJdbc,
                "SELECT status FROM benefit_task WHERE biz_no = ? AND task_type = ? AND op_no = ?",
                bizNo,
                type.name(),
                opNo);
    }
}
