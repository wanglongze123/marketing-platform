package com.mp.reward.config;

import com.mp.api.mock.service.MockProviderService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * reward 对 mock 供应方的远程引用。V4 新增，仅在分布式形态下装配。
 *
 * <p>设计理由与 {@code BenefitRemoteConfig} 一致，见其类注释。
 *
 * <p><b>这条调用是「下游结果不确定」这个核心约束的原产地</b>。V3 之前它是同进程方法调用，超时靠 {@code FaultInjector}
 * 人为制造；拆服务后进程边界真实存在，网络超时、连接重置、对端进程消失 都会自然发生 —— 四分类收敛机制第一次面对它本来要面对的东西。
 */
@Configuration
@ConditionalOnProperty(name = "mp.dubbo.remote", havingValue = "true")
public class RewardRemoteConfig {

    /**
     * mock 权益供应方。
     *
     * <p><b>{@code retries = 0} 在这里格外要紧</b>：Dubbo 的自动重试会在超时后再发一次请求，而下游 可能已经执行 ——
     * 那正是重复发放。本系统对这种不确定的处置是「以原 {@code opNo} 主动查单」， 由 {@code QUERY_GRANT} 任务承担，不能让框架在更低的一层擅自重试把它绕过去。
     */
    @Bean
    @DubboReference(protocol = "tri", timeout = 5000, retries = 0, check = false)
    public ReferenceBean<MockProviderService> mockProviderService() {
        return new ReferenceBean<>();
    }
}
