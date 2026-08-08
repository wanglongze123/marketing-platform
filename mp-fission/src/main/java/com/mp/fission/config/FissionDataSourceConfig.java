package com.mp.fission.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * {@code db_fission} 的数据源、会话工厂、事务管理器与迁移。
 *
 * <p>V2 时本模块无表也无 Mapper，故只配数据源与 Flyway；V3 PR-2 建四张表后补上 {@code SqlSessionFactory} 与
 * {@code @MapperScan} —— 新增的是这两项，连接形态、账号隔离与迁移目录约定 在 V2 就已成立，未动配置结构。
 *
 * <p>四库各持一份 {@code flyway_schema_history}，是「表分布与库归属一致」的可查依据 （《分阶段方案》§5.7 退出标准第 12 条）。
 */
@Configuration
@MapperScan(
        basePackages = "com.mp.fission.repository",
        sqlSessionFactoryRef = "fissionSqlSessionFactory")
public class FissionDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.fission")
    DataSourceProperties fissionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.fission.hikari")
    DataSource fissionDataSource(
            @Qualifier("fissionDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(initMethod = "migrate")
    Flyway fissionFlyway(@Qualifier("fissionDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/fission")
                .baselineOnMigrate(true)
                .load();
    }

    /** 依赖 Flyway 而非仅依赖 DataSource：保证任何 Mapper 可用之前，本库的表已建好。 */
    @Bean
    @DependsOn("fissionFlyway")
    SqlSessionFactory fissionSqlSessionFactory(
            @Qualifier("fissionDataSource") DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTypeAliasesPackage("com.mp.fission.entity");
        return factory.getObject();
    }

    /**
     * Bean 名带库前缀，组合注解按名引用。
     *
     * <p>四套数据源下不存在「默认」事务管理器：不带 {@code transactionManager} 属性的 {@code @Transactional}
     * 会按类型注入取到别库的管理器，本库的写各自自动提交 —— 不报错、不回滚。
     */
    @Bean
    DataSourceTransactionManager fissionTransactionManager(
            @Qualifier("fissionDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
