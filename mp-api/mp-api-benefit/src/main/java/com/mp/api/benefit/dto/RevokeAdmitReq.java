package com.mp.api.benefit.dto;

/**
 * 退款准入 + 权益回收入参（FR-B08）。
 *
 * <p><b>{@code refundReqNo} 是上游的退款请求号，不是内部生成的</b>：回收键与退款键都由它派生 （{@code bizNo + '_V_' + refundReqNo}
 * / {@code '_R_' + refundReqNo}），故它必须在重试时保持不变。
 *
 * <p>取内部计数器会让重试产生新键、绕过唯一索引 —— 那正是「同一订单退两次」的成因（技术方案 §4.1 的第 2 条铁律）。
 */
public class RevokeAdmitReq {

    private String bizNo;

    /** 上游退款请求号，重试保持不变。回收与退款两把幂等键由它派生 */
    private String refundReqNo;

    /** 操作人，落审计。人工触发时必填 */
    private String operator;

    /** 退款原因，落审计 */
    private String reason;

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getRefundReqNo() {
        return refundReqNo;
    }

    public void setRefundReqNo(String refundReqNo) {
        this.refundReqNo = refundReqNo;
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
}
