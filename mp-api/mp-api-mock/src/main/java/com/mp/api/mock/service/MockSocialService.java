package com.mp.api.mock.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * mock 社交能力：好友召回 + 过滤所依赖的画像数据。
 *
 * <p><b>它在服务边界的另一侧</b>，与 {@code MockProviderService} / {@code MockPayService} 同类：好友
 * 名单、粉丝量、拉黑关系、实验分组都是外部系统的数据，平台只消费不持有。建模在这一侧才能验 「依赖不可用时各过滤器的失败语义」—— 用平台自己的表模拟依赖，测的是「查不到数据怎么办」， 而
 * fail-open / fail-close 要处理的是「问不到人怎么办」，两者不同。
 *
 * <p><b>八个过滤器里只有关系过滤不经本接口</b>：它查的是 {@code fission_relation}，平台自己的表 —— 这也正是它能被下推 DB
 * 的原因（§7.1）。其余七条都要跨出边界，故都是批量接口。
 *
 * <p><b>每个方法都按「一批」设计，不提供单个查询</b>：单个查询的存在本身就是「逐用户查」这个 病根的入口（§7.1 的基线形态）。接口层面不给单查，编排层就写不出那个循环。
 */
public interface MockSocialService {

    /**
     * 拉好友候选，分页。
     *
     * <p>不可用时抛异常而非返回空列表 —— 空列表与「这个人没有好友」不可区分（{@code 5603}）。
     *
     * @param cursor 从 0 起的页码
     */
    List<String> recallFriends(String userId, int cursor, int pageSize);

    /** 批量取粉丝量（BR-F-07-e 影响力）。缺失的用户不在返回的 map 里。 */
    Map<String, Long> batchFollowerCount(List<String> userIds);

    /** 批量取账户状态（BR-F-07-c）：{@code NORMAL} / {@code CLOSED} / {@code MUTED}。 */
    Map<String, String> batchAccountStatus(List<String> userIds);

    /** 批量取用户角色（BR-F-07-d）：{@code NORMAL} / {@code MERCHANT} / {@code STAFF}。 */
    Map<String, String> batchUserRole(List<String> userIds);

    /** 该用户当天已收到同类分享的对象集合（BR-F-07-b 分享频控）。 */
    Set<String> batchSharedToday(String sponsorId, List<String> userIds);

    /** 拉黑了师傅的用户（BR-F-07-f 社交关系）。 */
    Set<String> batchBlocked(String sponsorId, List<String> userIds);

    /**
     * 命中指定实验组的用户（BR-F-07-g）。
     *
     * <p><b>该活动未配置实验时返回 {@code null}，而非空集</b>。两者对调用方是完全不同的答案：
     *
     * <ul>
     *   <li>{@code null} —— 这个活动没有实验分组这回事，本条规则不适用
     *   <li>空集 —— 配了实验，但这批人一个都不在组里，全部应被拒
     * </ul>
     *
     * <p>合并会让「未配置实验的活动」把全部候选人拒光 —— 而多数活动本来就不做实验。失败形态是 分享名单恒为空，且不报任何错。
     */
    Set<String> batchInExperiment(String activityId, List<String> userIds);

    /** 匹配活动人群标签的用户（BR-F-07-h 营销人群）。未配置人群包时返回 {@code null}，同上。 */
    Set<String> batchInCrowd(String activityId, List<String> userIds);
}
