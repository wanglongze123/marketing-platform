package com.mp.activity;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * activity 服务的独立启动入口。V4 新增。
 *
 * <p><b>它与 {@code GatewayApplication} 并存而非取代</b>：单进程形态下 gateway 扫 {@code com.mp} 把本模块
 * 一并装配，本类不参与；分布式形态下本类是 activity 服务的入口，gateway 只留接入层。同一份业务代码 两种形态都能跑，这正是 V4 退出标准第 1
 * 条要证明的事（《分阶段方案》§6A.3）。
 *
 * <p><b>扫描范围限定在本模块与 {@code com.mp.common}</b>，不扫 {@code com.mp}：后者会把 classpath 上 其余模块的
 * {@code @Component} 一并装配 —— 拆服务后若某个实现 jar 因传递依赖仍在 classpath 上， 广扫会把它悄悄装进本进程，于是「拆了但没真拆」，调用走本地 bean
 * 而非远程。
 *
 * <p><b>无 {@code @EnableScheduling}</b>：本模块没有可靠任务调度器。加了不报错但会凭空起一个空转的 调度线程池，反而让「哪些服务在跑任务」这件事变模糊。
 */
@SpringBootApplication(scanBasePackages = {"com.mp.activity", "com.mp.common"})
@EnableDubbo(scanBasePackages = "com.mp.activity")
public class ActivityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActivityApplication.class, args);
    }
}
