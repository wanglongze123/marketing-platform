package com.mp.api.fission.dto;

import java.util.List;

/**
 * 分享，为每个被分享对象创建 {@code INVITED} 关系（FR-F05 能力一）。
 *
 * <p>被分享对象须已通过好友过滤（BR-F-12）—— 过滤在 PR-6 落地，此前由调用方保证。
 */
public class ShareInviteReq {

    private String groupId;

    private String sponsorId;

    /** 被分享的徒弟 ID 列表 */
    private List<String> followerIds;

    /** {@code IM} / {@code QRCODE} / {@code PASSWORD} / {@code EXTERNAL} */
    private String shareMethod;

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

    public List<String> getFollowerIds() {
        return followerIds;
    }

    public void setFollowerIds(List<String> followerIds) {
        this.followerIds = followerIds;
    }

    public String getShareMethod() {
        return shareMethod;
    }

    public void setShareMethod(String shareMethod) {
        this.shareMethod = shareMethod;
    }
}
