package com.mp.api.mock.dto;

/** mock 供应方发放入参。 */
public class ProviderGrantReq {

    /** 调用方幂等号，供应方据此去重 */
    private String opNo;

    private String providerProductId;
    private String receiverId;
    private int qty;

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getProviderProductId() {
        return providerProductId;
    }

    public void setProviderProductId(String providerProductId) {
        this.providerProductId = providerProductId;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }
}
