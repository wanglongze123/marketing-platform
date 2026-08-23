package com.mp.api.benefit.dto;

import java.io.Serializable;

/**
 * 人工处置入参（FR-C07）。V3 PR-10 引入。
 *
 * <p><b>{@code operator} 与 {@code reason} 必填</b>（BR-C-27）：人工处置是绕过自动链路的写入口，不留操作人
 * 即无法追责，也无法在对账里把它与自动收敛区分开。
 *
 * <p><b>{@code ticketNo} 参与幂等键，取外部工单号</b>：一次处置一条审计记录，多次重试各留一条痕 （{@code play_op_record.op_seq}
 * 取它）。<b>但它不参与业务幂等键的派生</b> —— 重试发奖仍用原 {@code grantOpNo}，否则两次人工重试就是两笔发放。
 */
public class ManualRepairReq implements Serializable {

    private String bizNo;

    private RepairAction action;

    /** 操作人，必填。落审计 */
    private String operator;

    /** 处置原因，必填。落审计 */
    private String reason;

    /** 外部工单号。参与审计记录的 {@code op_seq}，不参与业务幂等键 */
    private String ticketNo;

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public RepairAction getAction() {
        return action;
    }

    public void setAction(RepairAction action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }
}
