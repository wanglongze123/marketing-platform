package com.mp.api.fission.service;

import com.mp.api.fission.dto.GroupQueryResp;
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
}
