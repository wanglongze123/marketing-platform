package com.mp.api.reward.dto;

import java.util.List;

/**
 * 统一发放入参。<b>此签名即终态签名，V1 不裁</b>（形状冻结项 6）。
 *
 * <p>判据：裂变的师徒双向发奖能否原样调用。裂变传 playType=FISSION、bizOrderNo=裂变关系号、 opNo=outFlowNo+"_FL"、receiverId=徒弟
 * userId —— 能原样调用，无需改签名。
 *
 * <p>因此字段中<b>不得出现 orderId / tradeNo 等权益售卖专有概念</b>。 V1 只有权益售卖一个调用方时，手会自然地把 orderId 塞进来，届时接口就废了。
 */
public class GrantRewardReq {

    /** FISSION / BENEFIT_SELL。落 reward_grant_record.play_type，NOT NULL */
    private String playType;

    private String activityId;

    /** 调用方业务单号 —— 语义是「调用方的业务标识」，不是「订单号」 */
    private String bizOrderNo;

    /** 幂等键。同 opNo 重复调用返回同结果 */
    private String opNo;

    /** 收奖人 userId */
    private String receiverId;

    /** 本次发放的奖励项，须同属一个 providerType */
    private List<RewardItem> rewardItems;

    public String getPlayType() {
        return playType;
    }

    public void setPlayType(String playType) {
        this.playType = playType;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getBizOrderNo() {
        return bizOrderNo;
    }

    public void setBizOrderNo(String bizOrderNo) {
        this.bizOrderNo = bizOrderNo;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public List<RewardItem> getRewardItems() {
        return rewardItems;
    }

    public void setRewardItems(List<RewardItem> rewardItems) {
        this.rewardItems = rewardItems;
    }
}
