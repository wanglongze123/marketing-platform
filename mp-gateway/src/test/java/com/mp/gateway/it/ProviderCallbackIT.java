package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.reward.dto.ProviderCallbackReq;
import com.mp.api.reward.dto.ProviderCallbackResp;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.security.ProviderNotifySigner;
import com.mp.common.util.IdempotentKeys;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
import com.mp.mock.fault.ProviderLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 供应方异步回调与发奖结果事件（FR-B06、技术方案 §6.7），对应《分阶段方案》§6.5 退出标准 18、19。
 *
 * <p><b>回调的幂等判据是 {@code (opNo, notifySeq)} 两维，不是 {@code opNo} 一维</b>：同一笔发放会收到
 * 多条语义不同的通知（先受理后成功、供应方补发），只按 {@code opNo} 去重会把第二条真实通知当成 重传丢弃。本类的用例分别覆盖这两个方向 ——
 * 同一条重投须只处理一次，不同的两条须都留痕。
 *
 * <p>退出标准 17（关掉事件仍收敛）不在本类，它需要另起一个禁用事件的上下文，见 {@code EventDisabledConvergenceIT}。
 */
class ProviderCallbackIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private PayLedger payLedger;
    @Autowired private ProviderLedger providerLedger;
    @Autowired private RewardService rewardService;
    @Autowired private ProviderNotifySigner providerNotifySigner;

    /**
     * 复位注入，<b>并清掉定向失败</b>。
     *
     * <p>{@code ProviderLedger} 是全局单例，定向失败设置会跨用例存活 —— 首版漏了这一句，实测下一个 用例的 {@code PROVIDER_A} 拿到
     * {@code FAIL}（{@code expected SUCCESS but was FAIL}）。
     *
     * <p>与 {@code RefundAdmissionIT} 记的「账本计数器是全局的」是同一类陷阱：共享状态上的布置若不
     * 清理，通过与否取决于用例执行顺序。差别是那处要改断言方式（取增量），这处要清状态 —— 因为 定向失败是布置而非计数。
     */
    @AfterEach
    void reset() {
        injector.reset();
        providerLedger.clearFailingProducts();
        providerLedger.clearDelays();
    }

    /** 建单 + 支付，发放停在 {@code GRANT_UNKNOWN}（注入下游超时），返回 bizNo。 */
    private String unknownGrantOrder(String tag) {
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        String bizNo = benefitOrderService.createTrade(newTradeReq(tag)).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_" + tag, "N1", "SUCCESS"));
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());
        injector.reset();
        return bizNo;
    }

    /**
     * 构造一条已签名的通知。
     *
     * <p><b>签名走真实签名器，不硬编码</b>：硬编码验的是「测试造的签名能被验过」，而签发侧与验签侧的 字段集合是否一致则验不到 —— 而那正是验签要挡的东西（与 {@code
     * newPayCallback} 同一处置）。
     */
    private ProviderCallbackReq notify(String opNo, String seq, RetStatus result) {
        ProviderCallbackReq req = new ProviderCallbackReq();
        req.setOpNo(opNo);
        req.setNotifySeq(seq);
        req.setResult(result);
        req.setProviderOrderNo("PRV_" + seq + "_" + opNo);
        req.setSign(providerNotifySigner.sign(req.signFields()));
        return req;
    }

    private String grantOpNo(String bizNo, String provider) {
        return IdempotentKeys.grantOpNo(bizNo, provider);
    }

    private int notifyCount(String opNo) {
        return count(rewardJdbc, "SELECT COUNT(*) FROM reward_notify_record WHERE op_no = ?", opNo);
    }

    private String grantRecordResult(String opNo) {
        return str(rewardJdbc, "SELECT result FROM reward_grant_record WHERE op_no = ?", opNo);
    }

    // ------------------------------------------------------------------
    // 主链路：回调推进发放态，事件把玩法层一并带到终态
    // ------------------------------------------------------------------

    /**
     * 回调收敛 {@code UNKNOWN}：发放记录进终态，<b>事件把主单也带到终态</b>。
     *
     * <p>这是「事件加速收敛」的正面用例 —— 没有事件的话，主单要等下一轮 {@code QUERY_GRANT} 才动。
     */
    @Test
    void callbackAdvancesGrantAndEventSettlesOrder() {
        String bizNo = unknownGrantOrder("cb_ok");
        String opNoA = grantOpNo(bizNo, "PROVIDER_A");
        String opNoB = grantOpNo(bizNo, "PROVIDER_B");

        ProviderCallbackResp respA =
                rewardService.providerCallback(notify(opNoA, "S1", RetStatus.SUCCESS));
        ProviderCallbackResp respB =
                rewardService.providerCallback(notify(opNoB, "S1", RetStatus.SUCCESS));

        assertThat(respA.isAccepted()).isTrue();
        assertThat(respA.isDuplicated()).isFalse();
        assertThat(respB.isAccepted()).isTrue();
        assertThat(grantRecordResult(opNoA)).isEqualTo(RetStatus.SUCCESS.name());

        // 事件已把主单带到终态，无需等查单
        assertThat(orderField("grant_status", bizNo))
                .as("事件应当把主单一并推进，这正是它存在的意义")
                .isEqualTo(GrantStatus.GRANT_SUCCESS.name());
    }

    // ------------------------------------------------------------------
    // 标准 18：providerCallback 幂等
    // ------------------------------------------------------------------

    /**
     * 标准 18：<b>同一条通知重复投递只处理一次</b>。
     *
     * <p>三条断言各挡一类：通知记录不增（{@code uk_notify} 生效）、发放态与供应方单号不变（不被二次 改写）、返回 {@code accepted=true}（ACK 语义
     * —— 返回失败会让供应方一直重投）。
     */
    @Test
    void duplicatedNotifyIsProcessedOnlyOnce() {
        String bizNo = unknownGrantOrder("cb_dup");
        String opNo = grantOpNo(bizNo, "PROVIDER_A");

        rewardService.providerCallback(notify(opNo, "S1", RetStatus.SUCCESS));
        String orderNoAfterFirst =
                str(
                        rewardJdbc,
                        "SELECT provider_order_no FROM reward_grant_item WHERE op_no = ?",
                        opNo);

        ProviderCallbackResp again =
                rewardService.providerCallback(notify(opNo, "S1", RetStatus.SUCCESS));

        assertThat(again.isAccepted()).as("重传须 ACK，否则供应方会一直重投").isTrue();
        assertThat(again.isDuplicated()).isTrue();
        assertThat(notifyCount(opNo)).as("同一条通知不得留两条记录").isEqualTo(1);
        assertThat(grantRecordResult(opNo)).isEqualTo(RetStatus.SUCCESS.name());
        assertThat(
                        str(
                                rewardJdbc,
                                "SELECT provider_order_no FROM reward_grant_item WHERE op_no = ?",
                                opNo))
                .as("供应方单号不得被二次改写")
                .isEqualTo(orderNoAfterFirst);
    }

    /**
     * <b>{@code notifySeq} 不同即两条不同的通知，都要留痕</b>。
     *
     * <p>这条与上一条方向相反，缺了它一个「只按 {@code opNo} 去重」的实现照样全绿 —— 而那种实现会把 供应方补发的第二条真实通知静默丢弃。
     *
     * <p>发放记录仍只推进一次（条件更新限定 {@code PROCESSING}），这是两道闸各司其职：{@code uk_notify}
     * 管「通知只处理一次」，条件更新管「终态不被覆盖」。
     */
    @Test
    void differentNotifySeqAreBothRecorded() {
        String bizNo = unknownGrantOrder("cb_two");
        String opNo = grantOpNo(bizNo, "PROVIDER_A");

        rewardService.providerCallback(notify(opNo, "S1", RetStatus.SUCCESS));
        ProviderCallbackResp second =
                rewardService.providerCallback(notify(opNo, "S2", RetStatus.SUCCESS));

        assertThat(second.isDuplicated()).as("流水号不同即不是重传").isFalse();
        assertThat(notifyCount(opNo)).as("两条不同的通知各留一条").isEqualTo(2);
        assertThat(grantRecordResult(opNo))
                .as("发放态仍只被推进一次，终态不被覆盖")
                .isEqualTo(RetStatus.SUCCESS.name());
    }

    /**
     * <b>后到的通知不得覆盖已终结的结果</b>。
     *
     * <p>本用例由注入自查补入：把 {@code settleUnresolved} 的 {@code result NOT IN ('SUCCESS','FAIL')} 谓词 去掉后，此前
     * 9 条用例<b>全部保持绿色</b> —— 因为它们发的两条通知携带的结果相同（都是 {@code SUCCESS}），覆盖与不覆盖的结果完全一样，那道谓词根本不可观测。
     *
     * <p>故本用例让第二条通知携带<b>相反的结果</b>：供应方补发一条迟到的失败通知，或查单与通知并发 到达而较早的结果后到。断言明细与汇总都停在 {@code SUCCESS} ——
     * 一笔已确认成功的发放不得被翻掉。
     *
     * <p><b>这与 §5.9 记的「多层遮蔽」是同一族的又一种形态</b>：约束存在、用例齐全，但用例的输入让 约束的两侧取值相同 ——
     * 断言够得着，只是分辨不出。破除方式是让两侧取值不同。
     */
    @Test
    void lateNotifyDoesNotOverwriteSettledResult() {
        String bizNo = unknownGrantOrder("cb_late");
        String opNo = grantOpNo(bizNo, "PROVIDER_A");

        rewardService.providerCallback(notify(opNo, "S1", RetStatus.SUCCESS));
        assertThat(grantRecordResult(opNo)).isEqualTo(RetStatus.SUCCESS.name());

        // 供应方补发一条迟到的失败通知（不同流水号，故不是重传）
        ProviderCallbackResp late =
                rewardService.providerCallback(notify(opNo, "S2", RetStatus.FAIL));

        assertThat(late.isAccepted()).as("迟到的通知照常 ACK，只是不改状态").isTrue();
        assertThat(notifyCount(opNo)).as("它是一条真实的通知，须留痕").isEqualTo(2);
        assertThat(grantRecordResult(opNo))
                .as("已确认成功的发放不得被迟到的失败翻掉")
                .isEqualTo(RetStatus.SUCCESS.name());
        assertThat(str(rewardJdbc, "SELECT result FROM reward_grant_item WHERE op_no = ?", opNo))
                .as("明细同样不得被覆盖 —— 覆盖了主单看着对、明细却是错的")
                .isEqualTo(RetStatus.SUCCESS.name());
    }

    /**
     * <b>验签不过一律拒绝，且不留任何痕迹</b>（BR-B-12 的同一条）。
     *
     * <p>留痕等于给伪造者一个无需密钥就能写库的入口。误判代价在此特别高：伪造的成功通知会让发放 记录进终态，而<b>终态不再被查单推进</b> ——
     * 这笔发放永远停在「已成功」，供应方那边什么都没有。 它不像重复发奖能被对账第 11 项数出来，因为记录数是对的。
     */
    @Test
    void invalidSignatureIsRejectedWithoutTrace() {
        String bizNo = unknownGrantOrder("cb_sig");
        String opNo = grantOpNo(bizNo, "PROVIDER_A");

        ProviderCallbackReq forged = notify(opNo, "S9", RetStatus.SUCCESS);
        forged.setSign("forged-signature");

        ProviderCallbackResp resp = rewardService.providerCallback(forged);

        assertThat(resp.isAccepted()).as("不可信的通知不得 ACK").isFalse();
        assertThat(resp.getErrorCode()).isEqualTo(ErrorCode.PROVIDER_NOTIFY_SIGN_INVALID);
        assertThat(notifyCount(opNo)).as("未验签的通知不得留痕").isZero();
        assertThat(grantRecordResult(opNo))
                .as("未验签的通知不得推进任何状态")
                .isEqualTo(RetStatus.PROCESSING.name());
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());
    }

    /**
     * <b>改一个不参与业务的字段也会验签失败</b> —— 签名覆盖全部业务字段，不只是结果。
     *
     * <p>只签结果的话，攻击者可以拿一条真实的成功通知改掉 {@code opNo}，把 A 单的发放算到 B 单头上： 结果没变、签名照样对。本用例改 {@code opNo}
     * 后签名保持原值，模拟的正是这种篡改。
     */
    @Test
    void tamperedOpNoFailsVerification() {
        String bizNo = unknownGrantOrder("cb_tamper");
        String opNoA = grantOpNo(bizNo, "PROVIDER_A");
        String opNoB = grantOpNo(bizNo, "PROVIDER_B");

        ProviderCallbackReq req = notify(opNoA, "S1", RetStatus.SUCCESS);
        // 签名已算好，此刻把收款方换成另一笔发放
        req.setOpNo(opNoB);

        ProviderCallbackResp resp = rewardService.providerCallback(req);

        assertThat(resp.isAccepted()).isFalse();
        assertThat(notifyCount(opNoB)).as("被篡改的目标不得留痕").isZero();
    }

    /** 非终态的通知直接拒绝 —— 供应方不会通知「我也不知道」，这类报文要么构造要么对接错误。 */
    @Test
    void nonTerminalNotifyIsRejected() {
        String bizNo = unknownGrantOrder("cb_mid");
        String opNo = grantOpNo(bizNo, "PROVIDER_A");

        ProviderCallbackResp resp =
                rewardService.providerCallback(notify(opNo, "S1", RetStatus.PROCESSING));

        assertThat(resp.isAccepted()).isFalse();
        assertThat(notifyCount(opNo)).isZero();
    }

    /**
     * 失败通知同样收敛，主单进 {@code GRANT_FAILED}。
     *
     * <p>只测成功那条的话，一个「无论通知说什么都置成功」的实现照样全绿。
     */
    @Test
    void failureNotifyConvergesToFailed() {
        String bizNo = unknownGrantOrder("cb_fail");
        String opNoA = grantOpNo(bizNo, "PROVIDER_A");
        String opNoB = grantOpNo(bizNo, "PROVIDER_B");

        rewardService.providerCallback(notify(opNoA, "S1", RetStatus.FAIL));
        rewardService.providerCallback(notify(opNoB, "S1", RetStatus.FAIL));

        assertThat(grantRecordResult(opNoA)).isEqualTo(RetStatus.FAIL.name());
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_FAILED.name());
    }

    // ------------------------------------------------------------------
    // 标准 19：履约扇出不 fail-fast
    // ------------------------------------------------------------------

    /**
     * 标准 19：<b>一组供应方失败时，其余组照常完成</b>。
     *
     * <p>失败形态是「B 组的调用发出了但结果无人收敛」，而主单状态看起来正常 —— 只断言主单终态发现 不了，<b>必须断言 B 组明细的实际状态</b>。
     *
     * <p>本用例用注入让两组都失败无法区分 fail-fast 与逐个失败，故构造的是「A 组失败、B 组成功」： 若实现用了 {@code invokeAny} 或在任一 future
     * 异常时提前返回，B 组的明细会停在非终态。
     *
     * <p><b>下游账本的计数是关键断言</b>：fail-fast 的形态是「调用发出去了但本地没记」，只看平台 侧的明细看不出区别 —— 那正是资损的成因。
     */
    @Test
    void fanOutDoesNotCancelSiblingsWhenOneProviderFails() {
        // A 组失败：mock 供应方对 PROVIDER_A 的产品返回失败，其余正常
        providerLedger.failProduct("PROD_A_001");
        String bizNo = benefitOrderService.createTrade(newTradeReq("fanout_partial")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_fanout", "N1", "SUCCESS"));

        int grantedBefore = providerLedger.size();
        runScheduler();

        assertThat(orderField("grant_status", bizNo))
                .as("有一组确定失败，整笔即失败")
                .isEqualTo(GrantStatus.GRANT_FAILED.name());

        // 关键：B 组必须真的执行完并落终态，而不是「未执行」
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_fulfillment_record"
                                        + " WHERE play_biz_record_no = ? AND provider_type = ?"
                                        + " AND grant_status = 'SUCCESS'",
                                bizNo,
                                "PROVIDER_B"))
                .as("A 组失败不得取消 B 组 —— 那会让已发出的调用无人收敛")
                .isEqualTo(1);
        assertThat(providerLedger.size() - grantedBefore)
                .as("下游确实收到了 B 组的发放，fail-fast 的形态正是「发出去了但本地没记」")
                .isEqualTo(1);
    }

    /**
     * 标准 19 的<b>可观测形态</b>：慢的那一组必须跑完，不得被先失败的一组取消。
     *
     * <p>本用例由注入自查补入。把扇出改成 fail-fast（任一失败即 {@code cancel} 其余）后，此前 10 条 用例<b>全部保持绿色</b> —— 因为 mock
     * 全部瞬时返回，取消发出去的时候其余组早已跑完，两种实现的 结果一模一样。<b>约束存在、用例齐全，而输入让约束的两侧取值相同。</b>
     *
     * <p>破除方式是给 B 组布置 300ms 延迟，让取消真的能打断它。这与 PR-6 的「数据量不足一页」同族 —— 那里靠灌数据破除，这里靠加耗时。
     *
     * <p><b>断言落在下游账本上</b>：fail-fast 的失效形态是「调用发出去了、本地没记」，只看平台侧 的明细分辨不出 —— 那正是资损的成因。
     */
    @Test
    void slowSiblingIsNotCancelledWhenAnotherProviderFailsFast() {
        providerLedger.failProduct("PROD_A_001");
        // B 组慢到取消能打断它 —— 没有这个延迟，fail-fast 与逐个跑完无法区分
        providerLedger.delayProduct("PROD_B_001", 300);

        String bizNo = benefitOrderService.createTrade(newTradeReq("fanout_slow")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_slow", "N1", "SUCCESS"));

        int grantedBefore = providerLedger.size();
        runScheduler();

        assertThat(providerLedger.size() - grantedBefore)
                .as("慢的那一组必须跑完并记账 —— fail-fast 会在它返回前把它 interrupt")
                .isEqualTo(1);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_fulfillment_record"
                                        + " WHERE play_biz_record_no = ? AND provider_type = ?"
                                        + " AND grant_status = 'SUCCESS'",
                                bizNo,
                                "PROVIDER_B"))
                .as("被取消的组会停在非终态，而它的调用其实已经发出去了")
                .isEqualTo(1);
    }

    /** 扇出的两组各自派生独立的幂等键，互不干扰。 */
    @Test
    void fanOutDerivesIndependentKeysPerProvider() {
        String bizNo = benefitOrderService.createTrade(newTradeReq("fanout_keys")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_keys", "N1", "SUCCESS"));
        runScheduler();

        assertThat(grantRecordResult(grantOpNo(bizNo, "PROVIDER_A")))
                .isEqualTo(RetStatus.SUCCESS.name());
        assertThat(grantRecordResult(grantOpNo(bizNo, "PROVIDER_B")))
                .isEqualTo(RetStatus.SUCCESS.name());
        assertThat(grantRecordCount(bizNo)).as("一供应方一条发放记录").isEqualTo(2);
    }
}
