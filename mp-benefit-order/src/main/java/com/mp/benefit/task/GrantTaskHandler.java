package com.mp.benefit.task;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code GRANT} 任务：驱动履约编排。
 *
 * <p>处理器只做一件事 —— 把任务转成一次 {@code grantBenefit} 调用。履约的幂等、分组、明细写入 都在编排层，与「谁触发的」无关：V1 由支付回调同步触发，V2
 * 由本任务触发，V3 还会由对账补偿 触发，三条路径必须走同一段代码，否则幂等要证明三遍。
 *
 * <p>不 catch 异常：抛出即由调度器按 {@code UNKNOWN} 处置（短退避重试）。在此处 catch 成 {@code FAIL} 会让「RPC
 * 发出后超时」被判为「下游没执行」，而后者是重复发放的起点。
 *
 * <p><b>结果未定但查单任务已落时，本任务就此结束</b>：{@code grantBenefit} 在任一供应方未收敛时 会与主单状态同事务落 {@code QUERY_GRANT}
 * ——「发起履约」这件事已经做完了，「等到结果」 归查单任务。详见 {@link #handle}。
 */
@Component
public class GrantTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(GrantTaskHandler.class);

    private final BenefitOrderService benefitOrderService;
    private final BenefitTaskMapper taskMapper;

    public GrantTaskHandler(BenefitOrderService benefitOrderService, BenefitTaskMapper taskMapper) {
        this.benefitOrderService = benefitOrderService;
        this.taskMapper = taskMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.GRANT;
    }

    /**
     * 执行履约，并在职责移交给查单任务后就此了结。
     *
     * <p>{@code grantBenefit} 返回 {@code UNKNOWN} / {@code PROCESSING} 时表达的是两件事之一：
     *
     * <ul>
     *   <li><b>已移交</b> —— 未收敛的供应方各自落了一条 {@code QUERY_GRANT}，收敛由它们负责
     *   <li><b>未移交</b> —— 落任务的那个事务未成功，无人接手，本任务必须继续重试
     * </ul>
     *
     * <p>二者在返回值上无法区分，故以「该单是否存在在途的查单任务」为判据。已移交则返回 {@code SUCCESS} 使调度器置 {@code DONE}。<b>这不是把「未知」谎报成
     * 「成功」</b>：任务的终态描述的是任务自身的执行结果，而非下游发放的结果 —— 后者记在 {@code play_biz_record.grant_status} 与 {@code
     * play_op_record} 上，此刻仍是 {@code GRANT_UNKNOWN}，对账与查单都据此工作。
     *
     * <p>不这样分的话，两条任务会各自独立地收敛同一件事而互不知情：{@code GRANT} 的 5 次短退避 先于查单的 10 次跑完，进 {@code DEAD}；此后查单收敛、主单转
     * {@code GRANT_SUCCESS}，而那条 死信留在表里。死信本是「重试至阈值仍未收敛，等人工处置」的入口，被已自行收敛的单填满 就失去了作用 —— 与 PR-8
     * 修正的「已支付订单的关单任务重试至死信」是同一类失效。
     *
     * <p>期间每一轮重试都会重新调用全部供应方（实测 5 次），虽由 {@code opNo} 幂等挡下不构成 资损，但那是白跑的下游调用。
     */
    @Override
    public RetStatus handle(BenefitTask task) {
        String bizNo = task.getBizNo();
        RetStatus result = benefitOrderService.grantBenefit(bizNo);

        if (result != RetStatus.UNKNOWN && result != RetStatus.PROCESSING) {
            return result;
        }

        if (taskMapper.countOpenQueryGrant(bizNo) > 0) {
            log.info(
                    "grant handed off to QUERY_GRANT, task done, bizNo={}, taskNo={},"
                            + " grantResult={}",
                    bizNo,
                    task.getTaskNo(),
                    result);
            return RetStatus.SUCCESS;
        }

        // 无查单任务在途：落任务的事务未成功，无人接手，本任务继续按退避重试
        log.warn(
                "grant unresolved and no open QUERY_GRANT, keep retrying, bizNo={}, taskNo={}",
                bizNo,
                task.getTaskNo());
        return result;
    }
}
