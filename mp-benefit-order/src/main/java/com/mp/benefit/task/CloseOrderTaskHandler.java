package com.mp.benefit.task;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.springframework.stereotype.Component;

/**
 * {@code CLOSE_ORDER} 任务：订单超时未支付时关闭。
 *
 * <p>任务在<b>建单事务内</b>落库，{@code next_time = expire_time}（技术方案 §5.2）—— 与订单状态同事务
 * 提交，无「消息没发出去导致订单永不关闭、库存永不释放」的缺口。远期任务不拖慢调度：{@code idx_sched(status, next_time)} 的 range scan 扫到
 * {@code next_time > now()} 即停。
 *
 * <p>不 catch 异常：抛出即由调度器按 {@code UNKNOWN} 处置。在此 catch 成 {@code FAIL} 会让「关单 RPC
 * 超时」被判为「关单失败」而停止重试，订单永远占着库存。
 */
@Component
public class CloseOrderTaskHandler implements TaskHandler {

    private final BenefitOrderService benefitOrderService;

    public CloseOrderTaskHandler(BenefitOrderService benefitOrderService) {
        this.benefitOrderService = benefitOrderService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.CLOSE_ORDER;
    }

    @Override
    public RetStatus handle(BenefitTask task) {
        // opSeq 传空串：超时关单是系统触发，没有外部工单号。
        // 一单至多一次关单，op_seq 恒空是 OpType.CLOSE_ORDER.atMostOnce 的要求
        return benefitOrderService.closeOrder(task.getBizNo(), "");
    }
}
