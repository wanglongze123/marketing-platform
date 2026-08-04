package com.mp.gateway.it;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.service.BenefitOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试基类：真实 MySQL + 完整 Spring 上下文。
 *
 * <p><b>为什么不用 H2</b>：本阶段要验的恰恰是 H2 与 MySQL 行为不一致的那几处 —— 唯一索引对多行 NULL 的处理、条件更新的 {@code
 * affected_rows}、Flyway DDL 可执行性（《开发规范》§9.2）。用 H2 测等于没测。
 *
 * <p><b>为什么用单例容器而非 {@code @Testcontainers + @Container}</b>：后者按类起停， 三个测试类就要起三次 MySQL。静态块只执行一次，容器由
 * Ryuk 在 JVM 退出时回收。 三个子类的注解完全一致，Spring 上下文亦只构建一次。
 *
 * <p>测试间不共享数据：每个用例用自己的 {@code tag} 派生 userId 与 clientReqNo， 断言一律按 bizNo 收窄，不依赖执行顺序（《开发规范》§9.3）。
 */
@SpringBootTest
abstract class AbstractMySqlIT {

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8")
                    .withDatabaseName("db_reward")
                    .withUsername("root")
                    .withPassword("test");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** seed 数据，由 V1090 / V1190 初始化 */
    protected static final String ACTIVITY_ID = "ACT_DEMO_001";

    protected static final String SKU_ID = "SKU_DEMO_001";

    /** seed SKU 售价，分 */
    protected static final long SALE_PRICE = 9900L;

    @Autowired protected BenefitOrderService benefitOrderService;

    @Autowired protected JdbcTemplate jdbc;

    protected CreateTradeReq newTradeReq(String tag) {
        CreateTradeReq req = new CreateTradeReq();
        req.setUserId("U_" + tag);
        req.setActivityId(ACTIVITY_ID);
        req.setSkuId(SKU_ID);
        req.setClientReqNo("REQ_" + tag);
        req.setQuantity(1);
        return req;
    }

    protected PayCallbackReq newPayCallback(
            String bizNo, String tradeNo, String notifySeq, String payStatus) {
        PayCallbackReq req = new PayCallbackReq();
        req.setOutTradeNo(bizNo);
        req.setTradeNo(tradeNo);
        req.setNotifySeq(notifySeq);
        req.setPayStatus(payStatus);
        req.setPayAmount(SALE_PRICE);
        req.setCurrency("CNY");
        req.setMerchantId("MCH_DEMO");
        return req;
    }

    // ---- 断言辅助 ----

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected String str(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    protected Integer num(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected String orderField(String column, String bizNo) {
        return str(
                "SELECT " + column + " FROM play_biz_record WHERE play_biz_record_no = ?", bizNo);
    }

    protected int opRecordCount(String bizNo, String opType) {
        return count(
                "SELECT COUNT(*) FROM play_op_record WHERE play_biz_record_no = ? AND op_type = ?",
                bizNo,
                opType);
    }

    protected int fulfillmentCount(String bizNo) {
        return count(
                "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no = ?",
                bizNo);
    }

    protected int grantRecordCount(String bizNo) {
        return count("SELECT COUNT(*) FROM reward_grant_record WHERE biz_order_no = ?", bizNo);
    }

    protected int grantItemCount(String bizNo) {
        return count(
                "SELECT COUNT(*) FROM reward_grant_item i JOIN reward_grant_record r"
                        + " ON i.op_no = r.op_no WHERE r.biz_order_no = ?",
                bizNo);
    }
}
