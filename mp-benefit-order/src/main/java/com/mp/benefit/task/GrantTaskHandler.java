package com.mp.benefit.task;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.springframework.stereotype.Component;

/**
 * {@code GRANT} 任务：驱动履约编排。
 *
 * <p>处理器只做一件事 —— 把任务转成一次 {@code grantBenefit} 调用。履约的幂等、分组、明细写入 都在编排层，与「谁触发的」无关：V1 由支付回调同步触发，V2
 * 由本任务触发，V3 还会由对账补偿 触发，三条路径必须走同一段代码，否则幂等要证明三遍。
 *
 * <p>不 catch 异常：抛出即由调度器按 {@code UNKNOWN} 处置（短退避重试）。在此处 catch 成 {@code FAIL} 会让「RPC
 * 发出后超时」被判为「下游没执行」，而后者是重复发放的起点。
 */
@Component
public class GrantTaskHandler implements TaskHandler {

    private final BenefitOrderService benefitOrderService;

    public GrantTaskHandler(BenefitOrderService benefitOrderService) {
        this.benefitOrderService = benefitOrderService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.GRANT;
    }

    @Override
    public RetStatus handle(BenefitTask task) {
        return benefitOrderService.grantBenefit(task.getBizNo());
    }
}
