package com.mp.api.activity.dto;

import java.io.Serializable;

/**
 * 创建活动（FR-C01）。建为 {@code DRAFT}，配置此时可以不完整 —— 完整性由发布校验把关。
 *
 * <p><b>草稿允许不完整是有意的</b>：运营分多次填配置是常态，建单时就要求齐全等于逼人一次填完， 或者逼人先填假值再改。校验点放在发布，那才是「这份配置要开始对用户生效」的时刻。
 */
public class CreateActivityReq implements Serializable {

    /** 幂等键，由调用方生成。同键重复提交返回原活动，不新建 */
    private String clientReqNo;

    private String name;

    /** {@code FISSION} / {@code BENEFIT_SELL} */
    private String playType;

    /** 场景路由 */
    private String scene;

    private String startTime;

    private String endTime;

    /** JSON 数组，空表示不限 */
    private String cityScope;

    private String channelScope;

    private String crowdRule;

    private String riskRule;

    /** 玩法私有配置，发布时快照进版本表 */
    private String playConfig;

    /** 奖励/价格/有效期/退款规则，发布时快照进版本表 */
    private String rewardConfig;

    /** 操作人，落审计（BR-C-27） */
    private String operator;

    public String getClientReqNo() {
        return clientReqNo;
    }

    public void setClientReqNo(String clientReqNo) {
        this.clientReqNo = clientReqNo;
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

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
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

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
