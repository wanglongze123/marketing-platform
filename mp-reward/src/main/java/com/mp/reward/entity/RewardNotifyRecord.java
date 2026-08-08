package com.mp.reward.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 供应方通知的幂等记录。{@code uk_notify(op_no, notify_seq)} 保证同一条通知至多处理一次。
 *
 * <p><b>与 {@link RewardGrantRecord} 的条件更新是两道不同的闸</b>：后者保证「终态不被覆盖」，本表 保证「同一条通知只处理一次」。同一个 {@code
 * opNo} 会收到多条语义不同的通知（先 {@code PROCESSING} 后 {@code SUCCESS}、供应方补发），它们各自都该留痕；而重复投递必须被识别成重传 —— 判据是
 * {@code notifySeq}，不是 {@code opNo}。
 */
@TableName("reward_notify_record")
public class RewardNotifyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String opNo;

    /** 供应方通知流水，重传保持不变 */
    private String notifySeq;

    private String providerOrderNo;

    /** 本条通知携带的四分类结果 */
    private String result;

    private String errorCode;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOpNo() {
        return opNo;
    }

    public void setOpNo(String opNo) {
        this.opNo = opNo;
    }

    public String getNotifySeq() {
        return notifySeq;
    }

    public void setNotifySeq(String notifySeq) {
        this.notifySeq = notifySeq;
    }

    public String getProviderOrderNo() {
        return providerOrderNo;
    }

    public void setProviderOrderNo(String providerOrderNo) {
        this.providerOrderNo = providerOrderNo;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
