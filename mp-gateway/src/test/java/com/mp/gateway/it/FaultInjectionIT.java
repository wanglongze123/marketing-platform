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

        // 长退避首档 30s×0.02=600ms，短退避首档 1s×0.02=20ms —— 两者相差 30 倍。
        // 下界取 300ms：既排除短退避（20ms 量级），也留出调度写入与本次读之间流逝的时间
        assertThat(processingPush)
                .as("PROCESSING 应走长退避（首档约 600ms），实际 %sms", processingPush)
                .isGreaterThan(300);

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
        // CLOSE_ORDER 排除在外：它是建单时落的超时关单任务（PR-6），next_time 在支付有效期后，
        // 本用例结束时本就应当仍是 PENDING —— 它「未完成」是正确的，不是残留
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND status <>"
                                        + " ? AND task_type <> ?",
                                bizNo,
                                TaskStatus.DONE.name(),
                                TaskType.CLOSE_ORDER.name()))
                .as("不应残留未完成任务")
                .isZero();

        // 下游确实没发
        assertThat(ledger.contains(bizNo + "_G_PROVIDER_A")).isFalse();
    }

    /**
     * 「连续查无满 3 次」必须真的是<b>连续</b>：中间夹杂 {@code PROCESSING} 应当重新计数。
     *
     * <p>下游若回过一次 {@code PROCESSING}，就说明它已经受理了这笔请求 —— 此后再查无更可能是
     * 查询侧的抖动，而非「原调用没到达」。此时重发是对一笔已受理的请求再发一次，正是 {@code PROCESSING} 与 {@code UNKNOWN} 要分开处置的理由。
     */
    @Test
    void processingInTheMiddleResetsTheMissStreak() {
        injector.setProviderMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        String bizNo = payFor("streak");
        runScheduler();

        // 查无两次
        for (int i = 0; i < 2; i++) {
            makeAllDue(bizNo);
            runScheduler();
        }
        assertThat(regrantTaskCount(bizNo)).isZero();

        // 下游转为「已受理、处理中」：连续查无被打断
        injector.setProviderMode(FaultMode.PROCESSING);
        injector.setProcessingTurns(99);
        makeAllDue(bizNo);
        runScheduler();

        // 再查无一次。若计数正确重置，此时只是「第 1 次查无」，不该重发
        injector.setProviderMode(FaultMode.TIMEOUT_BEFORE_COMMIT);
        makeAllDue(bizNo);
        runScheduler();

        assertThat(regrantTaskCount(bizNo)).as("PROCESSING 打断后应重新计数，不该因累计次数达标就重发一笔已受理的请求").isZero();
    }

    /**
     * 重发后仍未收敛时，查单任务必须能重建 —— 收敛链路不得断在第二轮。
     *
     * <p>{@code benefit_task} 的 {@code uk_biz_type_op} 是 {@code (biz_no, task_type, op_no)}，而重发 复用原
     * {@code op_no}。第二轮 {@code finishGrant} 要落的 {@code QUERY_GRANT} 与第一轮那条键完全相同， 若 {@code enqueue}
     * 对终态行不复活，该 insert 被静默丢弃：主单停在 {@code GRANT_UNKNOWN}，没有任何 查单任务存活，重发的 {@code GRANT} 自己重试到 {@code
     * DEAD}。
     *
     * <p>既有的重发用例覆盖不到：它在跑重发任务前已把注入恢复为 {@code SUCCESS}，一轮即收敛，
     * 走不到「重发后仍未收敛」这一步。此处全程保持故障，直到确认第二轮查单任务已重建。
     */
    @Test
    void queryTaskIsRebuiltWhenReDispatchAlsoFailsToConverge() {
        injector.setProviderMode(FaultMode.TIMEOUT_BEFORE_COMMIT);

        String bizNo = payFor("reconverge");
        runScheduler();
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_UNKNOWN.name());

        String opNoA = bizNo + "_G_PROVIDER_A";

        // 连续查无三次达阈值，落重发任务并把查单任务置 DONE
        for (int round = 1; round <= 3; round++) {
            makeAllDue(bizNo);
            runScheduler();
        }
        assertThat(taskStatus(bizNo, TaskType.QUERY_GRANT, opNoA))
                .as("达阈值后查单任务应已终结，由重发的 GRANT 接手")
                .isEqualTo(TaskStatus.DONE.name());
        assertThat(taskStatus(bizNo, TaskType.GRANT, opNoA)).as("重发任务应已落库").isNotNull();

        // 执行重发。注入仍未恢复，故它同样收不到结果 —— finishGrant 需再落一条 QUERY_GRANT，
        // op_no 与上一轮那条 DONE 行完全相同
        makeAllDue(bizNo);
        runScheduler();

        assertThat(taskStatus(bizNo, TaskType.QUERY_GRANT, opNoA))
                .as("重发后仍未收敛，查单任务必须复活为 PENDING —— 否则主单永停 GRANT_UNKNOWN 且无人再看它")
                .isEqualTo(TaskStatus.PENDING.name());
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type ="
                                        + " ? AND op_no = ?",
                                bizNo,
                                TaskType.QUERY_GRANT.name(),
                                opNoA))
                .as("复活而非新增：唯一键仍应只有一行")
                .isEqualTo(1);
        assertThat(
                        num(
                                benefitJdbc,
                                "SELECT retry_count FROM benefit_task WHERE biz_no = ? AND"
                                        + " task_type = ? AND op_no = ?",
                                bizNo,
                                TaskType.QUERY_GRANT.name(),
                                opNoA))
                .as("复活是新一轮发起，不继承上一轮的重试进度")
                .isZero();

        // 恢复后经由复活的查单任务收敛，且全程只发放一次
        injector.setProviderMode(FaultMode.SUCCESS);
        for (int round = 1; round <= 3; round++) {
            makeAllDue(bizNo);
            runScheduler();
        }
        assertThat(orderField("grant_status", bizNo)).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertNoDuplicateGrant(bizNo, List.of(opNoA, bizNo + "_G_PROVIDER_B"));
    }

    // ---- 辅助 ----

    /** 某条任务的状态，不存在则返回 null。 */
    private String taskStatus(String bizNo, TaskType type, String opNo) {
        List<String> rows =
                benefitJdbc.queryForList(
                        "SELECT status FROM benefit_task WHERE biz_no = ? AND task_type = ?"
                                + " AND op_no = ?",
                        String.class,
                        bizNo,
                        type.name(),
                        opNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

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

    /**
     * 某条查单任务本轮的退避量（毫秒）。
     *
     * <p><b>取 {@code next_time - NOW(3)}，两端都是数据库时钟</b>，而不是「本轮开始到结束的墙钟差」。 后者把调度器单轮自身的耗时算了进去 —— CI
     * 机器上那部分是几百毫秒，远超被测的退避量， 断言随机失败（本地 20ms 的退避在 CI 上量到 414ms）。
     */
    private long pushOfNextQuery(String bizNo, String opNo) {
        benefitJdbc.update(
                "UPDATE benefit_task SET next_time = NOW(3) WHERE biz_no = ? AND op_no = ?",
                bizNo,
                opNo);
        runScheduler();
        return backoffMillis(bizNo, opNo);
    }

    /** {@code next_time} 距数据库当前时刻的毫秒数，即调度器刚写入的退避量。 */
    private long backoffMillis(String bizNo, String opNo) {
        Integer ms =
                num(
                        benefitJdbc,
                        "SELECT TIMESTAMPDIFF(MICROSECOND, NOW(3), next_time) DIV 1000"
                                + " FROM benefit_task WHERE biz_no = ? AND op_no = ?",
                        bizNo,
                        opNo);
        return ms == null ? 0 : ms;
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
