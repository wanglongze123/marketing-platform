package com.mp.common.event;

import com.mp.common.enums.RetStatus;

/**
 * 发奖结果事件（技术方案 §4.3、§6.7）。V3 PR-9 引入。
 *
 * <p><b>它的存在理由是架构约束，不是解耦偏好</b>：供应方异步回调打到 {@code reward}（公共能力层）， 而需要知道结果的是玩法层。若 {@code reward} 同步
 * RPC 回调 {@code benefit-order} / {@code fission}， 就是下层调上层，直接违反 §1.1 的单向依赖。事件是唯一不破坏依赖方向的做法。且 {@code
 * reward} 被 两个玩法共用，轮询要每个上游各写一套查单，而事件只需发一次。
 *
 * <p><b>事件负责加速收敛，查单负责保证收敛</b>（§6.7）。这条分工决定了消费侧的形状：{@code opNo}
 * 幂等与查单任务<b>必须并存</b>，不得因为「有了事件就不用查单了」而把 {@code QUERY_GRANT} 去掉 —— 那样事件一丢即永久悬挂，与设计恰好相反。
 *
 * <p><b>事件体只携带幂等键与结果，不携带业务语义</b>：{@code opNo} 是发奖幂等键，消费侧据它反查 自己的业务对象。带上「这是哪个订单/哪条关系」会让 {@code
 * reward} 知道玩法层的概念 —— 依赖方向 在数据结构上又倒回去了。
 *
 * @param opNo 发奖幂等键，消费侧的唯一定位依据
 * @param receiverId 收奖人，供消费侧校验与日志
 * @param result 下游四分类结果。{@code UNKNOWN} / {@code PROCESSING} 不发事件——中间态没有通知的价值
 * @param providerOrderNo 供应方单号，可为空
 * @param notifySeq 供应方通知流水，消费侧幂等的第二维（同一 {@code opNo} 可有多条通知）
 */
public record RewardGrantResultEvent(
        String opNo,
        String receiverId,
        RetStatus result,
        String providerOrderNo,
        String notifySeq) {}
