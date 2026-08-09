package com.mp.fission.task;

import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.fission.entity.FissionTask;
import com.mp.fission.service.FissionTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code QUERY_GRANT} 任务：徒弟发奖 {@code UNKNOWN} 的收敛通路。
 *
 * <p><b>没有它，「不推进关系、保留查单任务」就只是把问题挂起</b>：任务会落在调度器的「无处理器」 分支里 —— {@code PENDING → DOING → PENDING}
 * 无限循环，{@code retry_count} 因该分支不计数而 永不增长，连死信都进不去。关系永久停在 {@code JOINED}，徒弟究竟收没收到奖无人查证。
 *
 * <p>本类由逐行审阅发现缺失：注入自查验的是既有用例是否有效，验不出<b>缺少哪个用例</b>（V2 §5.9 的同一条教训）。原用例断言查单任务为 {@code PENDING} ——
 * 而「等待收敛」与「无人处理」 都是 {@code PENDING}，两者用同一个断言分不开。
 *
 * <p>与权益侧 {@code QueryGrantTaskHandler} 的差别只在收敛后写什么：那边推进主单与履约明细， 这边推进关系并落师傅返奖任务，即复用 {@code
 * settleDone} 的四写。
 */
@Component
public class FissionQueryGrantTaskHandler implements FissionTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(FissionQueryGrantTaskHandler.class);

    private final RewardService rewardService;
    private final FissionTxService tx;

    public FissionQueryGrantTaskHandler(RewardService rewardService, FissionTxService tx) {
        this.rewardService = rewardService;
        this.tx = tx;
    }

    @Override
    public TaskType taskType() {
        return TaskType.QUERY_GRANT;
    }

    /**
     * 以原 {@code opNo} 查单收敛。
     *
     * <p>{@code op_no} 即 {@code followerGrantNo}，建任务时固化、重试只读不重生成 —— 这是「超时重试 必须复用原键」的落点。
     *
     * <p><b>裂变不做「连续查无满阈值即重发」</b>（权益侧有这一段）：那条规则的前提是「重发一定 安全」，靠的是下游按 {@code opNo}
     * 幂等。裂变这里同样成立，但重发要连带重跑四写事务 —— 而四写里含「落师傅返奖任务」，语义上属于确权后置而非发奖重试。V3 先只做查单收敛， 重发留给对账第 7
     * 项（徒弟已发师傅未返）与人工处置，届时走同一个 {@code settleDone}。
     */
    @Override
    public RetStatus handle(FissionTask task) {
        String opNo = task.getOpNo();
        String relationId = task.getBizNo();

        GrantRewardResp resp = rewardService.reconcileGrant(opNo);
        RetStatus downstream = resp.getRetStatus();

        switch (downstream) {
            case SUCCESS -> {
                // 收敛为成功：走与同步链路相同的四写，关系推进 + 师傅返奖任务落库。
                // 复用 settleDone 而非另写一段：两条路径若各写一份，「四写」的完整性要证明两遍
                boolean settled = tx.settleDoneFromQuery(relationId, opNo);
                log.info(
                        "fission queryGrant converged to SUCCESS, relationId={}, opNo={},"
                                + " settled={}",
                        relationId,
                        opNo,
                        settled);
                return RetStatus.SUCCESS;
            }
            case FAIL -> {
                // 确定失败：关系保持 JOINED 可重入，清空发奖在途豁免
                tx.markDoneFailedByGrantNo(relationId, opNo);
                log.info(
                        "fission queryGrant converged to FAIL, relationId={}, opNo={}",
                        relationId,
                        opNo);
                return RetStatus.SUCCESS;
            }
            default -> {
                // PROCESSING 走长退避、UNKNOWN 走短退避，由调度器按返回值选序列
                log.info(
                        "fission queryGrant unresolved, keep querying, relationId={}, result={}",
                        relationId,
                        downstream);
                return downstream;
            }
        }
    }
}
