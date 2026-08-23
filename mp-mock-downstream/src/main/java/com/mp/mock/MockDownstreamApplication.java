package com.mp.mock;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mock 下游服务的独立启动入口。V4 新增。
 *
 * <p>与 {@code GatewayApplication} 并存，理由见 {@code ActivityApplication} 的类注释。
 *
 * <p><b>它扮演的是「外部系统」</b>：支付方与权益供应方。拆服务后跑在自己的进程里，平台调它即真实 跨进程 RPC —— 这正是「下游结果不确定」这个核心约束在 V4
 * 变得可观测的地方：进程边界之外的超时 才是真超时，injvm 形态下那只是一次本地方法调用加人为延迟。
 *
 * <p><b>无数据源、无 Flyway</b>：mock 的账本是进程内 {@code ConcurrentHashMap}（{@code PayLedger} / {@code
 * ProviderLedger}），重启即清空。这是有意的——它模拟的是外部系统，不该有本平台的库。
 *
 * <p><b>无 {@code @EnableScheduling}</b>：mock 不主动推送任何东西。通知一律由外部触发（{@code MockFaultController}
 * 的签名端点配合手工或脚本调用），以此保留「通知是外部事件」的形状。
 */
@SpringBootApplication(scanBasePackages = {"com.mp.mock", "com.mp.common"})
@EnableDubbo(scanBasePackages = "com.mp.mock")
public class MockDownstreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockDownstreamApplication.class, args);
    }
}
