package com.mp.benefit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.benefit.entity.BenefitTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 可靠任务访问。
 *
 * <p><b>领取是两条语句，不是一条</b>：为绕开 MySQL 错误 1093（不能 UPDATE 又在子查询引用同表）， 很容易写成 {@code UPDATE ... WHERE id
 * IN (SELECT id FROM (SELECT ... FOR UPDATE SKIP LOCKED) t)}。本地在 MySQL 8.4.11 上
 * 实测该写法<b>不会重复领取，而是退化为互相阻塞</b>：A 持有 1、2 时 B 不是跳过而是等锁， 直至 {@code Lock wait timeout}。失效形态与技术方案 §7.3
 * 记的「锁子句失效导致重复领取」不同 —— 但两种形态都不可接受，且阻塞形态无法由并发用例区分（表现为变慢而非出错）。故保留两条语句 的写法，并由 {@code ShapeFreezeTest}
 * 静态禁止派生表形式。
 *
 * <p><b>全部写回携带 {@code lease_owner} 校验</b>：租约过期无法区分「持有者死了」与「持有者慢了」。 A 变慢、租约过期、B 正当接管并完成，随后 A
 * 苏醒把同一任务再写一遍终态 —— 不加 fencing 就拦不住。{@code affected_rows = 0} 即租约已易主，当前实例立即放弃且不重试本次写回（《分阶段方案》§5.6
 * ③）。
 */
@Mapper
public interface BenefitTaskMapper extends BaseMapper<BenefitTask> {

    /**
     * 幂等入队。命中 {@code uk_biz_type_op} 时按当前状态分流：在途（{@code PENDING} / {@code DOING}）保持不变， 终态（{@code
     * DONE} / {@code DEAD}）复活为 {@code PENDING}。
     *
     * <p>在途不覆盖：该任务正在等待执行或已被实例持有，重置 {@code next_time} 会使其提前到期，租约 与退避随之失真。
     *
     * <p>终态须复活，因为 {@code op_no} 标识的操作可被重新发起。查单重发链路依赖此行为：{@code QUERY_GRANT} 连续查无达阈值后落一条复用原 {@code
     * op_no} 的 {@code GRANT} 并将自身置 {@code DONE}；若重发仍未收敛，{@code finishGrant} 需再落 {@code
     * QUERY_GRANT}，此时命中的正是上一轮那条 {@code DONE} 行。不复活则该 insert 被静默丢弃，主单停在 {@code GRANT_UNKNOWN}
     * 且无查单任务存活。
     *
     * <p>复活时 {@code retry_count} 归零、租约清空：新一轮发起不继承上一轮的重试进度，否则首次执行 即可能因累计次数达标而直接进死信。
     *
     * <p>{@code next_time} 由 {@code NOW(3)} 加偏移在库内算出，入参为延迟秒数而非时刻。调度判据是 {@code next_time <=
     * NOW(3)}，两端须出自同一时钟；由应用传绝对时间则单机时区错配会导致整体偏移， 多实例下表现为实例间时钟漂移。
     *
     * <p>{@code status} 的赋值排在末位：MySQL 的 {@code ON DUPLICATE KEY UPDATE} 按书写顺序求值，后续
     * 表达式会读到已更新的值，故其余列必须先于它读取原 {@code status}。
     */
    @Update(
            "INSERT INTO benefit_task (task_no, biz_no, task_type, op_no, status, next_time,"
                    + " retry_count, payload) VALUES (#{taskNo}, #{bizNo}, #{taskType}, #{opNo},"
                    + " 'PENDING', DATE_ADD(NOW(3), INTERVAL #{delaySeconds} SECOND), 0,"
                    + " #{payload}) ON DUPLICATE KEY UPDATE"
                    + " next_time = IF(status IN ('DONE', 'DEAD'),"
                    + " DATE_ADD(NOW(3), INTERVAL #{delaySeconds} SECOND), next_time),"
                    + " retry_count = IF(status IN ('DONE', 'DEAD'), 0, retry_count),"
                    + " payload = IF(status IN ('DONE', 'DEAD'), #{payload}, payload),"
                    + " lease_owner = IF(status IN ('DONE', 'DEAD'), NULL, lease_owner),"
                    + " lease_expire = IF(status IN ('DONE', 'DEAD'), NULL, lease_expire),"
                    + " status = IF(status IN ('DONE', 'DEAD'), 'PENDING', status)")
    int enqueue(
            @Param("taskNo") String taskNo,
            @Param("bizNo") String bizNo,
            @Param("taskType") String taskType,
            @Param("opNo") String opNo,
            @Param("delaySeconds") long delaySeconds,
            @Param("payload") String payload);

