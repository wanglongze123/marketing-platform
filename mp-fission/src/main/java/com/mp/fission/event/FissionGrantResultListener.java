package com.mp.fission.event;

import com.mp.common.enums.RelationStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.event.RewardGrantResultEvent;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.repository.FissionTaskMapper;
import com.mp.fission.service.FissionTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 裂变侧的发奖结果事件消费。V3 PR-9。
 *
 * <p>与 {@code BenefitGrantResultListener} 同构，理由也相同（技术方案 §6.7 的分工）：事件加速、查单 保证，两者并存；处理失败只记日志由 {@code
 * QUERY_GRANT} 兜底；复用同步链路的事务方法而不另写 一段收敛逻辑。
 *
 * <p><b>两个玩法各写一个监听器，不做成一个带 if 的公共消费者</b>：它们收敛后要写的东西完全不同 ——
 * 权益侧推进主单与履约明细，裂变侧要走「四写」（操作记录、关系终态、查单任务、师傅返奖任务）。 合成一个则那段 if 会随玩法数量增长，而每个分支的事务边界还各不相同。
 *
 * <p><b>事件到达时关系可能已被查单收敛</b>：此时 {@code settleDoneFromQuery} 的条件更新命中 0 行， 返回 {@code
 * false}，不重复落师傅返奖任务。这是幂等的承重点，不是本类的 {@code if}。
 */
@Component
public class FissionGrantResultListener {

    private static final Logger log = LoggerFactory.getLogger(FissionGrantResultListener.class);

    private final FissionTxService tx;
    private final FissionTaskMapper taskMapper;
    private final FissionRelationMapper relationMapper;

    public FissionGrantResultListener(
            FissionTxService tx,
            FissionTaskMapper taskMapper,
            FissionRelationMapper relationMapper) {
        this.tx = tx;
        this.taskMapper = taskMapper;
        this.relationMapper = relationMapper;
    }

    /**
     * 消费发奖结果。
     *
     * <p>按 {@code opNo}（即 {@code followerGrantNo}）反查关系号 —— 查不到即这条事件不属于裂变侧 （权益售卖的发奖走同一个 {@code
     * reward}），静默跳过。
     */
    @EventListener
    public void onGrantResult(RewardGrantResultEvent event) {
        String opNo = event.opNo();
        try {
            String relationId = taskMapper.selectRelationIdByGrantOpNo(opNo);
            if (relationId == null) {
                log.debug("grant result event not for fission side, skip, opNo={}", opNo);
                return;
            }

            RetStatus result = event.result();
            if (result != RetStatus.SUCCESS && result != RetStatus.FAIL) {
                log.info("grant result event carries non-terminal {}, skip, opNo={}", result, opNo);
                return;
            }

            // 关系已终结则无事可做。真正的幂等由 settleDoneFromQuery 的条件更新
            // （WHERE status='JOINED'）承担，此处只省一次事务
            FissionRelation relation = relationMapper.selectByRelationId(relationId);
            if (relation == null) {
                log.warn("grant result event found no relation, opNo={}, id={}", opNo, relationId);
                return;
            }
            if (!RelationStatus.JOINED.name().equals(relation.getStatus())) {
                log.debug(
                        "relation not in JOINED, skip event, relationId={}, status={}",
                        relationId,
                        relation.getStatus());
                return;
            }

            if (result == RetStatus.SUCCESS) {
                // 与查单收敛走同一个四写：关系推进 + 师傅返奖任务落库，同事务
                boolean settled = tx.settleDoneFromQuery(relationId, opNo);
                log.info(
                        "grant result event settled relation, relationId={}, opNo={}, settled={}",
                        relationId,
                        opNo,
                        settled);
            } else {
                // 确定失败：关系保持 JOINED 可重入，清空发奖在途豁免
                tx.markDoneFailedByGrantNo(relationId, opNo);
                log.info(
                        "grant result event marked failed, relationId={}, opNo={}",
                        relationId,
                        opNo);
            }
        } catch (Exception e) {
            log.warn("handle grant result event failed, fall back to query, opNo={}", opNo, e);
        }
    }
}
