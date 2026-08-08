package com.mp.activity.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 活动主表。V1 由 seed SQL 初始化只读，V3 补齐创建、发布与状态变更。 */
@TableName("marketing_activity")
public class MarketingActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String activityId;
    private String name;
    private String playType;
    private String scene;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** JSON 数组，空表示不限。V3 资格决策据此判定 */
    private String cityScope;

    private String channelScope;

    private String crowdRule;

    private String riskRule;

    /** 草稿态玩法私有配置，发布时快照进版本表 */
    private String playConfig;

    /** 草稿态奖励/价格/退款规则，发布时快照进版本表 */
    private String rewardConfig;

    /** 当前发布配置版本。下单时冻结进主单 */
    private Integer curVersion;

    /** 最近一次创建/发布/状态变更的操作人（BR-C-27） */
    private String operator;

    private Integer deleted;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPlayType() {
        return playType;
    }

    public void setPlayType(String playType) {
        this.playType = playType;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getCityScope() {
        return cityScope;
    }

    public void setCityScope(String cityScope) {
        this.cityScope = cityScope;
    }

    public String getChannelScope() {
        return channelScope;
    }

    public void setChannelScope(String channelScope) {
        this.channelScope = channelScope;
    }

    public String getCrowdRule() {
        return crowdRule;
    }

    public void setCrowdRule(String crowdRule) {
        this.crowdRule = crowdRule;
    }

    public String getRiskRule() {
        return riskRule;
    }

    public void setRiskRule(String riskRule) {
        this.riskRule = riskRule;
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

    public Integer getCurVersion() {
        return curVersion;
    }

    public void setCurVersion(Integer curVersion) {
        this.curVersion = curVersion;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
