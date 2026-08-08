package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.activity.dto.CreateActivityReq;
import com.mp.api.activity.dto.PublishActivityResp;
import com.mp.api.activity.service.ActivityService;
import com.mp.common.enums.ActivityStatus;
import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 活动发布链路，对应《分阶段方案》§6.5 退出标准 21、22。
 *
 * <p><b>「不生成版本」必须与「拒绝了」同时断言</b>：先写版本再校验同样会抛出预期的错误码，只断言 异常则那种实现照常全绿 ——
 * 而它已经在版本表里留了一行从未生效过的快照，下一次发布会跳号。 这与 V2 第 16 条「均不建单」是同一条判据。
 */
class ActivityPublishIT extends AbstractMySqlIT {

    @Autowired private ActivityService activityService;

    /** 完整配置：六项校验全过。 */
    private static CreateActivityReq validReq(String tag) {
        CreateActivityReq req = new CreateActivityReq();
        req.setClientReqNo("REQ_" + tag);
        req.setName("测试活动_" + tag);
        req.setPlayType("BENEFIT_SELL");
        req.setScene("SCENE_" + tag);
        req.setStartTime("2026-01-01 00:00:00.000");
        req.setEndTime("2026-06-30 23:59:59.999");
        req.setPlayConfig("{\"branch\":\"default\"}");
        req.setRewardConfig("{\"items\":[{\"id\":\"R1\"}]}");
        req.setOperator("tester");
        return req;
    }

    /** 建活动为 DRAFT，版本号 0 —— 草稿尚未产生任何版本。 */
    @Test
    void createActivityStartsAsDraftWithoutVersion() {
        String activityId = activityService.createActivity(validReq("draft"));

        assertThat(activityService.queryActivityConf(activityId).getStatus())
                .isEqualTo(ActivityStatus.DRAFT.name());
        assertThat(activityService.queryActivityConf(activityId).getCurVersion())
                .as("草稿未发布，不应有版本")
                .isZero();
        assertThat(versionCount(activityId)).isZero();
        assertThat(opCount(activityId, "CREATE_ACTIVITY")).isEqualTo(1);
    }

    /** 同 clientReqNo 重复提交返回原活动，不新建。 */
    @Test
    void createActivityIsIdempotentOnClientReqNo() {
        CreateActivityReq req = validReq("idem");
        String first = activityService.createActivity(req);
        String second = activityService.createActivity(req);

        assertThat(second).isEqualTo(first);
        assertThat(
                        count(
                                activityJdbc,
                                "SELECT COUNT(*) FROM marketing_activity WHERE scene = ?",
                                req.getScene()))
                .as("幂等命中不应新建活动")
                .isEqualTo(1);
    }

    /** 标准 21：发布成功则生成不可变版本，状态推进到 SCHEDULED。 */
    @Test
    void publishGeneratesImmutableVersionAndAdvancesStatus() {
        String activityId = activityService.createActivity(validReq("pub"));

        PublishActivityResp resp = activityService.publishActivity(activityId, "tester");

        assertThat(resp.getVersion()).isEqualTo(1);
        assertThat(resp.getStatus()).isEqualTo(ActivityStatus.SCHEDULED.name());
        assertThat(activityService.queryActivityConf(activityId).getCurVersion()).isEqualTo(1);
        assertThat(versionCount(activityId)).isEqualTo(1);
        assertThat(opCount(activityId, "PUBLISH_ACTIVITY")).isEqualTo(1);

        // 快照内容与草稿一致 —— 发布快照的是提交的配置，不是别的字段
        assertThat(
                        str(
                                activityJdbc,
                                "SELECT reward_config FROM activity_config_version"
                                        + " WHERE activity_id = ? AND version = 1",
                                activityId))
                .contains("R1");
    }

    /**
     * 标准 21：六项校验任一不过则 {@code 4101}，且<b>不生成版本、不改状态</b>。
     *
     * <p>逐项构造不合格配置。每一项都单独断言「版本表无新行」—— 先写版本再校验的实现会在这里 留下孤儿快照，而只断言错误码时它照常全绿。
     */
    @Test
    void publishCheckFailureLeavesNoVersionAndNoStatusChange() {
        // ① 有效期非法：结束早于开始
        assertPublishRejected(
                r -> {
                    r.setStartTime("2026-06-30 00:00:00.000");
                    r.setEndTime("2026-01-01 00:00:00.000");
                },
                "period");

        // ② 奖励配置为空
        assertPublishRejected(r -> r.setRewardConfig("{}"), "reward");

        // ③ 玩法配置为空
        assertPublishRejected(r -> r.setPlayConfig("{}"), "play");

        // ④ 玩法类型非法
        assertPublishRejected(r -> r.setPlayType("UNKNOWN_TYPE"), "playType");

        // ⑤ 城市范围配成空数组：与「留空表示不限」语义相反，等于谁都不匹配
        assertPublishRejected(r -> r.setCityScope("[]"), "emptyCity");

        // ⑥ 有效期超上限
        assertPublishRejected(
                r -> {
                    r.setStartTime("2026-01-01 00:00:00.000");
                    r.setEndTime("2028-01-01 00:00:00.000");
                },
                "tooLong");
    }

