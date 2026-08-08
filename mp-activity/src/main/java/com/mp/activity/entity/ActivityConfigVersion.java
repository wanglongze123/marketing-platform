package com.mp.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 活动配置版本快照，一经发布不可变（BR-C-03）。
 *
 * <p>无 {@code updateTime} 字段：这行不允许原地修改，改配置只能发布新版本。
 */
@TableName("activity_config_version")
public class ActivityConfigVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String activityId;

    private Integer version;

    private String playConfig;

    private String rewardConfig;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getPlayConfig() {
        return playConfig;
    }

    public void setPlayConfig(String playConfig) {
        this.playConfig = playConfig;
    }

    public String getRewardConfig() {
        return rewardConfig;
    }

    public void setRewardConfig(String rewardConfig) {
        this.rewardConfig = rewardConfig;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
