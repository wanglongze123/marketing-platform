package com.mp.benefit.task;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.springframework.stereotype.Component;

/**
 * {@code QUERY_REFUND} 任务：{@code REFUNDING} 的收敛通路。
 *
 * <p><b>查单而非重发，这是「重复退款 = 0」与「该退必退」能同时成立的唯一形态</b>：查单是读操作， 无论问多少次都不会多退一分钱；而重发一笔可能已成功的退款就是重复退款。
 *
 * <p>与发放侧的 {@code QUERY_GRANT} 有一处关键差别：那边在连续查无满阈值后<b>会以原 {@code opNo}
 * 重发</b>（下游按幂等键兜底，重发不产生第二笔）。退款侧<b>不做重发</b> —— 支付方的幂等同样由 {@code refundNo}
 * 保证，但退款的失效代价不对称：多发一笔奖是可回收的，多退一笔钱要走人工追讨。 故退款只查不发，连续查无达阈值即进死信等人工。
 *
 * <p>归查单类（阈值 10）：读操作，重试无副作用，且支付方的退款处理本就可能持续较久。
 */
@Component
public class QueryRefundTaskHandler implements TaskHandler {

    private final BenefitOrderService benefitOrderService;

    public QueryRefundTaskHandler(BenefitOrderService benefitOrderService) {
        this.benefitOrderService = benefitOrderService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.QUERY_REFUND;
    }

    @Override
    public RetStatus handle(BenefitTask task) {
        return benefitOrderService.reconcileRefund(task.getBizNo());
    }
}
