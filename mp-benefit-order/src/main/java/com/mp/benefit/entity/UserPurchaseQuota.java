package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 用户购买额度。一用户一活动一商品一周期一行，运行时按需建行。 */
@TableName("user_purchase_quota")
public class UserPurchaseQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String activityId;
    private String skuId;

    /** 限购周期：{@code TOTAL} / {@code D20260802} / {@code W202631}。V2 只用 TOTAL */
    private String periodKey;

    private Integer usedQty;

    /** 快照自限购规则。扣减谓词读行内这一份，不读应用传入的值 */
    private Integer limitQty;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getPeriodKey() {
        return periodKey;
    }

    public void setPeriodKey(String periodKey) {
        this.periodKey = periodKey;
    }

    public Integer getUsedQty() {
        return usedQty;
    }

    public void setUsedQty(Integer usedQty) {
        this.usedQty = usedQty;
    }

    public Integer getLimitQty() {
        return limitQty;
    }

    public void setLimitQty(Integer limitQty) {
        this.limitQty = limitQty;
    }
}
