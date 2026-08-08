package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多数据源的分库形态，对应《分阶段方案》§5.7 退出标准 12、13。
 *
 * <p>V1 是单数据源 + 一条 {@code classpath:db/migration}，九张表全落 {@code db_reward}，另三库为空 ——
 * 表的库归属只存在于文档里。本类验的是「文档写的归属在运行期成立」。
 */
class MultiDataSourceIT extends AbstractMySqlIT {

    /**
     * 标准 12：四库各持一份迁移历史，且表分布与 §4.4 的库归属一致。
     *
     * <p>只查历史表存在不够 —— 四个 Flyway 实例若都指向同一 location，历史表也是四份，但内容相同、 表也会建成四份。故同时断言各库实际持有的业务表。
     */
    @Test
    void eachSchemaOwnsItsOwnMigrationHistoryAndTables() {
        assertHistoryOwnedBy(
                activityJdbc,
                "db_activity",
                "1001",
                "1090",
                "3001",
                "3002",
                "3003",
                "3004",
                "3090");
        assertHistoryOwnedBy(
                benefitJdbc,
                "db_benefit",
                "1101",
                "1102",
                "1103",
                "1104",
                "1105",
                "1190",
                "2101",
                "2102",
                "2103",
                "2190");
        assertHistoryOwnedBy(
                rewardJdbc, "db_reward", "0201", "1201", "1202", "1203", "3201", "3202");
        assertHistoryOwnedBy(fissionJdbc, "db_fission", "3301", "3302", "3303", "3304", "3305");

        assertThat(businessTables(activityJdbc, "db_activity"))
                .containsExactlyInAnyOrder(
                        "marketing_activity",
                        "activity_config_version",
                        "activity_op_record",
                        "activity_task");
        assertThat(businessTables(benefitJdbc, "db_benefit"))
                .containsExactlyInAnyOrder(
                        "benefit_sku",
                        "benefit_package",
                        "benefit_item",
                        "play_biz_record",
                        "benefit_fulfillment_record",
                        "play_op_record",
                        "benefit_task",
                        "marketing_stock",
                        "user_purchase_quota");
        // smoke_record 由 V1203 删除，不应残留
        assertThat(businessTables(rewardJdbc, "db_reward"))
                .containsExactlyInAnyOrder(
                        "reward_grant_record",
                        "reward_grant_item",
                        "reward_revoke_record",
                        "reward_notify_record");
        assertThat(businessTables(fissionJdbc, "db_fission"))
                .containsExactlyInAnyOrder(
                        "fission_group", "fission_relation", "fission_op_record", "fission_task");
    }

    /**
     * 标准 13：跨库查询在运行期失败。
     *
     * <p><b>不以「不带库名限定报 1146」为准</b>：那只说明连接的默认库里没有这张表，不配分库账号 也成立。带库名限定的 {@code
     * db_benefit.play_biz_record JOIN db_reward.reward_grant_record} 在同一实例上语法完全合法 ——
     * 只有权限隔离能拦下它。这正是《开发规范》§4.5「禁止跨库 JOIN」 从约定变为约束的那一步。
     */
    @Test
    void qualifiedCrossSchemaJoinIsDeniedByPrivilege() {
        assertThatThrownBy(
                        () ->
                                benefitJdbc.queryForList(
                                        "SELECT b.play_biz_record_no, r.op_no"
                                                + " FROM db_benefit.play_biz_record b"
                                                + " JOIN db_reward.reward_grant_record r"
                                                + " ON r.biz_order_no = b.play_biz_record_no"))
                .rootCause()
                .hasMessageContaining("SELECT command denied to user 'mp_benefit'");

        // 反向同样被拒：权限是双向隔离，不是「reward 可读 benefit」
        assertThatThrownBy(
                        () ->
                                rewardJdbc.queryForList(
                                        "SELECT COUNT(*) FROM db_benefit.play_biz_record"))
                .rootCause()
                .hasMessageContaining("SELECT command denied to user 'mp_reward'");

        // 对照：本库带库名限定的查询正常 —— 失败原因是权限，不是语法
        assertThat(count(benefitJdbc, "SELECT COUNT(*) FROM db_benefit.play_biz_record"))
                .isGreaterThanOrEqualTo(0);
    }

    /** Flyway 的 {@code version} 列不含 {@code V} 前缀，断言按列的实际取值写。 */
    private void assertHistoryOwnedBy(JdbcTemplate jdbc, String schema, String... versions) {
        List<String> applied =
                jdbc.queryForList(
                        "SELECT version FROM flyway_schema_history"
                                + " WHERE version IS NOT NULL ORDER BY installed_rank",
                        String.class);
        assertThat(applied).as("%s 的迁移历史", schema).containsExactly(versions);
    }

    /** 业务表 = 该 schema 下除 Flyway 历史表以外的全部表。 */
    private List<String> businessTables(JdbcTemplate jdbc, String schema) {
        return jdbc
                .queryForList(
                        "SELECT table_name FROM information_schema.tables"
                                + " WHERE table_schema = ? AND table_name <> 'flyway_schema_history'",
                        schema)
                .stream()
                .map(row -> String.valueOf(firstValue(row)))
                .toList();
    }

    private Object firstValue(Map<String, Object> row) {
        return row.values().iterator().next();
    }
}
