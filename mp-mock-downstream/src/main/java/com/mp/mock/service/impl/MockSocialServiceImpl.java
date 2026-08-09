package com.mp.mock.service.impl;

import com.mp.api.mock.dto.SocialDependency;
import com.mp.api.mock.service.MockSocialService;
import com.mp.mock.fault.SocialProfileStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * mock 社交能力，按 {@link SocialProfileStore} 布置的场景与故障开关行事。
 *
 * <p><b>每个查询方法都先检查对应的依赖开关</b>：注入后抛异常，模拟「问不到人」—— 而非返回空结果。两者对过滤器是完全不同的输入：空结果是一个有效答案（这批人都不满足），
 * 异常才是「不知道」。fail-open / fail-close 处理的是后者。
 */
@DubboService
@Service
public class MockSocialServiceImpl implements MockSocialService {

    private static final Logger log = LoggerFactory.getLogger(MockSocialServiceImpl.class);

    private final SocialProfileStore store;

    public MockSocialServiceImpl(SocialProfileStore store) {
        this.store = store;
    }

    @Override
    public List<String> recallFriends(String userId, int cursor, int pageSize) {
        // 先记到达再分流：抛不可用的那次也算 —— 请求确实发出了。与 ProviderLedger
        // 对发起次数与账本分开计数是同一处置
        store.recordRecallCall(userId);
        if (store.isRecallDown()) {
            // 不返回空列表：空列表与「这个人没有好友」不可区分，端上会显示一个看起来
            // 正常的空页面，而故障无人察觉
            log.warn("mock social recall unavailable, userId={}", userId);
            throw new IllegalStateException("模拟好友召回不可用: " + userId);
        }
        List<String> all = store.friendsOf(userId);
        int from = Math.min(cursor * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return all.subList(from, to);
    }

    @Override
    public Map<String, Long> batchFollowerCount(List<String> userIds) {
        store.failIfDown(SocialDependency.FOLLOWER_COUNT);
        return store.followerCounts(userIds);
    }

    @Override
    public Map<String, String> batchAccountStatus(List<String> userIds) {
        store.failIfDown(SocialDependency.ACCOUNT_STATUS);
        return store.accountStatuses(userIds);
    }

    @Override
    public Map<String, String> batchUserRole(List<String> userIds) {
        store.failIfDown(SocialDependency.USER_ROLE);
        return store.userRoles(userIds);
    }

    @Override
    public Set<String> batchSharedToday(String sponsorId, List<String> userIds) {
        store.failIfDown(SocialDependency.SHARE_FREQUENCY);
        return store.sharedToday(sponsorId, userIds);
    }

    @Override
    public Set<String> batchBlocked(String sponsorId, List<String> userIds) {
        store.failIfDown(SocialDependency.BLOCK_RELATION);
        return store.blocked(sponsorId, userIds);
    }

    @Override
    public Set<String> batchInExperiment(String activityId, List<String> userIds) {
        store.failIfDown(SocialDependency.EXPERIMENT);
        return store.inExperiment(activityId, userIds);
    }

    @Override
    public Set<String> batchInCrowd(String activityId, List<String> userIds) {
        store.failIfDown(SocialDependency.CROWD);
        return store.inCrowd(activityId, userIds);
    }
}
