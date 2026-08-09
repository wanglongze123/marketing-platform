package com.mp.benefit.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mp.api.reward.dto.GrantItemResult;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.service.RewardService;
import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
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

    /** 与 {@code BenefitOrderServiceImpl.JSON} 同配置：忽略未知字段，使 payload 加字段不导致解析失败。 */
    private static final ObjectMapper JSON =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final RewardService rewardService;
    private final OrderTxService tx;
    private final BenefitTaskMapper taskMapper;

    public QueryGrantTaskHandler(
            RewardService rewardService, OrderTxService tx, BenefitTaskMapper taskMapper) {
        this.rewardService = rewardService;
        this.tx = tx;
        this.taskMapper = taskMapper;
    }

    /**
     * 从查单结果取供应方单号。
     *
     * <p>一个 {@code opNo} 对应一次供应方调用，其下各明细项共享同一张下游单据，故取首个非空即可。 全空返回 {@code null}，由 {@code
     * settleByGrantOpNo} 的 {@code COALESCE} 保留库中原值。
     */
    private static String providerOrderNo(GrantRewardResp resp) {
        if (resp.getItems() == null) {
            return null;
        }
        return resp.getItems().stream()
                .map(GrantItemResult::getProviderOrderNo)
                .filter(no -> no != null && !no.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * 从 payload 读连续查无次数，缺失即 0。
     *
     * <p><b>读写两侧必须对 payload 用同一种表示</b>：写侧是 {@code JSON_SET}（{@link
     * BenefitTaskMapper#setMissStreak}），列类型也是 {@code JSON}，读侧就不能用正则去抠数字。
     *
     * <p>原先的正则实现今天能跑通，但它的失效是<b>静默</b>的：payload 里再加字段、或 {@code missStreak} 被写成字符串/浮点，正则匹配不上就返回
     * 0，连续查无计数被无声重置， {@link #MISS_THRESHOLD} 永远达不到，重发永不触发 —— 而重发正是 {@code TIMEOUT_BEFORE_COMMIT}
     * 那一类故障唯一的收敛通路。没有异常、没有日志，只有一笔停在 {@code GRANT_UNKNOWN} 的单。
     *
     * <p>解析失败判 0 而非抛出：读不出计数不该让整个查单任务失败 —— 那会把一个计数问题升级成 收敛中断。但<b>要留日志</b>，与「静默返回 0」区别开。
     */
    private static int currentMissStreak(BenefitTask task) {
        String payload = task.getPayload();
        if (payload == null || payload.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = JSON.readTree(payload).path("missStreak");
            // 缺字段是正常的：任务入队时 payload 为 "{}"，首次查无才写入
            if (node.isMissingNode() || node.isNull()) {
                return 0;
            }
            if (!node.canConvertToInt()) {
                log.warn(
                        "missStreak is not an int, treat as 0, taskNo={}, value={}",
                        task.getTaskNo(),
                        node);
                return 0;
            }
            return node.asInt();
        } catch (JsonProcessingException e) {
            log.warn(
                    "payload is not valid JSON, treat missStreak as 0, taskNo={}",
                    task.getTaskNo(),
                    e);
            return 0;
        }
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
                // 收敛成功：推进主单与履约明细，任务完成。
                // 带上查得的供应方单号 —— 首次调用超时未拿到它，此处是唯一的回填时机
                tx.settleGrant(bizNo, opNo, RetStatus.SUCCESS, providerOrderNo(resp));
                log.info("queryGrant converged to SUCCESS, bizNo={}, opNo={}", bizNo, opNo);
                return RetStatus.SUCCESS;
            }
            case FAIL -> {
                // 确定失败无单号可填，传 null 由 COALESCE 保留原值
                tx.settleGrant(bizNo, opNo, RetStatus.FAIL, null);
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
