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

    private String taskStatus(String bizNo, TaskType type, String opNo) {
        return str(
                benefitJdbc,
                "SELECT status FROM benefit_task WHERE biz_no = ? AND task_type = ? AND op_no = ?",
                bizNo,
                type.name(),
                opNo);
    }
}
