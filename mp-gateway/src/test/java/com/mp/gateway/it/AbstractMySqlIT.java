package com.mp.gateway.it;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.service.BenefitOrderService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 集成测试基类：真实 MySQL + 完整 Spring 上下文。
 *
 * <p><b>为什么不用 H2</b>：本阶段要验的恰恰是 H2 与 MySQL 行为不一致的那几处 —— 唯一索引对多行 NULL 的处理、条件更新的 {@code
 * affected_rows}、Flyway DDL 可执行性（《开发规范》§9.2）。用 H2 测等于没测。
 *
 * <p><b>为什么用单例容器而非 {@code @Testcontainers + @Container}</b>：后者按类起停， 四个测试类就要起四次 MySQL。静态块只执行一次，容器由
 * Ryuk 在 JVM 退出时回收。
 *
 * <p><b>四库同实例，账号分离</b>：容器挂载 {@code docker/mysql-init} 下的初始化脚本 —— 本地环境与 测试环境由同一份 SQL
 * 建库建号，改了权限模型不会只在一侧生效。四个数据源各用仅授权自身 schema 的账号，跨库查询在运行期被 MySQL 拒绝，而非仅靠约定（《分阶段方案》§5.6 ①）。
 *
 * <p><b>没有统一的 {@code JdbcTemplate}</b>：断言必须显式说明读的是哪个库。留一个「默认」模板 等于在测试侧重新打通四库，被权限隔离挡下的跨库读会以「测试连的是
 * root」的形式回来。
 *
 * <p>Spring 上下文构建两次：三个子类沿用本类的 {@code @SpringBootTest}，共用一个上下文； {@link HttpEndpointIT} 覆写为 {@code
 * RANDOM_PORT} 起真实 servlet 容器，构成第二个。 容器实例仍只有一个 —— 静态字段与上下文缓存无关。
 *
 * <p>测试间不共享数据：每个用例用自己的 {@code tag} 派生 userId 与 clientReqNo， 断言一律按 bizNo 收窄，不依赖执行顺序（《开发规范》§9.3）。
 */
@SpringBootTest
abstract class AbstractMySqlIT {

    /**
     * 容器自带的 {@code MYSQL_DATABASE} 仅用于就绪探测，四个业务库由挂载脚本创建。
     *
     * <p>root 口令只用于初始化脚本，业务数据源一律走分库账号。
     */
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8")
                    .withDatabaseName("db_reward")
                    .withUsername("root")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("../docker/mysql-init"),
                            "/docker-entrypoint-initdb.d");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasources(DynamicPropertyRegistry registry) {
        register(registry, "activity", "db_activity", "mp_activity");
        register(registry, "benefit", "db_benefit", "mp_benefit");
        register(registry, "reward", "db_reward", "mp_reward");
        register(registry, "fission", "db_fission", "mp_fission");
    }

    /** 账号名与口令同值，与 {@code docker/mysql-init/02-create-users.sql} 一致。 */
    private static void register(
            DynamicPropertyRegistry registry, String prefix, String schema, String account) {
        String key = "spring.datasource." + prefix + ".";
        registry.add(key + "url", () -> jdbcUrl(schema));
        registry.add(key + "username", () -> account);
        registry.add(key + "password", () -> account);
    }

    static String jdbcUrl(String schema) {
        return "jdbc:mysql://"
                + MYSQL.getHost()
                + ":"
                + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT)
                + "/"
                + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
    }

    /** seed 数据，由 V1090 / V1190 初始化 */
    protected static final String ACTIVITY_ID = "ACT_DEMO_001";

    protected static final String SKU_ID = "SKU_DEMO_001";

    /** seed SKU 售价，分 */
    protected static final long SALE_PRICE = 9900L;

    @Autowired protected BenefitOrderService benefitOrderService;

    protected JdbcTemplate activityJdbc;
    protected JdbcTemplate benefitJdbc;
    protected JdbcTemplate rewardJdbc;
    protected JdbcTemplate fissionJdbc;

    /**
     * 复用业务数据源自建模板 —— 断言与业务代码走同一批连接、同一批账号。
     *
     * <p>另开 root 连接会让测试看到业务代码看不到的东西：跨库查询在测试里成功、在运行时失败。
     */
    @Autowired
    void bindJdbcTemplates(
            @Qualifier("activityDataSource") DataSource activity,
            @Qualifier("benefitDataSource") DataSource benefit,
            @Qualifier("rewardDataSource") DataSource reward,
            @Qualifier("fissionDataSource") DataSource fission) {
        this.activityJdbc = new JdbcTemplate(activity);
        this.benefitJdbc = new JdbcTemplate(benefit);
        this.rewardJdbc = new JdbcTemplate(reward);
        this.fissionJdbc = new JdbcTemplate(fission);
    }

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
    // 库由首个参数显式指定，不设默认值：读错库的代价是查无此表而非断言恒真

    protected int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected String str(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    protected Integer num(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected String orderField(String column, String bizNo) {
        return str(
                benefitJdbc,
                "SELECT " + column + " FROM play_biz_record WHERE play_biz_record_no = ?",
                bizNo);
    }

    protected int opRecordCount(String bizNo, String opType) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM play_op_record WHERE play_biz_record_no = ? AND op_type = ?",
                bizNo,
                opType);
    }

    protected int fulfillmentCount(String bizNo) {
        return count(
                benefitJdbc,
                "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no = ?",
                bizNo);
    }

    protected int grantRecordCount(String bizNo) {
        return count(
                rewardJdbc,
                "SELECT COUNT(*) FROM reward_grant_record WHERE biz_order_no = ?",
                bizNo);
    }

    protected int grantItemCount(String bizNo) {
        return count(
                rewardJdbc,
                "SELECT COUNT(*) FROM reward_grant_item i JOIN reward_grant_record r"
                        + " ON i.op_no = r.op_no WHERE r.biz_order_no = ?",
                bizNo);
    }
}
