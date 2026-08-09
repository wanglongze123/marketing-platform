package com.mp.fission.filter;

import com.mp.fission.repository.FissionRelationMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 关系过滤的<b>下推实现</b>（§7.1 优化后的形态），默认启用。
 *
 * <p>整页一次 {@code IN} 查询，配合 {@code idx_group_follower_status} 转为 N 次 index seek：
 *
 * <ul>
 *   <li>SQL 次数 {@code page × N} → {@code page × 1}
 *   <li>单次扫描行数 O(师傅全量关系 R) → O(命中数 h)
 * </ul>
 *
 * <p><b>SQL 次数不与 {@code page} 解耦</b>：候选集本身是分页拉取的，每页仍要执行一次下推查询。 收益来自消除「页内逐用户查询」，而非消除分页（§7.1 的注）。
 */
@Component
@ConditionalOnProperty(
        name = "mp.fission.filter.pushdown",
        havingValue = "true",
        matchIfMissing = true)
public class PushdownRelationFilter implements RelationFilter {

    private final FissionRelationMapper relationMapper;

    public PushdownRelationFilter(FissionRelationMapper relationMapper) {
        this.relationMapper = relationMapper;
    }

    @Override
    public Set<String> findWithActiveRelation(String groupId, List<String> candidates) {
        if (candidates.isEmpty()) {
            // 空列表不能进 IN：MySQL 的 IN () 是语法错误，而空候选页在末页是常态
            return Set.of();
        }
        return new LinkedHashSet<>(relationMapper.selectActiveFollowerIdsIn(groupId, candidates));
    }

    @Override
    public String implName() {
        return "pushdown";
    }
}
