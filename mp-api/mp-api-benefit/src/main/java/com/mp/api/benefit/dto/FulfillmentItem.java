package com.mp.api.benefit.dto;

import com.mp.common.enums.ItemGrantStatus;

/** 履约明细项。注意用 ItemGrantStatus 而非主单的 GrantStatus。 */
public class FulfillmentItem {

    private String fulfillmentNo;
    private String benefitItemId;
    private String providerType;
    private String providerOrderNo;
    private ItemGrantStatus grantStatus;

    public String getFulfillmentNo() {
        return fulfillmentNo;
    }

    public void setFulfillmentNo(String fulfillmentNo) {
        this.fulfillmentNo = fulfillmentNo;
    }

    public String getBenefitItemId() {
        return benefitItemId;
    }

    public void setBenefitItemId(String benefitItemId) {
        this.benefitItemId = benefitItemId;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getProviderOrderNo() {
        return providerOrderNo;
    }

    public void setProviderOrderNo(String providerOrderNo) {
        this.providerOrderNo = providerOrderNo;
    }

    public ItemGrantStatus getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(ItemGrantStatus grantStatus) {
        this.grantStatus = grantStatus;
    }
}
