package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.RevokeAdmitReq;
import com.mp.api.benefit.dto.RevokeAdmitResp;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.exception.BizException;
import com.mp.common.util.IdempotentKeys;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 退款准入与权益回收（FR-B08、BR-B-30），对应《分阶段方案》§6.5 退出标准 10、11。
 *
 * <p><b>准入判据是「发放结果是否确定」，不是「是否成功」</b>（技术方案 §7.5）。这条分界线极易写错 成「未发放成功就不许退款」——
 * 那会把「已支付未履约」这类最需要退款的单永久锁死，而它正是对账 要自动补偿的头号场景。
 *
 * <p>故本类的五条准入用例<b>必须覆盖两个方向</b>：三条该放行的（{@code NOT_START} / {@code GRANT_FAILED} / {@code
 * GRANT_SUCCESS}）与两条该拒绝的（{@code GRANTING} / {@code GRANT_UNKNOWN}）。 只测拒绝那两条的话，「一律拒绝」的实现照样全绿。
 */
class RefundAdmissionIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private ProviderLedger providerLedger;
    @Autowired private PayLedger payLedger;

    @AfterEach
    void reset() {
        injector.reset();
    }

    /**
     * {@code ProviderLedger} / {@code PayLedger} 的计数器是<b>全局的</b>，跨用例累加。
     *
     * <p>故断言「本次没有调用回收」不能写成 {@code revokeSize() == 0} —— 别的用例留下的回收也在里面。 首版就是这么写的，实测 {@code expected
     * 0 but was 4}。改为记下调用前的基线，断言<b>增量</b>。
     *
     * <p>这与 {@code runScheduler()} 是全局的（{@code StockAndQuotaIT} §46 的注）是同一类陷阱：共享
     * 状态上的绝对值断言，通过与否取决于用例执行顺序。
     */
    private int revokeCalls() {
        return providerLedger.revokeSize();
    }

    /** 建单 + 支付成功，返回 bizNo。发放尚未驱动 —— grant 停在 {@code NOT_START}。 */
    private String paidOrder(String tag) {
        String bizNo = benefitOrderService.createTrade(newTradeReq(tag)).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_" + tag, "N1", "SUCCESS"));
        return bizNo;
    }

    /** 建单 + 支付 + 驱动履约，grant 进 {@code GRANT_SUCCESS}。 */
    private String grantedOrder(String tag) {
        String bizNo = paidOrder(tag);
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        return bizNo;
    }

    private RevokeAdmitResp admit(String bizNo, String refundReqNo) {
        RevokeAdmitReq req = new RevokeAdmitReq();
        req.setBizNo(bizNo);
        req.setRefundReqNo(refundReqNo);
        req.setOperator("cs_alice");
        req.setReason("用户申请退款");
        return benefitOrderService.revokeAndAdmit(req);
    }

    // ------------------------------------------------------------------
    // 标准 10：五态分流
    // ------------------------------------------------------------------

    /**
     * 标准 10 ①：{@code NOT_START} <b>可直接退款，无需回收</b>。
     *
     * <p>「已支付未履约」是对账要自动补偿的头号场景。写成「未发放成功就不许退款」会把这类单永久 锁死 —— 钱收了、货没给、还退不了。
     */
    @Test
    void notStartedGrantIsAdmittedWithoutRevoke() {
        String bizNo = paidOrder("adm_ns");
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.NOT_START.name());

        int revokeBefore = revokeCalls();
        RevokeAdmitResp resp = admit(bizNo, "RQ1");

        assertThat(resp.isAdmitted()).as("从未发放的单必须可退").isTrue();
        assertThat(resp.getRetStatus()).isEqualTo(RetStatus.SUCCESS);
        assertThat(resp.isRevokeRequired()).as("无权益在外，不必回收").isFalse();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());
        assertThat(revokeCalls() - revokeBefore).as("不得调用供应方回收").isZero();
    }

    /** 标准 10 ②：{@code GRANT_FAILED} 同样可直接退 —— 发放已确定失败，无权益在外。 */
    @Test
    void failedGrantIsAdmittedWithoutRevoke() {
        injector.setProviderMode(FaultMode.FAIL);
        String bizNo = paidOrder("adm_gf");
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_FAILED.name());
        injector.reset();

        RevokeAdmitResp resp = admit(bizNo, "RQ2");

        assertThat(resp.isAdmitted()).as("发放失败的单必须可退").isTrue();
        assertThat(resp.isRevokeRequired()).isFalse();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());
    }

    /** 标准 10 ③：{@code GRANT_SUCCESS} <b>须先回收再退款</b>（BR-B-30）。 */
    @Test
    void succeededGrantIsAdmittedAfterRevoke() {
        String bizNo = grantedOrder("adm_gs");

        int revokeBefore = revokeCalls();
        RevokeAdmitResp resp = admit(bizNo, "RQ3");

        assertThat(resp.isAdmitted()).isTrue();
        assertThat(resp.getRetStatus()).isEqualTo(RetStatus.SUCCESS);
        assertThat(resp.isRevokeRequired()).as("已发放的单必须先回收").isTrue();
        assertThat(resp.getUsageStatus()).isEqualTo("REVOKED");
        assertThat(revokeCalls() - revokeBefore).as("须真的调用供应方回收").isPositive();
        assertThat(fulfillmentRevokedCount(bizNo)).as("回收须落明细留痕").isPositive();
    }

    /**
     * 标准 10 ④⑤：{@code GRANTING} / {@code GRANT_UNKNOWN} <b>拒绝</b>（BR-B-29）。
     *
     * <p>结果未定即回收对象不明：权益可能已发出、可能没有。此时退款要么「退了钱权益还在」，要么 回收一笔根本不存在的发放。
     */
    @Test
    void unsettledGrantIsRejected() {
        // GRANT_UNKNOWN：注入下游超时，履约后主单进未知态
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        String bizNo = paidOrder("adm_unk");
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        int revokeBefore = revokeCalls();
        assertThatThrownBy(() -> admit(bizNo, "RQ4"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("结果未定须拒绝，等查单收敛后再判")
                .isEqualTo(ErrorCode.GRANT_NOT_SETTLED);

        assertThat(orderField("refund_status", bizNo))
                .as("拒绝时不得改动退款态")
                .isEqualTo(RefundStatus.NONE.name());
        assertThat(revokeCalls() - revokeBefore).as("拒绝时不得调用回收").isZero();
    }

    /**
     * <b>已核销的权益回收失败，不得退款</b>（BR-B-30、{@code 1752}）。
     *
     * <p>判定由供应方原子完成并回传真实使用态 —— 平台先查再回收存在窗口：查到未使用、用户随即 核销、平台再回收，于是券已花掉而平台以为回收成功、退了钱。
     */
    @Test
    void usedBenefitCannotBeRevokedSoRefundIsBlocked() {
        String bizNo = grantedOrder("adm_used");
        // 用户把券花掉了 —— 布置在下游侧，平台读不到
        String grantOpNo = IdempotentKeys.grantOpNo(bizNo, "COUPON_PROVIDER");
        markAllGrantedUsed(bizNo);

        RevokeAdmitResp resp = admit(bizNo, "RQ5");

        assertThat(resp.isAdmitted()).as("回收不成功即不得退款").isFalse();
        assertThat(resp.getRetStatus()).isEqualTo(RetStatus.FAIL);
        assertThat(resp.getReasonCode()).isEqualTo(ErrorCode.BENEFIT_ALREADY_USED);
        assertThat(orderField("refund_status", bizNo))
                .as("回收确定失败进 REVOKE_FAILED，可人工重试")
                .isEqualTo(RefundStatus.REVOKE_FAILED.name());
        assertThat(grantOpNo).isNotNull();
    }

    /** 未支付的单没有钱可退 —— 比发放态更基本的一道校验。 */
    @Test
    void unpaidOrderCannotBeRefunded() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("adm_unpaid")).getBizNo();

        assertThatThrownBy(() -> admit(bizNo, "RQ6"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
    }

    // ------------------------------------------------------------------
    // 标准 11：先回收后退款，顺序可审计
    // ------------------------------------------------------------------

    /**
     * 标准 11：<b>{@code revoke_no} / {@code revoke_time} 先落，退款在其后</b>（BR-B-32/34）。
     *
     * <p>顺序可审计不是「代码里先写哪一行」，而是<b>库里留下的时间戳能证明先后</b>。{@code revoke_time} 由库时钟取，与操作记录的 {@code
     * finish_time} 同源 —— 两端出自不同时钟时，这条顺序在审计时 可能反过来。
     */
    @Test
    void revokeIsRecordedBeforeRefund() {
        String bizNo = grantedOrder("ord_seq");

        admit(bizNo, "RQ7");

        // 回收已留痕，且退款尚未发生
        assertThat(fulfillmentRevokedCount(bizNo)).isPositive();
        assertThat(orderField("refund_no", bizNo)).as("此刻还没有退款单号").isNull();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());

        benefitOrderService.createRefund(bizNo, "RQ7");

        assertThat(orderField("refund_no", bizNo)).isNotNull();
        // 回收时间 <= 退款操作完成时间：库时钟同源，可比较
        Integer ordered =
                num(
                        benefitJdbc,
                        "SELECT COUNT(*) FROM benefit_fulfillment_record f"
                                + " JOIN play_op_record o"
                                + " ON o.play_biz_record_no = f.play_biz_record_no"
                                + " WHERE f.play_biz_record_no = ? AND o.op_type = 'CREATE_REFUND'"
                                + " AND f.revoke_time <= o.finish_time",
                        bizNo);
        assertThat(ordered).as("回收时间须不晚于退款完成时间，顺序可审计").isPositive();
    }

    /**
     * <b>回收失败则不进入退款</b>。
     *
     * <p>这是「先回收后退款」的实际意义：顺序本身不重要，重要的是<b>回收没成功时钱退不出去</b>。 只断言时间先后而不断言这一条，一个「回收失败照样退款」的实现同样能让时间戳有序。
     */
    @Test
    void refundIsBlockedWhenRevokeFailed() {
        String bizNo = grantedOrder("ord_blocked");
        int refundBefore = payLedger.refundSize();
        markAllGrantedUsed(bizNo);
        admit(bizNo, "RQ8");
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKE_FAILED.name());

        assertThatThrownBy(() -> benefitOrderService.createRefund(bizNo, "RQ8"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("回收未成功不得退款")
                .isEqualTo(ErrorCode.REVOKE_NOT_DONE);

        assertThat(payLedger.refundSize() - refundBefore).as("支付方不得收到退款请求").isZero();
    }

    /**
     * <b>绕过准入直接调退款会被前置态谓词挡下</b>。
     *
     * <p>「先回收后退款」由 {@code startRefund} 的 {@code WHERE refund_status = 'REVOKING'} 强制 —— 而 {@code
     * REVOKING} 只能由准入置入。这是那条顺序的机器化形式，不靠调用方自觉。
     */
    @Test
    void refundWithoutAdmissionIsRejected() {
        String bizNo = grantedOrder("ord_bypass");
        int refundBefore = payLedger.refundSize();
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.NONE.name());

        assertThatThrownBy(() -> benefitOrderService.createRefund(bizNo, "RQ9"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.REVOKE_NOT_DONE);

        assertThat(payLedger.refundSize() - refundBefore).isZero();
    }

    /** 回收幂等：同 {@code refundReqNo} 重复准入不二次回收。 */
    @Test
    void repeatedAdmissionDoesNotRevokeTwice() {
        String bizNo = grantedOrder("adm_idem");

        admit(bizNo, "RQ10");
        int afterFirst = revokeCalls();

        // 第二次准入：主单已在 REVOKING，条件更新命中 0 行
        assertThatThrownBy(() -> admit(bizNo, "RQ10"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);

        assertThat(revokeCalls()).as("不得二次回收").isEqualTo(afterFirst);
    }

    // ------------------------------------------------------------------
    // PR-7/8 review 补：三处缺陷各自的用例
    // ------------------------------------------------------------------

    /**
     * <b>实付金额未知的单不受理退款，且不得先把权益回收掉</b>（review 缺陷 ①）。
     *
     * <p>{@code pay_amount} 可空是 {@code applyPaidAfterClosing} 有意留下的：关单受理后查单确认 「这笔已收款」时支付方并未回答收了多少，按
     * §5.6 的口径留空等通知回填。这条路径真实存在。
     *
     * <p>原实现在 {@code callPayRefund} 直接拆箱 {@code Long} 抛 NPE，而 NPE 被「异常一律映射 {@code UNKNOWN}」 捕获 ——
     * 主单进 {@code REFUNDING}、落查单任务、回报「结果未定」，<b>表现与支付方超时完全一样</b>， 而退款请求根本没发出去。且查单收敛救不回来：支付方查无该单恒答
     * {@code UNKNOWN}，重试至死信后 这笔单永停 {@code REFUNDING}。与 PR-7 的键长溢出同族。
     *
     * <p><b>断言必须落在「回收没有发生」上</b>：只断言 {@code createRefund} 抛错的话，一个「准入照常 回收、退款时才拒绝」的实现同样能通过 ——
     * 而那时权益已被收走且钱退不出去，用户既没权益也没钱， 正是本链路要防的那一类。故拒绝必须发生在准入阶段。
     */
    @Test
    void orderWithUnknownPayAmountIsRejectedBeforeAnyRevoke() {
        String bizNo = grantedOrder("np_amount");
        // 造出 pay_amount 为空的单 —— 与 applyPaidAfterClosing 收敛后的状态一致
        benefitJdbc.update(
                "UPDATE play_biz_record SET pay_amount = NULL WHERE play_biz_record_no = ?", bizNo);

        int revokeBefore = revokeCalls();
        int refundBefore = payLedger.refundSize();

        assertThatThrownBy(() -> admit(bizNo, "RQ11"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("金额未知须给出确定的业务拒绝，而非按未知态收敛")
                .isEqualTo(ErrorCode.PAY_AMOUNT_UNKNOWN);

        assertThat(revokeCalls() - revokeBefore).as("拒绝须早于回收，权益不得被收走").isZero();
        assertThat(payLedger.refundSize() - refundBefore).as("支付方不得收到退款").isZero();
        assertThat(orderField("refund_status", bizNo))
                .as("状态不得被推进")
                .isEqualTo(RefundStatus.NONE.name());
    }

    /**
     * {@code createRefund} 侧同样挡住金额未知（review 缺陷 ①，第二道）。
     *
     * <p>它是公开接口，不强制经过准入 —— 准入那道保证「回收不会白做」，这道保证「不会拆箱成 NPE 再被映射成 UNKNOWN」。两道各自可被单独调用，故都要有。
     *
     * <p>本用例把单推到 {@code REVOKING} 后才抹掉金额，绕过准入那道，验证的正是内层这道。<b>与 PR-8 的「服务层 {@code if} 遮蔽了 DB
     * 谓词」是同一处置</b>：多层实现的每一层都要单独验过。
     */
    @Test
    void createRefundAlsoRejectsUnknownPayAmount() {
        String bizNo = grantedOrder("np_amount2");
        admit(bizNo, "RQ12");
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKING.name());

        benefitJdbc.update(
                "UPDATE play_biz_record SET pay_amount = NULL WHERE play_biz_record_no = ?", bizNo);
        int refundBefore = payLedger.refundSize();

        assertThatThrownBy(() -> benefitOrderService.createRefund(bizNo, "RQ12"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PAY_AMOUNT_UNKNOWN);

        assertThat(payLedger.refundSize() - refundBefore).as("支付方不得收到退款").isZero();
        assertThat(orderField("refund_status", bizNo))
                .as("拒绝后不得停在 REFUNDING —— 那是永不收敛的黑洞")
                .isEqualTo(RefundStatus.REVOKING.name());
    }

    /**
     * <b>部分核销时，已回收成功的那一项必须留痕</b>（review 缺陷 ②）。
     *
     * <p>一单跨两个供应方，A 已核销、B 未使用：整笔汇总为失败（权益还在，不得退款），<b>但 B 的券 确实已经被供应方收走了</b>。
     *
     * <p>原实现的 {@code markRevoked} 谓词是「一单一次」，而回收本身逐供应方发起 —— 汇总失败时一条 留痕都不落。后果是对账第 2 项（已退款权益未回收）把 B
     * 判成差异并补一次回收，<b>而这条项是 资损哨兵，假阳性会让它失效</b>；人工据此处置时也会以为一件都没收回来。
     *
     * <p>三条断言各挡一类：下游确实收了一件、平台确实记了一件、且只记了那一件（不能把 A 也标上 —— 那是反方向的错，对账会以为 A 已回收而放过一笔真的没收回来的券）。
     */
    @Test
    void successfullyRevokedItemIsRecordedEvenWhenOverallRevokeFails() {
        String bizNo = grantedOrder("partial_used");
        java.util.List<String> opNos =
                benefitJdbc.queryForList(
                        "SELECT grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? AND grant_status = 'SUCCESS'"
                                + " ORDER BY provider_type",
                        String.class,
                        bizNo);
        assertThat(opNos).as("seed 权益包须跨两个供应方，否则本用例验不到部分失败").hasSize(2);
        // 只核销 A，B 保持未使用
        providerLedger.markUsage(opNos.get(0), "USED");

        int revokeBefore = revokeCalls();
        RevokeAdmitResp resp = admit(bizNo, "RQ13");

        assertThat(resp.getRetStatus()).as("有一件收不回来，整笔即失败").isEqualTo(RetStatus.FAIL);
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.REVOKE_FAILED.name());
        assertThat(revokeCalls() - revokeBefore).as("B 确实被下游收走了").isEqualTo(1);

        assertThat(fulfillmentRevokedCount(bizNo)).as("已收走的那一件必须留痕，否则对账第 2 项假阳性").isEqualTo(1);
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT grant_op_no FROM benefit_fulfillment_record"
                                        + " WHERE play_biz_record_no = ? AND revoke_no IS NOT NULL",
                                bizNo))
                .as("留痕的必须是真被收走的那一件，不能是已核销的 A")
                .isEqualTo(opNos.get(1));
    }

    /**
     * <b>部分核销时 {@code usageStatus} 取最阻断的那一项</b>（review 缺陷 ②，第二半）。
     *
     * <p>原实现在循环里逐条覆盖，汇总值取决于 {@code selectGranted} 的返回顺序：A 已核销、B 回收成功 时最终留下 {@code
     * REVOKED}，于是调用方看到「失败，但券没被用过」——与事实相反。而 {@code reasonCode} 正按这个值在 {@code 1752}（已核销，确定收不回）与
     * {@code 1753}（回收未完成） 之间选码，人工会把一笔永远不可能成功的回收当成普通出错去重试。
     */
    @Test
    void usageStatusAggregatesToTheMostBlockingItem() {
        String bizNo = grantedOrder("partial_usage");
        java.util.List<String> opNos =
                benefitJdbc.queryForList(
                        "SELECT grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? AND grant_status = 'SUCCESS'"
                                + " ORDER BY provider_type",
                        String.class,
                        bizNo);
        providerLedger.markUsage(opNos.get(0), "USED");

        RevokeAdmitResp resp = admit(bizNo, "RQ14");

        assertThat(resp.getUsageStatus()).as("已核销不能被后一项的 REVOKED 盖掉").isEqualTo("USED");
        assertThat(resp.getReasonCode())
                .as("已核销是确定收不回，须区别于「回收未完成」")
                .isEqualTo(ErrorCode.BENEFIT_ALREADY_USED);
    }

    // ---- 断言辅助 ----

    private int fulfillmentRevokedCount(String bizNo) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no = ?"
                        + " AND revoke_no IS NOT NULL",
                bizNo);
    }

    /** 把该单已发放的全部权益标为已核销 —— 布置在下游侧，平台读不到。 */
    private void markAllGrantedUsed(String bizNo) {
        benefitJdbc
                .queryForList(
                        "SELECT grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? AND grant_status = 'SUCCESS'",
                        String.class,
                        bizNo)
                .forEach(opNo -> providerLedger.markUsage(opNo, "USED"));
    }
}
