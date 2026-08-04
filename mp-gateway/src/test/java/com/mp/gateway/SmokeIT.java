package com.mp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.common.util.BizNoGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V0 冒烟链路集成测试。
 *
 * <p>命名为 {@code *IT} 而非 {@code *Test}，由 failsafe 在 verify 阶段执行 —— 此时 Testcontainers 才起得来。命名错了会在
 * surefire 阶段跑，容器尚未就绪。
 *
 * <p>用真实 MySQL 而非 H2：H2 与 MySQL 的唯一索引 NULL 行为不一致、不支持 SKIP LOCKED，用它测等于没测。
 *
 * <p><b>V1 结束时删除</b>。
 */
@SpringBootTest
@Testcontainers
class SmokeIT {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8")
                    .withDatabaseName("db_reward")
                    .withUsername("root")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private BenefitOrderService benefitOrderService;

    @Autowired private JdbcTemplate jdbcTemplate;

    /** 链路贯穿四层，且末端真的写进了数据库 —— Flyway 建表、MyBatis-Plus 写入、事务提交全部验证到。 */
    @Test
    void smokeChainCrossesAllLayersAndPersists() {
        String bizNo = BizNoGenerator.bizNo();

        String chain = benefitOrderService.smoke(bizNo);

        assertThat(chain).isEqualTo("benefit-order,reward,mock");

        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM smoke_record WHERE biz_no = ?", Integer.class, bizNo);
        assertThat(rows).isEqualTo(1);
    }

    /** 业务单号必须每次不同 —— 与幂等键规则相反，见《开发规范》§5.1。 */
    @Test
    void bizNoIsAlwaysUnique() {
        assertThat(BizNoGenerator.bizNo()).isNotEqualTo(BizNoGenerator.bizNo());
    }
}
