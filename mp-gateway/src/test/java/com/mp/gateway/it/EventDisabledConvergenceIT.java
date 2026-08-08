package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.reward.dto.ProviderCallbackReq;
import com.mp.api.reward.service.RewardService;
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
import org.springframework.test.context.TestPropertySource;

/**
 * 退出标准 17：<b>事件丢失不产生资损，只退化收敛时间</b>（技术方案 §6.7）。
 *
 * <p><b>这是「事件加速、查单保证」这条论断的唯一验证手段</b>：事件正常工作时，收敛究竟由谁完成 无法区分——两条路径都会把状态推到终态，从结果上看不出是谁做的。必须把事件关掉，才能证明
 * 查单这条 lower bound 真的在。
 *
 * <p>结构上与 V2 第 21 条（去锁对照组）、PR-6 的基线过滤对照组同源：<b>把加速手段拿掉，断言正确性 不变、只有耗时变化</b>。三处都用同一种手段 —— 一个开关 +
 * 一个独立上下文。
 *
 * <p>{@code mp.event.enabled=false} 使 {@code GrantResultPublisher} 换成空实现。<b>消费侧的监听器仍然
 * 注册着</b>，只是收不到事件 —— 这比删掉监听器更接近真实的「消息丢了」。
 */
@TestPropertySource(properties = "mp.event.enabled=false")
class EventDisabledConvergenceIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private PayLedger payLedger;
    @Autowired private ProviderLedger providerLedger;
    @Autowired private RewardService rewardService;
    @Autowired private ProviderNotifySigner providerNotifySigner;

    @AfterEach
    void reset() {
        injector.reset();
    }

    private ProviderCallbackReq notify(String opNo, String seq, RetStatus result) {
        ProviderCallbackReq req = new ProviderCallbackReq();
        req.setOpNo(opNo);
        req.setNotifySeq(seq);
        req.setResult(result);
        req.setProviderOrderNo("PRV_" + seq + "_" + opNo);
        req.setSign(providerNotifySigner.sign(req.signFields()));
        return req;
    }

    /**
     * 标准 17：<b>回调到达但事件没发出去，主单仍由 {@code QUERY_GRANT} 收敛，且发放记录仍为 1 条</b>。
     *
     * <p>三条断言缺一不可：
     *
     * <ul>
     *   <li>事件关掉后主单<b>立刻不动</b>——证明前一个用例里推进主单的确实是事件，而不是别的什么
     *   <li>驱动查单后主单到达终态——证明查单这条兜底真的在，事件丢失只是慢一点
     *   <li>下游账本仍为 1 条——证明「慢一点」的代价里不含重复发放，即无资损
     * </ul>
     *
     * <p>第一条尤其重要：没有它，一个「事件根本没起作用，一直是查单在收敛」的实现同样能通过后两条。
     */
    @Test
    void convergesByQueryTaskWhenEventIsLost() {
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        String bizNo = benefitOrderService.createTrade(newTradeReq("ev_off")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_ev_off", "N1", "SUCCESS"));
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());
        injector.reset();

        int grantedBefore = providerLedger.size();
        String opNoA = IdempotentKeys.grantOpNo(bizNo, "PROVIDER_A");
        String opNoB = IdempotentKeys.grantOpNo(bizNo, "PROVIDER_B");

        // 供应方通知到达，reward 侧收敛
        rewardService.providerCallback(notify(opNoA, "S1", RetStatus.SUCCESS));
        rewardService.providerCallback(notify(opNoB, "S1", RetStatus.SUCCESS));

        assertThat(str(rewardJdbc, "SELECT result FROM reward_grant_record WHERE op_no = ?", opNoA))
                .as("reward 侧照常收敛 —— 事件开关不影响它")
                .isEqualTo(RetStatus.SUCCESS.name());
        assertThat(orderField("grant_status", bizNo))
                .as("事件没发出去，主单此刻必须还停在未定态；若已推进，说明推进它的不是事件")
                .isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        // 查单任务照常把它收敛掉 —— 这就是「退化到查单周期」
        for (int i = 0; i < 3; i++) {
            makeAllDue(bizNo);
            runScheduler();
        }

        assertThat(orderField("grant_status", bizNo))
                .as("事件丢失只该退化收敛时间，不该让单子永久悬挂")
                .isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(providerLedger.size() - grantedBefore)
                .as("收敛过程不得产生任何新的发放 —— 事件丢失不构成资损")
                .isZero();
        assertThat(grantRecordCount(bizNo)).as("发放记录仍是一供应方一条").isEqualTo(2);
    }

    /**
     * 事件关掉后，<b>没有回调也照样收敛</b> —— 查单不依赖任何通知。
     *
     * <p>上一条验的是「通知到了但事件丢了」，这条验的是「通知也没到」。两者是不同的失效：前者 reward 侧已收敛而玩法层不知道，后者两侧都还不知道。查单必须都能兜住。
     */
    @Test
    void convergesWithoutAnyCallbackOrEvent() {
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);
        String bizNo = benefitOrderService.createTrade(newTradeReq("ev_off2")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_ev_off2", "N1", "SUCCESS"));
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        // 恢复正常但不发任何通知，只驱动查单
        injector.reset();
        int grantedBefore = providerLedger.size();
        for (int i = 0; i < 3; i++) {
            makeAllDue(bizNo);
            runScheduler();
        }

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(providerLedger.size() - grantedBefore).as("查单是读操作，收敛过程不得新增发放").isZero();
    }

    private void makeAllDue(String bizNo) {
        benefitJdbc.update(
                "UPDATE benefit_task SET next_time = NOW(3) WHERE biz_no = ? AND status ="
                        + " 'PENDING'",
                bizNo);
    }
}
