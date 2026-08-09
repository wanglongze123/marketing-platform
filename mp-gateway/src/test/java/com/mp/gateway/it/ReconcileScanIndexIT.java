package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.ReconcileItem;
import com.mp.mock.fault.PayLedger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 对账扫描的<b>索引与时间下界</b>（PR-10 后置 review 的 P2 项）。
 *
 * <p><b>与 {@link ReconcileIT} 分开的理由是 {@code stale-seconds} 取值相反</b>：那个类压到 0 才能立刻构造出
 * 差异，而本类要验的正是「下界挡住了刚发生的单」—— 取 0 则下界恒等于不存在，本类的断言全部恒真。 同一个配置项服务两个相反的目的，只能分成两个上下文。
 *
 * <p><b>为什么索引要用 {@code EXPLAIN} 验而不是查 {@code SHOW INDEX}</b>：后者只能证明「索引建出来了」， 证明不了查询会走它 ——
 * 而这两件事经常不一致（谓词写成两列比较、列序不匹配、隐式类型转换，任一都 会让优化器绕开索引）。本类断言的是优化器的实际选择。
 *
 * <p><b>这类缺陷不会被任何功能用例判红</b>：几十行的测试表全表扫也是毫秒级。它只在数据量长上来后 表现为对账一轮跑不完 ——
 * 而对账跑不完的后果不是「慢」，是<b>资损哨兵失灵</b>：第 5/6/11 项的告警指标 由对账产出，一轮扫不完那几个指标就停止更新，监控上看到的是一条平线，与「一切正常」同形。
 */
@TestPropertySource(properties = "mp.reconcile.stale-seconds=3600")
class ReconcileScanIndexIT extends AbstractMySqlIT {

    @Autowired private PayLedger payLedger;

    /** 本类塞进去的行的单号前缀。{@link #cleanUp} 按它精确清理 */
    private static final String SEED_PREFIX = "IDX_";

    /**
     * <b>清掉本类塞的行</b>。
     *
     * <p>不清理会让 {@link ReconcileIT} 的多条用例变红 —— 那些用例断言的是「本轮差异数」，而对账扫的是 全表。测试容器在整个 JVM
     * 内共享，一个类留下的数据就是另一个类的输入。
     *
     * <p>实测确认：首版缺这一步，{@code ReconcileIT} 四条用例同时转红（本类塞的 200 行里有 {@code PAY_SUCCESS} 且 {@code
     * grant_status='NOT_START'} 的，正好落进第 1 项的扫描集合）。
     */
    @AfterEach
    void cleanUp() {
        benefitJdbc.update(
                "DELETE FROM play_biz_record WHERE play_biz_record_no LIKE ?", SEED_PREFIX + "%");
    }

    /**
     * 对账扫描必须走到<b>索引的第二列</b>，而不只是最左那一列。
     *
     * <p><b>断言 {@code type = 'RANGE'}，不是 {@code type <> 'ALL'}</b>。这条是注入自查改出来的：首版断言 「不是全表扫」，而把
     * {@code idx_pay_update} 从迁移里删掉之后<b>用例照样全绿</b> —— 因为查询退回 {@code idx_pay_grant} 的 {@code
     * pay_status} 最左前缀，{@code type} 变成 {@code REF} 而非 {@code ALL}。
     *
     * <p>那正是本项要修的缺陷形态：{@code pay_status} 只能把范围收到「全部已关闭的单」，{@code update_time} 落到回表后逐行过滤 ——
     * 在真实数据量下与全表扫相差无几，而 {@code type} 一栏看起来是「走了索引」。 <b>「走了索引」与「走对了索引」是两回事</b>，弱断言把后者放过去了。
     *
     * <p>{@code RANGE} 意味着 {@code update_time} 这个范围条件真的用在了索引上。
     */
    @Test
    void reconcileScansUseIndexesInsteadOfFullTableScan() {
        // MySQL 对空表/极小表可能直接选全表扫（那样确实更快），故先塞够行数让优化器认真选。
        // 不塞的话本条恒绿 —— 而它要验的正是「数据量大起来之后走不走索引」
        for (int i = 0; i < 200; i++) {
            benefitJdbc.update(
                    "INSERT INTO play_biz_record (play_biz_record_no, activity_id, sku_id, user_id,"
                            + " client_req_no, quantity, pay_status, order_amount, currency,"
                            + " config_version, price_snapshot, benefit_snapshot, expire_time) VALUES"
                            + " (?, ?, ?, ?, ?, 1, ?, 100, 'CNY', 1, '{}', '[]', NOW(3))",
                    SEED_PREFIX + i,
                    ACTIVITY_ID,
                    SKU_ID,
                    "U_idx_" + (i % 7),
                    "REQ_IDX_" + i,
                    // 三种支付态都要有量：优化器按选择性挑索引，某个取值缺少数据量时
                    // 它会随手挑一个能用的（实测 CLOSING 缺量时命中的是 idx_pay_grant），
                    // 于是断言测的是「碰巧选了什么」而非「该走哪个索引」
                    switch (i % 3) {
                        case 0 -> "PAY_SUCCESS";
                        case 1 -> "CLOSED";
                        default -> "CLOSING";
                    });
        }
        // execute 而非 update：ANALYZE TABLE 返回结果集，走 executeUpdate 会抛
        benefitJdbc.execute("ANALYZE TABLE play_biz_record");

        // 第 5 项：金额一致性（补下界后走 pay_status + update_time）
        assertUsesIndex(
                "第 5 项 金额一致性",
                "SELECT play_biz_record_no FROM play_biz_record WHERE pay_status = 'PAY_SUCCESS'"
                        + " AND pay_amount IS NOT NULL AND pay_amount <> order_amount AND update_time <"
                        + " DATE_SUB(NOW(3), INTERVAL 3600 SECOND) LIMIT 200");

        // 第 9 项：已关闭单仍占库存
        assertUsesIndex(
                "第 9 项 已关闭单仍占库存",
                "SELECT play_biz_record_no FROM play_biz_record WHERE pay_status = 'CLOSED'"
                        + " AND stock_status = 'LOCKED' AND update_time < DATE_SUB(NOW(3), INTERVAL 3600"
                        + " SECOND) LIMIT 200");

        // 第 14 项：关单中间态未收敛
        assertUsesIndex(
                "第 14 项 关单中间态未收敛",
                "SELECT play_biz_record_no FROM play_biz_record WHERE pay_status = 'CLOSING'"
                        + " AND update_time < DATE_SUB(NOW(3), INTERVAL 3600 SECOND) LIMIT 200");

        // 第 15 项：限购额度比对。它被逐额度行调用（一轮最多 200 次），全表扫在这里放大 200 倍。
        //
        // 本条不要求 RANGE：谓词是三个等值条件，没有范围条件，REF 已是最优形态。
        // 它命中的是 uk_idempotent(user_id, activity_id, sku_id, client_req_no) 的最左三列 ——
        // **唯一键的前缀本就是一个可用的普通索引**，故本项无需新建索引（实测确认）
        assertUsesIndexWithoutFullScan(
                "第 15 项 限购额度比对",
                "SELECT COALESCE(SUM(quantity), 0) FROM play_biz_record WHERE user_id = 'U_idx_1'"
                        + " AND activity_id = 'ACT_DEMO_001' AND sku_id = 'SKU_DEMO_001'"
                        + " AND quota_status = 'LOCKED'");
    }

