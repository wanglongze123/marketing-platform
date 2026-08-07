package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.activity.dto.ActivityConfResp;
import com.mp.api.activity.service.ActivityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 活动可用性判定：时间窗口由数据库时钟比较，不由 JVM 时钟。
 *
 * <p>窗口两端存在库里，判据两端须出自同一时钟。应用侧取 {@code LocalDateTime.now()} 与库中数据 比较，单机上时区配错即整体偏移，多实例下则是实例间漂移 ——
 * 同一活动在不同实例上可用性不一致。 这与 {@code benefit_task} 的 {@code next_time <= NOW(3)} 是同一条约束。
 */
class ActivityAvailabilityIT extends AbstractMySqlIT {

    private static final String ACT = "ACT_DEMO_001";

    @Autowired private ActivityService activityService;

    @AfterEach
    void restoreWindow() {
        // seed 的窗口，其余用例依赖它
        activityJdbc.update(
                "UPDATE marketing_activity SET status = 'ONLINE',"
                        + " start_time = '2025-01-01 00:00:00.000',"
                        + " end_time = '2030-12-31 23:59:59.999' WHERE activity_id = ?",
                ACT);
    }

    /** seed 活动在窗口内且已上线，可参与。 */
    @Test
    void activityInsideWindowIsAvailable() {
        assertThat(activityService.queryActivityConf(ACT).isAvailable()).isTrue();
    }

    /**
     * 窗口边界由库时钟判定。
     *
     * <p>把 {@code end_time} 用 {@code NOW(3)} 置为「刚刚过去」—— 两端都取库时钟，故断言与运行环境的 时区、与 JVM
     * 时钟是否偏移都无关。若实现改回应用侧比较，在时区错配的环境下本用例即失败。
     */
    @Test
    void activityIsUnavailableOnceWindowClosedByDatabaseClock() {
        activityJdbc.update(
                "UPDATE marketing_activity SET end_time = DATE_SUB(NOW(3), INTERVAL 1 SECOND)"
                        + " WHERE activity_id = ?",
                ACT);

        assertThat(activityService.queryActivityConf(ACT).isAvailable())
                .as("end_time 已过，活动不可参与")
                .isFalse();
    }

    /** 尚未开始同样不可参与 —— 只判 end_time 会让未到期的活动提前开放。 */
    @Test
    void activityIsUnavailableBeforeWindowOpens() {
        activityJdbc.update(
                "UPDATE marketing_activity SET start_time = DATE_ADD(NOW(3), INTERVAL 1 HOUR)"
                        + " WHERE activity_id = ?",
                ACT);

        assertThat(activityService.queryActivityConf(ACT).isAvailable())
                .as("start_time 未到，活动不可参与")
                .isFalse();
    }

    /** 未上线的活动即便落在窗口内也不可参与：状态与时间是两个独立条件。 */
    @Test
    void offlineActivityIsUnavailableEvenInsideWindow() {
        activityJdbc.update(
                "UPDATE marketing_activity SET status = 'PAUSED' WHERE activity_id = ?", ACT);

        ActivityConfResp resp = activityService.queryActivityConf(ACT);
        assertThat(resp.getStatus()).isEqualTo("PAUSED");
        assertThat(resp.isAvailable()).as("非 ONLINE 状态不可参与").isFalse();
    }

    /** 活动不存在返回 null，不抛异常 —— 调用方据此判「活动不可参与」。 */
    @Test
    void unknownActivityReturnsNull() {
        assertThat(activityService.queryActivityConf("ACT_NOT_EXIST")).isNull();
    }
}
