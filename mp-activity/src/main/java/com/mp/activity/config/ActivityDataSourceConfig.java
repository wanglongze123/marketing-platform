package com.mp.activity.config;

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
 * {@code db_activity} 的数据源、会话工厂、事务管理器与迁移。
 *
 * <p><b>为什么配置随模块而非集中在 gateway</b>：V3 拆服务时本类原样迁走，不需要从 gateway 里 摘一段出来。库的归属是模块的属性，不是部署形态的属性。
 *
 * <p><b>为什么用 {@code mp_activity} 账号而非 root</b>：四个 schema 同实例时，多数据源只能拦截 不带库名限定的表引用，{@code
 * db_reward.x JOIN db_benefit.y} 在同一连接上照常执行。 权限隔离让「禁止跨库 JOIN」在运行期成为约束（《开发规范》§4.5、《分阶段方案》§5.6 ①）。
 *
 * <p><b>迁移路径按库分目录</b>：单进程下四个模块的 jar 共用一条 classpath，{@code classpath:db/migration}
 * 会解析出全部模块的脚本并全部执行于当前库 —— 这正是 V1 九张表全落 {@code db_reward} 的原因。
 */
@Configuration
@MapperScan(
        basePackages = "com.mp.activity.repository",
        sqlSessionFactoryRef = "activitySqlSessionFactory")
public class ActivityDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.activity")
    DataSourceProperties activityDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.activity.hikari")
    DataSource activityDataSource(
            @Qualifier("activityDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * {@code initMethod = "migrate"} 使迁移在 Bean 初始化时执行。
     *
     * <p>{@code baselineOnMigrate} 保留：允许在已有表的库上首次接管。
     */
    @Bean(initMethod = "migrate")
    Flyway activityFlyway(@Qualifier("activityDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/activity")
                .baselineOnMigrate(true)
                .load();
    }

    /** 依赖 Flyway 而非仅依赖 DataSource：保证任何 Mapper 可用之前，本库的表已建好。 */
    @Bean
    @DependsOn("activityFlyway")
    SqlSessionFactory activitySqlSessionFactory(
            @Qualifier("activityDataSource") DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTypeAliasesPackage("com.mp.activity.entity");
        return factory.getObject();
    }

    /**
     * Bean 名带库前缀，组合注解按名引用。
     *
     * <p>四套数据源下不存在「默认」事务管理器：不带 {@code transactionManager} 属性的 {@code @Transactional}
     * 会按类型注入取到别库的管理器，本库的写各自自动提交 —— 不报错、不回滚。 {@code ShapeFreezeTest} 静态禁止裸注解，回滚 IT 验证绑定指向正确的库。
     */
    @Bean
    DataSourceTransactionManager activityTransactionManager(
            @Qualifier("activityDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
