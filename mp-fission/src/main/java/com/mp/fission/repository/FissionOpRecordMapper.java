package com.mp.fission.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 裂变操作记录访问。
 *
 * <p>两道唯一索引各挡一类重入：{@code uk_idempotent} 挡「同一个幂等键发两次」（网络重传、任务 重试）；{@code uk_biz_op}
 * 挡「同一业务语义生成了两个不同幂等键」。
 */
@Mapper
public interface FissionOpRecordMapper {

    /**
     * 落一条操作记录。撞唯一键由调用方按幂等命中处置，不在此吞掉。
     *
     * <p>{@code idempotentKey} 取 {@code outFlowNo} —— 它标识「本次操作」；{@code outBizNo} 标识
     * 「一次师徒关系」，两者不天然相等（BR-F-14/15）。
     */
    @Insert(
            "INSERT INTO fission_op_record (op_no, idempotent_key, out_biz_no, activity_id,"
                    + " subject_id, op_type, op_seq, status, downstream_result)"
                    + " VALUES (#{opNo}, #{idempotentKey}, #{outBizNo}, #{activityId},"
                    + " #{subjectId}, #{opType}, #{opSeq}, #{status}, #{downstreamResult})")
    int insert(
            @Param("opNo") String opNo,
            @Param("idempotentKey") String idempotentKey,
            @Param("outBizNo") String outBizNo,
            @Param("activityId") String activityId,
            @Param("subjectId") String subjectId,
            @Param("opType") String opType,
            @Param("opSeq") String opSeq,
            @Param("status") String status,
            @Param("downstreamResult") String downstreamResult);

    /**
     * 回写终态：本地执行态与下游四分类结果<b>分列两栏</b>。
     *
     * <p>合并会让「下游 PROCESSING」被写成本地 UNKNOWN，而两者处置策略不同（§6.6）：前者长退避 等下游完成，后者短退避尽快查证。
     */
    @Update(
            "UPDATE fission_op_record SET status = #{status}, downstream_result = #{downstream},"
                    + " finish_time = NOW(3)"
                    + " WHERE idempotent_key = #{idempotentKey}")
    int finish(
            @Param("idempotentKey") String idempotentKey,
            @Param("status") String status,
            @Param("downstream") String downstream);

    /** 按幂等键取记录状态，收敛与测试用。 */
    @Select("SELECT status FROM fission_op_record WHERE idempotent_key = #{idempotentKey}")
    String selectStatusByIdempotentKey(@Param("idempotentKey") String idempotentKey);

    /** 按幂等键回查，用于区分「幂等命中」与「单号碰撞」。 */
    @Select("SELECT op_no FROM fission_op_record WHERE idempotent_key = #{idempotentKey}")
    String selectOpNoByIdempotentKey(@Param("idempotentKey") String idempotentKey);

    /** 某业务某类操作的记录数，测试与审计用。 */
    @Select(
            "SELECT COUNT(*) FROM fission_op_record"
                    + " WHERE out_biz_no = #{outBizNo} AND op_type = #{opType}")
    int countByType(@Param("outBizNo") String outBizNo, @Param("opType") String opType);
}
