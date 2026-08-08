package com.mp.api.fission.service;

import com.mp.api.fission.dto.FollowerDoneReq;
import com.mp.api.fission.dto.FollowerJoinReq;
import com.mp.api.fission.dto.GroupQueryResp;
import com.mp.api.fission.dto.ShareInviteReq;
import com.mp.api.fission.dto.ShareInviteResp;
import com.mp.api.fission.dto.SponsorQueryReq;
import com.mp.api.fission.dto.SponsorQueryResp;

/**
 * 裂变玩法（玩法层）。V3 PR-2 落进场与轮次管理，分享/建联/加入/完成在后续 PR。
 *
 * <p><b>资格判定不在本服务实现</b>：调 {@code activity.decideQualification}（公共能力层）。玩法层 重复实现一遍人群、城市、风控，两处判据迟早漂移
 * —— 而漂移的表现是「咨询说能参与，进场说不能」。
 */
public interface FissionService {

    /**
     * 师傅进场（FR-F01）：资格校验 → 复用或创建轮次 → 签发邀请凭证。
     *
     * <p>无可参与活动返回 {@code available=false} + 原因码，<b>不抛异常</b>（BR-F-01）。
     *
     * <p>自动开轮的活动在此创建轮次；手动开轮的不创建，由 {@link #openGroup} 显式触发 （BR-F-03）—— 未开轮不是错误。
     */
    SponsorQueryResp sponsorQuery(SponsorQueryReq req);

    /**
     * 手动开轮（FR-F02 能力一）。
     *
     * <p>已有进行中轮次时拒绝（BR-F-04，{@code 1602}）—— 一个师傅在一个活动下同时只能有一轮， 否则邀请来的徒弟不知道该记进哪一轮。
     *
     * @return 新轮次的裂变组号
     */
    String openGroup(String activityId, String sponsorId);

    /**
     * 轮次查询（FR-F02 能力二）。
     *
     * <p>三个开关默认关闭（BR-F-05）。本方法只放开「含历史轮次」，另两个开关随对应能力在后续 PR 接入 ——
     * 提前放开会返回一个恒为空的字段，调用方无从判断是「没有」还是「没实现」。
     */
    GroupQueryResp queryGroup(String activityId, String sponsorId, boolean includeHistory);

    /**
     * 分享：为每个被分享对象创建 {@code INVITED} 关系（FR-F05 能力一）。
     *
     * <p>重复分享不重复创建（BR-F-11）—— 由 {@code uk_group_follower_active} 保证，撞键即视为 已邀请过，不作为错误。
     */
    ShareInviteResp shareInvite(ShareInviteReq req);

    /**
     * 建联：{@code INVITED → CONNECTED}（FR-F05 能力二）。
     *
     * <p>重复点击不重复推进（BR-F-13）：条件更新 {@code WHERE status='INVITED'}，第二次命中 0 行。
     *
     * @return 是否发生了推进；{@code false} 表示重复点击或关系不在 {@code INVITED}
     */
    boolean followerConnect(String groupId, String followerId);

    /**
     * 徒弟加入：推进至 {@code JOINED} 并回填 {@code outBizNo}（FR-F06）。
     *
     * <p>不存在关系时直建 {@code JOINED} —— 二维码/口令分享的徒弟没有事先建立的 {@code INVITED}。
     *
     * <p>师徒同人拒绝（{@code 1614}）；同一徒弟并发加入只产生一条关系（BR-F-16），由 {@code uk_group_follower_active} 兜底。
     *
     * @return 关系号
     */
    String followerJoin(FollowerJoinReq req);

    /**
     * 徒弟完成，触发双向发奖（FR-F07）。
     *
     * <p>组件序：关系预处理 → 徒弟发奖 → 关系后处理 → 师傅返奖（异步）。
     *
     * <p><b>接口响应不返回师傅返奖状态</b>（BR-F-21）：它走本地消息表异步，结果由子流程保证。 同步返回等于让调用方等两次外部发奖。
     *
     * <p>关系非 {@code JOINED} 抛 {@code 1617}；发奖未收敛时关系<b>不推进</b>，由查单任务收敛。
     *
     * @return 关系号
     */
    String followerDone(FollowerDoneReq req);
}