    /**
     * <b>金额对账的时间下界挡住刚支付的单</b>（PR-10 后置 review 补）。
     *
     * <p>本项曾是十五项里唯一不带下界的扫描。下界在这里不只是省开销，它是判据的一部分：{@code pay_amount} 由支付通知回填，一笔刚走完关单收敛的单在通知到达前 {@code
     * pay_amount} 与 {@code order_amount} 本就 可能不等 —— 没有下界时这个正常的中间态每轮都会被报成金额差异。
     *
     * <p><b>而假告警会让资损哨兵失效</b>，第 5 项正是哨兵之一。用例造一笔金额不符但<b>刚刚发生</b>的单， 断言它不被计入 —— 去掉下界即变红。
     */
    @Test
    void freshAmountMismatchIsOutsideTheStaleWindow() {
        int before = benefitOrderService.reconcile().diffOf(ReconcileItem.AMOUNT_MISMATCH);

        String bizNo = benefitOrderService.createTrade(newTradeReq("rec_fresh_amt")).getBizNo();
        payLedger.markPaid(bizNo);
        benefitOrderService.payCallback(newPayCallback(bizNo, "T_fresh", "N1", "SUCCESS"));
        // 金额改成不符，但 update_time 就是此刻 —— 落在 1 小时的下界之内
        benefitJdbc.update(
                "UPDATE play_biz_record SET pay_amount = 1 WHERE play_biz_record_no = ?", bizNo);

        int after = benefitOrderService.reconcile().diffOf(ReconcileItem.AMOUNT_MISMATCH);

        assertThat(after - before).as("刚发生的金额不符不算差异 —— 缺下界则每笔在途单每轮报一次，哨兵被噪音淹没").isZero();
    }

    // ------------------------------------------------------------------

    /**
     * {@code EXPLAIN} 该查询，断言<b>范围条件真的用在了索引上</b>（{@code type = RANGE}）。
     *
     * <p>用于带 {@code update_time <} 范围条件的三条扫描。要求 {@code RANGE} 而非「不是 {@code ALL}」：
     * 后者放得过「只用上最左那一列」的实现 —— 见本类首个用例的注释。
     */
    private void assertUsesIndex(String label, String sql) {
        String type = explainType(label, sql);
        assertThat(type)
                .as("%s 须走到索引的第二列（RANGE）—— 只命中最左前缀时 update_time 仍是回表后逐行过滤", label)
                .isEqualTo("RANGE");
    }

    /**
     * {@code EXPLAIN} 该查询，只断言没有全表扫。
     *
     * <p>用于全等值谓词的扫描：没有范围条件，{@code REF} 已是最优，要求 {@code RANGE} 反而错。
     */
    private void assertUsesIndexWithoutFullScan(String label, String sql) {
        String type = explainType(label, sql);
        assertThat(type).as("%s 不得全表扫 —— 它被逐额度行调用，一轮最多 200 次", label).isNotEqualTo("ALL");
    }

    private String explainType(String label, String sql) {
        List<Map<String, Object>> rows = benefitJdbc.queryForList("EXPLAIN " + sql);
        assertThat(rows).as("%s: EXPLAIN 应有输出", label).isNotEmpty();

        Object key = rows.get(0).get("key");
        assertThat(key).as("%s 未命中任何索引", label).isNotNull();
        return String.valueOf(rows.get(0).get("type")).toUpperCase(Locale.ROOT);
    }
}
