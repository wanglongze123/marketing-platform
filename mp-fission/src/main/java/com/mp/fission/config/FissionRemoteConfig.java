package com.mp.fission.config;

import com.mp.api.activity.service.ActivityService;
import com.mp.api.mock.service.MockSocialService;
import com.mp.api.reward.service.RewardService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * fission 对下层服务的远程引用。V4 新增，仅在分布式形态下装配。
 *
 * <p>设计理由与 {@code BenefitRemoteConfig} 一致，见其类注释。
 *
 * <p><b>裂变侧有一处 injvm 与 tri 的语义差异要留意</b>：{@code FissionServiceImpl} 的原注释记着 —— injvm 会把业务异常包成 {@code
 * RuntimeException} 而丢掉错误码。切 tri 后异常经序列化传回， {@code BizException} 的 {@code code} 得以保留，资格决策的 {@code
 * 1201}（不符合条件）与 {@code 5201}（系统异常）在调用侧终于可分。这是拆服务顺带修好的一处，不是引入的问题。
 */
@Configuration
@ConditionalOnProperty(name = "mp.dubbo.remote", havingValue = "true")
public class FissionRemoteConfig {

    /** 活动配置与资格决策。师傅进场要先过资格校验，在主链路上。 */
    @Bean
    @DubboReference(protocol = "tri", timeout = 3000, check = false)
    public ReferenceBean<ActivityService> activityService() {
        return new ReferenceBean<>();
    }

    /** 统一发奖。{@code retries = 0} 的理由同 benefit 侧：超时由查单收敛，不由框架重试。 */
    @Bean
    @DubboReference(protocol = "tri", timeout = 5000, retries = 0, check = false)
    public ReferenceBean<RewardService> rewardService() {
        return new ReferenceBean<>();
    }

    /**
     * mock 社交关系。
     *
     * <p><b>超时给到 5 秒</b>：好友召回是分页批量调用，一页 50 人，比单笔业务调用重。它不在资金 链路上，超时的后果是过滤链降级（{@code
     * FriendFilterChain} 按 fail-open / fail-close 分别处置）， 不是资损。
     */
    @Bean
    @DubboReference(protocol = "tri", timeout = 5000, check = false)
    public ReferenceBean<MockSocialService> socialService() {
        return new ReferenceBean<>();
    }
}
