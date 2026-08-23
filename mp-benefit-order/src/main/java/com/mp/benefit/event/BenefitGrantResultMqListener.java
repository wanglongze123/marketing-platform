package com.mp.benefit.event;

import com.mp.common.event.EventTopics;
import com.mp.common.event.RewardGrantResultEvent;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 发奖结果事件的 RocketMQ 消费入口。V4 新增，仅在 {@code mp.event.transport=rocketmq} 时装配。
 *
 * <p><b>它不含任何业务逻辑，只把消息转交 {@link BenefitGrantResultListener}</b>。那个类的处理体 与传输方式无关 —— 按 {@code opNo}
 * 反查、非终态跳过、复用 {@code settleGrant} 收敛，无论事件 从进程内总线来还是从 MQ 来都是同一套判断。两份实现会让「主单何时能置终态」这个判断
 * 要证明两遍，而它们迟早漂移。
 *
 * <p><b>被转交的那个类仍带着 {@code @EventListener}，这不会导致重复消费</b>：MQ 形态下发布方装配的是 {@code
 * rocketMqGrantResultPublisher}，它只往 MQ 发，从不调 {@code ApplicationEventPublisher} ——
 * 进程内事件根本不会产生，那个注解自然不会被触发。反过来在 injvm 形态下本类不装配。 两条通路各自完整，从不同时活跃。
 *
 * <p><b>幂等由 {@code settleGrant} 内部的条件更新承担，不在这一层</b>。RocketMQ 是至少一次投递， 同一条消息可能重复送达；明细的 {@code
 * settleByGrantOpNo} 限定「未终结」、主单的推进限定前置态， 重复消费不产生第二次推进。这与进程内投递时的处置完全一致 —— 消费侧本就不该假设「只会收到一次」。
 *
 * <p><b>异常不向上抛</b>：抛出会让 RocketMQ 重投，而这条通路本就允许丢（事件加速、查单保证）。 重投一条注定失败的消息只会在消费组里堆积，最终进死信队列 ——
 * 那是给一个不需要可靠性的通路 强加可靠性。失败由 {@code QUERY_GRANT} 兜底。
 */
@Component
@ConditionalOnProperty(name = "mp.event.transport", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = EventTopics.GRANT_RESULT,
        consumerGroup = EventTopics.GROUP_BENEFIT)
public class BenefitGrantResultMqListener implements RocketMQListener<RewardGrantResultEvent> {

    private static final Logger log = LoggerFactory.getLogger(BenefitGrantResultMqListener.class);

    private final BenefitGrantResultListener delegate;

    public BenefitGrantResultMqListener(BenefitGrantResultListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onMessage(RewardGrantResultEvent event) {
        try {
            delegate.onGrantResult(event);
        } catch (Exception e) {
            log.warn(
                    "consume grant result event failed, convergence falls back to query, opNo={}",
                    event.opNo(),
                    e);
        }
    }
}
