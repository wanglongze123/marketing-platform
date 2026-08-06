package com.mp.benefit.task;

import com.mp.benefit.entity.BenefitTask;
import com.mp.benefit.repository.BenefitTaskMapper;
import java.util.ArrayList;
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
 * benefitTransactionManager} —— 四数据源下不带限定的 注解会取到别库的管理器（《分阶段方案》§5.6 ②）。
 *
 * <p>领取必须在一个事务内完成「挑一批 + 打租约」两条语句：行锁随事务提交释放，若分两个事务， 第一条提交后锁即释放，另一实例可在打租约之前挑到同一批。
 */
@Service
public class TaskClaimService {

    private final BenefitTaskMapper taskMapper;

    public TaskClaimService(BenefitTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 领一批待执行任务。
     *
     * @param owner 本实例标识，写入 {@code lease_owner}，后续写回以它作 fencing 条件
     */
    @Transactional(
            transactionManager = "benefitTransactionManager",
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public List<BenefitTask> claimPending(String owner, int limit, int leaseSeconds) {
        return claim(taskMapper.lockPendingIds(limit), owner, leaseSeconds);
    }

    /**
     * 回收租约已过期的僵尸任务。
     *
     * <p>与 {@link #claimPending} 拆成两个方法而非一个带参数的方法：调用频率不同，僵尸回收可低频 （如每 10 轮一次），合并后无法分别调频。
     */
    @Transactional(
            transactionManager = "benefitTransactionManager",
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public List<BenefitTask> claimExpired(String owner, int limit, int leaseSeconds) {
        return claim(taskMapper.lockExpiredIds(limit), owner, leaseSeconds);
    }

    private List<BenefitTask> claim(List<Long> ids, String owner, int leaseSeconds) {
        if (ids.isEmpty()) {
            return List.of();
        }
        taskMapper.claim(ids, owner, leaseSeconds);

        List<BenefitTask> claimed = new ArrayList<>(ids.size());
        for (Long id : ids) {
            claimed.add(taskMapper.selectByIdPlain(id));
        }
        return claimed;
    }
}
