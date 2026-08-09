package com.mp.api.benefit.dto;

import java.util.List;

/**
 * 商品详情出参，供商品页渲染。
 *
 * <p><b>只读</b>。V1 不提供商品管理接口（《分阶段方案》§4.6 列为范围外），本接口不改变这一点 —— 它让端侧不必硬编码 seed SQL 里的名称与价格，而非开放配置写入。
 *
 * <p>价格单位为分，端侧自行换算展示。不返回格式化字符串 —— 金额格式属展示层职责。
 */
public class QuerySkuResp {

    private String skuId;
    private String activityId;
    private String skuName;
    private String skuType;

    /** ON_SALE 之外的取值端侧应禁用下单入口 */
    private String saleStatus;

    /** 划线价，分 */
    private long listPrice;

    /** 售卖价，分。<b>下单应付以服务端重算为准</b>，此值仅供展示 */
    private long salePrice;

    private String benefitPackageId;
    private Integer packageVersion;

    /** 包内权益项，按 grantOrder 升序 */
    private List<BenefitItemView> items;

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSkuName() {
        return skuName;
    }

    public void setSkuName(String skuName) {
        this.skuName = skuName;
    }

    public String getSkuType() {
        return skuType;
    }

    public void setSkuType(String skuType) {
        this.skuType = skuType;
    }

    public String getSaleStatus() {
        return saleStatus;
    }

    public void setSaleStatus(String saleStatus) {
        this.saleStatus = saleStatus;
    }

    public long getListPrice() {
        return listPrice;
    }

    public void setListPrice(long listPrice) {
        this.listPrice = listPrice;
    }

    public long getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(long salePrice) {
        this.salePrice = salePrice;
    }

    public String getBenefitPackageId() {
        return benefitPackageId;
    }

    public void setBenefitPackageId(String benefitPackageId) {
        this.benefitPackageId = benefitPackageId;
    }

    public Integer getPackageVersion() {
        return packageVersion;
    }

    public void setPackageVersion(Integer packageVersion) {
        this.packageVersion = packageVersion;
    }

    public List<BenefitItemView> getItems() {
        return items;
    }

    public void setItems(List<BenefitItemView> items) {
        this.items = items;
    }
}
