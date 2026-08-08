package com.mp.fission.service;

import com.mp.fission.config.FissionTx;
import com.mp.fission.repository.FissionGroupMapper;
import com.mp.fission.repository.FissionRelationMapper;
import com.mp.fission.repository.FissionRelationMapper.IdRange;
import com.mp.fission.task.ExpireShard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 关系与轮次过期治理（FR-F09）。
 *
 * <p><b>两张表一起治理，不能只做关系</b>：关系过期不释放 {@code active_flag} 会让该师徒下一轮分享 被静默吞掉；轮次过期不释放则该师傅<b>永远开不了下一轮</b>
 * —— 后者是《分阶段方案》§6.6 在 PR-2 修正唯一键时连带确定的语义，落地点就在这里。
 *
 * <p><b>每个 {@code LIMIT} 批次一个独立事务，不是整轮一个大事务</b>。一轮可能更新数万行，单事务下：
 *
 * <ul>
 *   <li>回滚段与行锁持有到轮末，期间其他写路径（分享建关系、确权终结）在同一批行上排队
 *   <li>中途失败则整轮白做 —— 而过期治理天然幂等，已推进的行下一轮自动离开 {@code WHERE} 集合， 按批提交只会少做，不会做错
 * </ul>
 *
 * <p><b>循环上界不是「跑到 {@code affected_rows < limit}」一句话</b>：那是终止条件，但没有上界的循环
 * 在「有行持续到期」时不会结束（治理跑得比新到期慢时）。故另设批次上限，超出即本轮结束， 剩余的下一轮继续 —— 任务的 {@code next_time} 会把它排在下一个周期。
 */
@Service
public class RelationExpireService {

    private static final Logger log = LoggerFactory.getLogger(RelationExpireService.class);

    /** 单批更新上限，与技术方案 §3.3 的 {@code LIMIT 500} 一致 */
    private static final int BATCH = 500;

    /** 单轮批次上限：500 × 200 = 10 万行。超出即本轮结束，剩余下轮继续 */
    private static final int MAX_ROUNDS = 200;

    private final FissionRelationMapper relationMapper;
    private final FissionGroupMapper groupMapper;
    private final ExpireTxService tx;

    /**
     * 分片总数。V3 单进程恒为 1，V4 多实例时改为从注册中心取实例数。
     *
     * <p><b>不因为「只有一个分片」就省掉 {@code id BETWEEN}</b>（《分阶段方案》§6.4 ②）：省了它，V4 加分片时这条 SQL
     * 要重写，而它是一条会扫百万行的批量语句，重写要重新验证执行计划。
     */
    private final int shardTotal;

    public RelationExpireService(
            FissionRelationMapper relationMapper,
            FissionGroupMapper groupMapper,
            ExpireTxService tx,
            @Value("${mp.fission.expire.shard-total:1}") int shardTotal) {
        this.relationMapper = relationMapper;
        this.groupMapper = groupMapper;
        this.tx = tx;
        this.shardTotal = shardTotal;
    }

    /** 本进程的分片总数，播种任务时按它生成 {@code biz_no}。 */
    public int shardTotal() {
        return shardTotal;
    }

    /**
     * 治理一个分片，返回本次推进到 {@code EXPIRED} 的总行数（关系 + 轮次）。
     *
     * <p>关系先于轮次：一个已过期的轮次下必然有一批已过期的关系（关系有效期取轮次有效期，见 {@code relationExpireOf}）。顺序对正确性无影响 ——
     * 两条语句的谓词互不相关，各自幂等 —— 但先关系后轮次让日志读起来与业务因果一致。
     */
    public int sweep(ExpireShard shard) {
        int relations = sweepRelations(shard);
        int groups = sweepGroups(shard);
        if (relations + groups > 0) {
            log.info(
                    "expire sweep done, shard={}, relations={}, groups={}",
                    shard.bizNo(),
                    relations,
                    groups);
        }
        return relations + groups;
    }

    private int sweepRelations(ExpireShard shard) {
        IdRange range = relationMapper.selectIdRange();
        if (range == null || range.isEmpty()) {
            return 0;
        }
        long from = shard.fromId(range.getMinId(), range.getMaxId());
        long to = shard.toId(range.getMinId(), range.getMaxId());

        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int rows = tx.expireRelationBatch(from, to, BATCH);
            total += rows;
            if (rows < BATCH) {
                return total;
            }
        }
        // 到达批次上限：本轮不再继续，剩余交给下一次调度。记 warn 而非静默返回 ——
        // 持续触顶说明到期速度超过治理速度，须加分片或调高频率，而这只有日志能说明
        log.warn(
                "relation expire hit round cap, shard={}, expired={}, more pending",
                shard.bizNo(),
                total);
        return total;
    }

    private int sweepGroups(ExpireShard shard) {
        IdRange range = groupMapper.selectIdRange();
        if (range == null || range.isEmpty()) {
            return 0;
        }
        long from = shard.fromId(range.getMinId(), range.getMaxId());
        long to = shard.toId(range.getMinId(), range.getMaxId());

        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int rows = tx.expireGroupBatch(from, to, BATCH);
            total += rows;
            if (rows < BATCH) {
                return total;
            }
        }
        log.warn(
                "group expire hit round cap, shard={}, expired={}, more pending",
                shard.bizNo(),
                total);
        return total;
    }

    /**
     * 单批次的事务边界。
     *
     * <p><b>独立 bean 而非 {@link RelationExpireService} 的方法</b>：同类内部调用不经代理，{@code @FissionTx}
     * 不生效、不报错（V1 缺陷 ①）。循环体在外、事务在内，正是最容易踩这个坑的形状 —— 把注解 写在 {@code sweep} 上，整轮变成一个大事务；写在被同类调用的 {@code
     * expireBatch} 上，则一个 事务都没有，而两种写法都不报错。
     */
    @Service
    public static class ExpireTxService {

        private final FissionRelationMapper relationMapper;
        private final FissionGroupMapper groupMapper;

        public ExpireTxService(
                FissionRelationMapper relationMapper, FissionGroupMapper groupMapper) {
            this.relationMapper = relationMapper;
            this.groupMapper = groupMapper;
        }

        @FissionTx
        public int expireRelationBatch(long fromId, long toId, int limit) {
            return relationMapper.expireBatch(fromId, toId, limit);
        }

        @FissionTx
        public int expireGroupBatch(long fromId, long toId, int limit) {
            return groupMapper.expireBatch(fromId, toId, limit);
        }
    }
}
