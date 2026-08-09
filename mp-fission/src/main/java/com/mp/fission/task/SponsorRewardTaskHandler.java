package com.mp.fission.task;

import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
import com.mp.fission.entity.FissionRelation;
import com.mp.fission.entity.FissionTask;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.service.RewardItemFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code SPONSOR_REWARD} 任务：师傅返奖。
 *
 * <p>徒弟确权的四写事务内落库、异步执行（技术方案 §5.1 ⑦）。主链路只保证徒弟发奖同步完成 —— 一次请求串两次外部发奖会把 RT 拉长一倍，而师傅奖最终一致即可（BR-F-21）。
 *
 * <p><b>失败不回滚徒弟已成功的奖励</b>（BR-F-20）：两笔发放各自独立幂等、独立重试。回滚徒弟奖 等于因为师傅没拿到而把已发给徒弟的收回去 —— 对用户即已到手的奖励被收回。
 *
 * <p>任务的 {@code op_no} 即 {@code sponsorFlowNo}，建任务时固化、重试只读不重生成 —— 这是「超时 重试必须复用原键」的落点。
 */
@Component
public class SponsorRewardTaskHandler implements FissionTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(SponsorRewardTaskHandler.class);

    /** 师傅返奖的奖励类型与配置 id。V3 取固定值，运营配置化后改由活动配置快照填充 */
    private static final String SPONSOR_REWARD_TYPE = "COUPON";

    private static final String SPONSOR_REWARD_CONFIG_ID = "FISSION_SPONSOR_REWARD";

    private final RewardService rewardService;
    private final FissionRelationMapper relationMapper;

    public SponsorRewardTaskHandler(
            RewardService rewardService, FissionRelationMapper relationMapper) {
        this.rewardService = rewardService;
        this.relationMapper = relationMapper;
    }

    @Override
    public TaskType taskType() {
        return TaskType.SPONSOR_REWARD;
    }

    /**
     * 给师傅发奖。
     *
     * <p>{@code biz_no} 取 {@code relationId} 而非 {@code groupId}（技术方案 §3.3）：一个组下 N 条关系 各自触发一次返奖，取
     * {@code groupId} 则唯一键让一师傅仅一条任务 —— 后续徒弟的返奖全部漏发。
     *
     * <p>不 catch 异常：抛出即由调度器按 {@code UNKNOWN} 短退避重试。catch 成 {@code FAIL} 会让 「RPC
     * 发出后超时」被判为「下游没执行」，而后者是重复发放的起点。
     */
    @Override
    public RetStatus handle(FissionTask task) {
        String relationId = task.getBizNo();
        FissionRelation relation = relationMapper.selectByRelationId(relationId);
        if (relation == null) {
            // 关系不存在是确定的业务拒绝，重试拿到的还是同一个答案 —— 调度器按 1xxx 判终态
            throw new BizException(ErrorCode.INVALID_PARAM, "关系不存在: " + relationId);
        }

        GrantRewardReq req = new GrantRewardReq();
        req.setPlayType("FISSION");
        req.setActivityId(relation.getActivityId());
        req.setBizOrderNo(relationId);
        // op_no 即建任务时固化的 sponsorFlowNo，重试复用同一把键
        req.setOpNo(task.getOpNo());
        req.setReceiverId(relation.getSponsorId());
        req.setRewardItems(
                RewardItemFactory.of(SPONSOR_REWARD_TYPE, List.of(SPONSOR_REWARD_CONFIG_ID)));

        RetStatus result = rewardService.grantReward(req).getRetStatus();
        log.info(
                "sponsorReward done, relationId={}, sponsorId={}, opNo={}, result={}",
                relationId,
                relation.getSponsorId(),
                task.getOpNo(),
                result);
        return result;
    }
}
