package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.mock.dto.FaultMode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.TaskStatus;
import com.mp.common.enums.TaskType;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.ProviderLedger;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 四分类收敛，对应《分阶段方案》§5.7 退出标准 1–4。
 *
 * <p><b>「无重复发放」的判定口径四处同时成立</b>（§5.3）：平台侧 {@code reward_grant_record} 按 {@code op_no} 计数 1、{@code
 * reward_grant_item} 按 {@code (op_no, item_seq)} 无重复、 {@code benefit_fulfillment_record} 按 {@code
 * (bizNo, benefitItemId)} 无重复，以及 —— 关键的第四条 —— <b>mock 账本按 {@code opNo} 计数
 * 1</b>。前三条都只证明平台记录，只有第四条跨过了服务边界。
 *
 * <p>不以日志或调用次数为准：收敛过程本就包含重复调用，重复调用不等于重复发放。
 */
class FaultInjectionIT extends AbstractMySqlIT {

    @Autowired private FaultInjector injector;
    @Autowired private ProviderLedger ledger;

    @AfterEach
    void resetInjection() {
        // 每个用例自己设模式，不依赖执行顺序（《开发规范》§9.3）
        injector.reset();
    }

    /**
     * 标准 1：{@code TIMEOUT_AFTER_COMMIT} —— 下游已执行但调用方未收到结果。
     *
     * <p><b>这是四分类唯一不可替代的场景</b>。若把 {@code UNKNOWN} 误判为 {@code FAIL} 去补发， 下游账本就会出现第二条 ——
     * 一笔权益发两次。此处断言的正是「不重发」与「账本仍为 1 条」。
     */
    @Test
    void timeoutAfterCommitConvergesByQueryWithoutRedispatch() {
        injector.setProviderMode(FaultMode.TIMEOUT_AFTER_COMMIT);

        String bizNo = payFor("afterCommit");
        runScheduler(); // GRANT 任务执行：下游已记账但抛超时

        // 主单停在 GRANT_UNKNOWN —— 不是 GRANT_FAILED。后者会让对账把已发放的单当作待补偿
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        // 每个供应方各一条查单任务，op_no 即原发奖幂等号
        List<String> queryOpNos =
                benefitJdbc.queryForList(
                        "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?"
                                + " ORDER BY op_no",
                        String.class,
                        bizNo,
                        TaskType.QUERY_GRANT.name());
        assertThat(queryOpNos).containsExactly(bizNo + "_G_PROVIDER_A", bizNo + "_G_PROVIDER_B");

        // 下游此刻已发放（TIMEOUT_AFTER_COMMIT 先记账再抛），账本各 1 条
        for (String opNo : queryOpNos) {
            assertThat(ledger.contains(opNo)).as("%s 下游应已发放", opNo).isTrue();
        }

        // 查单收敛：查得已发放 → GRANT_SUCCESS
        runScheduler();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertNoDuplicateGrant(bizNo, queryOpNos);

        // 没有产生重发任务 —— 查得即收敛，重发是多余的
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type ="
                                        + " ? AND op_no <> ?",
                                bizNo,
                                TaskType.GRANT.name(),
                                bizNo + "_GRANT"))
                .as("查得已发放就不该重发")
                .isZero();
    }

    /**
     * 标准 2：{@code TIMEOUT_BEFORE_COMMIT} —— 连续查无满 3 次后以原 {@code opNo} 重发。
     *
     * <p>单次查无不足以判定「原调用未到达」，可能只是提交在途。阈值的误判是安全的：若原调用 实际已到达，重发携带同一 {@code opNo} 会被下游账本 put-if-absent
     * 挡下。
     */
    @Test
    void timeoutBeforeCommitReDispatchesWithOriginalOpNoAfterThreeMisses() {
        injector.setProviderMode(FaultMode.TIMEOUT_BEFORE_COMMIT);

        String bizNo = payFor("beforeCommit");
        runScheduler();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());
        String opNoA = bizNo + "_G_PROVIDER_A";
        // 下游未记账 —— 这正是与标准 1 的分水岭
        assertThat(ledger.contains(opNoA)).as("TIMEOUT_BEFORE_COMMIT 不应记账").isFalse();

        // 连续查无：前两次只累计，不重发
        for (int round = 1; round <= 2; round++) {
            makeAllDue(bizNo);
            runScheduler();
            assertThat(regrantTaskCount(bizNo)).as("第 %s 次查无不应重发", round).isZero();
        }

        // 第三次查无达阈值 → 落重发任务，op_no 与首发一致
        makeAllDue(bizNo);
        runScheduler();

        List<String> regrantOpNos =
                benefitJdbc.queryForList(
                        "SELECT op_no FROM benefit_task WHERE biz_no = ? AND task_type = ?"
                                + " AND op_no <> ? ORDER BY op_no",
                        String.class,
                        bizNo,
                        TaskType.GRANT.name(),
                        bizNo + "_GRANT");
        assertThat(regrantOpNos)
                .as("重发必须复用原 opNo，重新派生会让每次重发都成为一笔新发放")
                .containsExactly(bizNo + "_G_PROVIDER_A", bizNo + "_G_PROVIDER_B");

        // 恢复正常后重发成功，最终两侧账本各 1 条
        injector.setProviderMode(FaultMode.SUCCESS);
        makeAllDue(bizNo);
        runScheduler();
        makeAllDue(bizNo);
        runScheduler();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertNoDuplicateGrant(bizNo, regrantOpNos);
    }

    /**
     * 标准 3：{@code PROCESSING} 走长退避，序列与短退避不同。
     *
     * <p>两者用同一序列等于把「已受理」和「不知道」当成同一回事 —— 而这正是四分类要拆开的东西。 断言的是倍率关系，不是绝对值（生产长退避跑满需 12.5 分钟）。
     */
    @Test
    void processingUsesLongBackoffDistinctFromShort() {
        // 第 3 次查单才转成功，前两次保持 PROCESSING
        injector.setProviderMode(FaultMode.PROCESSING);
        injector.setProcessingTurns(3);

        String bizNo = payFor("processing");
        runScheduler();

        // 下游受理但未完成：主单停在 GRANT_UNKNOWN，明细为未定态
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        String opNo = bizNo + "_G_PROVIDER_A";
        long processingPush = pushOfNextQuery(bizNo, opNo);

        assertThat(processingPush)
                .as("PROCESSING 应走长退避（首档 30s×scale=600ms），实际 %sms", processingPush)
                .isGreaterThan(300);

        // 对照：UNKNOWN 的首档是 1s×scale=20ms，两者不在同一量级
        assertThat(processingPush).as("长退避应显著长于短退避首档 20ms").isGreaterThan(20 * 5);

        // 继续查单直至下游完成，最终收敛且不重复发放
        injector.setProcessingTurns(1);
        makeAllDue(bizNo);
        runScheduler();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertNoDuplicateGrant(bizNo, List.of(opNo, bizNo + "_G_PROVIDER_B"));
    }

    /**
     * 标准 4：{@code FAIL} 走失败分支，置 {@code GRANT_FAILED} 且不再产生重试任务。
     *
     * <p>{@code FAIL} 是<b>唯一</b>允许走失败分支的一类：下游明确回答「没做」，才可以据此补偿。
     */
    @Test
    void failGoesToFailureBranchAndLeavesNoPendingTask() {
        injector.setProviderMode(FaultMode.FAIL);

        String bizNo = payFor("fail");
        runScheduler();

        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_FAILED.name());

        // 明细为 FAILED，非 UNKNOWN —— 下游明确失败，不是不知道
        List<String> itemStatuses =
                benefitJdbc.queryForList(
                        "SELECT grant_status FROM benefit_fulfillment_record"
                                + " WHERE play_biz_record_no = ?",
                        String.class,
                        bizNo);
        assertThat(itemStatuses).containsOnly(ItemGrantStatus.FAILED.name());

        // 确定失败不需要查单收敛：不落查单任务，GRANT 任务也已完结
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type ="
                                        + " ?",
                                bizNo,
                                TaskType.QUERY_GRANT.name()))
                .as("确定失败不该再查单")
                .isZero();
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND status <>"
                                        + " ?",
                                bizNo,
                                TaskStatus.DONE.name()))
                .as("不应残留未完成任务")
                .isZero();

        // 下游确实没发
        assertThat(ledger.contains(bizNo + "_G_PROVIDER_A")).isFalse();
    }

    // ---- 辅助 ----

    /** 下单 + 支付成功，返回 bizNo。此时 GRANT 任务已落库待执行。 */
    private String payFor(String tag) {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq(tag));
        benefitOrderService.payCallback(
                newPayCallback(
                        created.getBizNo(), created.getTradeNo(), "NS_" + tag + "_1", "SUCCESS"));
        return created.getBizNo();
    }

    /**
     * 「无重复发放」四处口径同时成立。
     *
     * <p>前三条只证明平台记录没写重，第四条才跨过服务边界证明下游只发了一次。
     */
    private void assertNoDuplicateGrant(String bizNo, List<String> opNos) {
        for (String opNo : opNos) {
            assertThat(
                            count(
                                    rewardJdbc,
                                    "SELECT COUNT(*) FROM reward_grant_record WHERE op_no = ?",
                                    opNo))
                    .as("平台发奖记录按 op_no 应为 1 条")
                    .isEqualTo(1);
            assertThat(
                            count(
                                    rewardJdbc,
                                    "SELECT COUNT(*) FROM reward_grant_item WHERE op_no = ?",
                                    opNo))
                    .as("发奖明细不应重复")
                    .isEqualTo(1);

            // 跨过服务边界的那一条：下游账本按 opNo 只有一笔
            assertThat(ledger.contains(opNo)).as("%s 下游应已发放", opNo).isTrue();

            // 平台记录的下游单号与账本一致 —— 不一致即意味着发生过第二次发放
            String platformOrderNo =
                    str(
                            rewardJdbc,
                            "SELECT provider_order_no FROM reward_grant_item WHERE op_no = ?",
                            opNo);
            assertThat(platformOrderNo).as("平台记录的下游单号应与账本一致").isEqualTo(ledger.find(opNo));
        }

        // 履约明细按 (bizNo, benefitItemId) 不重复
        assertThat(fulfillmentCount(bizNo)).isEqualTo(2);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(DISTINCT benefit_item_id) FROM"
                                        + " benefit_fulfillment_record WHERE play_biz_record_no = ?",
                                bizNo))
                .isEqualTo(2);
    }

    /** 本单所有任务立即可领，绕开退避等待（不改退避本身）。 */
    private void makeAllDue(String bizNo) {
        benefitJdbc.update(
                "UPDATE benefit_task SET next_time = NOW(3) WHERE biz_no = ? AND status ="
                        + " 'PENDING'",
                bizNo);
    }

    /** 某条查单任务本轮被推后的毫秒数，用于区分长短退避。 */
    private long pushOfNextQuery(String bizNo, String opNo) {
        benefitJdbc.update(
                "UPDATE benefit_task SET next_time = NOW(3) WHERE biz_no = ? AND op_no = ?",
                bizNo,
                opNo);
        String before = queryNextTime(bizNo, opNo);
        runScheduler();
        String after = queryNextTime(bizNo, opNo);
        return java.time.Duration.between(parse(before), parse(after)).toMillis();
    }

    private String queryNextTime(String bizNo, String opNo) {
        return str(
                benefitJdbc,
                "SELECT next_time FROM benefit_task WHERE biz_no = ? AND op_no = ?",
                bizNo,
                opNo);
    }

    private static java.time.LocalDateTime parse(String ts) {
        return java.time.LocalDateTime.parse(ts.replace(' ', 'T'));
    }

    /** 重发任务数：op_no 不等于支付回调落的那条，即为查单触发的定向重发。 */
    private int regrantTaskCount(String bizNo) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type = ? AND op_no <>"
                        + " ?",
                bizNo,
                TaskType.GRANT.name(),
                bizNo + "_GRANT");
    }
}
