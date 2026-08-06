package com.mp.reward.config;

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
 * {@code db_reward} 的数据源、会话工厂、事务管理器与迁移。
 *
 * <p>设计意图见 {@code ActivityDataSourceConfig}。
 */
@Configuration
@MapperScan(
        basePackages = "com.mp.reward.repository",
        sqlSessionFactoryRef = "rewardSqlSessionFactory")
public class RewardDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.reward")
    DataSourceProperties rewardDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.reward.hikari")
    DataSource rewardDataSource(
            @Qualifier("rewardDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(initMethod = "migrate")
    Flyway rewardFlyway(@Qualifier("rewardDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/reward")
                .baselineOnMigrate(true)
                .load();
    }

    @Bean
    @DependsOn("rewardFlyway")
    SqlSessionFactory rewardSqlSessionFactory(@Qualifier("rewardDataSource") DataSource dataSource)
            throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTypeAliasesPackage("com.mp.reward.entity");
        return factory.getObject();
    }

    @Bean
    DataSourceTransactionManager rewardTransactionManager(
            @Qualifier("rewardDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
