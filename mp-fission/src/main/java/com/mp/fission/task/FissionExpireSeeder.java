package com.mp.fission.task;

import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import com.mp.fission.repository.FissionTaskMapper;
import com.mp.fission.service.RelationExpireService;
import org.springframework.stereotype.Component;

/**
 * {@code RELATION_EXPIRE} 任务的播种器：保证每个分片恒有一条存活的治理任务。
 *
 * <p><b>周期任务没有「谁来建它」的业务事件</b>，这是它与此前全部任务的根本差别 —— {@code GRANT} 由支付成功建、{@code SPONSOR_REWARD}
 * 由确权建，而治理任务的建立者只能是系统自己。
 *
 * <p><b>周期性完全由播种提供，任务自身不循环</b>：治理任务扫完一遍即 {@code SUCCESS} 置 {@code DONE}， 下一个播种周期把它复活。这正是 {@code
 * enqueue} 的 upsert 语义，无须任何额外机制：
 *
 * <ul>
 *   <li>任务在途（{@code PENDING} / {@code DOING}）→ 全部字段保持不变，{@code next_time} 不被重置 ——
 *       上一轮还没跑完时播种不会把它提前催起来
 *   <li>任务已 {@code DONE} / {@code DEAD} → 复活为 {@code PENDING} 且 {@code retry_count} 归零
 * </ul>
 *
 * <p>「复活终态行」这条 upsert 分支原本是为查单重发链路写的（V2），此处第二次用上它 —— 两者要的 是同一件事：{@code op_no}
 * 标识的操作可以被重新发起。故本类不需要新增任何 SQL。
 *
 * <p><b>播种周期即治理周期</b>，取 30 秒。它不与调度器同频：播种是一条 upsert，每轮调度都播一次 等于每秒一次无意义的写；而治理晚 30 秒开始没有后果 ——
 * 过期关系多存在半分钟不影响任何不变量 （唯一键的占用要到该师徒下一轮分享时才显形）。
 *
 * <p><b>定时触发拆到 {@link FissionExpireSeederTrigger}，本类自身不带 {@code @Scheduled}</b>：与调度器和 它的 trigger
 * 拆开是同一个理由，且此处更强 —— 集成测试要显式调 {@link #seed()} 再驱动调度器 （播种与执行分开触发，才能断言「播了但还没跑」），因此本类在测试中必须存在。若把
 * {@code @ConditionalOnProperty} 打在本类上，关掉定时器的测试连 bean 都注入不到。
 */
@Component
public class FissionExpireSeeder {

    private final FissionTaskMapper taskMapper;
    private final RelationExpireService expireService;

    /** 首轮播种的延迟秒数。取 0：治理任务无须等待，启动即可跑第一轮 */
    private static final long NO_DELAY = 0;

    public FissionExpireSeeder(FissionTaskMapper taskMapper, RelationExpireService expireService) {
        this.taskMapper = taskMapper;
        this.expireService = expireService;
    }

    /**
     * 为每个分片播一条任务。
     *
     * <p>{@code public} 供集成测试显式驱动 —— 与 {@code runOnce()} 同一个理由：靠真实定时触发去碰运气 既慢又不稳定。
     *
     * @return 播种覆盖的分片数
     */
    public int seed() {
        int total = expireService.shardTotal();
        for (int i = 0; i < total; i++) {
            ExpireShard shard = new ExpireShard(i, total);
            taskMapper.enqueue(
                    BizNoGenerator.fissionTaskNo(),
                    shard.bizNo(),
                    TaskType.RELATION_EXPIRE.name(),
                    IdempotentKeys.expireOpNo(shard.bizNo()),
                    NO_DELAY,
                    "{}");
        }
        return total;
    }
}
