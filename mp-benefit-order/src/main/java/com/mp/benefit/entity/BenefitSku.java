package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 售卖商品。V1 由 seed SQL 初始化，只读。 */
@TableName("benefit_sku")
public class BenefitSku {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuId;
    private String activityId;
    private String skuName;
    private String skuType;
    private String saleStatus;

    /** 划线价，分 */
    private Long listPrice;

    /** 售卖价，分 */
    private Long salePrice;

    private String benefitPackageId;
    private Integer packageVersion;

    /** 每人限购数量，0=不限购。V3 建限购规则表后改由规则快照填充 */
    private Integer purchaseLimitQty;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Long getListPrice() {
        return listPrice;
    }

    public void setListPrice(Long listPrice) {
        this.listPrice = listPrice;
    }

    public Long getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(Long salePrice) {
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

    public Integer getPurchaseLimitQty() {
        return purchaseLimitQty;
    }

    public void setPurchaseLimitQty(Integer purchaseLimitQty) {
        this.purchaseLimitQty = purchaseLimitQty;
    }
}
