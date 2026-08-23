package com.mp.benefit.config;

import com.mp.api.activity.service.ActivityService;
import com.mp.api.mock.service.MockPayService;
import com.mp.api.reward.service.RewardService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * benefit-order 对下层服务的远程引用。V4 新增，仅在分布式形态下装配。
 *
 * <p><b>为什么用 {@code @Bean} + {@link ReferenceBean} 而不是在字段上标 {@code @DubboReference}</b>：
 *
 * <ul>
 *   <li>本模块有 13 处跨模块注入，其中 4 处是<b>构造器注入</b>（{@code QueryGrantTaskHandler} 等）。
 *       {@code @DubboReference} 标不到构造器参数上，逐处改注解覆盖不全，且会把字段注入与构造器注入 改成两种风格
 *   <li>业务代码<b>一行不动</b>。{@code @Autowired ActivityService} 与 {@code private final RewardService}
 *       都保持原样，容器里那个 bean 从哪来由本类决定 —— 这正是 V4 退出标准第 1 条「业务代码零改动」 想要的形状（《分阶段方案》§6A.3）
 *   <li>开关集中在一处。散落 13 个注解意味着以后加一处调用就要记得加一次，而漏掉不报错 —— 它会安静地注入本地 bean
 * </ul>
 *
 * <p><b>{@code mp.dubbo.remote=true} 才装配</b>。单进程形态下本类整体不生效，跨模块调用仍走 Spring 容器里的本地实现，与 V3
 * 完全一致；分布式形态下本类提供远程代理，而本地实现根本不在 classpath 上（pom 已切断实现模块依赖）。
 *
 * <p><b>不用 {@code scope} 占位符做单注解切换</b>：{@code @DubboReference(scope="${...}")} 确实能在 两种取值间切，但 {@code
 * scope=local} 要求本地有同接口的 {@code @DubboService} 暴露 —— 拆服务后 那个实现类不在本进程，注入会直接失败。两种形态的差别不只是「本地还是远程」，
 * 而是「有没有这个 bean」，故用装配开关而非协议开关。
 */
@Configuration
@ConditionalOnProperty(name = "mp.dubbo.remote", havingValue = "true")
public class BenefitRemoteConfig {

    /** 活动配置与资格决策。查配置在下单主链路上，超时取 3 秒。 */
    @Bean
    @DubboReference(protocol = "tri", timeout = 3000, check = false)
    public ReferenceBean<ActivityService> activityService() {
        return new ReferenceBean<>();
    }

    /**
     * 统一发奖。
     *
     * <p><b>超时必须显著短于任务租约</b>：扇出跑超租约时任务会被另一实例正当接管并重跑，于是同一笔 发放有两个实例同时在途。取 5 秒，与 {@code
     * FANOUT_TIMEOUT_SECONDS} 的 10 秒留出层次 —— 单次 RPC 5 秒、整体扇出 10 秒、租约 30 秒。
     *
     * <p><b>{@code retries = 0}</b>：发奖不是幂等重试的地方。Dubbo 层自动重试会绕过四分类收敛 —— 超时后该由 {@code QUERY_GRANT} 以原
     * {@code opNo} 查单确认，而不是盲目再发一次。
     */
    @Bean
    @DubboReference(protocol = "tri", timeout = 5000, retries = 0, check = false)
    public ReferenceBean<RewardService> rewardService() {
        return new ReferenceBean<>();
    }

    /** mock 支付方。{@code retries = 0} 同上：支付相关调用一律不由框架重试。 */
    @Bean
    @DubboReference(protocol = "tri", timeout = 5000, retries = 0, check = false)
    public ReferenceBean<MockPayService> mockPayService() {
        return new ReferenceBean<>();
    }
}
