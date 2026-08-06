package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.dto.RewardItem;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RetStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V1 幂等与重入。对应《分阶段方案》§4.7 退出标准 8–11、15–17。
 *
 * <p>验的是「重复请求不产生第二份副作用」，而非「重复请求被拒绝」—— 后者会让调用方误判为出错而再次重试。
 */
class IdempotencyIT extends AbstractMySqlIT {

    @Autowired private RewardService rewardService;

    /** 标准 8：同一 clientReqNo 重复下单返回原单。幂等由 uk_idempotent 兜底，不靠「先查后插」。 */
    @Test
    void duplicateCreateTradeReturnsOriginalOrder() {
        CreateTradeReq req = newTradeReq("dupCreate");

        CreateTradeResp first = benefitOrderService.createTrade(req);
        CreateTradeResp second = benefitOrderService.createTrade(req);

        assertThat(second.getBizNo()).isEqualTo(first.getBizNo());
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE client_req_no = ?",
                                "REQ_dupCreate"))
                .isEqualTo(1);
    }

    /**
     * 幂等命中不得返回他人订单。{@code uk_idempotent} 命中返回原单，{@code uk_biz_no} 碰撞须换号重试，合并处理会让 B 用户拿到 A 用户的单。
     *
     * <p><b>只覆盖幂等命中一侧</b>：单号由服务内部生成，不注入生成器无法制造碰撞。碰撞分支无自动化覆盖， 待 V2 引入注入点后补测（《分阶段方案》§4.8）。
     */
    @Test
    void idempotentHitNeverReturnsAnotherUsersOrder() {
        CreateTradeResp a = benefitOrderService.createTrade(newTradeReq("ownerA"));
        CreateTradeResp b = benefitOrderService.createTrade(newTradeReq("ownerB"));

        assertThat(b.getBizNo()).isNotEqualTo(a.getBizNo());

        // 重复投递 A 的请求，拿回的必须是 A 的单
        CreateTradeResp again = benefitOrderService.createTrade(newTradeReq("ownerA"));
        assertThat(again.getBizNo()).isEqualTo(a.getBizNo());
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT user_id FROM play_biz_record WHERE play_biz_record_no = ?",
                                again.getBizNo()))
                .isEqualTo("U_ownerA");
    }

    /** 标准 9：同一 opNo 重复发奖返回原结果，不重复调下游。 */
    @Test
    void duplicateGrantRewardReturnsOriginalResult() {
        String bizNo = "BZ_IT_dupGrant";
        String opNo = bizNo + "_G_PROVIDER_A";

        GrantRewardResp first = rewardService.grantReward(grantReq(bizNo, opNo));
        GrantRewardResp second = rewardService.grantReward(grantReq(bizNo, opNo));

        assertThat(first.getRetStatus()).isEqualTo(RetStatus.SUCCESS);
        assertThat(second.getRetStatus()).isEqualTo(RetStatus.SUCCESS);
        // 第二次走幂等出口读原记录，返回的下游单号必须与第一次相同 —— 不同即意味着又发了一次
        assertThat(second.getItems().get(0).getProviderOrderNo())
                .isEqualTo(first.getItems().get(0).getProviderOrderNo());

        assertThat(
                        count(
                                rewardJdbc,
                                "SELECT COUNT(*) FROM reward_grant_record WHERE op_no = ?",
                                opNo))
                .isEqualTo(1);
        assertThat(
                        count(
                                rewardJdbc,
                                "SELECT COUNT(*) FROM reward_grant_item WHERE op_no = ?",
                                opNo))
                .isEqualTo(1);
    }

    /** 标准 10：同一 notifySeq 重复回调不改状态、不新增操作记录，只累加 retry_count。 */
    @Test
    void duplicatePayCallbackWithSameNotifySeqChangesNothing() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("dupNotify"));
        String bizNo = created.getBizNo();
        String tradeNo = created.getTradeNo();

        benefitOrderService.payCallback(newPayCallback(bizNo, tradeNo, "NS_1", "SUCCESS"));
        String updateTimeAfterFirst =
                str(
                        benefitJdbc,
                        "SELECT update_time FROM play_biz_record WHERE play_biz_record_no = ?",
                        bizNo);

        benefitOrderService.payCallback(newPayCallback(bizNo, tradeNo, "NS_1", "SUCCESS"));

        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT update_time FROM play_biz_record WHERE play_biz_record_no = ?",
                                bizNo))
                .isEqualTo(updateTimeAfterFirst);

        // uk_biz_op 命中，走 upsert 累加而非新插一行
        assertThat(opRecordCount(bizNo, OpType.PAY_CALLBACK.name())).isEqualTo(1);
        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT retry_count FROM play_op_record WHERE play_biz_record_no = ?"
                                        + " AND op_type = ?",
                                bizNo,
                                OpType.PAY_CALLBACK.name()))
                .isEqualTo(1);

        // 履约未被触发第二次
        assertThat(fulfillmentCount(bizNo)).isEqualTo(2);
        assertThat(grantRecordCount(bizNo)).isEqualTo(2);
    }

    /**
     * 标准 11：不同 notifySeq 的第二条回调新增操作记录，主单状态不变。
     *
     * <p>体现 op_seq 与条件更新的分工：留痕归 op_record，拦截归条件更新。若 op_seq 取空串， 第二条通知在 uk_biz_op
     * 上被拒，既丢痕迹又执行不到条件更新，V2 的乱序防线随之落空。
     */
    @Test
    void secondNotifyWithDifferentSeqIsRecordedButDoesNotAdvanceState() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("reNotify"));
        String bizNo = created.getBizNo();
        String tradeNo = created.getTradeNo();

        benefitOrderService.payCallback(newPayCallback(bizNo, tradeNo, "NS_A", "SUCCESS"));
        benefitOrderService.payCallback(newPayCallback(bizNo, tradeNo, "NS_B", "SUCCESS"));

        // 两条通知各自留痕
        List<String> seqs =
                benefitJdbc.queryForList(
                        "SELECT op_seq FROM play_op_record WHERE play_biz_record_no = ?"
                                + " AND op_type = ? ORDER BY op_seq",
                        String.class,
                        bizNo,
                        OpType.PAY_CALLBACK.name());
        assertThat(seqs).containsExactly("NS_A", "NS_B");

        // 状态只推进一次：第二条的条件更新 affected_rows = 0（前置状态已不是 WAIT_PAY）
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(fulfillmentCount(bizNo)).isEqualTo(2);
        assertThat(grantRecordCount(bizNo)).isEqualTo(2);
    }

    /** 标准 15：主单已 GRANT_SUCCESS 时重复履约，直接返回不调 reward。 */
    @Test
    void grantBenefitIsNoOpWhenAlreadySucceeded() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("reGrant"));
        String bizNo = created.getBizNo();
        benefitOrderService.payCallback(
                newPayCallback(bizNo, created.getTradeNo(), "NS_1", "SUCCESS"));

        String providerOrderNo =
                str(
                        benefitJdbc,
                        "SELECT provider_order_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? ORDER BY benefit_item_id LIMIT 1",
                        bizNo);

        assertThat(benefitOrderService.grantBenefit(bizNo)).isEqualTo(RetStatus.SUCCESS);

        assertThat(fulfillmentCount(bizNo)).isEqualTo(2);
        assertThat(grantRecordCount(bizNo)).isEqualTo(2);
        assertThat(opRecordCount(bizNo, OpType.GRANT_BENEFIT.name())).isEqualTo(1);
        // 下游单号未变 —— 变了就说明又发了一次奖
        assertThat(
                        str(
                                benefitJdbc,
                                "SELECT provider_order_no FROM benefit_fulfillment_record"
                                        + " WHERE play_biz_record_no = ? ORDER BY benefit_item_id LIMIT 1",
                                bizNo))
                .isEqualTo(providerOrderNo);
    }

    /**
     * 标准 16：GRANTING 中途重入不抛 DuplicateKeyException。
     *
     * <p>置回 GRANTING 模拟「RPC 发出后进程崩溃」。重入时三处写入均已有数据：{@code op_record} 一行、 {@code
     * benefit_fulfillment_record} 两行、主单条件更新前置状态不匹配。任一处写成普通 insert 都会抛异常中断崩溃恢复。
     */
    @Test
    void reentrantGrantWhileGrantingDoesNotThrow() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("granting"));
        String bizNo = created.getBizNo();
        benefitOrderService.payCallback(
                newPayCallback(bizNo, created.getTradeNo(), "NS_1", "SUCCESS"));

        benefitJdbc.update(
                "UPDATE play_biz_record SET grant_status = ? WHERE play_biz_record_no = ?",
                GrantStatus.GRANTING.name(),
                bizNo);

        assertThatCode(() -> benefitOrderService.grantBenefit(bizNo)).doesNotThrowAnyException();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(fulfillmentCount(bizNo)).isEqualTo(2);
        assertThat(opRecordCount(bizNo, OpType.GRANT_BENEFIT.name())).isEqualTo(1);
        // reward 侧按 opNo 幂等：重入复用原键，不产生第二条发奖记录
        assertThat(grantRecordCount(bizNo)).isEqualTo(2);
    }

    /**
     * 标准 17：uk_trade_no 上多行 NULL 可共存。
     *
     * <p>「唯一索引字段必须 NOT NULL DEFAULT ''」这条规则不适用于后填的外部单号 —— 若 trade_no 建单时填空串，第二笔未支付订单就会撞唯一索引建不出来。
     * MySQL 的唯一索引不约束 NULL，这正是此处要的行为，也是不能用 H2 验证的原因之一。
     */
    @Test
    void multipleOrdersCoexistWithNullTradeNo() {
        // 绕开 createTrade 直接插两行，避免 mock 支付回填 trade_no 掩盖该场景
        insertBareOrder("BZ_IT_null_1", "U_nullA");
        assertThatCode(() -> insertBareOrder("BZ_IT_null_2", "U_nullB")).doesNotThrowAnyException();

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE trade_no IS NULL"))
                .isGreaterThanOrEqualTo(2);
    }

    private void insertBareOrder(String bizNo, String userId) {
        benefitJdbc.update(
                "INSERT INTO play_biz_record (play_biz_record_no, activity_id, sku_id, user_id,"
                        + " client_req_no, quantity, pay_status, order_amount, config_version,"
                        + " price_snapshot, benefit_snapshot, expire_time)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, 1, '{}', '[]',"
                        + " DATE_ADD(NOW(3), INTERVAL 30 MINUTE))",
                bizNo,
                ACTIVITY_ID,
                SKU_ID,
                userId,
                "REQ_" + bizNo,
                PayStatus.WAIT_PAY.name(),
                SALE_PRICE);
    }

    private GrantRewardReq grantReq(String bizNo, String opNo) {
        GrantRewardReq req = new GrantRewardReq();
        req.setPlayType("BENEFIT_SELL");
        req.setActivityId(ACTIVITY_ID);
        req.setBizOrderNo(bizNo);
        req.setOpNo(opNo);
        req.setReceiverId("U_dupGrant");

        RewardItem item = new RewardItem();
        item.setItemSeq(0);
        item.setRewardType("MONTH_CARD");
        item.setProviderType("PROVIDER_A");
        item.setProviderProductId("PROD_A_001");
        item.setQty(1);
        item.setCore(true);
        req.setRewardItems(List.of(item));
        return req;
    }
}
