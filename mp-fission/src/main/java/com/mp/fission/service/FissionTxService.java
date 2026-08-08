package com.mp.fission.service;

import com.mp.common.enums.OpStatus;
import com.mp.common.enums.RelationStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.config.FissionTx;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.repository.FissionOpRecordMapper;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.repository.FissionTaskMapper;
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
    private final FissionTaskMapper taskMapper;

    public FissionTxService(
            FissionGroupMapper groupMapper,
            FissionRelationMapper relationMapper,
            FissionOpRecordMapper opRecordMapper,
            FissionTaskMapper taskMapper) {
        this.groupMapper = groupMapper;
        this.relationMapper = relationMapper;
        this.opRecordMapper = opRecordMapper;
        this.taskMapper = taskMapper;
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
     * 确权前置：落 {@code PROCESSING} 操作记录 + 置发奖在途豁免 + 落查单任务，<b>同事务</b>。
     *
     * <p><b>任务前置是收敛率 100% 的基础</b>（技术方案 §5.1 ④）：查单任务与 {@code op=PROCESSING} 同事务落库，而非等发奖成功后再建。否则 RPC
     * 发出后宕机，记录停在 {@code PROCESSING}、 无任务，对账若只扫 {@code UNKNOWN} 就永久悬挂 —— 徒弟究竟收没收到奖，无人查证。
     *
     * <p>{@code granting_until} 同时置上：发奖在途期间过期治理须跳过这条关系（BR-F-26），否则一边 在发奖一边被推到 {@code EXPIRED}。
     *
     * @return {@code false} 表示幂等命中（该 {@code outFlowNo} 已受理过）
     */
    @FissionTx
    public boolean prepareDone(
            String relationId,
            String groupId,
            String activityId,
            String followerId,
            String outBizNo,
            String outFlowNo,
            String followerGrantNo,
            long grantingWindowSeconds) {
        try {
            opRecordMapper.insert(
                    BizNoGenerator.fissionOpNo(),
                    outFlowNo,
                    outBizNo,
                    activityId,
                    followerId,
                    "FOLLOWER_DONE",
                    "",
                    OpStatus.PROCESSING.name(),
                    null);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同 outFlowNo 已受理：幂等命中，不重复发起
            return false;
        }

        relationMapper.markGranting(relationId, grantingWindowSeconds);
        taskMapper.enqueue(
                BizNoGenerator.fissionTaskNo(),
                relationId,
                TaskType.QUERY_GRANT.name(),
                followerGrantNo,
                0,
                "{}");
        return true;
    }

    /**
     * 确权后置，<b>四写同一本地事务</b>（技术方案 §5.1 ⑥、BR-F-20）：
     *
     * <ol>
     *   <li>{@code op_record} 置 {@code SUCCESS}
     *   <li>关系 {@code JOINED → DONE}，同时释放 {@code active_flag}、清空 {@code granting_until}
     *   <li>查单任务置 {@code DONE}
     *   <li>落 {@code SPONSOR_REWARD} 任务
     * </ol>
     *
     * <p><b>分两个事务即漏发师傅奖</b>：若「op=SUCCESS」与「关系 DONE + 师傅返奖任务」分属两个 事务，中间宕机将导致徒弟已发奖、而师傅返奖任务根本不存在 ——
     * 师傅奖永久漏发且无重试载体。
     *
     * <p>关系推进用 {@code terminate}（条件更新 {@code WHERE status='JOINED'}）：重复确权时 {@code
     * affected_rows=0}，天然幂等（BR-F-17）。
     *
     * @return {@code false} 表示关系已被推进（重复确权）
     */
    @FissionTx
    public boolean settleDone(
            String relationId,
            String groupId,
            String outBizNo,
            String outFlowNo,
            String followerGrantNo,
            String sponsorFlowNo) {
        int rows =
                relationMapper.terminate(
                        relationId, RelationStatus.JOINED.name(), RelationStatus.DONE.name());
        if (rows == 0) {
            return false;
        }

        opRecordMapper.finish(outFlowNo, OpStatus.SUCCESS.name(), RetStatus.SUCCESS.name());
        taskMapper.markDoneByBizAndOp(relationId, TaskType.QUERY_GRANT.name(), followerGrantNo);

        // 师傅返奖走本地消息表异步：主链路只保证徒弟发奖同步完成，避免一次请求串两次外部
        // 发奖拉长 RT。biz_no 取 relationId 而非 groupId —— 后者会让一师傅仅一条任务、漏发
        taskMapper.enqueue(
                BizNoGenerator.fissionTaskNo(),
                relationId,
                TaskType.SPONSOR_REWARD.name(),
                sponsorFlowNo,
                0,
                "{}");

        // 轮次进度 +1（BR-F-18）。SQL 自增而非读出加一回写，并发下两个徒弟同时完成才不会只记一个
        groupMapper.incrementProgress(groupId);
        return true;
    }

    /**
     * 查单收敛为成功时的四写，参数由 {@code followerGrantNo} 反推。
     *
     * <p>查单任务手上只有 {@code (relationId, followerGrantNo)}，而四写还需要 {@code outFlowNo} 与 {@code
     * sponsorFlowNo}。三者同源：{@code followerGrantNo = outFlowNo + "_FL"}， 故去掉后缀即得 {@code
     * outFlowNo}，再派生出 {@code sponsorFlowNo}。
     *
     * <p><b>反推而非把它们存进任务 payload</b>：payload 里存一份就多一处可能与键规则漂移的副本， 而后缀规则是 {@link IdempotentKeys}
     * 里唯一的事实来源。
     *
     * <p><b>本方法内调 {@link #settleDone} 是同类调用，被调方的 {@code @FissionTx} 不生效</b>（V1 缺陷
     * ①：注解不经代理、不报错）。此处安全的原因是本方法自身带 {@code @FissionTx}，四写落在 <b>本方法的</b>事务里 —— 而不是因为 {@code
     * settleDone} 上有注解。把注解从本方法上去掉，四写会 各自自动提交且不报任何错。
     *
     * @return {@code false} 表示关系已被推进（同步链路先收敛了）
     */
    @FissionTx
    public boolean settleDoneFromQuery(String relationId, String followerGrantNo) {
        String outFlowNo = IdempotentKeys.outFlowNoOfFollowerGrant(followerGrantNo);
        FissionRelation relation = relationMapper.selectByRelationId(relationId);
        if (relation == null) {
            return false;
        }
        return settleDone(
                relationId,
                relation.getGroupId(),
                relation.getOutBizNo(),
                outFlowNo,
                followerGrantNo,
                IdempotentKeys.sponsorFlowNo(outFlowNo));
    }

    /** 查单收敛为确定失败：同 {@link #markDoneFailed}，参数由 {@code followerGrantNo} 反推。 */
    @FissionTx
    public void markDoneFailedByGrantNo(String relationId, String followerGrantNo) {
        markDoneFailed(relationId, IdempotentKeys.outFlowNoOfFollowerGrant(followerGrantNo));
    }

    /** 发奖未收敛：操作记录置未知态，<b>不推进关系</b>，保留查单任务由收敛处置。 */
    @FissionTx
    public void markDoneUnresolved(String outFlowNo, RetStatus downstream) {
        opRecordMapper.finish(outFlowNo, OpStatus.UNKNOWN.name(), downstream.name());
    }

    /**
     * 发奖确定失败：操作记录置失败，清空 {@code granting_until}，<b>关系保持 {@code JOINED}</b>。
     *
     * <p>关系不推进也不终结 —— 可人工或重试重入（技术方案 §5.1 异常分支）。清 {@code granting_until} 是因为发奖已不在途，留着会让过期治理永久跳过这条关系。
     */
    @FissionTx
    public void markDoneFailed(String relationId, String outFlowNo) {
        opRecordMapper.finish(outFlowNo, OpStatus.FAILED.name(), RetStatus.FAIL.name());
        relationMapper.clearGranting(relationId);
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
