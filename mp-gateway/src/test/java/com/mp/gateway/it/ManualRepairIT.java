package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.ManualRepairReq;
import com.mp.api.benefit.dto.ManualRepairResp;
import com.mp.api.benefit.dto.RepairAction;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 人工处置（FR-C07、BR-C-27），对应《分阶段方案》§6.5 退出标准 20。
 *
 * <p><b>本类的核心断言只有一条：连点两次不重复发放</b>。人工处置是最容易被重复点击的入口（客服连点）， 而它又绕过了自动链路的入口校验 ——
 * 若它新造幂等键，就等于给系统开了一个可以重复发奖的后门。
 *
 * <p><b>「不重复发放」的判据取下游账本，不取平台记录</b>：平台的唯一索引只能证明平台没重复受理； 奖发了几次，只有供应方数得准（§5.3）。
 */
class ManualRepairIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private PayLedger payLedger;
    @Autowired private ProviderLedger providerLedger;

    @AfterEach
    void reset() {
        injector.reset();
        providerLedger.clearFailingProducts();
        providerLedger.clearDelays();
    }

    private String grantedOrder(String tag) {
        String bizNo = benefitOrderService.createTrade(newTradeReq(tag)).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_" + tag, "N1", "SUCCESS"));
        runScheduler();
        return bizNo;
    }

    private ManualRepairReq req(String bizNo, RepairAction action, String ticket) {
        ManualRepairReq r = new ManualRepairReq();
        r.setBizNo(bizNo);
        r.setAction(action);
        r.setOperator("cs_carol");
        r.setReason("用户反馈未到账");
        r.setTicketNo(ticket);
        return r;
    }

    private int taskCount(String bizNo, TaskType type) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                bizNo,
                type.name());
    }

    private int manualRepairOpCount(String bizNo) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM play_op_record WHERE play_biz_record_no = ? AND op_type = ?",
                bizNo,
                OpType.MANUAL_REPAIR.name());
    }

    // ------------------------------------------------------------------
    // 标准 20：复用原幂等键，重复点击不重复发放
    // ------------------------------------------------------------------

    /**
     * 标准 20：<b>连点两次「重试发奖」，发放记录仍 1 条、供应方单号不变</b>。
     *
     * <p>三条断言各挡一类：
     *
     * <ul>
     *   <li>下游账本不增 —— 真正的「没有重复发放」，跨过服务边界的判据
     *   <li>{@code reward_grant_record} 仍 1 条 —— 平台没有重复受理
     *   <li>两次返回同一个 {@code reusedKey} —— 「复用原键」的可见证据
     * </ul>
     *
     * <p>只断言第二条的话，一个「新造键但被别处拦下」的实现同样能通过 —— 而那种实现换个场景就会漏。
     */
    @Test
    void repeatedRetryGrantDoesNotDuplicate() {
        String bizNo = grantedOrder("mr_retry");
        int ledgerBefore = providerLedger.size();
        int recordsBefore = grantRecordCount(bizNo);
        String orderNoBefore =
                str(
                        rewardJdbc,
                        "SELECT provider_order_no FROM reward_grant_item i"
                                + " JOIN reward_grant_record r ON i.op_no = r.op_no"
                                + " WHERE r.biz_order_no = ? ORDER BY i.item_seq LIMIT 1",
                        bizNo);

        ManualRepairResp first =
                benefitOrderService.manualRepair(req(bizNo, RepairAction.RETRY_GRANT, "TK1"));
        runScheduler();
        int tasksAfterFirst = taskCount(bizNo, TaskType.GRANT);

        ManualRepairResp second =
                benefitOrderService.manualRepair(req(bizNo, RepairAction.RETRY_GRANT, "TK2"));
        runScheduler();

        assertThat(first.getReusedKey())
                .as("两次须复用同一把原键 —— 新造键即绕开唯一索引")
                .isEqualTo(second.getReusedKey());
        assertThat(providerLedger.size() - ledgerBefore).as("下游不得多发一笔").isZero();
        assertThat(grantRecordCount(bizNo)).as("发放记录仍是一供应方一条").isEqualTo(recordsBefore);
        assertThat(
                        str(
                                rewardJdbc,
                                "SELECT provider_order_no FROM reward_grant_item i"
                                        + " JOIN reward_grant_record r ON i.op_no = r.op_no"
                                        + " WHERE r.biz_order_no = ? ORDER BY i.item_seq LIMIT 1",
                                bizNo))
                .as("供应方单号不得变 —— 变了即产生了新的发放")
                .isEqualTo(orderNoBefore);

        // 任务不得堆积：这是「复用原键」在本条链路上真正起作用的地方。
        //
        // 本断言由注入自查补入：把重试发奖改成新造键后，上面四条断言全绿 ——
        // 因为 grantBenefit 对已终态的单直接短路返回，新造的键根本走不到下游，
        // 而下游那一层还有 opNo 幂等兜底。外层的短路把内层的键失效遮住了。
        //
        // 新造键的实际危害是 uk_biz_type_op 不再去重：每点一次就多几条 GRANT 任务，
        // 每条都会被调度器领走再跑一遍 grantBenefit。单子越多、点得越勤，任务表涨得越快。
        //
        // 断言取「第二次点击前后不变」而非某个绝对值：一单跨两个供应方即两把原键，
        // 加上支付回调落的那条，基数取决于 seed 权益包的构成 —— 写死绝对值会在改 seed 时误红
        assertThat(taskCount(bizNo, TaskType.GRANT))
                .as("第二次点击不得再堆出新任务 —— uk_biz_type_op 靠的正是原键")
                .isEqualTo(tasksAfterFirst);
    }

    /**
     * 标准 20 下半：<b>落 {@code operator} 与 {@code reason}</b>（BR-C-27）。
     *
     * <p>不留操作人则人工干预与自动收敛在库里无从区分，对账算不出真实的自动收敛率 —— 而那个数正是 用来判断自动化程度的。
     */
    @Test
    void manualRepairWritesAuditTrail() {
        String bizNo = grantedOrder("mr_audit");

        benefitOrderService.manualRepair(req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_A"));

        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT operator FROM play_op_record WHERE play_biz_record_no = ?"
                                        + " AND op_type = ?",
                                bizNo,
                                OpType.MANUAL_REPAIR.name()))
                .isEqualTo("cs_carol");
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT reason FROM play_op_record WHERE play_biz_record_no = ?"
                                        + " AND op_type = ?",
                                bizNo,
                                OpType.MANUAL_REPAIR.name()))
                .isEqualTo("用户反馈未到账");
    }

    /**
     * <b>同一工单号重复提交只留一条审计，不同工单号各留一条</b>。
     *
     * <p>两个方向都要验：只验前者的话，一个「所有处置共用一条审计」的实现照样通过 —— 而审计要能回答「这单被处置过几次、分别是谁」。
     */
    @Test
    void auditIsIdempotentPerTicketButDistinctAcrossTickets() {
        String bizNo = grantedOrder("mr_ticket");

        benefitOrderService.manualRepair(req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_X"));
        benefitOrderService.manualRepair(req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_X"));
        assertThat(manualRepairOpCount(bizNo)).as("同工单号重复提交只留一条").isEqualTo(1);

        benefitOrderService.manualRepair(req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_Y"));
        assertThat(manualRepairOpCount(bizNo)).as("不同工单号各留一条痕").isEqualTo(2);
    }

    /** {@code operator} / {@code reason} / {@code ticketNo} 缺一即拒 —— BR-C-27 是硬要求。 */
    @Test
    void missingAuditFieldsAreRejected() {
        String bizNo = grantedOrder("mr_missing");

        ManualRepairReq noOperator = req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_M");
        noOperator.setOperator(null);
        assertThatThrownBy(() -> benefitOrderService.manualRepair(noOperator))
                .isInstanceOf(BizException.class);

        ManualRepairReq noReason = req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_M");
        noReason.setReason("  ");
        assertThatThrownBy(() -> benefitOrderService.manualRepair(noReason))
                .isInstanceOf(BizException.class);

        assertThat(manualRepairOpCount(bizNo)).as("被拒的处置不得留痕").isZero();
    }

    // ------------------------------------------------------------------
    // 七类动作各自的形状
    // ------------------------------------------------------------------

    /** 重试回收：复用原 {@code revokeNo} 补建 {@code REVOKE} 任务。 */
    @Test
    void retryRevokeReusesOriginalKey() {
        String bizNo = grantedOrder("mr_revoke");
        var admitReq = new com.mp.api.benefit.dto.RevokeAdmitReq();
        admitReq.setBizNo(bizNo);
        admitReq.setRefundReqNo("MRQ1");
        admitReq.setOperator("cs_carol");
        benefitOrderService.revokeAndAdmit(admitReq);

        ManualRepairResp resp =
                benefitOrderService.manualRepair(req(bizNo, RepairAction.RETRY_REVOKE, "TK_R"));

        assertThat(resp.getReusedKey())
                .as("须复用准入时的原回收键")
                .isEqualTo(com.mp.common.util.IdempotentKeys.revokeNo(bizNo, "MRQ1"));
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ? AND op_no = ?",
                                bizNo,
                                TaskType.REVOKE.name(),
                                resp.getReusedKey()))
                .isEqualTo(1);
    }

    /**
     * <b>重试退款只补查单任务，不补重发</b>。
     *
     * <p>与 {@code QUERY_REFUND} 的设计同源：多发一笔奖可回收，多退一笔钱要走人工追讨，两者的失效
     * 代价不对称。人工点「重试退款」想要的是把它推到终态，而查单能做到且无副作用。
     *
     * <p><b>断言 {@code REFUND} 任务数为 0 是本用例的重点</b>：只断言 {@code QUERY_REFUND} 被补建的话，一个 「两种任务都补」的实现同样通过
     * —— 而那会真的再退一次钱。
     */
    @Test
    void retryRefundOnlyQueriesNeverRedispatches() {
        String bizNo = grantedOrder("mr_refund");
        var admitReq = new com.mp.api.benefit.dto.RevokeAdmitReq();
        admitReq.setBizNo(bizNo);
        admitReq.setRefundReqNo("MRQ2");
        admitReq.setOperator("cs_carol");
        benefitOrderService.revokeAndAdmit(admitReq);
        injector.setPayMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        benefitOrderService.createRefund(bizNo, "MRQ2");
        injector.reset();

        int payBefore = payLedger.refundSize();
        ManualRepairResp resp =
                benefitOrderService.manualRepair(req(bizNo, RepairAction.RETRY_REFUND, "TK_F"));

        assertThat(resp.getReusedKey())
                .isEqualTo(com.mp.common.util.IdempotentKeys.refundNo(bizNo, "MRQ2"));
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type = ?",
                                bizNo,
                                TaskType.REFUND.name()))
                .as("重试退款不得补建重发任务 —— 那是真的再退一次")
                .isZero();
        assertThat(payLedger.refundSize() - payBefore).as("支付方不得收到第二笔退款").isZero();
    }

    /**
     * 标记人工完成：<b>唯一写终态而不调下游的动作</b>，且只认 {@code GRANT_UNKNOWN} 这一条入边。
     *
     * <p>不做成「随便改成任意状态」——那等于给人一个绕过全部状态机的入口，而状态机正是「已支付必履约」 这类不变量的载体。
     */
    @Test
    void markDoneAdvancesOnlyFromUnknown() {
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        String bizNo = grantedOrder("mr_mark");
        injector.reset();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        int ledgerBefore = providerLedger.size();
        benefitOrderService.manualRepair(req(bizNo, RepairAction.MARK_DONE, "TK_D"));

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(providerLedger.size() - ledgerBefore).as("标记完成不得调下游").isZero();

        // 已终态的单再标一次不改变任何东西（条件更新命中 0 行）
        benefitOrderService.manualRepair(req(bizNo, RepairAction.MARK_DONE, "TK_D2"));
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
    }

    /** 导出对账证据：只读，含三子状态与关键单号，且不改任何状态。 */
    @Test
    void exportEvidenceIsReadOnly() {
        String bizNo = grantedOrder("mr_export");
        String grantBefore = orderField("grant_status", bizNo);

        ManualRepairResp resp =
                benefitOrderService.manualRepair(req(bizNo, RepairAction.EXPORT_EVIDENCE, "TK_E"));

        assertThat(resp.getEvidence()).contains(bizNo).contains("pay=").contains("grant=");
        assertThat(orderField("grant_status", bizNo)).isEqualTo(grantBefore);
    }

    /** 无发奖记录的单不能「重试发奖」——没有原键可复用，此时该走的是正常履约。 */
    @Test
    void retryGrantWithoutRecordIsRejected() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("mr_norec")).getBizNo();

        assertThatThrownBy(
                        () ->
                                benefitOrderService.manualRepair(
                                        req(bizNo, RepairAction.RETRY_GRANT, "TK_N")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无原幂等键");
    }

    /**
     * <b>动作失败，审计仍留痕</b>（BR-C-27）。
     *
     * <p>「重试发奖」在无发奖记录时抛 {@code BizException}，而那次抛出发生在<b>落审计之后</b> ——
     * 审计行必须还在：人要能看到「有人点过这个按钮，没成」。看不到的话，一次失败的人工处置在库里 与「从没人碰过」完全一样，追责与复盘都无从下手。
     *
     * <p><b>这条同时锁住「这个类不该加 {@code @BenefitTx}」</b>。它看起来像漏了事务注解，但 {@code @BenefitTx} 带 {@code
     * rollbackFor = Exception.class}，加上之后这里的审计行会随异常一起回滚，本用例立刻变红 —— 静态守卫（{@code
     * ShapeFreezeTest}）挡的是注解本身，这条挡的是它造成的行为。
     *
     * <p>审计与动作不同事务，正是因为两者的失败处置相反：动作失败要回滚，审计失败不该连累动作， 而审计更不该被动作的失败抹掉。
     */
    @Test
    void auditSurvivesFailedAction() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("mr_auditkeep")).getBizNo();

        assertThatThrownBy(
                        () ->
                                benefitOrderService.manualRepair(
                                        req(bizNo, RepairAction.RETRY_GRANT, "TK_AK")))
                .isInstanceOf(BizException.class);

        assertThat(manualRepairOpCount(bizNo)).as("动作失败了，但「有人点过」这件事必须留下").isEqualTo(1);
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT operator FROM play_op_record WHERE play_biz_record_no = ?"
                                        + " AND op_type = ?",
                                bizNo,
                                OpType.MANUAL_REPAIR.name()))
                .as("操作人须可追溯 —— 审计的用处正在于此")
                .isEqualTo("cs_carol");
    }
}
