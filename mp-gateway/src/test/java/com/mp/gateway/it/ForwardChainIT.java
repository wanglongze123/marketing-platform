package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.FulfillmentItem;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * V1 正向链路：下单 → 支付回调 → 履约 → 查单。
 *
 * <p>对应《分阶段方案》§4.7 退出标准 1–7、18–20、22。
 */
class ForwardChainIT extends AbstractMySqlIT {

    /** 标准 1、2、6、7、18：一条链路跑通，主单三子状态、快照、支付关联、配置版本全部落对。 */
    @Test
    void e2eChainCompletesAndOrderReachesTerminalState() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("e2e"));
        String bizNo = created.getBizNo();

        // 建单即返回 tradeNo，且已回填进库 —— 标准 7
        assertThat(created.getTradeNo()).isNotBlank();
        assertThat(orderField("trade_no", bizNo)).isEqualTo(created.getTradeNo());
        assertThat(created.getPayStatus()).isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(created.getOrderAmount()).isEqualTo(SALE_PRICE);

        // 回调按 outTradeNo（= bizNo）定位，不依赖 trade_no
        RetStatus ret =
                benefitOrderService.payCallback(
                        newPayCallback(bizNo, created.getTradeNo(), "NS_e2e_1", "SUCCESS"));
        assertThat(ret).isEqualTo(RetStatus.SUCCESS);

        // 标准 2：三子状态独立推进，各自到位
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(orderField("refund_status", bizNo)).isEqualTo(RefundStatus.NONE.name());

        // 标准 18：下单时冻结的配置版本 = 活动当前版本，不是硬编码
        Integer curVersion =
                num(
                        activityJdbc,
                        "SELECT cur_version FROM marketing_activity WHERE activity_id = ?",
                        ACTIVITY_ID);
        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT config_version FROM play_biz_record WHERE play_biz_record_no = ?",
                                bizNo))
                .isEqualTo(curVersion)
                .isEqualTo(1);

        // 标准 6：快照非空，且与 seed 的两个权益项一致 —— 履约只读它，不再查配置表
        String snapshot = orderField("benefit_snapshot", bizNo);
        assertThat(snapshot).contains("ITEM_DEMO_A", "ITEM_DEMO_B", "PROVIDER_A", "PROVIDER_B");
    }

    /** 标准 3：履约明细两行，各自拿到供应方单号。用 ItemGrantStatus.SUCCESS，非主单的 GRANT_SUCCESS。 */
    @Test
    void fulfillmentRecordsAreOnePerBenefitItem() {
        String bizNo = payAndGrant("ff");

        assertThat(fulfillmentCount(bizNo)).isEqualTo(2);

        List<Map<String, Object>> rows =
                benefitJdbc.queryForList(
                        "SELECT benefit_item_id, provider_type, provider_order_no, grant_status,"
                                + " grant_op_no FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ? ORDER BY benefit_item_id",
                        bizNo);

        assertThat(rows)
                .extracting(r -> r.get("benefit_item_id"))
                .containsExactly("ITEM_DEMO_A", "ITEM_DEMO_B");
        assertThat(rows)
                .allSatisfy(
                        r -> {
                            assertThat(r.get("provider_order_no")).asString().isNotBlank();
                            assertThat(r.get("grant_status"))
                                    .isEqualTo(ItemGrantStatus.SUCCESS.name());
                        });

        // 幂等键确定性派生，一供应方一个 —— 键写死在明细上，重试时可原样复用
        assertThat(rows)
                .extracting(r -> r.get("grant_op_no"))
                .containsExactly(bizNo + "_G_PROVIDER_A", bizNo + "_G_PROVIDER_B");
    }

    /** 标准 4：三条操作记录，op_seq 各按其「是否至多一次」取值。 */
    @Test
    void opRecordsCoverEveryStateChangeWithCorrectOpSeq() {
        String bizNo = payAndGrant("op");

        List<Map<String, Object>> ops =
                benefitJdbc.queryForList(
                        "SELECT op_type, op_seq, status, idempotent_key FROM play_op_record"
                                + " WHERE play_biz_record_no = ? ORDER BY id",
                        bizNo);
        assertThat(ops).hasSize(3);

        Map<String, Object> create = ops.get(0);
        assertThat(create.get("op_type")).isEqualTo(OpType.CREATE_TRADE.name());
        // 至多一次的操作恒空串，不是 NULL —— 参与唯一索引的字段不可为 NULL
        assertThat(create.get("op_seq")).isEqualTo("");
        assertThat(create.get("status")).isEqualTo(OpStatus.SUCCESS.name());

        Map<String, Object> pay = ops.get(1);
        assertThat(pay.get("op_type")).isEqualTo(OpType.PAY_CALLBACK.name());
        // 支付回调可多次：op_seq 取 notifySeq。取空串会让第二条通知在 uk_biz_op 上被拒，
        // 执行不到主单条件更新 —— V2 的乱序防线会因此失效
        assertThat(pay.get("op_seq")).isEqualTo("NS_op_1");
        assertThat(pay.get("idempotent_key")).asString().endsWith("_NS_op_1");

        Map<String, Object> grant = ops.get(2);
        assertThat(grant.get("op_type")).isEqualTo(OpType.GRANT_BENEFIT.name());
        assertThat(grant.get("op_seq")).isEqualTo("");
        assertThat(grant.get("status")).isEqualTo(OpStatus.SUCCESS.name());
    }

    /** 标准 5、19、20：发奖侧按供应方拆两次调用，各自一条主记录一条明细，item_seq 组内独立编号。 */
    @Test
    void rewardIsSplitPerProviderWithGroupLocalItemSeq() {
        String bizNo = payAndGrant("rw");

        assertThat(grantRecordCount(bizNo)).isEqualTo(2);
        assertThat(grantItemCount(bizNo)).isEqualTo(2);

        List<Map<String, Object>> records =
                rewardJdbc.queryForList(
                        "SELECT op_no, play_type, result FROM reward_grant_record"
                                + " WHERE biz_order_no = ? ORDER BY op_no",
                        bizNo);
        assertThat(records)
                .extracting(r -> r.get("op_no"))
                .containsExactly(bizNo + "_G_PROVIDER_A", bizNo + "_G_PROVIDER_B");
        assertThat(records)
                .allSatisfy(
                        r -> {
                            // 标准 20：发奖侧记录玩法类型，裂变接入时同表区分
                            assertThat(r.get("play_type")).isEqualTo("BENEFIT_SELL");
                            assertThat(r.get("result")).isEqualTo(RetStatus.SUCCESS.name());
                        });

        // 标准 19：item_seq 是组内下标，两组都从 0 起。
        // 若误用全局下标，第二组会是 1，而 uk_op_item 的第一维 op_no 已隔开不同组 —— 全局编号既多余又会
        // 让「按供应方重发某一组」时下标对不上
        List<Integer> seqs =
                rewardJdbc.queryForList(
                        "SELECT i.item_seq FROM reward_grant_item i JOIN reward_grant_record r"
                                + " ON i.op_no = r.op_no WHERE r.biz_order_no = ? ORDER BY i.op_no",
                        Integer.class,
                        bizNo);
        assertThat(seqs).containsExactly(0, 0);
    }

    /** 标准 22：中间态先于 RPC 落库 —— 履约完成后 GRANT_BENEFIT 记录仍是同一条，被更新为终态而非新插入。 */
    @Test
    void grantOpRecordIsCreatedBeforeRpcAndUpdatedInPlace() {
        String bizNo = payAndGrant("mid");

        List<Map<String, Object>> ops =
                benefitJdbc.queryForList(
                        "SELECT status, downstream_result, finish_time, create_time"
                                + " FROM play_op_record WHERE play_biz_record_no = ? AND op_type = ?",
                        bizNo,
                        OpType.GRANT_BENEFIT.name());
        assertThat(ops).hasSize(1);

        Map<String, Object> op = ops.get(0);
        assertThat(op.get("status")).isEqualTo(OpStatus.SUCCESS.name());
        // 本地执行态与下游四分类分列两栏：一个是 SUCCESS/FAILED，一个是 SUCCESS/FAIL。
        // 混用会让「本地已收敛但下游结果未知」这一状态无法表达
        assertThat(op.get("downstream_result")).isEqualTo(RetStatus.SUCCESS.name());
        // finish_time 由回写终态时填 —— 有值即证明记录先建后改，不是等结果回来才插
        assertThat(op.get("finish_time")).isNotNull();
    }

    /** 查单出参：三子状态各自返回，不派生成单一 biz_status。 */
    @Test
    void queryOrderReturnsThreeSubStatusesSeparately() {
        String bizNo = payAndGrant("qry");

        QueryOrderResp resp = benefitOrderService.queryOrder(bizNo);
        assertThat(resp.getPayStatus()).isEqualTo(PayStatus.PAY_SUCCESS);
        assertThat(resp.getGrantStatus()).isEqualTo(GrantStatus.GRANT_SUCCESS);
        assertThat(resp.getRefundStatus()).isEqualTo(RefundStatus.NONE);
        assertThat(resp.getOrderAmount()).isEqualTo(SALE_PRICE);
        assertThat(resp.getPayAmount()).isEqualTo(SALE_PRICE);
        assertThat(resp.getConfigVersion()).isEqualTo(1);

        assertThat(resp.getFulfillments()).hasSize(2);
        assertThat(resp.getFulfillments())
                .extracting(FulfillmentItem::getGrantStatus)
                .containsOnly(ItemGrantStatus.SUCCESS);
        assertThat(resp.getFulfillments())
                .extracting(FulfillmentItem::getProviderType)
                .containsExactlyInAnyOrder("PROVIDER_A", "PROVIDER_B");
    }

    /** 下单 + 支付成功，返回 bizNo。履约由 payCallback 同步触发（V1 形态）。 */
    private String payAndGrant(String tag) {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq(tag));
        benefitOrderService.payCallback(
                newPayCallback(
                        created.getBizNo(), created.getTradeNo(), "NS_" + tag + "_1", "SUCCESS"));
        return created.getBizNo();
    }
}