    /**
     * 主扫描：待执行任务，走 {@code idx_sched(status, next_time)}。
     *
     * <p>必须在事务内调用，锁随事务提交释放。
     */
    @Select(
            "SELECT id FROM benefit_task WHERE status = 'PENDING' AND next_time <= NOW(3)"
                    + " ORDER BY next_time LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<Long> lockPendingIds(@Param("limit") int limit);

    /**
     * 兜底扫描：租约过期的僵尸任务，走 {@code idx_lease(status, lease_expire)}。
     *
     * <p>与主扫描<b>拆开而非用 OR 合并</b>：{@code WHERE (status='PENDING' AND next_time<=?) OR (status='DOING'
     * AND lease_expire<?)} 配合 {@code ORDER BY} 会退化为 index merge + filesort。
     *
     * <p>它与 {@code SKIP LOCKED} 解决的是两个不同问题：后者管同一轮扫描的并发互斥，对宕机实例 遗留的 {@code DOING} 行无能为力 —— 那些行不在
     * {@code PENDING} 集合里，也没有任何人持有其行锁。
     */
    @Select(
            "SELECT id FROM benefit_task WHERE status = 'DOING' AND lease_expire < NOW(3)"
                    + " ORDER BY lease_expire LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<Long> lockExpiredIds(@Param("limit") int limit);

    /** 按 id 打租约。与上面的 SELECT 同事务，故这批 id 已被本实例锁住。 */
    @Update({
        "<script>",
        "UPDATE benefit_task SET status = 'DOING', lease_owner = #{owner},",
        " lease_expire = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND)",
        " WHERE id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int claim(
            @Param("ids") List<Long> ids,
            @Param("owner") String owner,
            @Param("leaseSeconds") int leaseSeconds);

    /**
     * 按 id 批量读回。领取后取任务全量字段用，一批一条 SQL。
     *
     * <p>顺序由 {@code id} 保证而非依赖 {@code IN} 列表的书写次序：后者在 MySQL 中不构成排序约束。
     */
    @Select({
        "<script>",
        "SELECT * FROM benefit_task",
        " WHERE id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>",
        " ORDER BY id",
        "</script>"
    })
    List<BenefitTask> selectByIds(@Param("ids") List<Long> ids);

    /** 完成。fencing：租约已易主则 {@code affected_rows = 0}，放弃写回。 */
    @Update(
            "UPDATE benefit_task SET status = 'DONE', lease_owner = NULL, lease_expire = NULL"
                    + " WHERE id = #{id} AND status = 'DOING' AND lease_owner = #{owner}")
    int markDone(@Param("id") Long id, @Param("owner") String owner);

    /**
     * 失败重排：回 {@code PENDING} 并按退避后推。
     *
     * <p>{@code retry_count} 由 SQL 自增而非应用回写 —— 应用侧「读出来 +1 再写回」在接管场景下会 用陈旧值覆盖接管者的计数。
     */
    @Update(
            "UPDATE benefit_task SET status = 'PENDING', retry_count = retry_count + 1,"
                    + " next_time = DATE_ADD(NOW(3), INTERVAL #{backoffMicros} MICROSECOND),"
                    + " lease_owner = NULL, lease_expire = NULL"
                    + " WHERE id = #{id} AND status = 'DOING' AND lease_owner = #{owner}")
    int markRetry(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("backoffMicros") long backoffMicros);

    /**
     * 归还租约但不计重试：回 {@code PENDING} 并按退避后推，{@code retry_count} 保持不变。
     *
     * <p>用于「任务类型尚无处理器」这一分支。该情形不是任务执行失败，而是本实例没有能力处理它 —— 计入重试会使 {@code retry_count}
     * 在等待处理器接入期间无上限累积，待处理器真正注册后， 首次返回非终态即达阈值直接进死信。
     *
     * <p>仍带 fencing 与退避：租约必须还回去，否则任务留在 {@code DOING} 直到僵尸回收才被捞回； 退避则避免无处理器的任务在每一轮调度中被反复领取。
     */
    @Update(
            "UPDATE benefit_task SET status = 'PENDING',"
                    + " next_time = DATE_ADD(NOW(3), INTERVAL #{backoffMicros} MICROSECOND),"
                    + " lease_owner = NULL, lease_expire = NULL"
                    + " WHERE id = #{id} AND status = 'DOING' AND lease_owner = #{owner}")
    int releaseWithoutRetry(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("backoffMicros") long backoffMicros);

    /** 死信。超过阈值停止重试，等人工处置 —— 不静默丢弃。 */
    @Update(
            "UPDATE benefit_task SET status = 'DEAD', retry_count = retry_count + 1,"
                    + " lease_owner = NULL, lease_expire = NULL"
                    + " WHERE id = #{id} AND status = 'DOING' AND lease_owner = #{owner}")
    int markDead(@Param("id") Long id, @Param("owner") String owner);

    /** 续租。长耗时任务执行中延长租约，同样受 fencing 约束。 */
    @Update(
            "UPDATE benefit_task SET lease_expire = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds}"
                    + " SECOND) WHERE id = #{id} AND status = 'DOING' AND lease_owner = #{owner}")
    int renewLease(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("leaseSeconds") int leaseSeconds);

    /**
     * 记录连续查无次数，写入 {@code payload}。
     *
     * <p><b>不能复用 {@code retry_count}</b>：后者由调度器对所有非终态结果自增，{@code PROCESSING} 也算 在内 —— 于是「连续查无 3
     * 次」会退化成「查询 3 次且最后一次查无」。下游回过 {@code PROCESSING} 即表明它已受理，此后的查无更可能是查询侧抖动，此时重发是对一笔已受理的请求再发一次。
     *
     * <p>存 {@code payload} 而非内存：随任务持久化，实例重启或任务被接管都不丢。
     */
    @Update(
            "UPDATE benefit_task SET payload = JSON_SET(COALESCE(payload, '{}'), '$.missStreak',"
                    + " #{missStreak}) WHERE id = #{id}")
    int setMissStreak(@Param("id") Long id, @Param("missStreak") int missStreak);

    /**
     * 该业务号下尚在途的查单任务数，供 {@code GRANT} 任务判断职责是否已移交。
     *
     * <p>只算 {@code PENDING} / {@code DOING}：{@code DONE} 表示上一轮查单已收敛完毕，{@code DEAD} 表示查单自己也放弃了 ——
     * 两者都不构成「另有通路正在收敛」，此时 {@code GRANT} 任务仍须自行重试。
     */
    @Select(
            "SELECT COUNT(*) FROM benefit_task WHERE biz_no = #{bizNo}"
                    + " AND task_type = 'QUERY_GRANT' AND status IN ('PENDING', 'DOING')")
    int countOpenQueryGrant(@Param("bizNo") String bizNo);

    /** 可观测端点用：按业务号取全部任务快照。 */
    @Select("SELECT * FROM benefit_task WHERE biz_no = #{bizNo} ORDER BY task_type, op_no")
    List<BenefitTask> selectByBizNo(@Param("bizNo") String bizNo);
}
