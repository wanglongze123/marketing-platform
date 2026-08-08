package com.mp.api.fission.dto;

import java.util.List;

/**
 * 轮次查询结果（FR-F02 能力二）。
 *
 * <p>三个返回开关（含历史轮次 / 含已完成徒弟列表 / 含阶梯奖励）<b>默认全关</b>（BR-F-05）：默认全开 会让每次查询都拉出膨胀表的明细，而多数调用方只要当前轮的进度。
 */
public class GroupQueryResp {

    private String activityId;

    private String sponsorId;

    /** 当前进行中的轮次；无则为空 */
    private RoundInfo current;

    /** 历史轮次，仅当开关打开时返回 */
    private List<RoundInfo> history;

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSponsorId() {
        return sponsorId;
    }

    public void setSponsorId(String sponsorId) {
        this.sponsorId = sponsorId;
    }

    public RoundInfo getCurrent() {
        return current;
    }

    public void setCurrent(RoundInfo current) {
        this.current = current;
    }

    public List<RoundInfo> getHistory() {
        return history;
    }

    public void setHistory(List<RoundInfo> history) {
        this.history = history;
    }

    /** 单个轮次的公开信息。 */
    public static class RoundInfo {
        private String groupId;
        private Integer roundNo;
        private String status;
        private Integer progress;
        private Integer targetCount;

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public Integer getRoundNo() {
            return roundNo;
        }

        public void setRoundNo(Integer roundNo) {
            this.roundNo = roundNo;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getProgress() {
            return progress;
        }

        public void setProgress(Integer progress) {
            this.progress = progress;
        }

        public Integer getTargetCount() {
            return targetCount;
        }

        public void setTargetCount(Integer targetCount) {
            this.targetCount = targetCount;
        }
    }
}
