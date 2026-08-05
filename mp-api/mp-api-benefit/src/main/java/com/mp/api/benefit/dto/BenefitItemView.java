package com.mp.api.benefit.dto;

/**
 * 权益项视图，供商品详情展示。
 *
 * <p>与履约明细的 {@link FulfillmentItem} 是不同东西：此处是<b>配置</b>（这个包卖什么）， 那边是<b>履约结果</b>（这一单发成了什么）。前者无状态。
 */
public class BenefitItemView {

    private String benefitItemId;
    private String benefitType;

    /** 供应方类型。履约时按此分组，每组一个 grantOpNo */
    private String providerType;

    private String providerProductId;

    /** 是否核心权益。核心项发放失败时整单不可用 */
    private boolean core;

    /** 发放顺序 */
    private Integer grantOrder;

    public String getBenefitItemId() {
        return benefitItemId;
    }

    public void setBenefitItemId(String benefitItemId) {
        this.benefitItemId = benefitItemId;
    }

    public String getBenefitType() {
        return benefitType;
    }

    public void setBenefitType(String benefitType) {
        this.benefitType = benefitType;
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

    public boolean isCore() {
        return core;
    }

    public void setCore(boolean core) {
        this.core = core;
    }

    public Integer getGrantOrder() {
        return grantOrder;
    }

    public void setGrantOrder(Integer grantOrder) {
        this.grantOrder = grantOrder;
    }
}
