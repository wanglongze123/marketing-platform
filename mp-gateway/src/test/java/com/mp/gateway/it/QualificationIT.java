package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.activity.dto.QualifyReq;
import com.mp.api.activity.dto.QualifyResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.QualifyReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 资格决策，对应《分阶段方案》§6.5 退出标准 23。
 *
 * <p><b>只读无副作用</b>是这个接口的核心约束：它在预咨询、进场、下单三处被调用，任一处留下副作用 都会让「咨询一下」变成「改了库」。故每条用例都断言三张表行数不变。
 *
 * <p><b>1201 与 5201 必须分开</b>：合并的话，风控依赖挂掉时全部用户会被告知「你不符合条件」—— 业务上误判，排查时也看不出系统故障（BR-C-07）。
 */
class QualificationIT extends AbstractMySqlIT {

    private static final String ACT = "ACT_DEMO_001";

    @Autowired private ActivityService activityService;

    @AfterEach
    void restoreActivity() {
        activityJdbc.update(
                "UPDATE marketing_activity SET status = 'ONLINE',"
                        + " start_time = '2025-01-01 00:00:00.000',"
                        + " end_time = '2030-12-31 23:59:59.999',"
                        + " city_scope = NULL, channel_scope = NULL,"
                        + " crowd_rule = NULL, risk_rule = NULL"
                        + " WHERE activity_id = ?",
                ACT);
    }

    private static QualifyReq req(String userId) {
        QualifyReq r = new QualifyReq();
        r.setUserId(userId);
        r.setActivityId(ACT);
        r.setCity("SH");
        r.setChannel("APP");
        r.setDeviceId("DEV_1");
        r.setClientIp("10.0.0.1");
        return r;
    }

