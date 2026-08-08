package com.mp.fission.service;

import com.mp.common.enums.OpStatus;
import com.mp.common.enums.RelationStatus;
import com.mp.common.util.BizNoGenerator;
import com.mp.fission.config.FissionTx;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.repository.FissionOpRecordMapper;
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
    private final FissionOpRecordMapper opRecordMapper;

    public FissionTxService(
            FissionGroupMapper groupMapper,
            FissionRelationMapper relationMapper,
            FissionOpRecordMapper opRecordMapper) {
        this.groupMapper = groupMapper;
        this.relationMapper = relationMapper;
        this.opRecordMapper = opRecordMapper;
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
     * 分享：建一条 {@code INVITED} 关系。
     *
     * <p>撞 {@code uk_group_follower_active} 抛出，由调用方按「已邀请过」处置（BR-F-11）—— 不在此
     * catch：吞掉的话调用方无从区分「新建了」与「早就有了」，而端上要靠这个区分标记头像。
     */
    @FissionTx
    public void createInvitedRelation(
            String groupId,
            String activityId,
            String sponsorId,
            String followerId,
            String shareMethod,
            String expireTime) {
        relationMapper.insertActive(
                BizNoGenerator.fissionRelationNo(),
                groupId,
                activityId,
                sponsorId,
                followerId,
                "",
                RelationStatus.INVITED.name(),
                shareMethod,
                expireTime);
    }

    /**
     * 徒弟加入：推进或直建关系 + 落操作记录，<b>同事务</b>。
     *
     * <p>分成两个事务会让「关系已 {@code JOINED} 但没有操作记录」成为可能 —— 而后续的完成操作要 靠操作记录判幂等，缺了它同一次加入可以被重复受理。
     *
     * <p>已有 {@code INVITED}/{@code CONNECTED} 时走条件更新回填 {@code outBizNo}；无关系时直建 {@code
     * JOINED}（二维码/口令分享的徒弟没有事先建立的邀请）。
     *
     * @return 关系号；{@code null} 表示条件更新未命中（并发已推进）
     */
    @FissionTx
    public String joinRelation(
            String groupId,
            String activityId,
            String sponsorId,
            String followerId,
            String outBizNo,
            String outFlowNo,
            String expireTime) {
        FissionRelation existing = relationMapper.selectActive(groupId, followerId);

        String relationId;
        if (existing == null) {
            // 直建 JOINED。撞 uk_group_follower_active 由调用方按并发处置
            relationId = BizNoGenerator.fissionRelationNo();
            relationMapper.insertActive(
                    relationId,
                    groupId,
                    activityId,
                    sponsorId,
                    followerId,
                    outBizNo,
                    RelationStatus.JOINED.name(),
                    null,
                    expireTime);
        } else {
            relationId = existing.getRelationId();
            RelationStatus from = RelationStatus.valueOf(existing.getStatus());
            if (from == RelationStatus.JOINED) {
                // 已加入：幂等命中，不重复推进也不报错
                return relationId;
            }
            int rows =
                    relationMapper.fillOutBizNoAndAdvance(
                            groupId,
                            followerId,
                            outBizNo,
                            from.name(),
                            RelationStatus.JOINED.name());
            if (rows == 0) {
                // 条件更新未命中：并发已推进，交由调用方回查
                return null;
            }
        }

        opRecordMapper.insert(
                BizNoGenerator.fissionOpNo(),
                outFlowNo,
                outBizNo,
                activityId,
                followerId,
                "FOLLOWER_JOIN",
                "",
                OpStatus.SUCCESS.name(),
                null);
        return relationId;
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
