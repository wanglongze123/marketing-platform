package com.mp.fission.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * {@code db_fission} 的数据源、事务管理器与迁移。
 *
 * <p><b>本模块 V2 尚无表与 Mapper</b>，因此不配 {@code SqlSessionFactory}，也不做 {@code @MapperScan}。 配数据源与 Flyway
 * 的意义在于：四库的连接形态、账号隔离与迁移目录约定在 V3 填充裂变 之前就已成立 —— 届时新增的是脚本与 Mapper，不是配置结构。
 *
 * <p>迁移目录空转时 Flyway 仍建出本库的 {@code flyway_schema_history}，四库各持一份历史 是「表分布与库归属一致」的可查依据（《分阶段方案》§5.7
 * 退出标准第 12 条）。
 */
@Configuration
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

    @Bean
    DataSourceTransactionManager fissionTransactionManager(
            @Qualifier("fissionDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
