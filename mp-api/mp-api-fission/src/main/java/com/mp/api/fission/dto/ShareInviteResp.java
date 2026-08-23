package com.mp.api.fission.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 分享结果。
 *
 * <p><b>逐个返回而非只给一个总数</b>：一次分享给 N 个人，其中若干已有进行中关系（重复分享）、 若干新建成功、若干未通过过滤。调用方需要知道具体是哪些 —— 只回「成功 3
 * 个」时，端上无法标记 哪几个头像已邀请过、哪几个该置灰。
 *
 * <p><b>三类互不重叠，且含义各不相同</b>：新建是「这次生效了」，已邀请是「上次就生效了」
 * （BR-F-11，不是错误），未通过过滤是「这个人本轮不能被邀请」（BR-F-12）。合并任意两类都会 让端上无从区分该显示什么。
 */
public class ShareInviteResp implements Serializable {

    /** 本次新建 {@code INVITED} 关系的徒弟 */
    private List<String> invitedFollowerIds;

    /** 已存在进行中关系、本次未新建的徒弟（BR-F-11 重复分享不重复创建） */
    private List<String> alreadyInvitedFollowerIds;

    /** 未通过过滤的徒弟 → 原因（BR-F-12）。带原因而非只给 id —— 运营要看拒绝原因分布 */
    private Map<String, String> filteredFollowerIds;

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

    public Map<String, String> getFilteredFollowerIds() {
        return filteredFollowerIds;
    }

    public void setFilteredFollowerIds(Map<String, String> filteredFollowerIds) {
        this.filteredFollowerIds = filteredFollowerIds;
    }
}
