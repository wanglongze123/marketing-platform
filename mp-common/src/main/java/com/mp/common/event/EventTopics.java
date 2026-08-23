package com.mp.common.event;

/**
 * 事件广播的 topic 与消费组常量。V4 引入。
 *
 * <p><b>放在 {@code mp-common} 而非各自模块</b>：发布方（reward）与两个消费方（benefit-order、fission）必须用同一个 topic
 * 名，各写各的字符串则拼错时不会报错 —— 消息发出去了，没人收到，而收敛退化为 查单后一切照常工作，只是慢。这类失效没有任何现成信号。
 *
 * <p><b>两个消费组，不是一个</b>：RocketMQ 的同一消费组内是负载均衡（一条消息只有一个实例收到）， 不同消费组之间才是广播。benefit 与 fission
 * 需要各自都收到全部发奖结果事件 —— 事件体只带 {@code opNo}，消费侧按它反查自己的业务对象，查不到就跳过。共用一个组会让消息被两个玩法瓜分， 每个玩法只看到一半属于自己的事件。
 */
public final class EventTopics {

    /** 发奖结果。生产方 reward，消费方 benefit-order 与 fission 各成一组。 */
    public static final String GRANT_RESULT = "MP_GRANT_RESULT";

    /** 权益售卖侧的消费组。 */
    public static final String GROUP_BENEFIT = "MP_GRANT_RESULT_BENEFIT";

    /** 裂变侧的消费组。 */
    public static final String GROUP_FISSION = "MP_GRANT_RESULT_FISSION";

    private EventTopics() {}
}
