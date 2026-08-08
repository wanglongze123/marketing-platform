package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.activity.dto.CreateActivityReq;
import com.mp.api.activity.service.ActivityService;
import com.mp.common.enums.ActivityStatus;
import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 活动状态机（PRD §4.1）。
 *
 * <p>流转：{@code DRAFT → SCHEDULED → ONLINE → ENDED}；{@code ONLINE ↔ PAUSED}。
 *
 * <p><b>非法迁移抛错而非静默忽略</b>：静默会让运营以为改成功了，而后台看到的状态与刚才的操作不符， 且没有任何错误可查 —— 这类问题在生产上最难排查，因为它不产生任何异常信号。
 */
class ActivityStatusTransitionIT extends AbstractMySqlIT {

    @Autowired private ActivityService activityService;

    private String newPublishedActivity(String tag) {
        CreateActivityReq req = new CreateActivityReq();
        req.setClientReqNo("REQ_ST_" + tag);
        req.setName("状态机测试_" + tag);
        req.setPlayType("BENEFIT_SELL");
        req.setScene("SCENE_ST_" + tag);
        req.setStartTime("2026-01-01 00:00:00.000");
        req.setEndTime("2026-06-30 23:59:59.999");
        req.setPlayConfig("{\"branch\":\"default\"}");
        req.setRewardConfig("{\"items\":[{\"id\":\"R1\"}]}");
        req.setOperator("tester");

        String activityId = activityService.createActivity(req);
        activityService.publishActivity(activityId, "tester");
        return activityId;
    }

    /** 合法链路：SCHEDULED → ONLINE → PAUSED → ONLINE → ENDED。 */
    @Test
    void legalTransitionsAreAccepted() {
        String activityId = newPublishedActivity("legal");

        activityService.changeActivityStatus(activityId, "ONLINE", "S1", "tester");
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.ONLINE.name());

        activityService.changeActivityStatus(activityId, "PAUSED", "S2", "tester");
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.PAUSED.name());

        activityService.changeActivityStatus(activityId, "ONLINE", "S3", "tester");
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.ONLINE.name());

        activityService.changeActivityStatus(activityId, "ENDED", "S4", "tester");
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.ENDED.name());

        // 每次变更各留一条记录
        assertThat(
                        count(
                                activityJdbc,
                                "SELECT COUNT(*) FROM activity_op_record"
                                        + " WHERE activity_id = ? AND op_type = 'CHANGE_STATUS'",
                                activityId))
                .isEqualTo(4);
    }

    /** 非法迁移拒绝，且状态不变。 */
    @Test
    void illegalTransitionIsRejectedAndLeavesStatusUnchanged() {
        String activityId = newPublishedActivity("illegal");

        // SCHEDULED 不能直接到 ENDED
        assertThatThrownBy(
                        () ->
                                activityService.changeActivityStatus(
                                        activityId, "ENDED", "X1", "tester"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        assertThat(statusOf(activityId)).as("非法迁移不应改状态").isEqualTo(ActivityStatus.SCHEDULED.name());
        assertThat(
                        count(
                                activityJdbc,
                                "SELECT COUNT(*) FROM activity_op_record"
                                        + " WHERE activity_id = ? AND op_type = 'CHANGE_STATUS'",
                                activityId))
                .as("非法迁移不应留痕")
                .isZero();
    }

    /** ENDED 是终态，任何出边都非法。 */
    @Test
    void endedIsTerminal() {
        String activityId = newPublishedActivity("terminal");
        activityService.changeActivityStatus(activityId, "ONLINE", "T1", "tester");
        activityService.changeActivityStatus(activityId, "ENDED", "T2", "tester");

        for (String target : new String[] {"ONLINE", "PAUSED", "SCHEDULED", "DRAFT"}) {
            assertThatThrownBy(
                            () ->
                                    activityService.changeActivityStatus(
                                            activityId, target, "T_" + target, "tester"))
                    .as("ENDED → %s 应被拒绝", target)
                    .isInstanceOf(BizException.class);
        }
        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.ENDED.name());
    }

    /** 目标状态等于当前状态：幂等返回，不报错也不重复留痕。 */
    @Test
    void transitionToSameStatusIsIdempotent() {
        String activityId = newPublishedActivity("same");
        activityService.changeActivityStatus(activityId, "ONLINE", "M1", "tester");

        activityService.changeActivityStatus(activityId, "ONLINE", "M2", "tester");

        assertThat(statusOf(activityId)).isEqualTo(ActivityStatus.ONLINE.name());
        assertThat(
                        count(
                                activityJdbc,
                                "SELECT COUNT(*) FROM activity_op_record"
                                        + " WHERE activity_id = ? AND op_type = 'CHANGE_STATUS'",
                                activityId))
                .as("重复推进到同一状态不应留第二条记录")
                .isEqualTo(1);
    }

    /** 状态取值非法 → 4001，不是 4103 —— 前者是入参错，后者是流转错。 */
    @Test
    void unknownStatusValueIsParamError() {
        String activityId = newPublishedActivity("badvalue");

        assertThatThrownBy(
                        () ->
                                activityService.changeActivityStatus(
                                        activityId, "NOT_A_STATUS", "B1", "tester"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM);
    }

    private String statusOf(String activityId) {
        return str(
                activityJdbc,
                "SELECT status FROM marketing_activity WHERE activity_id = ?",
                activityId);
    }
}
