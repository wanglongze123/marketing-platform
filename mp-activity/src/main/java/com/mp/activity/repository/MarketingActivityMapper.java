package com.mp.activity.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.activity.entity.MarketingActivity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MarketingActivityMapper extends BaseMapper<MarketingActivity> {

    /**
     * 查活动，并<b>由数据库判定当前是否落在有效期内</b>。
     *
     * <p>时间窗口的两端（{@code start_time} / {@code end_time}）存在库里，比较也须用库的时钟。 应用侧取 {@code
     * LocalDateTime.now()} 是拿 JVM 时钟与库中数据比较：单机上时区配错即整体偏移，
     * 活动整体提前或延后开放；多实例下则是实例间时钟漂移，同一活动在不同实例上可用性不一致。
     *
     * <p>与 {@code benefit_task} 的 {@code next_time <= NOW(3)} 同一条约束：判据两端必须出自同一个时钟。
     *
     * <p>{@code available} 由 SQL 算出而非返回两个时间戳让调用方比较 —— 后者等于把同一个判断散到 每个调用点，其中任何一处忘了用库时钟就重新引入本缺陷。
     */
    @Select(
            "SELECT a.*, (a.status = 'ONLINE' AND NOW(3) >= a.start_time"
                    + " AND NOW(3) < a.end_time) AS available"
                    + " FROM marketing_activity a"
                    + " WHERE a.activity_id = #{activityId} AND a.deleted = 0")
    ActivityRow selectWithAvailability(@Param("activityId") String activityId);

    /** 按活动号取行，不算可用性。写路径的前置读用它。 */
    @Select("SELECT * FROM marketing_activity WHERE activity_id = #{activityId} AND deleted = 0")
    MarketingActivity selectByActivityId(@Param("activityId") String activityId);

    /**
     * 建活动，状态固定 {@code DRAFT}。
     *
     * <p>时间由调用方传入字符串再由库解析：两端都在库里比较（{@code selectWithAvailability} 用 {@code NOW(3)}），写入也走同一侧，避免 JVM
     * 与库时钟不一致导致活动整体提前或延后开放。
     */
    @Insert(
            "INSERT INTO marketing_activity (activity_id, name, play_type, scene, status,"
                    + " start_time, end_time, city_scope, channel_scope, crowd_rule, risk_rule,"
                    + " play_config, reward_config, cur_version, operator)"
                    + " VALUES (#{activityId}, #{name}, #{playType}, #{scene}, 'DRAFT',"
                    + " #{startTime}, #{endTime}, #{cityScope}, #{channelScope},"
                    + " #{crowdRule}, #{riskRule}, #{playConfig}, #{rewardConfig}, 0,"
                    + " #{operator})")
    int insertDraft(
            @Param("activityId") String activityId,
            @Param("name") String name,
            @Param("playType") String playType,
            @Param("scene") String scene,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("cityScope") String cityScope,
            @Param("channelScope") String channelScope,
            @Param("crowdRule") String crowdRule,
            @Param("riskRule") String riskRule,
            @Param("playConfig") String playConfig,
            @Param("rewardConfig") String rewardConfig,
            @Param("operator") String operator);

    /**
     * 发布：推进版本号与状态，<b>条件更新</b>（《开发规范》§7.1）。
     *
     * <p>{@code WHERE status = #{fromStatus} AND cur_version = #{fromVersion}} 两个谓词缺一不可：
     *
     * <ul>
     *   <li>只判状态：两个并发发布都读到 {@code DRAFT}，各自算出同一个新版本号，后一个覆盖前一个 —— 版本表里两行、主表指向其中一行，另一行成了没人认的孤儿
     *   <li>只判版本：{@code ENDED} 的活动也能被发布，状态机不起作用
     * </ul>
     *
     * <p>{@code affected_rows = 0} 即并发已被另一方推进，调用方须放弃本次发布而非重试 —— 重试会 基于陈旧的版本号再算一次。
     */
    @Update(
            "UPDATE marketing_activity SET cur_version = #{toVersion}, status = #{toStatus},"
                    + " operator = #{operator}"
                    + " WHERE activity_id = #{activityId} AND status = #{fromStatus}"
                    + " AND cur_version = #{fromVersion} AND deleted = 0")
    int advanceVersionAndStatus(
            @Param("activityId") String activityId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("fromVersion") int fromVersion,
            @Param("toVersion") int toVersion,
            @Param("operator") String operator);

    /**
     * 状态变更，条件更新防乱序与并发。
     *
     * <p>合法性由 {@code ActivityStatus.canTransitTo} 在应用侧判定后再落此更新：SQL 只保证「前置状态 是我读到的那个」，判不了「这条边是否合法」——
     * 把流转表写进 SQL 会让每加一个状态都要改 SQL。
     */
    @Update(
            "UPDATE marketing_activity SET status = #{toStatus}, operator = #{operator}"
                    + " WHERE activity_id = #{activityId} AND status = #{fromStatus}"
                    + " AND deleted = 0")
    int advanceStatus(
            @Param("activityId") String activityId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("operator") String operator);

    /** 活动行 + 库侧算出的可用性。 */
    class ActivityRow extends MarketingActivity {
        private boolean available;

        public boolean isAvailable() {
            return available;
        }

        public void setAvailable(boolean available) {
            this.available = available;
        }
    }
}