    /** 无限制的活动：通过，且 reasonCode 为 PASS。 */
    @Test
    void unrestrictedActivityPasses() {
        QualifyResp resp = activityService.decideQualification(req("U_ok"));

        assertThat(resp.isPass()).isTrue();
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.PASS.name());
        assertThat(resp.getErrorCode()).as("通过时不应带错误码").isNull();
        assertThat(resp.getActivityId()).isEqualTo(ACT);
    }

    /** 标准 23：只读无副作用 —— 三张表行数不变。 */
    @Test
    void qualificationLeavesNoTrace() {
        int activities = tableCount("marketing_activity");
        int versions = tableCount("activity_config_version");
        int ops = tableCount("activity_op_record");

        activityService.decideQualification(req("U_trace"));
        // 拒绝路径也不能留痕
        activityJdbc.update(
                "UPDATE marketing_activity SET city_scope = ? WHERE activity_id = ?",
                "[\"BJ\"]",
                ACT);
        activityService.decideQualification(req("U_trace2"));

        assertThat(tableCount("marketing_activity")).isEqualTo(activities);
        assertThat(tableCount("activity_config_version")).isEqualTo(versions);
        assertThat(tableCount("activity_op_record")).as("资格决策不得留操作记录").isEqualTo(ops);
    }

    /** 活动不可用（已下线）→ 1201 + ACTIVITY_UNAVAILABLE。 */
    @Test
    void offlineActivityIsRejectedAsBusinessReason() {
        activityJdbc.update(
                "UPDATE marketing_activity SET status = 'PAUSED' WHERE activity_id = ?", ACT);

        QualifyResp resp = activityService.decideQualification(req("U_off"));

        assertThat(resp.isPass()).isFalse();
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.ACTIVITY_UNAVAILABLE.name());
        assertThat(resp.getErrorCode()).isEqualTo(ErrorCode.NOT_QUALIFIED);
    }

    /** 城市不在范围 → CITY_NOT_MATCH。 */
    @Test
    void cityOutOfScopeIsRejected() {
        activityJdbc.update(
                "UPDATE marketing_activity SET city_scope = ? WHERE activity_id = ?",
                "[\"BJ\",\"GZ\"]",
                ACT);

        QualifyResp resp = activityService.decideQualification(req("U_city"));

        assertThat(resp.isPass()).isFalse();
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.CITY_NOT_MATCH.name());
        assertThat(resp.getErrorCode()).isEqualTo(ErrorCode.NOT_QUALIFIED);
    }

    /** 城市在范围内则通过 —— 只测拒绝会让「一律拒绝」的实现也全绿。 */
    @Test
    void cityInScopePasses() {
        activityJdbc.update(
                "UPDATE marketing_activity SET city_scope = ? WHERE activity_id = ?",
                "[\"SH\",\"BJ\"]",
                ACT);

        assertThat(activityService.decideQualification(req("U_city_ok")).isPass()).isTrue();
    }

    /** 渠道不在范围 → CHANNEL_NOT_MATCH。 */
    @Test
    void channelOutOfScopeIsRejected() {
        activityJdbc.update(
                "UPDATE marketing_activity SET channel_scope = ? WHERE activity_id = ?",
                "[\"H5\"]",
                ACT);

        QualifyResp resp = activityService.decideQualification(req("U_ch"));

        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.CHANNEL_NOT_MATCH.name());
    }

    /** 命中风控黑名单 → RISK_REJECTED。 */
    @Test
    void blacklistedUserHitsRisk() {
        activityJdbc.update(
                "UPDATE marketing_activity SET risk_rule = ? WHERE activity_id = ?",
                "{\"blacklist\":[\"U_bad\"]}",
                ACT);

        QualifyResp resp = activityService.decideQualification(req("U_bad"));

        assertThat(resp.isPass()).isFalse();
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.RISK_REJECTED.name());
        assertThat(resp.getErrorCode()).isEqualTo(ErrorCode.NOT_QUALIFIED);

        // 同一条规则下，不在黑名单的用户照常通过
        assertThat(activityService.decideQualification(req("U_good")).isPass()).isTrue();
    }

    /**
     * <b>配了范围但取不到值时判不通过</b>，而非放行。
     *
     * <p>放行等于「配置形同虚设」：运营配了城市限制，而请求不带城市就能绕过去。
     */
    @Test
    void missingValueAgainstConfiguredScopeIsRejected() {
        activityJdbc.update(
                "UPDATE marketing_activity SET city_scope = ? WHERE activity_id = ?",
                "[\"SH\"]",
                ACT);

        QualifyReq r = req("U_nocity");
        r.setCity(null);

        assertThat(activityService.decideQualification(r).isPass())
                .as("配了范围而请求无值，须拒绝而非放行")
                .isFalse();
    }

    /**
     * <b>依赖异常走 5201，与业务拒绝严格分开</b>（BR-C-07）。
     *
     * <p>这一条是本类里最容易漏的：不构造异常路径的话，把 {@code 5201} 改成 {@code 1201} 其余用例 照常全绿 ——
     * 而那正是「风控挂了却告诉全部用户你不符合条件」的实现。
     *
     * <p>构造方式取「风控规则损坏」：JSON 列存了一个合法 JSON 但不是对象（数组），判定逻辑读不懂它。 这是真实的依赖故障形态 ——
     * 数据在、格式对、语义不可用，而非「查不到」。查不到是业务结论 （活动不存在），读不懂是系统故障，二者的错误码分区必须不同。
     *
     * <p><b>风控读不懂时不放行</b>：fail-close。放行等于风控挂掉的那段时间门是开着的。
     */
    @Test
    void dependencyFailureIsSystemErrorNotBusinessRejection() {
        activityJdbc.update(
                "UPDATE marketing_activity SET risk_rule = CAST(? AS JSON) WHERE activity_id = ?",
                "[\"损坏的规则\"]",
                ACT);

        QualifyResp resp = activityService.decideQualification(req("U_ctx"));

        assertThat(resp.getErrorCode())
                .as("依赖异常须归 5201，归 1201 等于把系统故障说成用户不合格")
                .isEqualTo(ErrorCode.QUALIFY_CONTEXT_ERROR);
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.CONTEXT_UNAVAILABLE.name());
        assertThat(resp.isPass()).as("风控读不懂时须 fail-close").isFalse();
    }

    /** 活动不存在 → 业务拒绝，不是系统异常。 */
    @Test
    void unknownActivityIsBusinessRejection() {
        QualifyReq r = req("U_none");
        r.setActivityId("ACT_NOT_EXIST");

        QualifyResp resp = activityService.decideQualification(r);

        assertThat(resp.isPass()).isFalse();
        assertThat(resp.getReasonCode()).isEqualTo(QualifyReason.ACTIVITY_UNAVAILABLE.name());
        assertThat(resp.getErrorCode()).as("查无活动是业务结论，不是系统故障").isEqualTo(ErrorCode.NOT_QUALIFIED);
    }

    private int tableCount(String table) {
        return count(activityJdbc, "SELECT COUNT(*) FROM " + table);
    }
}
