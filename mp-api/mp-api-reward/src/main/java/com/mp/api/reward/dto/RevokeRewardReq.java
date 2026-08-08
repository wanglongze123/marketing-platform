package com.mp.api.reward.dto;

/**
 * 统一回收入参（BR-B-30）。
 *
 * <p><b>与 {@link GrantRewardReq} 一样不含玩法专有概念</b>：裂变若将来需要回收（V3 无逆向链路， 见 PRD 附录 A），可原样调用。字段命名沿用 {@code
 * bizOrderNo} / {@code receiverId} 而非 {@code orderNo} / {@code buyerId}。
 *
 * <p><b>回收键与发奖键不复用</b>（BR-C-11）：{@code revokeNo} 独立且各有唯一索引承载。复用发奖键 会让回收撞上 {@code
 * reward_grant_record.uk_op_no} —— 被当成发奖重传吞掉，权益实际没回收， 而调用方拿到「成功」。
 *
 * <p><b>{@code opNo} 是被回收的那笔发奖</b>，不是本次操作的幂等键。两者都要传：前者定位回收对象， 后者保证不二次回收。
 */
public class RevokeRewardReq {

    /** 回收幂等键，与发奖 {@code opNo} 不复用 */
    private String revokeNo;

    /** 调用方业务单号 */
    private String bizOrderNo;

    /** 被回收的原发奖操作单号 */
    private String opNo;

    /** 收奖人 userId，与原发放一致 */
    private String receiverId;

    public String getRevokeNo() {
        return revokeNo;
    }

    public void setRevokeNo(String revokeNo) {
        this.revokeNo = revokeNo;
    }

    public String getBizOrderNo() {
        return bizOrderNo;
    }

    public void setBizOrderNo(String bizOrderNo) {
        this.bizOrderNo = bizOrderNo;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }
}
