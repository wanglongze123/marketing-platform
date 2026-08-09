package com.mp.api.fission.dto;

/**
 * 拉可分享好友（FR-F03）。
 *
 * <p>召回过程中回调过滤（FR-F04）—— 两者是一次请求的两段，不是两个接口：先过滤后召回无从谈起， 而召回完不过滤则端上会显示一批点了没反应的头像。
 */
public class GetFriendsReq {

    private String groupId;

    private String sponsorId;

    /** 拉取页数上限。候选集大小须在受控上限内（FR-F04 前置条件） */
    private int maxPages;

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getSponsorId() {
        return sponsorId;
    }

    public void setSponsorId(String sponsorId) {
        this.sponsorId = sponsorId;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }
}
