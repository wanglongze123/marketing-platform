package com.mp.benefit.task;

import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.service.RewardService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final BenefitTaskMapper taskMapper;

    public QueryGrantTaskHandler(
            RewardService rewardService, OrderTxService tx, BenefitTaskMapper taskMapper) {
        this.rewardService = rewardService;
        this.tx = tx;
        this.taskMapper = taskMapper;
    }

    /** 从 payload 读连续查无次数，缺失即 0。 */
    private static int currentMissStreak(BenefitTask task) {
        String payload = task.getPayload();
        if (payload == null || !payload.contains("missStreak")) {
            return 0;
        }
        Matcher m = Pattern.compile("\"missStreak\"\\s*:\\s*(\\d+)").matcher(payload);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
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
                // 下游已受理、仍在处理：长退避继续查，不重发 —— 重发对已受理的请求毫无意义。
                // 同时把查无计数归零：「连续」查无才构成「原调用未到达」的证据，中间夹一次
                // PROCESSING 就说明它到达过了
                taskMapper.setMissStreak(task.getId(), 0);
                log.info("queryGrant still PROCESSING, bizNo={}, opNo={}", bizNo, opNo);
                return RetStatus.PROCESSING;
            }
            default -> {
                return onMiss(task, bizNo, opNo);
            }
        }
    }

    /**
     * 查无一次。<b>连续</b>累计到阈值才落重发任务。
     *
     * <p>计数存在任务的 {@code payload} 里，随任务持久化 —— 实例重启或任务被接管都不丢，内存计数器 会在接管时归零使重发永不触发。
     *
     * <p><b>不复用 {@code retry_count}</b>：它由调度器对所有非终态结果自增，{@code PROCESSING} 也算在内， 于是「连续查无 3
     * 次」会退化成「查询 3 次且最后一次查无」，中间夹杂的 {@code PROCESSING} 被一并 计入 —— 而下游回过 {@code PROCESSING}
     * 即表明它已受理，此后重发是对一笔已受理的请求再发一次。
     */
    private RetStatus onMiss(BenefitTask task, String bizNo, String opNo) {
        int misses = currentMissStreak(task) + 1;
        taskMapper.setMissStreak(task.getId(), misses);
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
