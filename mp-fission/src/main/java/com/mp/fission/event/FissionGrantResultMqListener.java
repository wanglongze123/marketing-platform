package com.mp.fission.event;

import com.mp.common.event.EventTopics;
import com.mp.common.event.RewardGrantResultEvent;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 发奖结果事件的 RocketMQ 消费入口（裂变侧）。V4 新增，仅在 {@code mp.event.transport=rocketmq} 时装配。
 *
 * <p>结构与权益侧的 {@code BenefitGrantResultMqListener} 一致，见其类注释。只转交，不含业务逻辑。
 *
 * <p><b>消费组必须与权益侧不同</b>（{@link EventTopics#GROUP_FISSION} 对 {@link
 * EventTopics#GROUP_BENEFIT}）：RocketMQ 同组内是负载均衡，一条消息只有一个实例收到。 两个玩法共用一组的话，发奖结果会被瓜分 ——
 * 每个玩法只看到一半属于自己的事件，另一半 静默丢失，而收敛退化为查单后表面一切正常。
 */
@Component
@ConditionalOnProperty(name = "mp.event.transport", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = EventTopics.GRANT_RESULT,
        consumerGroup = EventTopics.GROUP_FISSION)
public class FissionGrantResultMqListener implements RocketMQListener<RewardGrantResultEvent> {

    private static final Logger log = LoggerFactory.getLogger(FissionGrantResultMqListener.class);

    private final FissionGrantResultListener delegate;

    public FissionGrantResultMqListener(FissionGrantResultListener delegate) {
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
