package com.mp.fission.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.fission.entity.FissionGroup;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 裂变组访问。 */
@Mapper
public interface FissionGroupMapper extends BaseMapper<FissionGroup> {

    /**
     * 开轮。撞 {@code uk_activity_sponsor_round} 即并发进场已建过同一轮，调用方按幂等命中回查。
     *
     * <p>{@code expire_time} 由库内算出（{@code DATE_ADD(NOW(3), ...)}）而非应用侧传绝对时刻：与 {@code
     * benefit_task.next_time} 同一条约束 —— 写入与比较两端须出自同一时钟，否则单机时区 错配即整体偏移，多实例下是实例间漂移。
     */
    @Insert(
            "INSERT INTO fission_group (group_id, activity_id, sponsor_id, round_no, active_flag,"
                    + " status, progress, target_count, config_version, expire_time)"
                    + " VALUES (#{groupId}, #{activityId}, #{sponsorId}, #{roundNo}, 'ACTIVE',"
                    + " 'RUNNING', 0, #{targetCount}, #{configVersion},"
                    + " DATE_ADD(NOW(3), INTERVAL #{ttlSeconds} SECOND))")
    int insertRunning(
            @Param("groupId") String groupId,
            @Param("activityId") String activityId,
            @Param("sponsorId") String sponsorId,
            @Param("roundNo") int roundNo,
            @Param("targetCount") int targetCount,
            @Param("configVersion") int configVersion,
            @Param("ttlSeconds") long ttlSeconds);

    /**
     * 取该师傅在该活动下进行中的轮次。
     *
     * <p>「进行中」同时要求状态为 {@code RUNNING} 且未过期 —— 只判状态会让已到期但尚未被治理
     * 推进的轮次被当成可复用，师傅继续往一个已经结不了的轮次里拉人。过期判定用库时钟。
     */
    @Select(
            "SELECT * FROM fission_group"
                    + " WHERE activity_id = #{activityId} AND sponsor_id = #{sponsorId}"
                    + " AND status = 'RUNNING' AND expire_time > NOW(3)"
                    + " ORDER BY round_no DESC LIMIT 1")
    FissionGroup selectRunning(
            @Param("activityId") String activityId, @Param("sponsorId") String sponsorId);

    /** 该师徒在该活动下的最大轮次号，开新轮时 +1。无历史返回 null。 */
    @Select(
            "SELECT MAX(round_no) FROM fission_group"
                    + " WHERE activity_id = #{activityId} AND sponsor_id = #{sponsorId}")
    Integer selectMaxRound(
            @Param("activityId") String activityId, @Param("sponsorId") String sponsorId);

    @Select("SELECT * FROM fission_group WHERE group_id = #{groupId}")
    FissionGroup selectByGroupId(@Param("groupId") String groupId);

    /**
     * 该轮次是否尚未过期，<b>按库时钟判定</b>。
     *
     * <p>与 {@link #selectRunning} 的 {@code expire_time > NOW(3)} 是同一个谓词，只是按 {@code group_id} 索取 ——
     * 分享与加入手上已有 {@code groupId}，不必绕道 {@code (activityId, sponsorId)}。
     *
     * <p><b>不在 Java 侧比 {@code LocalDateTime.now()}</b>：{@code expire_time} 由库的 {@code
     * DATE_ADD(NOW(3), ...)} 算出，判定与生成取自同一个时钟才不会因应用与库的时差在边界抖动 —— 与《分阶段方案》§5.6 ⑦ 对 {@code next_time}
     * 的处置同源，那里的时差表现为「调度器一条任务也领不到」。
     */
    @Select(
            "SELECT COUNT(*) > 0 FROM fission_group"
                    + " WHERE group_id = #{groupId} AND expire_time > NOW(3)")
    boolean isUnexpired(@Param("groupId") String groupId);

    /**
     * 取占着 {@code active_flag} 的那一轮，<b>不看有效期</b>。
     *
     * <p>与 {@link #selectRunning} 的分工：本方法的判据与 {@code uk_activity_sponsor_active} 完全一致， 供撞键后回查使用 ——
     * 若改用带有效期过滤的那个，「撞了键却查不到」会让调用方误判为组号 碰撞并换号重试。
     */
    @Select(
            "SELECT * FROM fission_group"
                    + " WHERE activity_id = #{activityId} AND sponsor_id = #{sponsorId}"
                    + " AND active_flag = 'ACTIVE'")
    FissionGroup selectActive(
            @Param("activityId") String activityId, @Param("sponsorId") String sponsorId);

    /** 历史轮次，供轮次查询的「含历史」开关使用。 */
    @Select(
            "SELECT * FROM fission_group"
                    + " WHERE activity_id = #{activityId} AND sponsor_id = #{sponsorId}"
                    + " ORDER BY round_no DESC")
    List<FissionGroup> selectHistory(
            @Param("activityId") String activityId, @Param("sponsorId") String sponsorId);

    /**
     * 推进有效徒弟计数，条件更新。
     *
     * <p>{@code progress = progress + 1} 由 SQL 自增而非应用读出加一回写 —— 后者在并发下会用陈旧值 覆盖，两个徒弟同时完成只记一个。
     */
    @Update(
            "UPDATE fission_group SET progress = progress + 1"
                    + " WHERE group_id = #{groupId} AND status = 'RUNNING'")
    int incrementProgress(@Param("groupId") String groupId);

    /**
     * <b>唯一的终态写入口</b>：置终态并把 {@code active_flag} 释放为 {@code group_id}。
     *
     * <p>与 {@code fission_relation.terminate} 同构。三条终态路径（{@code DONE} / {@code EXPIRED} / {@code
     * CANCEL}）全部经由此方法 —— 漏掉任一条的释放，该师傅在该活动下<b>永远开不了下一轮</b>： {@code uk_activity_sponsor_active}
     * 仍被那条已终结的记录占着，而开轮前的「有无进行中轮次」 检查只看 {@code status='RUNNING'}，看不见这个冲突，表现为「开轮报唯一键冲突」而非业务提示。
     *
     * <p>条件更新带 {@code status = 'RUNNING'}：并发终结只成功一个。
     */
    @Update(
            "UPDATE fission_group SET status = #{toStatus}, active_flag = group_id"
                    + " WHERE group_id = #{groupId} AND status = #{fromStatus}")
    int terminate(
            @Param("groupId") String groupId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);
}
