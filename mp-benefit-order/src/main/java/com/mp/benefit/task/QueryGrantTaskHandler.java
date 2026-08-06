package com.mp.benefit.task;

import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.service.RewardService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.service.OrderTxService;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code QUERY_GRANT} 任务：{@code UNKNOWN} 的收敛通路。
 *
 * <p>V2 里 {@code PROCESSING} 与 {@code UNKNOWN} 都只有查单这一条收敛通路 —— 技术方案 §6.6 说的 「等下游通知为主」中的 {@code
 * providerCallback} 属 V3 范围。
 *
 * <p><b>查无不立即重发</b>：{@code queryGrant} 查无返回 {@code UNKNOWN}，单次查无不足以判定「原调用 未到达」—— 可能只是下游提交在途。连续查无满
 * {@link #MISS_THRESHOLD} 次（短退避跑完一轮）才落 {@code GRANT} 任务重发，且必须复用原 {@code opNo}（《分阶段方案》§5.3）。
 *
 * <p>该阈值的误判是安全的：若原调用实际已到达，重发携带同一 {@code opNo}，被下游账本 put-if-absent 挡下并返回原 {@code providerOrderNo} ——
 * 幂等键复用让「重发时机判早了」不构成资损，判晚了只是收敛慢。
 */
@Component
public class QueryGrantTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryGrantTaskHandler.class);

    /** 连续查无达此次数才重发。取 3 = 短退避序列跑完一轮 */
    static final int MISS_THRESHOLD = 3;

    private final RewardService rewardService;
    private final OrderTxService tx;

    public QueryGrantTaskHandler(RewardService rewardService, OrderTxService tx) {
        this.rewardService = rewardService;
        this.tx = tx;
    }

    @Override
    public TaskType taskType() {
        return TaskType.QUERY_GRANT;
    }

    @Override
    public RetStatus handle(BenefitTask task) {
        String opNo = task.getOpNo();
        String bizNo = task.getBizNo();

        GrantRewardResp resp = rewardService.reconcileGrant(opNo);
        RetStatus downstream = resp.getRetStatus();

        switch (downstream) {
            case SUCCESS -> {
                // 收敛成功：推进主单与履约明细，任务完成
                tx.settleGrant(bizNo, opNo, RetStatus.SUCCESS);
                log.info("queryGrant converged to SUCCESS, bizNo={}, opNo={}", bizNo, opNo);
                return RetStatus.SUCCESS;
            }
            case FAIL -> {
                tx.settleGrant(bizNo, opNo, RetStatus.FAIL);
                log.info("queryGrant converged to FAIL, bizNo={}, opNo={}", bizNo, opNo);
                return RetStatus.SUCCESS;
            }
            case PROCESSING -> {
                // 下游已受理、仍在处理：长退避继续查，不重发 —— 重发对已受理的请求毫无意义
                log.info("queryGrant still PROCESSING, bizNo={}, opNo={}", bizNo, opNo);
                return RetStatus.PROCESSING;
            }
            default -> {
                return onMiss(task, bizNo, opNo);
            }
        }
    }

    /**
     * 查无一次。累计到阈值才落重发任务。
     *
     * <p>用任务自身的 {@code retry_count} 计数，不另建计数器：它由 SQL 自增、随任务持久化，实例重启
     * 或任务被接管都不丢。另建内存计数器会在接管时归零，重发因此永不触发。
     */
    private RetStatus onMiss(BenefitTask task, String bizNo, String opNo) {
        int misses = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        if (misses < MISS_THRESHOLD) {
            log.info(
                    "queryGrant found nothing ({}/{}), keep querying, bizNo={}, opNo={}",
                    misses,
                    MISS_THRESHOLD,
                    bizNo,
                    opNo);
            return RetStatus.UNKNOWN;
        }

        // 连续查无满阈值：判定原调用未到达，以原 opNo 重发
        tx.enqueueRegrant(bizNo, opNo);
        log.warn(
                "queryGrant missed {} times, re-dispatch GRANT with original opNo,"
                        + " bizNo={}, opNo={}",
                misses,
                bizNo,
                opNo);
        // 本查单任务到此结束，后续由重发的 GRANT 任务接手
        return RetStatus.SUCCESS;
    }
}
