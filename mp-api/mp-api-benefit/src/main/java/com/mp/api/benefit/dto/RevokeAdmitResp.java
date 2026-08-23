package com.mp.api.benefit.dto;

import com.mp.common.enums.RetStatus;
import java.io.Serializable;

/**
 * 退款准入结果。
 *
 * <p><b>{@code admitted} 与 {@code retStatus} 分列</b>：前者是「这单能不能退」，后者是「本次回收 操作成没成」。两者的组合有意义：
 *
 * <table>
 *   <tr><th>admitted</th><th>retStatus</th><th>含义</th></tr>
 *   <tr><td>true</td><td>{@code SUCCESS}</td><td>准入通过，无需回收或回收已完成 —— 可以退款</td></tr>
 *   <tr><td>true</td><td>{@code UNKNOWN}</td><td>准入通过但回收结果未定 —— <b>不得退款</b>，等收敛</td></tr>
 *   <tr><td>false</td><td>—</td><td>准入未通过，{@code reasonCode} 说明为什么</td></tr>
 * </table>
 *
 * <p>合成一个字段会让「不能退」与「还不知道能不能退」不可区分，而前者该给用户提示、后者该等待。
 */
public class RevokeAdmitResp implements Serializable {

    /** 准入是否通过 */
    private boolean admitted;

    /** 本次回收操作的四分类结果；无需回收时为 {@code SUCCESS} */
    private RetStatus retStatus;

    /** 未通过时的原因码：{@code 1751} 结果未定、{@code 1752} 已核销、{@code 1753} 回收未成功 */
    private String reasonCode;

    /** 本次回收的幂等键，退款执行时要用同一个 {@code refundReqNo} 派生的退款键 */
    private String revokeNo;

    /** 回收当时供应方回传的真实使用态 */
    private String usageStatus;

    /** 是否实际发生了回收。{@code NOT_START} / {@code GRANT_FAILED} 无权益在外，直接退不回收 */
    private boolean revokeRequired;

    public boolean isAdmitted() {
        return admitted;
    }

    public void setAdmitted(boolean admitted) {
        this.admitted = admitted;
    }

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getRevokeNo() {
        return revokeNo;
    }

    public void setRevokeNo(String revokeNo) {
        this.revokeNo = revokeNo;
    }

    public String getUsageStatus() {
        return usageStatus;
    }

    public void setUsageStatus(String usageStatus) {
        this.usageStatus = usageStatus;
    }

    public boolean isRevokeRequired() {
        return revokeRequired;
    }

    public void setRevokeRequired(boolean revokeRequired) {
        this.revokeRequired = revokeRequired;
    }
}
