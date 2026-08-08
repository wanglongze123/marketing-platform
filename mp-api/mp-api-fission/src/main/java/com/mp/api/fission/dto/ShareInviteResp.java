package com.mp.api.fission.dto;

import java.util.List;

/**
 * 分享结果。
 *
 * <p><b>逐个返回而非只给一个总数</b>：一次分享给 N 个人，其中若干已有进行中关系（重复分享）、 若干新建成功。调用方需要知道具体是哪些 —— 只回「成功 3
 * 个」时，端上无法标记哪几个头像已邀请过。
 */
public class ShareInviteResp {

    /** 本次新建 {@code INVITED} 关系的徒弟 */
    private List<String> invitedFollowerIds;

    /** 已存在进行中关系、本次未新建的徒弟（BR-F-11 重复分享不重复创建） */
    private List<String> alreadyInvitedFollowerIds;

    public List<String> getInvitedFollowerIds() {
        return invitedFollowerIds;
    }

    public void setInvitedFollowerIds(List<String> invitedFollowerIds) {
        this.invitedFollowerIds = invitedFollowerIds;
    }

    public List<String> getAlreadyInvitedFollowerIds() {
        return alreadyInvitedFollowerIds;
    }

    public void setAlreadyInvitedFollowerIds(List<String> alreadyInvitedFollowerIds) {
        this.alreadyInvitedFollowerIds = alreadyInvitedFollowerIds;
    }
}
