package com.mp.activity.service;

import com.mp.activity.config.ActivityTx;
import com.mp.activity.repository.ActivityConfigVersionMapper;
import com.mp.activity.repository.ActivityOpRecordMapper;
import com.mp.activity.repository.MarketingActivityMapper;
import com.mp.common.enums.ActivityStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.util.BizNoGenerator;
import org.springframework.stereotype.Service;

/**
 * {@code db_activity} 的事务边界。
 *
 * <p><b>独立 bean，不与编排逻辑同类</b>：{@code @Transactional} 用于同类内部调用时不经代理、不生效、 <b>不报错</b>（V1 缺陷
 * ①）。后果是状态已改而操作记录未落库，且没有任何异常提示。
 *
 * <p>方法一律用 {@link ActivityTx} 而非裸 {@code @Transactional}：四库四个事务管理器，不带限定的 注解按类型注入取
 * {@code @Primary}，事务静默落到别库的管理器上（《分阶段方案》§5.6 ②）。
 */
@Service
public class ActivityTxService {

    private final MarketingActivityMapper activityMapper;
    private final ActivityConfigVersionMapper versionMapper;
    private final ActivityOpRecordMapper opRecordMapper;

    public ActivityTxService(
            MarketingActivityMapper activityMapper,
            ActivityConfigVersionMapper versionMapper,
            ActivityOpRecordMapper opRecordMapper) {
        this.activityMapper = activityMapper;
        this.versionMapper = versionMapper;
        this.opRecordMapper = opRecordMapper;
    }

    /** 建草稿 + 落操作记录，同事务。 */
    @ActivityTx
    public void createDraft(
            String activityId,
            String idempotentKey,
            String name,
            String playType,
            String scene,
            String startTime,
            String endTime,
            String cityScope,
            String channelScope,
            String crowdRule,
            String riskRule,
            String playConfig,
            String rewardConfig,
            String operator) {
        activityMapper.insertDraft(
                activityId,
                name,
                playType,
                scene,
                startTime,
                endTime,
                cityScope,
                channelScope,
                crowdRule,
                riskRule,
                playConfig,
                rewardConfig,
                operator);
        opRecordMapper.insert(
                BizNoGenerator.activityOpNo(),
                idempotentKey,
                activityId,
                "CREATE_ACTIVITY",
                "",
                OpStatus.SUCCESS.name(),
                null,
                ActivityStatus.DRAFT.name(),
                null,
                operator,
                null);
    }

    /**
     * 发布：写版本快照 + 推进主表 + 落操作记录，<b>三写同一事务</b>。
     *
     * <p>分成两个事务会留下「版本已生成但状态没推进」的中间态 —— 下一次发布读到的 {@code cur_version}
     * 是旧值，算出同一个版本号，插入时撞唯一键；而那个已写入的快照从未生效过，也没有任何机制会 回头清理它。
     *
     * <p>条件更新命中 0 行时抛出，由事务整体回滚 —— 快照与操作记录都不留。并发发布只应成功一个。
     *
     * @return 条件更新影响行数，0 表示并发已被另一方推进
     */
    @ActivityTx
    public int publish(
            String activityId,
            int fromVersion,
            int toVersion,
            String playConfig,
            String rewardConfig,
            String idempotentKey,
            String operator) {
        versionMapper.insertSnapshot(activityId, toVersion, playConfig, rewardConfig);

        int rows =
                activityMapper.advanceVersionAndStatus(
                        activityId,
                        ActivityStatus.DRAFT.name(),
                        ActivityStatus.SCHEDULED.name(),
                        fromVersion,
                        toVersion,
                        operator);
        if (rows == 0) {
            // 抛出使快照一并回滚。返回 0 让调用方处置也可以，但那要求调用方记得回滚快照，
            // 而「记得」正是最容易漏的一环
            throw new IllegalStateException("并发发布，条件更新未命中: " + activityId);
        }

        opRecordMapper.insert(
                BizNoGenerator.activityOpNo(),
                idempotentKey,
                activityId,
                "PUBLISH_ACTIVITY",
                String.valueOf(toVersion),
                OpStatus.SUCCESS.name(),
                ActivityStatus.DRAFT.name(),
                ActivityStatus.SCHEDULED.name(),
                toVersion,
                operator,
                null);
        return rows;
    }

    /** 状态变更 + 操作记录，同事务。 */
    @ActivityTx
    public int changeStatus(
            String activityId,
            String fromStatus,
            String toStatus,
            String opSeq,
            String idempotentKey,
            String operator) {
        int rows = activityMapper.advanceStatus(activityId, fromStatus, toStatus, operator);
        if (rows == 0) {
            return 0;
        }
        opRecordMapper.insert(
                BizNoGenerator.activityOpNo(),
                idempotentKey,
                activityId,
                "CHANGE_STATUS",
                opSeq,
                OpStatus.SUCCESS.name(),
                fromStatus,
                toStatus,
                null,
                operator,
                null);
        return rows;
    }
}
