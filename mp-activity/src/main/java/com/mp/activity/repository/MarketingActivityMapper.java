package com.mp.activity.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.activity.entity.MarketingActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
