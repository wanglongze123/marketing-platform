package com.mp.api.benefit.dto;

/** 下单入参。 */
public class CreateTradeReq {

    private String userId;
    private String activityId;
    private String skuId;

    /** 客户端生成，重试保持不变。参与业务幂等键 user+activity+sku+clientReqNo */
    private String clientReqNo;

    /**
     * 购买份数。<b>V1 冻结为 1</b>，不等于 1 直接拒绝（4001）。
     *
     * <p>字段保留是形状冻结：quantity &gt; 1 牵连应付金额计算、RewardItem.qty、 履约明细行数三处语义，须与库存限购一起在 V2 定。 显式校验而非默默忽略
     * —— 否则调用方传 3 会付一份钱得一份权益且无报错。
     */
    private int quantity;

    // V2 加：consultToken（咨询凭证）、ReqTerminalInfo、RiskInfo

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

    public String getClientReqNo() {
        return clientReqNo;
    }

    public void setClientReqNo(String clientReqNo) {
        this.clientReqNo = clientReqNo;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
