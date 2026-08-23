package com.mp.api.benefit.dto;

import java.io.Serializable;

/**
 * 订单列表入参。
 *
 * <p>四个筛选项均可为空，为空即不限。<b>只读查询，无副作用</b>，不携带幂等键。
 */
public class QueryOrderPageReq implements Serializable {

    /** 用户 ID。为空时查全量，仅供运营/客服；端侧调用应始终传值 */
    private String userId;

    private String activityId;

    /** 支付状态，取值见 {@code PayStatus}。非法取值返回 4001 而非静默忽略 */
    private String payStatus;

    /** 发放状态，取值见 {@code GrantStatus}（带 GRANT_ 前缀） */
    private String grantStatus;

    /** 页码，从 1 起 */
    private int page = 1;

    /** 每页行数，上限见实现类 */
    private int size = 20;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public String getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(String grantStatus) {
        this.grantStatus = grantStatus;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
