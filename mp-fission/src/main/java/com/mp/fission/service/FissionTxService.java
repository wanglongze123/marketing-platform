package com.mp.fission.service;

import com.mp.common.enums.RelationStatus;
import com.mp.fission.config.FissionTx;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.repository.FissionRelationMapper;
import org.springframework.stereotype.Service;

/**
 * {@code db_fission} 的事务边界。
 *
 * <p><b>独立 bean，不与编排逻辑同类</b>：{@code @Transactional} 用于同类内部调用时不经代理、不生效、 <b>不报错</b>（V1 缺陷 ①）。方法一律用
 * {@link FissionTx} 而非裸 {@code @Transactional}。
 *
 * <p>PR-2 只落开轮与关系终结两个边界；双向发奖的四写同事务在 PR-4 补入。
 */
@Service
public class FissionTxService {

    private final FissionGroupMapper groupMapper;
    private final FissionRelationMapper relationMapper;

    public FissionTxService(FissionGroupMapper groupMapper, FissionRelationMapper relationMapper) {
        this.groupMapper = groupMapper;
        this.relationMapper = relationMapper;
    }

    /**
     * 开轮。撞唯一键由调用方按幂等命中处置。
     *
     * <p>单写也走事务方法：PR-4 要在此处追加「同事务落任务」，边界先立好，届时是加语句不是改结构 —— 与 V1 冻结项 5 的理由相同。
     */
    @FissionTx
    public void openGroup(
            String groupId,
            String activityId,
            String sponsorId,
            int roundNo,
            int targetCount,
            int configVersion,
            long ttlSeconds) {
        groupMapper.insertRunning(
                groupId, activityId, sponsorId, roundNo, targetCount, configVersion, ttlSeconds);
    }

    /**
     * 终结关系：置终态并释放 {@code active_flag}。
     *
     * <p>三条终态路径（{@code DONE} / {@code EXPIRED} / {@code CANCEL}）全部经由此方法 —— 散写 {@code UPDATE
     * status=...} 迟早漏掉 {@code active_flag}，而漏掉是静默失败。
     *
     * @return 影响行数，0 表示已被并发终结
     */
    @FissionTx
    public int terminateRelation(String relationId, RelationStatus from, RelationStatus to) {
        if (!from.canTransitTo(to)) {
            throw new IllegalArgumentException("非法关系状态迁移: " + from + " → " + to);
        }
        if (!to.isTerminal()) {
            throw new IllegalArgumentException("terminate 只接受终态，实际 " + to);
        }
        return relationMapper.terminate(relationId, from.name(), to.name());
    }
}
