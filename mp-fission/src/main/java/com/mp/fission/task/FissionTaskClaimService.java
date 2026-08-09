package com.mp.fission.task;

import com.mp.fission.entity.FissionTask;
import com.mp.fission.repository.FissionTaskMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务领取，独立事务边界。
 *
 * <p><b>为什么显式声明 {@code READ_COMMITTED}</b>：MySQL 默认 RR 下范围扫描会加间隙锁，而 {@code SKIP LOCKED} 跳过的是行锁不是间隙锁
 * —— 领取事务仍可能阻塞新任务插入（技术方案 §7.3）。 本类是全仓唯一需要非默认隔离级别的地方。
 *
 * <p><b>为什么不用 {@code @BenefitTx}</b>：组合注解固化了传播与回滚行为但不含隔离级别，此处需要 覆盖 {@code isolation}。仍显式绑定 {@code
 * fissionTransactionManager} —— 四数据源下不带限定的 注解会取到别库的管理器（《分阶段方案》§5.6 ②）。
 *
 * <p>领取必须在一个事务内完成「挑一批 + 打租约」两条语句：行锁随事务提交释放，若分两个事务， 第一条提交后锁即释放，另一实例可在打租约之前挑到同一批。
 */
@Service
public class FissionTaskClaimService {

    private final FissionTaskMapper taskMapper;

    public FissionTaskClaimService(FissionTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 领一批待执行任务。
     *
     * @param owner 本实例标识，写入 {@code lease_owner}，后续写回以它作 fencing 条件
     */
    @Transactional(
            transactionManager = "fissionTransactionManager",
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public List<FissionTask> claimPending(String owner, int limit, int leaseSeconds) {
        return claim(taskMapper.lockPendingIds(limit), owner, leaseSeconds);
    }

    /**
     * 回收租约已过期的僵尸任务。
     *
     * <p>与 {@link #claimPending} 拆成两个方法而非一个带参数的方法：调用频率不同，僵尸回收可低频 （如每 10 轮一次），合并后无法分别调频。
     */
    @Transactional(
            transactionManager = "fissionTransactionManager",
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public List<FissionTask> claimExpired(String owner, int limit, int leaseSeconds) {
        return claim(taskMapper.lockExpiredIds(limit), owner, leaseSeconds);
    }

    private List<FissionTask> claim(List<Long> ids, String owner, int leaseSeconds) {
        if (ids.isEmpty()) {
            return List.of();
        }
        taskMapper.claim(ids, owner, leaseSeconds);
        // 批量读回，一批一条 SQL。逐个 selectById 会使每轮领取产生 BATCH 次往返
        return taskMapper.selectByIds(ids);
    }
}