    /**
     * JSON 语法由建单路径把关，落 {@code 4001}。
     *
     * <p>这几列在库里是 {@code JSON} 类型，非法值会被 MySQL 拒成数据完整性异常 —— 那是 5xxx 形态的
     * 系统异常，而「运营填错了格式」是确定的入参错误。<b>发布校验里放语法检查是一条永不触发的分支</b>： 非法 JSON 根本活不到发布那一步。
     */
    @Test
    void malformedJsonIsRejectedAtCreateWithParamError() {
        CreateActivityReq req = validReq("badjson");
        req.setCrowdRule("不是 JSON");

        assertThatThrownBy(() -> activityService.createActivity(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("格式错误是入参问题，不是系统异常")
                .isEqualTo(ErrorCode.INVALID_PARAM);

        assertThat(
                        count(
                                activityJdbc,
                                "SELECT COUNT(*) FROM marketing_activity WHERE scene = ?",
                                req.getScene()))
                .as("校验不过不应建活动")
                .isZero();
    }

    /** 校验不过的活动改好配置后可正常发布 —— 否则「一律拒绝」就成了「从此发不出去」。 */
    @Test
    void activityCanBePublishedAfterFixingConfig() {
        CreateActivityReq req = validReq("fixable");
        req.setRewardConfig("{}");
        String activityId = activityService.createActivity(req);

        assertThatThrownBy(() -> activityService.publishActivity(activityId, "tester"))
                .isInstanceOf(BizException.class);

        // 补上奖励配置
        activityJdbc.update(
                "UPDATE marketing_activity SET reward_config = ? WHERE activity_id = ?",
                "{\"items\":[{\"id\":\"R9\"}]}",
                activityId);

        PublishActivityResp resp = activityService.publishActivity(activityId, "tester");
        assertThat(resp.getVersion()).isEqualTo(1);
        assertThat(versionCount(activityId)).isEqualTo(1);
    }

    /** 已发布的活动不可再次发布 —— 第六项校验拦住 SCHEDULED。 */
    @Test
    void publishTwiceIsRejectedBySecondCheck() {
        String activityId = activityService.createActivity(validReq("twice"));
        activityService.publishActivity(activityId, "tester");

        assertThatThrownBy(() -> activityService.publishActivity(activityId, "tester"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PUBLISH_CHECK_FAILED);

        assertThat(versionCount(activityId)).as("重复发布不应产生第二个版本").isEqualTo(1);
    }

    /** 标准 22：配置版本快照冻结 —— 改了草稿配置，已发布的版本内容不变。 */
    @Test
    void publishedSnapshotIsFrozenAgainstLaterConfigEdits() {
        String activityId = activityService.createActivity(validReq("frozen"));
        activityService.publishActivity(activityId, "tester");

        String before =
                str(
                        activityJdbc,
                        "SELECT reward_config FROM activity_config_version"
                                + " WHERE activity_id = ? AND version = 1",
                        activityId);

        // 运营改草稿配置
        activityJdbc.update(
                "UPDATE marketing_activity SET reward_config = ? WHERE activity_id = ?",
                "{\"items\":[{\"id\":\"CHANGED\"}]}",
                activityId);

        String after =
                str(
                        activityJdbc,
                        "SELECT reward_config FROM activity_config_version"
                                + " WHERE activity_id = ? AND version = 1",
                        activityId);

        assertThat(after).as("已发布的版本不可变，改草稿不应影响它").isEqualTo(before);
        assertThat(after).doesNotContain("CHANGED");
    }

    /** V1 的 seed 活动 cur_version=1，V3090 补的快照行必须存在，否则存量单据指向不存在的版本。 */
    @Test
    void seedActivityHasMatchingConfigVersionRow() {
        assertThat(versionCount("ACT_DEMO_001"))
                .as("V1 存量活动的 config_version=1 须有对应快照")
                .isEqualTo(1);
    }

    // ---- 辅助 ----

    private void assertPublishRejected(
            java.util.function.Consumer<CreateActivityReq> breaker, String tag) {
        CreateActivityReq req = validReq(tag);
        breaker.accept(req);
        String activityId = activityService.createActivity(req);

        assertThatThrownBy(() -> activityService.publishActivity(activityId, "tester"))
                .as("%s 应被发布校验拒绝", tag)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PUBLISH_CHECK_FAILED);

        assertThat(versionCount(activityId)).as("%s 校验不过不应生成版本", tag).isZero();
        assertThat(
                        str(
                                activityJdbc,
                                "SELECT status FROM marketing_activity WHERE activity_id = ?",
                                activityId))
                .as("%s 校验不过不应改状态", tag)
                .isEqualTo(ActivityStatus.DRAFT.name());
        assertThat(opCount(activityId, "PUBLISH_ACTIVITY")).as("%s 校验不过不应留发布记录", tag).isZero();
    }

    private int versionCount(String activityId) {
        return count(
                activityJdbc,
                "SELECT COUNT(*) FROM activity_config_version WHERE activity_id = ?",
                activityId);
    }

    private int opCount(String activityId, String opType) {
        return count(
                activityJdbc,
                "SELECT COUNT(*) FROM activity_op_record WHERE activity_id = ? AND op_type = ?",
                activityId,
                opType);
    }
}
