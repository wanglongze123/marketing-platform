package com.mp.gateway;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * V0/V1 单进程启动入口（模块化单体）。
 *
 * <p>各 module 以 Dubbo injvm 协议在同一进程内本地调用，V3 改为 tri 远程调用 —— 业务代码不变，只改配置。
 */
@SpringBootApplication(scanBasePackages = "com.mp")
@EnableDubbo(scanBasePackages = "com.mp")
@MapperScan("com.mp.*.repository")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
