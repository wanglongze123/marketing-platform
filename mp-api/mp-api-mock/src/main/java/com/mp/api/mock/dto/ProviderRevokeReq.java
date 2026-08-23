package com.mp.api.mock.dto;

import java.io.Serializable;

/** mock 供应方回收入参。 */
public class ProviderRevokeReq implements Serializable {

    /** 回收幂等号，供应方据此去重。与发放的 {@code opNo} 不是同一个键 */
    private String revokeNo;

    /** 被回收的那笔发放 */
    private String grantOpNo;

    private String receiverId;

    public String getRevokeNo() {
        return revokeNo;
    }

    public void setRevokeNo(String revokeNo) {
        this.revokeNo = revokeNo;
    }

    public String getGrantOpNo() {
        return grantOpNo;
    }

    public void setGrantOpNo(String grantOpNo) {
        this.grantOpNo = grantOpNo;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }
}
