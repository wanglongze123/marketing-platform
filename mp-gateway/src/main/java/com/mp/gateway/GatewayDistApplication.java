package com.mp.gateway;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gateway 的分布式形态启动入口。V4 新增。
 *
 * <p><b>为什么不复用 {@link GatewayApplication}</b>：那个类扫 {@code com.mp}，因为单进程形态要靠它 把全部模块装配进同一个容器。而
 * gateway 的 pom 至今仍依赖五个实现模块（单进程形态的职责所在）， 于是分布式形态下这条广扫会连带做两件错事：
 *
 * <ul>
 *   <li>把 benefit / fission / activity / reward 的实现类装进 gateway 进程 —— 拆了但没真拆， Dubbo 发现同进程有提供者就走本地
 *       bean，跨服务 RPC 从不发生
 *   <li>四个 {@code RemoteConfig} 同时被扫到。它们各自声明对 {@code ActivityService} 与 {@code RewardService}
 *       的引用，bean 名加了模块前缀后不再撞名，但同类型多实例会让 按类型注入报歧义
 * </ul>
 *
 * <p>本类只扫 {@code com.mp.gateway} 与 {@code com.mp.common}：前者是接入层自身（两个 controller、 异常处理、traceId
 * 过滤器），后者是无状态工具与签名器。业务能力一律经 {@code GatewayRemoteConfig} 的远程引用向后取。
 *
 * <p><b>无 {@code @EnableScheduling}</b>：拆服务后 gateway 不再驱动任何任务，三个定时器随 benefit-order 与 fission 走。
 *
 * <p>启动方式：{@code java -jar mp-gateway-*-boot.jar} 时由 {@code spring-boot-maven-plugin} 的 {@code
 * mainClass} 指定用哪个入口，见本模块 pom。
 */
@SpringBootApplication(
        scanBasePackages = {"com.mp.gateway", "com.mp.common"},
        // 拆服务后 gateway 不连任何库，但 JDBC 相关的 jar 仍随传递依赖在 classpath 上，
        // Boot 的自动配置见状就要找 spring.datasource.url，找不到即启动失败
        // （Failed to configure a DataSource）。四个真实数据源由各业务服务自己的
        // DataSourceConfig 建，从不走这条自动配置，故这里整体关掉。
        exclude = {
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc
                    .DataSourceTransactionManagerAutoConfiguration.class,
            // Redis 同理：分布式锁在 benefit-order 侧，gateway 不用。留着的话
            // Redisson 的自动配置会在启动时试连 localhost:6379 并因连不上而失败
            org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
            org.redisson.spring.starter.RedissonAutoConfigurationV2.class
        })
@EnableDubbo(scanBasePackages = "com.mp.gateway")
public class GatewayDistApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayDistApplication.class, args);
    }
}
