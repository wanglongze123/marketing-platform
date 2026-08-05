package com.mp.gateway;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * V0/V1 单进程启动入口（模块化单体）。
 *
 * <p>各 module 以 Dubbo injvm 协议在同一进程内本地调用，V3 改为 tri 远程调用 —— 业务代码不变，只改配置。
 *
 * <p><b>此处不再有 {@code @MapperScan}</b>：一条 {@code com.mp.*.repository} 通配把四个模块的 Mapper 全绑到同一个 {@code
 * SqlSessionFactory} 上，等价于单库。V2 起各模块在自己的 {@code DataSourceConfig} 中 按包 scan 并指定 {@code
 * sqlSessionFactoryRef}，库的归属随模块走，V3 拆服务时配置原样迁移。
 */
@SpringBootApplication(scanBasePackages = "com.mp")
@EnableDubbo(scanBasePackages = "com.mp")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
