package com.mp.fission.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.fission.entity.FissionRelation;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 裂变关系访问。
 *
 * <p><b>终态释放收敛到 {@link #terminate} 一个方法</b>（技术方案 §3.3 的工程约束）：禁止在各业务 分支散写 {@code UPDATE status=...}
 * —— 散落的写法迟早漏掉 {@code active_flag}，而漏掉 是静默失败：该行仍占着唯一键，该师徒下一轮分享被当成幂等命中，静默返回已终结的关系。
 */
@Mapper
public interface FissionRelationMapper extends BaseMapper<FissionRelation> {

    /** 非终态的 {@code active_flag} 取值。受 {@code uk_group_follower_active} 约束，同组同徒至多一条。 */
    String ACTIVE = "ACTIVE";

    /** 建关系，状态由调用方给定（分享建 {@code INVITED}，直接加入建 {@code JOINED}）。 */
    @Insert(
            "INSERT INTO fission_relation (relation_id, group_id, activity_id, sponsor_id,"
                    + " follower_id, out_biz_no, active_flag, status, share_method, expire_time)"
                    + " VALUES (#{relationId}, #{groupId}, #{activityId}, #{sponsorId},"
                    + " #{followerId}, #{outBizNo}, 'ACTIVE', #{status}, #{shareMethod},"
                    + " #{expireTime})")
    int insertActive(
            @Param("relationId") String relationId,
            @Param("groupId") String groupId,
            @Param("activityId") String activityId,
            @Param("sponsorId") String sponsorId,
            @Param("followerId") String followerId,
            @Param("outBizNo") String outBizNo,
            @Param("status") String status,
            @Param("shareMethod") String shareMethod,
            @Param("expireTime") String expireTime);

    /** 取该组该徒弟的进行中关系。{@code uk_group_follower_active} 保证至多一条。 */
    @Select(
            "SELECT * FROM fission_relation"
                    + " WHERE group_id = #{groupId} AND follower_id = #{followerId}"
                    + " AND active_flag = 'ACTIVE'")
    FissionRelation selectActive(
            @Param("groupId") String groupId, @Param("followerId") String followerId);

    @Select("SELECT * FROM fission_relation WHERE relation_id = #{relationId}")
    FissionRelation selectByRelationId(@Param("relationId") String relationId);

    /**
     * 非终态之间的推进，条件更新（《开发规范》§7.1）。
     *
     * <p>{@code WHERE status = #{fromStatus}} 使重复推进 {@code affected_rows = 0}，天然幂等 （BR-F-17）。<b>不更新
     * {@code active_flag}</b> —— 非终态之间流转，它恒为 {@code ACTIVE}。
     */
    @Update(
            "UPDATE fission_relation SET status = #{toStatus}"
                    + " WHERE relation_id = #{relationId} AND status = #{fromStatus}")
    int advanceStatus(
            @Param("relationId") String relationId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /**
     * {@code INVITED → CONNECTED/JOINED} 时回填上游业务号。
     *
     * <p>{@code AND out_biz_no = ''} 使重复回填不覆盖已有值：上游用两个不同业务号并发调用时， 只有第一个落进去 —— 与「{@code out_biz_no}
     * 不进唯一键」是同一个不变量的两半。
     */
    @Update(
            "UPDATE fission_relation SET out_biz_no = #{outBizNo}, status = #{toStatus}"
                    + " WHERE group_id = #{groupId} AND follower_id = #{followerId}"
                    + " AND status = #{fromStatus} AND out_biz_no = ''")
    int fillOutBizNoAndAdvance(
            @Param("groupId") String groupId,
            @Param("followerId") String followerId,
            @Param("outBizNo") String outBizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /**
     * <b>唯一的终态写入口</b>：置终态的同时把 {@code active_flag} 释放为 {@code relation_id}。
     *
     * <p>三条终态路径（{@code DONE} / {@code EXPIRED} / {@code CANCEL}）全部经由此方法，不在业务 分支各写一条 UPDATE。列到列赋值
     * {@code active_flag = relation_id} 天然唯一，等于把这行 移出 {@code uk_group_follower_active} 的约束范围。
     *
     * <p>同时清空 {@code granting_until}：关系已终结，发奖在途豁免不再有意义，留着会让对账第 13 项 （在途标志超时）扫出一批永远收敛不了的行。
     *
     * <p>条件更新带 {@code status = #{fromStatus}}：并发终结只成功一个。
     */
    @Update(
            "UPDATE fission_relation SET status = #{toStatus}, active_flag = relation_id,"
                    + " granting_until = NULL"
                    + " WHERE relation_id = #{relationId} AND status = #{fromStatus}")
    int terminate(
            @Param("relationId") String relationId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** 该组的关系列表，游标分页走 {@code idx_group_status_id}。 */
    @Select(
            "SELECT * FROM fission_relation WHERE group_id = #{groupId}"
                    + " AND id > #{cursor} ORDER BY id LIMIT #{limit}")
    List<FissionRelation> selectByGroupAfter(
            @Param("groupId") String groupId,
            @Param("cursor") long cursor,
            @Param("limit") int limit);
}
