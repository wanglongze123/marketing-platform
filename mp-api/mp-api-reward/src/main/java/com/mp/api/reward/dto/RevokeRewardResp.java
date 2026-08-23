package com.mp.api.reward.dto;

import com.mp.common.enums.RetStatus;
import java.io.Serializable;

/**
 * 回收结果。
 *
 * <p><b>{@code usageStatus} 由供应方原子判定并回传</b>（BR-B-30）：「仅当未使用才回收」这个判断 必须与回收动作在供应方那一侧原子完成 ——
 * 平台先查再回收存在窗口，查完到回收之间用户可以 把券花掉，于是券已核销而平台以为回收成功，退了钱。
 *
 * <p>故平台侧的前置查询<b>只作准入初筛，不作最终依据</b>：初筛把明显不可退的挡在外面（省一次 RPC），最终以本字段为准。
 */
public class RevokeRewardResp implements Serializable {

    private RetStatus retStatus;

    /** 供应方回传的真实使用态：{@code UNUSED} / {@code PARTIALLY_USED} / {@code USED} / {@code EXPIRED} */
    private String usageStatus;

    /** 供应方回收单号 */
    private String providerOrderNo;

    private String errorCode;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getUsageStatus() {
        return usageStatus;
    }

    public void setUsageStatus(String usageStatus) {
        this.usageStatus = usageStatus;
    }

    public String getProviderOrderNo() {
        return providerOrderNo;
    }

    public void setProviderOrderNo(String providerOrderNo) {
        this.providerOrderNo = providerOrderNo;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
