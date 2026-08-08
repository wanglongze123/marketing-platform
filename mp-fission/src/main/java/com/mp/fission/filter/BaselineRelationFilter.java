package com.mp.fission.filter;

import com.mp.fission.repository.FissionRelationMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 关系过滤的<b>基线实现</b>（§7.1 优化前的形态），由 {@code mp.fission.filter.pushdown=false} 启用。
 *
 * <p><b>它存在的唯一理由是产出对照数据</b>：退出标准第 2 条要 SQL 次数、扫描行数、P99 三组 before/after
 * 对照。若只写优化后的实现，「基线」就只能靠口算或引用文档里的公式 —— 那不是实测 （《分阶段方案》§6.4 ①）。
 *
 * <p><b>它是技术方案 §7.1 写死的那个形态，照抄，不额外劣化</b>：逐用户各查一次，每次按 {@code (group_id, follower_id)}
 * 定位。刻意写慢会让对照数据失去意义 —— 那样量出来的是「我把 基线写得多差」，不是「优化带来多少收益」。
 *
 * <p>病根在<b>循环的最内层</b>：一次查询只问一个人，故 SQL 次数 = 候选人数。这与「查得快不快」 无关，是次数本身的问题。
 */
@Component
@ConditionalOnProperty(name = "mp.fission.filter.pushdown", havingValue = "false")
public class BaselineRelationFilter implements RelationFilter {

    private final FissionRelationMapper relationMapper;

    public BaselineRelationFilter(FissionRelationMapper relationMapper) {
        this.relationMapper = relationMapper;
    }

    @Override
    public Set<String> findWithActiveRelation(String groupId, List<String> candidates) {
        Set<String> hit = new LinkedHashSet<>();
        for (String followerId : candidates) {
            // ← 病根：一次 SQL 只问一个人。SQL 次数 = 候选人数
            if (relationMapper.countActiveRelation(groupId, followerId) > 0) {
                hit.add(followerId);
            }
        }
        return hit;
    }

    @Override
    public String implName() {
        return "baseline";
    }
}
