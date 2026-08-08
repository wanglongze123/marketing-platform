package com.mp.benefit.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
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
 * {@code db_benefit} 的数据源、会话工厂、事务管理器与迁移。
 *
 * <p>本库承载「状态、操作记录、任务同库同事务」这条硬约束（技术方案 §6.5 本地消息表的前提）—— 主单、操作记录与 V2 的 {@code benefit_task}
 * 三者必须落在同一个事务管理器下， 否则本地消息表退化为「写完主单再尽力写任务」，进程在两者之间崩溃即丢任务。
 *
 * <p>其余设计意图见 {@code ActivityDataSourceConfig}。
 */
@Configuration
@MapperScan(
        basePackages = "com.mp.benefit.repository",
        sqlSessionFactoryRef = "benefitSqlSessionFactory")
public class BenefitDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.benefit")
    DataSourceProperties benefitDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.benefit.hikari")
    DataSource benefitDataSource(
            @Qualifier("benefitDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(initMethod = "migrate")
    Flyway benefitFlyway(@Qualifier("benefitDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/benefit")
                .baselineOnMigrate(true)
                .load();
    }

    @Bean
    @DependsOn("benefitFlyway")
    SqlSessionFactory benefitSqlSessionFactory(
            @Qualifier("benefitDataSource") DataSource dataSource,
            MybatisPlusInterceptor interceptor)
            throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTypeAliasesPackage("com.mp.benefit.entity");
        // 拦截器必须显式挂到本工厂上。自建 SqlSessionFactory 不经 starter 的自动装配，
        // 容器里有 MybatisPlusInterceptor 这个 bean 也不会被用上 ——
        // 表现是 selectPage 不报错却不分页：SQL 无 LIMIT、返回全表、getTotal() 恒 0
        factory.setPlugins(interceptor);
        return factory.getObject();
    }

    @Bean
    DataSourceTransactionManager benefitTransactionManager(
            @Qualifier("benefitDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
