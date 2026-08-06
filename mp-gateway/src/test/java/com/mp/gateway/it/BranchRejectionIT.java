package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import org.junit.jupiter.api.Test;

/**
 * V1 分支与拒绝路径。
 *
 * <p>对应《分阶段方案》§4.7 退出标准 12–14。
 *
 * <p>共同点：<b>被拒绝时不留下半截状态</b>。拒绝而不回滚比不拒绝更危险 —— 前者会留下一笔 状态与事实不符的单据，且没有任何日志说明它为何在那里。
 */
class BranchRejectionIT extends AbstractMySqlIT {

    /** 标准 12：支付失败使主单进入 PAY_FAILED，且不触发履约。 */
    @Test
    void failedPaymentDoesNotTriggerFulfillment() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("payFail"));
        String bizNo = created.getBizNo();

        benefitOrderService.payCallback(
                newPayCallback(bizNo, created.getTradeNo(), "NS_1", "FAILED"));
        // 驱动一轮，证明「没有履约」不是因为没人跑，而是因为压根没有任务
        runScheduler();

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_FAILED.name());
        // 发放态停在初始值，不是 GRANT_FAILED —— 没发起过和发起后失败是两回事，
        // 混为一谈会让对账把从未履约的单据算进「履约失败待补偿」
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.NOT_START.name());
        assertThat(fulfillmentCount(bizNo)).isZero();
        assertThat(grantRecordCount(bizNo)).isZero();
        // 支付失败不落 GRANT 任务 —— 落了就意味着调度器迟早会给未付款的单发货。
        //
        // 断言收窄到 GRANT 一类，不再是「一条任务都没有」：自 PR-5 起支付失败要落
        // STOCK_RELEASE 与 QUOTA_RELEASE（交易未成立，库存与额度都得还回去）。
        // 原断言的意图始终是「不会发货」，而不是「什么都不做」
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = ?",
                                bizNo,
                                TaskType.GRANT.name()))
                .as("支付失败不得落履约任务")
                .isZero();
        // 而释放类任务必须落 —— 少了它们，未付款的单会永久占着库存与限购额度
        assertThat(
                        benefitJdbc.queryForList(
                                "SELECT task_type FROM benefit_task WHERE biz_no = ?",
                                String.class,
                                bizNo))
                .containsExactlyInAnyOrder(
                        TaskType.STOCK_RELEASE.name(), TaskType.QUOTA_RELEASE.name());
    }

    /**
     * 标准 13：CLOSED 回调被拒绝，不改变任何状态。
     *
     * <p>CLOSED 属 V2 关单链路语义。此处显式拒绝而非提前实现：V2 引入 CLOSING 中间态后， 「WAIT_PAY 直接到
     * CLOSED」的逻辑还要重写，而提前写的分支没有测试覆盖。
     */
    @Test
    void closedPayStatusIsRejectedInV1() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("payClosed"));
        String bizNo = created.getBizNo();

        assertThatThrownBy(
                        () ->
                                benefitOrderService.payCallback(
                                        newPayCallback(
                                                bizNo, created.getTradeNo(), "NS_1", "CLOSED")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK")).isZero();
        assertThat(fulfillmentCount(bizNo)).isZero();
    }

    /**
     * 标准 14：quantity != 1 被拒绝且不建单。
     *
     * <p>字段保留是形状冻结，但值必须校验。默默按 1 处理会让调用方付一份钱得一份权益且无报错 —— 这类「不报错的错」在对账时表现为金额对得上、权益给少了，最难追。
     */
    @Test
    void quantityOtherThanOneIsRejectedWithoutCreatingOrder() {
        CreateTradeReq req = newTradeReq("qty3");
        req.setQuantity(3);

        assertThatThrownBy(() -> benefitOrderService.createTrade(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE client_req_no = ?",
                                "REQ_qty3"))
                .isZero();
    }

    /** 金额不一致的回调被拒，且不推进任何状态 —— 验签只证明消息来源，不证明金额与本单应付一致。 */
    @Test
    void amountMismatchIsRejectedAndAdvancesNothing() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("amtBad"));
        String bizNo = created.getBizNo();

        var callback = newPayCallback(bizNo, created.getTradeNo(), "NS_1", "SUCCESS");
        callback.setPayAmount(1L);

        assertThatThrownBy(() -> benefitOrderService.payCallback(callback))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PAY_AMOUNT_MISMATCH);

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK")).isZero();
        assertThat(fulfillmentCount(bizNo)).isZero();
    }

    /** 不存在的业务单号一律拒绝，不静默建单也不返回空对象。 */
    @Test
    void unknownBizNoIsRejected() {
        assertThatThrownBy(() -> benefitOrderService.queryOrder("BZ_NOT_EXIST"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> benefitOrderService.grantBenefit("BZ_NOT_EXIST"))
                .isInstanceOf(BizException.class);
    }
}
