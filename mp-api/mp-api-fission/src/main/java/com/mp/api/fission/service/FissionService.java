package com.mp.api.fission.service;

import com.mp.api.fission.dto.FollowerDoneReq;
import com.mp.api.fission.dto.FollowerJoinReq;
import com.mp.api.fission.dto.FriendFilterResp;
import com.mp.api.fission.dto.GetFriendsReq;
import com.mp.api.fission.dto.GroupQueryResp;
import com.mp.api.fission.dto.ShareInviteReq;
import com.mp.api.fission.dto.ShareInviteResp;
import com.mp.api.fission.dto.SponsorQueryReq;
import com.mp.api.fission.dto.SponsorQueryResp;
import java.util.Map;

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
     * 拉可分享好友（FR-F03），召回过程中回调过滤（FR-F04）。
     *
     * <p><b>只读，无任何副作用</b>。返回四部分：通过集合、按原因归类的拒绝集合、生效配置版本、 本次降级规则清单。
     *
     * <p>召回不可用抛 {@code 5603}，<b>不降级为空列表</b> —— 空列表与「这个人没有好友」不可区分， 端上会显示一个看起来正常的空页面而故障无人察觉。这与过滤器的
     * fail-open 不是同一类判断： 召回失败时手上没有任何可放行的对象。
     *
     * <p>日志默认只记数量与原因分布，不打印完整用户列表（FR-F04 的日志要求）。
     */
    FriendFilterResp getFriends(GetFriendsReq req);

    /**
     * 分享：为每个被分享对象创建 {@code INVITED} 关系（FR-F05 能力一）。
     *
     * <p>重复分享不重复创建（BR-F-11）—— 由 {@code uk_group_follower_active} 保证，撞键即视为 已邀请过，不作为错误。
     *
     * <p><b>被分享对象须通过过滤</b>（BR-F-12，{@code 1611}）。分享侧独立校验一次，不信任 {@code getFriends}
     * 的结果：两次调用之间对方可能已注销、已被拉黑，或客户端根本没调过 {@code getFriends} 而直接构造了一批 id —— 而分享是写路径，把关只在读路径上做等于没做。
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

    /**
     * 跑一轮裂变侧对账（FR-C06、技术方案 §6.8 第 7、10、12、13 项）。
     *
     * <p><b>本方法是那四项对账的唯一入口</b>：{@code FissionReconcileService} 不被任何调度或接口直接触达， 缺了这个入口它一次都不会运行。其中第 7
     * 项（徒弟已发师傅未返）与第 13 项（发奖在途标志超时） 都是资损哨兵 —— 一条对账项写好了却没接线，与没写的区别只在代码行数上。
     *
     * <p>处置形态与权益侧 {@code BenefitOrderService#reconcile} 一致：<b>可自愈的补建任务，只告警的不改数</b>。 唯一会改字段的是第 13
     * 项（清空过期的 {@code granting_until}），清的是豁免标志而非业务结果 —— 清完之后关系回到过期治理的扫描范围内，仍然是「把单子推回既有通路」。
     *
     * <p>与权益侧分成两个方法而非合成一个：两者读写不同的库、绑不同的事务管理器，合并即要求某一层 同时持有两套数据源。
     *
     * @return 各项差异数，无差异的项不出现在返回里
     */
    Map<String, Integer> reconcile();
}
