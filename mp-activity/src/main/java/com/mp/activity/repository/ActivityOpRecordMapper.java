package com.mp.activity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动操作记录访问。
 *
 * <p>两道唯一索引各挡一类重入：{@code uk_idempotent} 挡「同一个幂等键发两次」（重传、重试）， {@code uk_biz_op}
 * 挡「同一业务语义生成了两个不同幂等键」（运营连点两次发布按钮）。
 */
@Mapper
public interface ActivityOpRecordMapper {

    /** 落一条操作记录。撞唯一键由调用方按幂等命中处置，不在此吞掉。 */
    @Insert(
            "INSERT INTO activity_op_record (op_no, idempotent_key, activity_id, op_type, op_seq,"
                    + " status, from_status, to_status, version_no, operator, reason)"
                    + " VALUES (#{opNo}, #{idempotentKey}, #{activityId}, #{opType}, #{opSeq},"
                    + " #{status}, #{fromStatus}, #{toStatus}, #{versionNo}, #{operator},"
                    + " #{reason})")
    int insert(
            @Param("opNo") String opNo,
            @Param("idempotentKey") String idempotentKey,
            @Param("activityId") String activityId,
            @Param("opType") String opType,
            @Param("opSeq") String opSeq,
            @Param("status") String status,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("versionNo") Integer versionNo,
            @Param("operator") String operator,
            @Param("reason") String reason);

    /** 按幂等键回查，用于区分「幂等命中」与「单号碰撞」。 */
    @Select("SELECT activity_id FROM activity_op_record WHERE idempotent_key = #{idempotentKey}")
    String selectActivityIdByIdempotentKey(@Param("idempotentKey") String idempotentKey);

    /** 某活动某类操作的记录数，测试与审计用。 */
    @Select(
            "SELECT COUNT(*) FROM activity_op_record"
                    + " WHERE activity_id = #{activityId} AND op_type = #{opType}")
    int countByType(@Param("activityId") String activityId, @Param("opType") String opType);
}
