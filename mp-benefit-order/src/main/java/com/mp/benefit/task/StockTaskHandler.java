package com.mp.benefit.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 库存类任务：{@code STOCK_CONSUME} / {@code STOCK_RELEASE} / {@code QUOTA_RELEASE}。
 *
 * <p>三类只差一个动作，故用同一个类按类型实例化三个 bean，而不是抄三遍。抄三遍的代价不是行数 —— 是「加载订单」「订单不存在怎么办」这些逻辑会有三份，改一处漏两处。
 *
 * <p><b>为什么库存变更要经过任务而不在支付回调事务里直接做</b>（技术方案 §7.4）：同一 SKU 的库存 是单行，500 QPS 的支付通知加上 300 QPS
 * 的下单预占全部争抢它，持锁时间还会把事务内其余三写 一起拖长，P99 ≤ 100ms 无法达成。移出后热点行不在同步路径上，最终一致由任务保证。
 *
 * <p><b>每单幂等不由这里承担</b>：由 {@code benefit_task.uk_biz_type_op} 挡在入队处。库存 SQL 的 下界（{@code WHERE locked
 * >= ?}）挡的是「总数被减成负值」，挡不住「某一单重复释放」—— 那两件事 常被当成一件，实际上后者会释放掉别人的预占，可售余量多出一份。
 */
public class StockTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(StockTaskHandler.class);

    private final TaskType taskType;
    private final PlayBizRecordMapper bizRecordMapper;
    private final BiFunction<OrderTxService, PlayBizRecord, RetStatus> action;
    private final OrderTxService tx;

    public StockTaskHandler(
            TaskType taskType,
            PlayBizRecordMapper bizRecordMapper,
            OrderTxService tx,
            BiFunction<OrderTxService, PlayBizRecord, RetStatus> action) {
        this.taskType = taskType;
        this.bizRecordMapper = bizRecordMapper;
        this.tx = tx;
        this.action = action;
    }

    @Override
    public TaskType taskType() {
        return taskType;
    }

    @Override
    public RetStatus handle(com.mp.benefit.entity.BenefitTask task) {
        String bizNo = task.getBizNo();
        PlayBizRecord order =
                bizRecordMapper.selectOne(
                        Wrappers.<PlayBizRecord>lambdaQuery()
                                .eq(PlayBizRecord::getPlayBizRecordNo, bizNo));
        if (order == null) {
            // 主单不存在：任务落库与主单在同一事务，此情形只可能是人工清理过数据。
            // 返回 FAIL 让它进失败分支而非无限重试 —— 单都没了，重试多少次也变不出来
            log.error("stock task found no order, bizNo={}, type={}", bizNo, taskType);
            return RetStatus.FAIL;
        }
        return action.apply(tx, order);
    }
}
