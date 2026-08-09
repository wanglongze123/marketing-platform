package com.mp.benefit.task;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.springframework.stereotype.Component;

/**
 * {@code REVOKE} 任务：回收结果未定的收敛通路。
 *
 * <p>{@code REVOKING} 是「回收结果未定」的中间态，它<b>必须有出口</b>（技术方案 §6.4 的通用约束）。
 * 停在这个状态的代价是双向的：权益可能已被收走而钱没退（用户既没权益也没钱），也可能权益还在 而平台不敢退款。
 *
 * <p><b>收敛后不自动发起退款</b>：回收与退款是两个独立的决定。自动串起来会让一次收敛顺带把钱退 出去 —— 而此刻可能已有别的处置在进行（人工介入、用户撤销退款申请）。
 *
 * <p><b>归执行类（阈值 5）而非查单类</b>：它每次重试都是一次真实的回收调用，不是只读查询。连续 5 次失败已说明不是瞬时故障 —— 而下游按 {@code revokeNo}
 * 幂等，重试不会二次回收。
 */
@Component
public class RevokeTaskHandler implements TaskHandler {

    private final BenefitOrderService benefitOrderService;

    public RevokeTaskHandler(BenefitOrderService benefitOrderService) {
        this.benefitOrderService = benefitOrderService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.REVOKE;
    }

    @Override
    public RetStatus handle(BenefitTask task) {
        return benefitOrderService.reconcileRevoke(task.getBizNo());
    }
}
