package com.mp.api.reward.dto;

import com.mp.common.enums.RetStatus;
import java.util.List;

/** 统一发放出参。汇总态 + 每项独立态。 */
public class GrantRewardResp {

    /** 汇总四分类。调用方据此决定推进终态、补偿、还是挂查单任务 */
    private RetStatus retStatus;

    private String retCode;
    private String retMsg;

    private List<GrantItemResult> items;

    public RetStatus getRetStatus() {
        return retStatus;
    }

    public void setRetStatus(RetStatus retStatus) {
        this.retStatus = retStatus;
    }

    public String getRetCode() {
        return retCode;
    }

    public void setRetCode(String retCode) {
        this.retCode = retCode;
    }

    public String getRetMsg() {
        return retMsg;
    }

    public void setRetMsg(String retMsg) {
        this.retMsg = retMsg;
    }

    public List<GrantItemResult> getItems() {
        return items;
    }

    public void setItems(List<GrantItemResult> items) {
        this.items = items;
    }
}
