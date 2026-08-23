package com.mp.api.benefit.dto;

import java.io.Serializable;

/**
 * 预咨询出参：试算结果 + 咨询凭证。
 *
 * <p>{@code dealPrice} 与 {@code consultToken} 中的成交价<b>是同一个值的两种呈现</b>：前者给端上展示， 后者供 {@code
 * createTrade} 比对。端上拿到的价格与凭证里签着的价格必须一致 —— 否则用户看到 9.9 元、 下单时凭证里签的是 19.9 元，比价通过而用户被多收钱。
 *
 * <p>V2 未做资格决策与人群频控（{@code qualifyResult} / {@code reasonCode}），SKU 不可售时直接抛 {@code 4001}。字段留待 V3 接
 * {@code decideQualification} 时补 —— 提前加空字段会让调用方以为 平台已经做了资格判断。
 */
public class PreConsultResp implements Serializable {

    private String activityId;
    private String skuId;

    /** 冻结进凭证的配置版本，下单时随主单落库 */
    private int configVersion;

    /** 划线价，分 */
    private long originPrice;

    /** 服务端计算的成交价，分。<b>不接受客户端回传</b>（PRD BR-B-03/B-04） */
    private long dealPrice;

    /** 咨询凭证，下单时原样回传 */
    private String consultToken;

    /** 凭证过期时刻（epoch 毫秒），供端上判断是否需要重新咨询 */
    private long expireAt;

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

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public long getOriginPrice() {
        return originPrice;
    }

    public void setOriginPrice(long originPrice) {
        this.originPrice = originPrice;
    }

    public long getDealPrice() {
        return dealPrice;
    }

    public void setDealPrice(long dealPrice) {
        this.dealPrice = dealPrice;
    }

    public String getConsultToken() {
        return consultToken;
    }

    public void setConsultToken(String consultToken) {
        this.consultToken = consultToken;
    }

    public long getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(long expireAt) {
        this.expireAt = expireAt;
    }
}
