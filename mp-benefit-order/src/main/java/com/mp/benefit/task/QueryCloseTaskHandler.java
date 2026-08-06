package com.mp.benefit.task;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.springframework.stereotype.Component;

/**
 * {@code QUERY_CLOSE} 任务：{@code CLOSING} 的收敛通路。
 *
 * <p>{@code CLOSING} 是「关单结果未定」的中间态，它<b>必须有出口</b> —— 技术方案 §6.4 的通用约束：
 * 「任何中间态都必须同时具备准入谓词的入边与对账的一行，否则它就是个只进不出的黑洞」。本处理器 就是那条出口，收敛到 {@code CLOSED}（确认未支付）或 {@code
 * PAY_SUCCESS}（确认已支付）。
 *
 * <p>停在 {@code CLOSING} 的代价是双向的：库存与限购额度被冻结（不敢释放，因为钱可能已收），而若 实际已支付则还多一笔「已收款未履约」。故本任务是查单类，阈值 10
 * 次，按长退避约覆盖 1 小时。
 */
@Component
public class QueryCloseTaskHandler implements TaskHandler {

    private final BenefitOrderService benefitOrderService;

    public QueryCloseTaskHandler(BenefitOrderService benefitOrderService) {
        this.benefitOrderService = benefitOrderService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.QUERY_CLOSE;
    }

    @Override
    public RetStatus handle(BenefitTask task) {
        return benefitOrderService.reconcileClose(task.getBizNo());
    }
}
