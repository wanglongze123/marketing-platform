package com.mp.benefit.lock;

import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * L2 分布式锁（技术方案 §6.3）。
 *
 * <p><b>锁是性能优化，不是正确性保证。</b> 这一条决定了本类的每一个设计：
 *
 * <ul>
 *   <li>抢不到锁不阻塞等待，直接抛「处理中」交由上游重试 —— 锁等待会放大 RT
 *   <li>显式传 {@code leaseTime} 即<b>禁用看门狗自动续租</b>。发钱场景不该依赖无限续租：实例假死时 锁若永不释放，业务将永久阻塞
 *   <li>{@code unlock} 前判 {@code isHeldByCurrentThread()} —— 锁若已因超时被他人获取，直接解锁会抛 {@code
 *       IllegalMonitorStateException}，把业务本已成功的请求变成失败响应
 * </ul>
 *
 * <p><b>锁失效时正确性由 L3 兜底</b>：唯一索引与条件更新不会因超时/宕机/时钟漂移而失效，锁会。 故任何写操作都必须自带 L3，绝不能因为「这里加了锁」就省掉唯一约束 —— 那正是
 * §6.1 反复强调的 「L2 减少走到 L3 的冲突，L3 才是兜底」。
 *
 * <p>本类可整体禁用（{@code mp.lock.enabled=false}），用于退出标准第 15 条的<b>去锁对照组</b>： 断言正确性结果两组完全一致，只有 DB
 * 冲突指标不同。若去掉锁正确性就变了，说明有地方错把锁 当成了正确性依据。
 */
@Component
public class BizLock {

    private static final Logger log = LoggerFactory.getLogger(BizLock.class);

    /**
     * 租约 10 秒。
     *
     * <p>须显著长于临界区（本项目的临界区是几个短事务，百毫秒级），又短到实例宕机后能较快释放。 与任务租约的 30 秒不同量级：那个要覆盖「一次 RPC + 两个事务」，这个只覆盖纯 DB
     * 操作。
     */
    private static final long LEASE_SECONDS = 10;

    private final RedissonClient redisson;
    private final boolean enabled;

    public BizLock(RedissonClient redisson, @Value("${mp.lock.enabled:true}") boolean enabled) {
        this.redisson = redisson;
        this.enabled = enabled;
        if (!enabled) {
            // 打 WARN 而非 INFO：这是对照组配置，误留到正式环境应当显眼
            log.warn("distributed lock DISABLED — 仅用于去锁对照组，正确性应由 L3 保证");
        }
    }

    /** 支付回调锁。键取 {@code tradeNo} —— 入参就有，不必先查库（技术方案 §6.3）。 */
    public <T> T aroundPayCallback(String tradeNo, Supplier<T> action) {
        return around("lock:ben:trade:" + tradeNo, action);
    }

    /** 建单锁。键取业务幂等键的四元组，与 {@code uk_idempotent} 同维度。 */
    public <T> T aroundCreateTrade(
            String userId,
            String activityId,
            String skuId,
            String clientReqNo,
            Supplier<T> action) {
        return around(
                "lock:ben:trade:" + userId + ":" + activityId + ":" + skuId + ":" + clientReqNo,
                action);
    }

    /** 关单锁。键取主单号。 */
    public <T> T aroundCloseOrder(String bizNo, Supplier<T> action) {
        return around("lock:ben:close:" + bizNo, action);
    }

    /**
     * 加锁执行。
     *
     * <p>禁用时直接执行 —— 这是对照组的入口，也是「锁失效」的最坏情况：若此时正确性就崩了， 说明 L3 没做到位。
     */
    private <T> T around(String key, Supplier<T> action) {
        if (!enabled) {
            return action.get();
        }

        RLock lock = redisson.getLock(key);
        boolean locked;
        try {
            // waitTime=0：不等待，抢不到立刻失败。等待会把并发压力转成 RT
            locked = lock.tryLock(0, LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断不判失败：请求可能已在别处推进，交由上游按未知态重试
            throw new BizException(ErrorCode.DOWNSTREAM_UNKNOWN, "获取锁被中断: " + key);
        }

        if (!locked) {
            // 不是错误：同一业务对象正在被处理。上游按「处理中」重试即可
            log.info("lock busy, key={}", key);
            throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "该业务正在处理中，请稍后重试");
        }

        try {
            return action.get();
        } finally {
            // 锁可能已因租约到期被他人持有，此时解锁会抛 IllegalMonitorStateException，
            // 把业务本已成功的请求变成失败响应
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                log.warn("lock expired before release, key={}", key);
            }
        }
    }
}
