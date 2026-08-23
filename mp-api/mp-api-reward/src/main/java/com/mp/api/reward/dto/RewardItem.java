package com.mp.api.reward.dto;

import java.io.Serializable;

/** 奖励项。一次 grantReward 调用内的项必须同属一个 provider_type。 */
public class RewardItem implements Serializable {

    /**
     * 组内下标，<b>相对本次调用的 rewardItems 列表从 0 起连续编号</b>，不是订单内全局下标。
     *
     * <p>因 grantOpNo 粒度是「一次调用 = 一个供应方」，各组独立编号； {@code uk_op_item(op_no, item_seq)} 的第一维已隔开不同组。
     */
    private int itemSeq;

    private String rewardType;
    private String providerType;
    private String providerProductId;
    private int qty;

    /** 是否核心权益。核心项失败需回收附加项后退款 */
    private boolean core;

    public int getItemSeq() {
        return itemSeq;
    }

    public void setItemSeq(int itemSeq) {
        this.itemSeq = itemSeq;
    }

    public String getRewardType() {
        return rewardType;
    }

    public void setRewardType(String rewardType) {
        this.rewardType = rewardType;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getProviderProductId() {
        return providerProductId;
    }

    public void setProviderProductId(String providerProductId) {
        this.providerProductId = providerProductId;
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public boolean isCore() {
        return core;
    }

    public void setCore(boolean core) {
        this.core = core;
    }
}
