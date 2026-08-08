package com.mp.fission.repository;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 裂变侧的对账扫描（技术方案 §6.8 第 7、10、12、13 项）。V3 PR-10。
 *
 * <p><b>与权益侧的 {@code ReconcileMapper} 分开，不合成一个</b>：两者查的是不同的库（{@code db_fission} 与 {@code
 * db_benefit}），各自的数据源与事务管理器也不同。合成一个 mapper 会让它同时绑两个 {@code SqlSessionFactory} —— 而按包路径绑定数据源正是 V2
 * 定下的隔离方式，绕过它等于把「禁止跨库」这条约束 从运行期拉回到约定层面。
 *
 * <p>每条扫描同样带时间下界：对账查的是「长期未推进」，不是「此刻还没推进」。
 */
@Mapper
public interface FissionReconcileMapper {

    /**
     * 第 7 项：徒弟已发奖但师傅返奖任务缺失。
     *
     * <p><b>判据是「任务在不在」，不是「师傅到账没有」</b>：师傅返奖走本地消息表异步，任务存在即链路 完整 ——
     * 它自己会重试到成功或死信。而任务不存在意味着<b>那次四写事务只成了一半</b>，师傅奖永久 漏发且无重试载体，这才是本项要捞的。
     *
     * <p>关系已 {@code DONE} 且无 {@code SPONSOR_REWARD} 任务即差异。
     */
    @Select(
            "SELECT r.relation_id FROM fission_relation r"
                    + " WHERE r.status = 'DONE'"
                    + " AND r.update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " AND NOT EXISTS (SELECT 1 FROM fission_task t"
                    + " WHERE t.biz_no = r.relation_id AND t.task_type = 'SPONSOR_REWARD')"
                    + " LIMIT #{limit}")
    List<String> scanSponsorRewardMissing(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 10 项：关系已完成但轮次进度未含它。
     *
     * <p>进度是 SQL 自增的计数器（{@code progress = progress + 1}），与关系行是两处写。四写事务保证了 两者同事务，故差异只可能来自人工改库或历史数据
     * —— 但<b>「不可能发生」不是不做对账的理由</b>： 进度决定轮次能否达标，它少一即师傅可能永远拿不到奖。
     *
     * <p>判据是「该组 {@code DONE} 的关系数 > 组进度」。取组维度而非关系维度：进度是组上的一个数，逐条 关系比对无从判断它少的是哪一条。
     */
    @Select(
            "SELECT g.group_id FROM fission_group g"
                    + " WHERE g.update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " AND (SELECT COUNT(*) FROM fission_relation r"
                    + " WHERE r.group_id = g.group_id AND r.status = 'DONE') > g.progress"
                    + " LIMIT #{limit}")
    List<String> scanProgressLag(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 12 项：发奖操作已成功但关系未推进到 {@code DONE}。
     *
     * <p>与第 7 项方向相反：那项是「关系已 {@code DONE} 而任务缺失」，本项是「发奖已成功而关系没动」。 两者对应四写事务的两种半途失败，各自要有一条对账。
     *
     * <p>处置是重放 {@code advanceAfterGrantConfirmed(opNo)} —— 该方法本身幂等（关系推进用条件更新 {@code WHERE
     * status='JOINED'}），重放不会重复落师傅返奖任务。
     */
    @Select(
            "SELECT o.out_biz_no FROM fission_op_record o"
                    + " JOIN fission_relation r ON r.out_biz_no = o.out_biz_no"
                    + " WHERE o.op_type = 'FOLLOWER_DONE' AND o.status = 'SUCCESS'"
                    + " AND r.status <> 'DONE'"
                    + " AND o.update_time < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)"
                    + " LIMIT #{limit}")
    List<String> scanGrantDoneRelationLag(
            @Param("staleSeconds") int staleSeconds, @Param("limit") int limit);

    /**
     * 第 13 项：发奖在途标志已超时。
     *
     * <p>{@code granting_until} 是过期治理的豁免窗口（§3.3）。超时意味着「发奖早该收敛而没有」—— 此时关系既不被治理接管（豁免还在字段上），也没人推进它。
     *
     * <p>处置是强制查单收敛；仍未定则告警并<b>允许治理接管</b>（清空该字段），否则这行会永久豁免。
     */
    @Select(
            "SELECT relation_id FROM fission_relation"
                    + " WHERE granting_until IS NOT NULL AND granting_until < NOW(3)"
                    + " LIMIT #{limit}")
    List<String> scanGrantingExpired(@Param("limit") int limit);

    /** 按关系号取其徒弟发奖幂等号所属的 {@code outBizNo}，供第 12 项重放定位。 */
    @Select("SELECT out_biz_no FROM fission_relation WHERE relation_id = #{relationId}")
    String selectOutBizNo(@Param("relationId") String relationId);
}
