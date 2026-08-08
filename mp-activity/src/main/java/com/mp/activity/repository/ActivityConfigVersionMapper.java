package com.mp.activity.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.activity.entity.ActivityConfigVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 配置版本快照访问。只增不改 —— 表本身就是不可变的。 */
@Mapper
public interface ActivityConfigVersionMapper extends BaseMapper<ActivityConfigVersion> {

    /**
     * 写入一个版本快照。
     *
     * <p>不用 {@code INSERT ... ON DUPLICATE KEY UPDATE}：撞 {@code uk_activity_version} 说明同一版本号
     * 被发布了两次，那是并发发布，应当抛出让上层处置，而不是把后来的内容覆盖上去 —— 快照不可变的 前提是它一旦写入就不再变。
     */
    @Insert(
            "INSERT INTO activity_config_version (activity_id, version, play_config, reward_config)"
                    + " VALUES (#{activityId}, #{version}, #{playConfig}, #{rewardConfig})")
    int insertSnapshot(
            @Param("activityId") String activityId,
            @Param("version") int version,
            @Param("playConfig") String playConfig,
            @Param("rewardConfig") String rewardConfig);

    /** 按版本取快照。履约与退款一律读它，不读活动主表的当前配置。 */
    @Select(
            "SELECT * FROM activity_config_version"
                    + " WHERE activity_id = #{activityId} AND version = #{version}")
    ActivityConfigVersion selectByVersion(
            @Param("activityId") String activityId, @Param("version") int version);
}
