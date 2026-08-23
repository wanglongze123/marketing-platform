package com.mp.gateway.dist;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gateway 的分布式形态启动入口。V4 新增。
 *
 * <p><b>为什么不复用 {@code GatewayApplication}</b>：那个类扫 {@code com.mp}，因为单进程形态要靠它 把全部模块装配进同一个容器。而
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
 *
 * <p><b>放在 {@code com.mp.gateway.dist} 子包而非与 {@code GatewayApplication} 并列</b>：
 * {@code @SpringBootTest} 不指定 {@code classes} 时按测试类所在包向上找 {@code @SpringBootConfiguration}，
 * 两个类同包会让它抛 {@code Found multiple @SpringBootConfiguration annotated classes} 而拒绝启动。
 *
 * <p>指定 {@code classes} 也能解决同名冲突，但代价大得多：那会关掉 Boot 的自动配置探测， Redisson、MyBatis 等 starter 全部失效，34
 * 个集成测试改报 {@code No qualifying bean of type RedissonClient}。挪个包是零副作用的做法 —— 测试从 {@code
 * com.mp.gateway.it} 往上找， 只会看见同层的 {@code GatewayApplication}。
 */
// 排除项写在 application-dist.yml 的 spring.autoconfigure.exclude 里，不写在这里：
// 注解上的 exclude 会被 Spring 的自动配置排除清单全局收集 —— 即便本类不是当前上下文的
// 配置源，它的排除项照样生效。实测表现为 34 个集成测试全报
// No qualifying bean of type RedissonClient：测试用的是 GatewayApplication，却被这个
// 类的注解连累。写进 dist profile 的配置里则只在该 profile 激活时生效。
@SpringBootApplication(scanBasePackages = {"com.mp.gateway", "com.mp.common"})
@EnableDubbo(scanBasePackages = "com.mp.gateway")
public class GatewayDistApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayDistApplication.class, args);
    }
}
