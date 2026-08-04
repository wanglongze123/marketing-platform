package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 权益项。V1 由 seed SQL 初始化，只读。下单时组装进 benefit_snapshot。 */
@TableName("benefit_item")
public class BenefitItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String benefitItemId;
    private String benefitPackageId;
    private Integer packageVersion;
    private String benefitType;

    /** 供应方类型。grantBenefit 按此分组，每组一个 grantOpNo */
    private String providerType;

    private String providerProductId;
    private Integer isCore;
    private Integer grantOrder;
    private Integer rollbackSupported;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBenefitItemId() {
        return benefitItemId;
    }

    public void setBenefitItemId(String benefitItemId) {
        this.benefitItemId = benefitItemId;
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

    public Integer getIsCore() {
        return isCore;
    }

    public void setIsCore(Integer isCore) {
        this.isCore = isCore;
    }

    public Integer getGrantOrder() {
        return grantOrder;
    }

    public void setGrantOrder(Integer grantOrder) {
        this.grantOrder = grantOrder;
    }

    public Integer getRollbackSupported() {
        return rollbackSupported;
    }

    public void setRollbackSupported(Integer rollbackSupported) {
        this.rollbackSupported = rollbackSupported;
    }
}
