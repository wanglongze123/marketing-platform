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
     * 按 {@code (groupId, followerId)} 取关系，<b>不看 {@code active_flag}</b>。
     *
     * <p>与 {@link #selectActive} 的分工：确权成功后关系进终态、{@code active_flag} 已释放，此时 {@code selectActive}
     * 查不到它。幂等命中要回查那条已终结的关系，用本方法。
     *
     * <p>同一对师徒在多轮之间可有多条历史关系，故取最新一条 —— 而「进行中至多一条」由唯一键 保证，不需要在这里再判。
     */
    @Select(
            "SELECT * FROM fission_relation"
                    + " WHERE group_id = #{groupId} AND follower_id = #{followerId}"
                    + " ORDER BY id DESC LIMIT 1")
    FissionRelation selectLatest(
            @Param("groupId") String groupId, @Param("followerId") String followerId);

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
     * 按 {@code (groupId, followerId)} 推进状态，条件更新。
     *
     * <p>建联用它而非先查后判：先查后判在并发下两个线程都读到 {@code INVITED}，各推进一次 —— 而条件更新让第二次命中 0 行（BR-F-13）。
     */
    @Update(
            "UPDATE fission_relation SET status = #{toStatus}"
                    + " WHERE group_id = #{groupId} AND follower_id = #{followerId}"
                    + " AND status = #{fromStatus} AND active_flag = 'ACTIVE'")
    int advanceStatusByGroupFollower(
            @Param("groupId") String groupId,
            @Param("followerId") String followerId,
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

    /**
     * 置发奖在途豁免（BR-F-26）：发奖期间过期治理须跳过这条关系。
     *
     * <p>窗口由库内算出（{@code DATE_ADD(NOW(3), ...)}），与治理判据 {@code granting_until < NOW(3)} 同一时钟 ——
     * 应用侧传绝对时刻会让两端时钟不一致时豁免要么永不失效、要么立即失效。
     *
     * <p>它<b>必须有过期时间而非永久标记</b>：发奖进程崩溃后标记若永不失效，这行关系就永生 —— 既不推进也不被治理。过期即允许接管并告警，对账第 13 项据此扫描。
     */
    @Update(
            "UPDATE fission_relation"
                    + " SET granting_until = DATE_ADD(NOW(3), INTERVAL #{windowSeconds} SECOND)"
                    + " WHERE relation_id = #{relationId}")
    int markGranting(
            @Param("relationId") String relationId, @Param("windowSeconds") long windowSeconds);

    /** 清空发奖在途豁免。发奖已确定失败时用 —— 留着会让过期治理永久跳过这条关系。 */
    @Update("UPDATE fission_relation SET granting_until = NULL WHERE relation_id = #{relationId}")
    int clearGranting(@Param("relationId") String relationId);

    /** 该组的关系列表，游标分页走 {@code idx_group_status_id}。 */
    @Select(
            "SELECT * FROM fission_relation WHERE group_id = #{groupId}"
                    + " AND id > #{cursor} ORDER BY id LIMIT #{limit}")
    List<FissionRelation> selectByGroupAfter(
            @Param("groupId") String groupId,
            @Param("cursor") long cursor,
            @Param("limit") int limit);

    /**
     * 好友过滤的关系下推（§7.1 的优化对象、BR-F-10）。
     *
     * <p><b>整页一次 {@code IN} 查询，替代「页内逐用户各查一次」</b>。收益是两笔账：
     *
     * <ul>
     *   <li>批量化把 SQL 次数从 {@code page × N} 降到 {@code page × 1}（N 为每页人数，≈200）
     *   <li>下推 + 正确索引把单次扫描行数从 O(师傅全量关系 R) 降到 O(命中数 h)
     * </ul>
     *
     * <p><b>索引必须是 {@code idx_group_follower_status (group_id, follower_id, status)}</b>。若走 {@code
     * (group_id, status)}，{@code follower_id} 只能在回表后逐行过滤，扫描行数仍等于该师傅当前 轮次的全部进行中关系（千级）—— 下推就没有意义。换索引后
     * {@code IN} 列表转为 N 次 index seek， 扫描行数等于命中行数。
     *
     * <p><b>只返回 {@code follower_id} 而非整行</b>：过滤只需要「这个人有没有进行中关系」这一个 布尔值。取整行会让覆盖索引失效（{@code status}
     * 之外的列要回表），把刚省下的扫描成本还回去。
     *
     * <p>{@code IN} 列表长度受候选页大小约束（N ≤ 200），不会退化为大 {@code IN}。
     */
    @Select({
        "<script>",
        "SELECT follower_id FROM fission_relation",
        " WHERE group_id = #{groupId}",
        " AND follower_id IN <foreach item='id' collection='followerIds' open='(' separator=','"
                + " close=')'>#{id}</foreach>",
        " AND status IN ('INVITED', 'CONNECTED', 'JOINED')",
        "</script>"
    })
    List<String> selectActiveFollowerIdsIn(
            @Param("groupId") String groupId, @Param("followerIds") List<String> followerIds);

    /**
     * 单个用户是否有进行中关系。<b>基线实现专用，主链路不调用</b>。
     *
     * <p>它就是 §7.1 记的病根形态 —— 候选页内逐用户各查一次。保留它是因为退出标准第 2 条要 before/after
     * 三组对照数据，而<b>「基线」若只靠口算或引用文档公式，那不是实测</b>。
     *
     * <p><b>不额外劣化</b>：这条 SQL 与下推版走同一张表、同一批谓词，差别只在「一次问一个人」 还是「一次问一页人」。基线写成一个刻意很慢的实现，对照数据就没有意义了。
     */
    @Select(
            "SELECT COUNT(*) FROM fission_relation"
                    + " WHERE group_id = #{groupId} AND follower_id = #{followerId}"
                    + " AND status IN ('INVITED', 'CONNECTED', 'JOINED')")
    int countActiveRelation(
            @Param("groupId") String groupId, @Param("followerId") String followerId);

    /**
     * 过期治理的批量语句（FR-F09）：分片 + 排除在途 + 释放唯一性，<b>三者缺一不可</b>。
     *
     * <p><b>释放 {@code active_flag} 是这条语句里最容易漏的一半</b>：只置 {@code status='EXPIRED'} 的话，该行 仍占着 {@code
     * (group_id, follower_id, 'ACTIVE')}，该师徒下一轮分享插入时唯一键冲突 —— 而分享
     * 的「先插后判」把冲突当幂等命中，静默返回那条已过期的关系。用户点了分享看似成功，实际什么 也没发生（技术方案 §3.3）。
     *
     * <p><b>{@code granting_until} 的两个分支各自必要</b>：{@code IS NULL} 表示不在发奖流程（BR-F-26 的正面）； {@code <
     * NOW(3)} 是超时兜底 —— 发奖进程崩溃后标记若永不失效，这行关系既不推进也不被治理， 永生（技术方案 §3.3）。<b>只写前一个分支即为漏兜底</b>，且它不会有任何用例变红
     * —— 崩溃场景 本就少见，而缺失的表现是「几行数据一直在」，不是报错。
     *
     * <p><b>不加 {@code ORDER BY}</b>：{@code expire_time <} 是范围条件，其后的 {@code id} 已无法用于索引 排序，加了只引入
     * filesort，而批量过期不需要有序。
     *
     * <p><b>不用游标</b>：{@code UPDATE} 不返回被更新行的 {@code id}，游标无从推进；而更新后这些行的 {@code status} 已变为 {@code
     * EXPIRED}，自动离开 {@code WHERE} 集合，循环 {@code LIMIT} 到 {@code affected_rows < limit} 即可，天然不会重复扫描。
     *
     * <p>{@code NOW(3)} 取库时钟：与写入 {@code expire_time} 的那一端同源。应用侧传时刻则单机时区错配 即整体偏移，多实例下是实例间漂移。
     */
    @Update(
            "UPDATE fission_relation SET status = 'EXPIRED', active_flag = relation_id"
                    + " WHERE status IN ('INVITED', 'CONNECTED', 'JOINED')"
                    + " AND expire_time < NOW(3)"
                    + " AND (granting_until IS NULL OR granting_until < NOW(3))"
                    + " AND id BETWEEN #{fromId} AND #{toId}"
                    + " LIMIT #{limit}")
    int expireBatch(
            @Param("fromId") long fromId, @Param("toId") long toId, @Param("limit") int limit);

    /**
     * 全表 {@code id} 边界，供分片区间计算。
     *
     * <p>两个聚合放一条 SQL：分两次查会在两次之间插入新行，算出的区间以旧 {@code MAX} 为界而 {@code MIN} 已是新值 ——
     * 虽然末片上界取的是无穷大、不会漏，但两个数出自不同时刻这件事本身 会让分片划分不可复现，排查时对不上账。
     *
     * <p>空表返回一行两个 {@code NULL}，故用包装类型接。
     */
    @Select("SELECT MIN(id) AS minId, MAX(id) AS maxId FROM fission_relation")
    IdRange selectIdRange();

    /** {@link #selectIdRange} 的返回。空表时两字段均为 {@code null}。 */
    class IdRange {
        private Long minId;
        private Long maxId;

        public Long getMinId() {
            return minId;
        }

        public void setMinId(Long minId) {
            this.minId = minId;
        }

        public Long getMaxId() {
            return maxId;
        }

        public void setMaxId(Long maxId) {
            this.maxId = maxId;
        }

        /** 空表：没有任何行可治理。 */
        public boolean isEmpty() {
            return minId == null || maxId == null;
        }
    }
}
