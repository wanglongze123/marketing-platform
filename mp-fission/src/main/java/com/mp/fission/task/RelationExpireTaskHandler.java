package com.mp.fission.task;

import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.fission.entity.FissionTask;
import com.mp.fission.service.RelationExpireService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code RELATION_EXPIRE} 任务：关系与轮次过期治理（FR-F09）。
 *
 * <p><b>它是本仓第一个「周期任务」，与此前全部任务不同类</b>。{@code GRANT} / {@code QUERY_GRANT} / {@code SPONSOR_REWARD}
 * 都由一次业务事件建立、收敛后了结；本任务没有对应的业务事件。
 *
 * <p><b>一次执行 = 扫一遍 = 一条任务的一生</b>：扫完即 {@code SUCCESS}，任务置 {@code DONE}。周期性 由播种器每隔一个周期重新 {@code
 * enqueue} 提供 —— 而 {@code enqueue} 的 upsert 对终态行的处置正是 「复活为 {@code PENDING} 且 {@code retry_count}
 * 归零」，无须任何额外机制（见 {@code FissionExpireSeeder}）。
 *
 * <p><b>另一种写法是返回 {@code PROCESSING} 让任务永不了结</b>，靠长退避序列当作周期。它跑得通， 但 {@code retry_count} 会随每一轮执行累加，达
 * {@link TaskType#getMaxRetry()} 即进 {@code DEAD} —— 而那不是失败，只是「跑了很多轮」。于是死信表被正常运转的治理任务填满，与 V2 PR-8
 * 修正的第 2 项（正常成交的订单每笔贡献一条死信）是同一族失效。退出标准第 24 条要的正是 {@code DONE} 而非 {@code DEAD}。
 *
 * <p>「扫完置 {@code DONE}」也让 {@code TaskType.RELATION_EXPIRE} 的阈值 5 回到本义：连续 5 次<b>扫描
 * 失败</b>才进死信，那确实需要人看。
 *
 * <p><b>治理本身幂等</b>（BR-F-25）：批量语句更新后行自动离开 {@code WHERE} 集合，重复执行只会 {@code
 * affected_rows=0}。故租约易主、任务被接管、同一分片被执行两次，都不产生副作用。
 */
@Component
public class RelationExpireTaskHandler implements FissionTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(RelationExpireTaskHandler.class);

    private final RelationExpireService expireService;

    public RelationExpireTaskHandler(RelationExpireService expireService) {
        this.expireService = expireService;
    }

    @Override
    public TaskType taskType() {
        return TaskType.RELATION_EXPIRE;
    }

    /**
     * 扫一个分片。
     *
     * <p>分片身份从 {@code biz_no} 解析，<b>不读本实例的配置</b>：任务表的领取不分片，任一实例都可能 领到任一分片的任务。执行侧若按自己的配置算区间，领到 1
     * 号分片的实例会去扫 0 号分片的范围 —— 两个分片的差集永远无人扫描（见 {@link ExpireShard}）。
     *
     * <p>扫完返回 {@code SUCCESS}，任务置 {@code DONE}，下一个播种周期由 {@code enqueue} 复活它。
     *
     * <p><b>「本轮触顶、还有剩余」不改变返回值</b>：{@link RelationExpireService} 内部有批次上限，触顶时 本轮提前结束并记
     * warn。那仍是一次成功的扫描 —— 剩余部分由下一轮处理，而不是把本次判为未完成。 判未完成会让退避把下一轮推得更远，恰好与「积压时应更快再扫」相反。
     */
    @Override
    public RetStatus handle(FissionTask task) {
        ExpireShard shard = ExpireShard.parse(task.getBizNo());
        int expired = expireService.sweep(shard);
        log.debug("relation expire round finished, shard={}, expired={}", shard.bizNo(), expired);
        return RetStatus.SUCCESS;
    }
}
