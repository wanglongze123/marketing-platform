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

    /**
     * 咨询凭证，由 {@code preConsult} 签发，下单时原样回传。
     *
     * <p>验签只证明凭证由平台签发且未被篡改，<b>不证明它是发给本次请求的</b> —— 故 {@code createTrade} 还要把凭证里的 user/activity/sku
     * 与请求逐字段比对，并以凭证中的成交价 与服务端重算价比对（技术方案 §5.2 ①、④.5）。
     *
     * <p>成交价<b>不在本请求里</b>：客户端回传金额一律不信任（PRD BR-B-04）。价格的唯一可信来源 是凭证中被签名覆盖的那一份。
     */
    private String consultToken;

    // V3 加：ReqTerminalInfo、RiskInfo

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

    public String getConsultToken() {
        return consultToken;
    }

    public void setConsultToken(String consultToken) {
        this.consultToken = consultToken;
    }
}
