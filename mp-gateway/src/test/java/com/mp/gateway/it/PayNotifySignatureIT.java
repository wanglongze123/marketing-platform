package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.PayStatus;
import com.mp.common.exception.BizException;
import com.mp.common.security.PayNotifySigner;
import org.junit.jupiter.api.Test;

/**
 * 支付通知验签，对应 PRD FR-B03 ①、BR-B-12、错误码 {@code 4731}。
 *
 * <p><b>BR-B-12「未通过验签的通知不得更新任何业务状态」是本类的核心断言</b>，且「任何」要按字面 理解 ——
 * 状态、操作记录、可靠任务，一样都不能留。留痕看似无害，实则给了攻击者一个无需密钥 就能写库的入口。
 *
 * <p>验签与金额校验（{@code 1731}）挡的是两件事，缺任一件另一件都补不上：
 *
 * <ul>
 *   <li>金额校验证明「金额与本单应付一致」，挡不住<b>照着真实订单金额伪造</b>的通知 —— 伪造者 只需先自己下一单，就知道该填多少
 *   <li>验签证明「这条通知来自支付方」，挡不住支付方自己发错金额
 * </ul>
 */
class PayNotifySignatureIT extends AbstractMySqlIT {

    /**
     * 正常带签名的通知照常推进 —— 先确认验签没有把正常链路挡住。
     *
     * <p>这条看似多余，实则是「验签是否接错了线」的判据：把 {@code verify} 写成恒 false，其余用例 全部照常通过（它们本就期望被拒），只有这一条会红。
     */
    @Test
    void properlySignedNotificationIsAccepted() {
        String bizNo = createOrder("signOk");

        benefitOrderService.payCallback(
                newPayCallback(bizNo, "PAY1_" + bizNo, "NS_signOk", "SUCCESS"));

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());
    }

    /**
     * <b>无签名的通知被拒，且不留任何痕迹</b>（BR-B-12）。
     *
     * <p>「不留痕迹」与「被拒」必须同时断言：先落操作记录再验签同样会抛 {@code 4731}，只断言异常 则那种实现照样全绿 —— 而它已经让未验签的请求写进了业务库。
     */
    @Test
    void unsignedNotificationChangesNothing() {
        String bizNo = createOrder("noSign");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_noSign", "SUCCESS");
        req.setSign(null);

        assertRejectedWithoutTrace(req, bizNo);
    }

    /** 伪造签名被拒 —— 攻击者不知道密钥，签不出能验过的通知。 */
    @Test
    void forgedSignatureChangesNothing() {
        String bizNo = createOrder("forgedSign");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_forged", "SUCCESS");
        req.setSign("ZmFrZS1zaWduYXR1cmU");

        assertRejectedWithoutTrace(req, bizNo);
    }

    /**
     * <b>篡改金额后签名失效。</b>
     *
     * <p>先按正确金额签名，再把金额改小 —— 这是最直接的「少付多得」。注意它<b>先于</b>金额校验 被拦下：两道防线的顺序决定了攻击者能探测到什么。
     */
    @Test
    void tamperedAmountChangesNothing() {
        String bizNo = createOrder("tamperAmount");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_amt", "SUCCESS");
        req.setPayAmount(1L);

        assertRejectedWithoutTrace(req, bizNo);
    }

    /**
     * <b>拿别单的合法通知改订单号，被拒。</b>
     *
     * <p>这条挡的是「签名只覆盖金额」那种实现：那样的话，攻击者拿自己那笔 99 元订单的真实通知， 把 {@code outTradeNo} 改成别人的单，金额没变、签名照样对 ——
     * 别人的订单就被他付款了。
     */
    @Test
    void reusingAnotherOrdersNotificationChangesNothing() {
        String victimBizNo = createOrder("signVictim");
        String attackerBizNo = createOrder("signAttacker");

        // 攻击者自己那单的合法通知
        PayCallbackReq legit =
                newPayCallback(attackerBizNo, "PAY1_" + attackerBizNo, "NS_atk", "SUCCESS");
        assertThat(payNotifySigner.verify(legit.signFields(), legit.getSign()))
                .as("前置：这条通知本身是合法的")
                .isTrue();

        // 只改订单号，签名不动
        legit.setOutTradeNo(victimBizNo);

        assertRejectedWithoutTrace(legit, victimBizNo);
        assertThat(orderField("pay_status", attackerBizNo))
                .as("攻击者自己那单也不该被影响")
                .isEqualTo(PayStatus.WAIT_PAY.name());
    }

    /**
     * <b>把 FAILED 改成 SUCCESS 被拒。</b>
     *
     * <p>伪造收款最直接的形态：拿一条真实的失败通知，把结果字段改掉。
     */
    @Test
    void tamperedPayStatusChangesNothing() {
        String bizNo = createOrder("tamperStatus");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_st", "FAILED");
        req.setPayStatus("SUCCESS");

        assertRejectedWithoutTrace(req, bizNo);
    }

    /**
     * 用错密钥签的通知被拒。
     *
     * <p>验的是密钥确实参与了运算 —— 若 {@code verify} 忽略密钥（如比对的是明文摘要），本条会绿而 安全性荡然无存。
     */
    @Test
    void notificationSignedWithWrongKeyChangesNothing() {
        String bizNo = createOrder("wrongKey");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_wk", "SUCCESS");
        req.setSign(new PayNotifySigner("some-other-secret").sign(req.signFields()));

        assertRejectedWithoutTrace(req, bizNo);
    }

    /**
     * <b>验签先于金额校验</b>：金额不符<b>且</b>签名不对时，报的是 {@code 4731} 而非 {@code 1731}。
     *
     * <p>顺序不是风格问题。金额校验会读主单——那意味着未验签的请求已经能通过响应差异探测「这个 {@code bizNo}
     * 存不存在」「它的应付金额是多少」。<b>信任边界之外的输入，在通过验签以前 不该触碰任何业务数据。</b>
     */
    @Test
    void signatureIsVerifiedBeforeAmountCheck() {
        String bizNo = createOrder("orderOfChecks");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_ooc", "SUCCESS");
        req.setPayAmount(12345L); // 金额既不对，签名也随之失效

        assertThatThrownBy(() -> benefitOrderService.payCallback(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("应先在验签处被拒，而非走到金额校验")
                .isEqualTo(ErrorCode.PAY_NOTIFY_SIGN_INVALID);
    }

    /**
     * 验签通过但金额不符时，仍由金额校验拦下（{@code 1731}）。
     *
     * <p>说明两道防线各自独立：不能因为「验签过了」就信任金额 —— 支付方自己也可能发错。
     */
    @Test
    void validSignatureWithWrongAmountStillRejected() {
        String bizNo = createOrder("amountOnly");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_ao", "SUCCESS");
        req.setPayAmount(12345L);
        // 按改后的金额重新签名：签名合法，但金额与本单应付不符
        req.setSign(payNotifySigner.sign(req.signFields()));

        assertThatThrownBy(() -> benefitOrderService.payCallback(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PAY_AMOUNT_MISMATCH);

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());
    }

    /**
     * <b>他人商户的通知被拒</b>（{@code 1731}，技术方案 §5.3 ①.5）。
     *
     * <p>本条与 {@link #notificationSignedWithWrongKeyChangesNothing} 的差别正是商户校验存在的理由：那条
     * 用错密钥签，本条<b>用正确的密钥签</b> —— 签名合法，只是商户号不是本平台的。
     *
     * <p>它对应两个真实场景：支付方多商户共用一把通知密钥；或密钥泄露后照着别家商户的单伪造。 两者<b>验签都会通过</b>，只有商户校验拦得住。
     */
    @Test
    void notificationFromAnotherMerchantIsRejected() {
        String bizNo = createOrder("foreignMch");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_fm", "SUCCESS");
        req.setMerchantId("MCH_SOMEONE_ELSE");
        // 按改后的商户号重新签名：签名合法，商户号不是本平台的
        req.setSign(payNotifySigner.sign(req.signFields()));
        assertThat(payNotifySigner.verify(req.signFields(), req.getSign()))
                .as("用例前提：这条通知验签必须是通过的，否则测的是验签而非商户校验")
                .isTrue();

        assertThatThrownBy(() -> benefitOrderService.payCallback(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PAY_AMOUNT_MISMATCH);

        assertThat(orderField("pay_status", bizNo))
                .as("商户不符不得推进支付态")
                .isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK")).as("商户不符不得留操作记录").isZero();
    }

    /**
     * <b>商户校验对非收款通知同样生效</b> —— 这条守的是校验的<b>位置</b>。
     *
     * <p>把商户校验写进 {@code PAY_SUCCESS} 分支（与金额校验并排）是最自然的写法，而那样只拦收款通知： 一条来自他人商户的 {@code CLOSED}
     * 通知照样能把本单关掉，库存与额度随之释放。
     *
     * <p>两者的判据不同：金额只在收款通知里有内容可校验（{@code FAILED} / {@code CLOSED} 不带金额是常态）， 而「这笔属不属于本商户」对每一类通知都成立。
     */
    @Test
    void merchantIsCheckedOnNonPaymentNotificationsToo() {
        String bizNo = createOrder("foreignMchClose");

        PayCallbackReq req = newPayCallback(bizNo, "PAY1_" + bizNo, "NS_fmc", "FAILED");
        req.setMerchantId("MCH_SOMEONE_ELSE");
        req.setSign(payNotifySigner.sign(req.signFields()));

        assertThatThrownBy(() -> benefitOrderService.payCallback(req))
                .as("非收款通知也须校验商户 —— 否则他人商户能关掉本平台的单")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PAY_AMOUNT_MISMATCH);

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());
    }

    // ------------------------------------------------------------------

    private String createOrder(String tag) {
        return benefitOrderService.createTrade(newTradeReq(tag)).getBizNo();
    }

    /**
     * 被拒 + <b>零痕迹</b>。
     *
     * <p>三处同时断言：状态未变、无操作记录、无新任务。只断言状态则「先落操作记录再验签」的实现 照常全绿 —— 而那已经让未验签的请求写进了业务库。
     */
    private void assertRejectedWithoutTrace(PayCallbackReq req, String bizNo) {
        assertThatThrownBy(() -> benefitOrderService.payCallback(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PAY_NOTIFY_SIGN_INVALID);

        assertThat(orderField("pay_status", bizNo))
                .as("验签失败不得推进支付态")
                .isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK"))
                .as("验签失败不得留操作记录 —— 留痕等于给了无密钥写库的入口")
                .isZero();
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type IN ('GRANT', 'STOCK_CONSUME',"
                                        + " 'STOCK_RELEASE')",
                                bizNo))
                .as("验签失败不得落任何履约或库存任务")
                .isZero();
    }
}
