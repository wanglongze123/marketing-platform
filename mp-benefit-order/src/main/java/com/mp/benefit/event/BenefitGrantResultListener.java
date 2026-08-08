package com.mp.benefit.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.BenefitFulfillmentRecordMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.event.RewardGrantResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 权益侧的发奖结果事件消费。V3 PR-9。
 *
 * <p><b>事件负责加速收敛，查单负责保证收敛</b>（技术方案 §6.7）。这条分工决定了本类的每一处设计：
 *
 * <ul>
 *   <li><b>不删 {@code QUERY_GRANT} 任务的兜底</b>——本类只是让收敛更早发生。事件一丢，查单照常把
 *       它收敛掉，只是慢一个退避周期。反过来若因为「有了事件」就去掉查单，事件丢失即永久悬挂
 *   <li><b>处理失败只记日志，不抛</b>——异常会让发布方（{@code reward} 的回调链路）连带失败，而那条 链路已经把状态落库了。且 V3
 *       是进程内同步投递，抛出会直接打到供应方的 ACK 上
 *   <li><b>复用 {@code settleGrant}，不另写一段收敛逻辑</b>——它与查单走同一个事务方法。两条路径若 各写一份，「主单何时能置终态」这个判断要证明两遍
 * </ul>
 *
 * <p>幂等由 {@code settleGrant} 内部的条件更新承担：明细的 {@code settleByGrantOpNo} 限定「未终结」，
 * 主单的推进限定前置态。<b>重复消费同一条事件不产生第二次推进</b>，这正是「至少一次投递」下消费侧 必须自带的那道闸。
 */
@Component
public class BenefitGrantResultListener {

    private static final Logger log = LoggerFactory.getLogger(BenefitGrantResultListener.class);

    private final OrderTxService tx;
    private final BenefitFulfillmentRecordMapper fulfillmentMapper;
    private final PlayBizRecordMapper bizRecordMapper;

    public BenefitGrantResultListener(
            OrderTxService tx,
            BenefitFulfillmentRecordMapper fulfillmentMapper,
            PlayBizRecordMapper bizRecordMapper) {
        this.tx = tx;
        this.fulfillmentMapper = fulfillmentMapper;
        this.bizRecordMapper = bizRecordMapper;
    }

    /**
     * 消费发奖结果。
     *
     * <p><b>按 {@code opNo} 反查主单，事件里没有 {@code bizNo}</b>：事件体只携带幂等键与结果，不携带玩法层 的业务概念 ——
     * 带上就等于让公共能力层知道「订单」是什么。查不到即这条事件不属于权益侧（裂变 的发奖也走同一个 {@code reward}），静默跳过。
     */
    @EventListener
    public void onGrantResult(RewardGrantResultEvent event) {
        String opNo = event.opNo();
        try {
            String bizNo = fulfillmentMapper.selectBizNoByGrantOpNo(opNo);
            if (bizNo == null) {
                // 不是本玩法的发奖。两个玩法共用一个 reward，事件是广播的
                log.debug("grant result event not for benefit side, skip, opNo={}", opNo);
                return;
            }

            RetStatus result = event.result();
            if (result != RetStatus.SUCCESS && result != RetStatus.FAIL) {
                // 中间态不推进。发布侧已经挡过一道，此处再挡是因为消费侧不该依赖
                // 发布侧的校验——V4 换成 MQ 后，事件可能来自另一个版本的发布方
                log.info("grant result event carries non-terminal {}, skip, opNo={}", result, opNo);
                return;
            }

            // 主单已终态则无事可做。这一层判断不是幂等的承重点（settleGrant 内部的条件更新才是），
            // 只是省掉一次无谓的事务
            PlayBizRecord order =
                    bizRecordMapper.selectOne(
                            Wrappers.<PlayBizRecord>lambdaQuery()
                                    .eq(PlayBizRecord::getPlayBizRecordNo, bizNo));
            if (order == null) {
                log.warn("grant result event found no order, opNo={}, bizNo={}", opNo, bizNo);
                return;
            }
            GrantStatus current = GrantStatus.valueOf(order.getGrantStatus());
            if (current == GrantStatus.GRANT_SUCCESS || current == GrantStatus.GRANT_FAILED) {
                log.debug("order already settled, skip event, bizNo={}, grant={}", bizNo, current);
                return;
            }

            tx.settleGrant(bizNo, opNo, result, event.providerOrderNo());
            log.info(
                    "grant result event applied, bizNo={}, opNo={}, result={}",
                    bizNo,
                    opNo,
                    result);
        } catch (Exception e) {
            // 吞掉：事件只是加速手段，处理失败由 QUERY_GRANT 兜底收敛。
            // 抛出会让发布方的回调处理连带失败——而它的状态已经落库了
            log.warn("handle grant result event failed, fall back to query, opNo={}", opNo, e);
        }
    }
}
