package com.mp.benefit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.benefit.entity.PlayOpRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlayOpRecordMapper extends BaseMapper<PlayOpRecord> {

    /**
     * 幂等插入：命中 {@code uk_biz_op} 时累加重试次数而非抛异常。
     *
     * <p>重入是预期路径（重复回调、V2 任务重试），不是错误。写成普通 insert 会让第二次调用 抛 DuplicateKeyException 中断链路 —— 而它本该正常返回。
     */
    @Update(
            "INSERT INTO play_op_record (op_no, idempotent_key, play_biz_record_no, subject_id,"
                    + " activity_id, op_type, op_seq, status, retry_count) VALUES (#{opNo},"
                    + " #{idempotentKey}, #{bizNo}, #{subjectId}, #{activityId}, #{opType}, #{opSeq},"
                    + " #{status}, 0) ON DUPLICATE KEY UPDATE retry_count = retry_count + 1")
    int upsert(
            @Param("opNo") String opNo,
            @Param("idempotentKey") String idempotentKey,
            @Param("bizNo") String bizNo,
            @Param("subjectId") String subjectId,
            @Param("activityId") String activityId,
            @Param("opType") String opType,
            @Param("opSeq") String opSeq,
            @Param("status") String status);

    /**
     * 按幂等键回查，用于区分「同一次请求的重传」与「另一次请求」。V3 PR-7 引入。
     *
     * <p><b>这两件事必须分开</b>：退款准入若只看主单 {@code refund_status} 是否已在退款中，客服换个 工单号再点一次会拿到「受理成功」——
     * 而那笔退款根本没发生。钱不会多退（后续闸挡得住）， 但调用方据此把工单标为已处理，用户的第二次诉求就此消失。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT op_no FROM play_op_record WHERE idempotent_key = #{idempotentKey}")
    String selectByIdempotentKey(@Param("idempotentKey") String idempotentKey);

    /**
     * 落一条人工处置审计记录（BR-C-27）。V3 PR-10。
     *
     * <p><b>{@code op_seq} 取外部工单号，不取空串</b>：人工处置对一单可发生多次（重试发奖不成再重试一次）， 每次都要留痕。取空串会让第二次撞 {@code
     * uk_biz_op} 被当成重传吞掉 —— 而审计要能看到「处置过 几次、分别是谁」。
     *
     * <p><b>{@code operator} / {@code reason} 随记录一并落库</b>，不另开审计表：处置动作与它的操作人必须
     * 在同一行，分表则存在「动作落了而审计没落」的窗口，那正是审计最不能出现的缺口。
     *
     * <p>幂等：同一工单号重复提交命中 {@code uk_biz_op}，{@code retry_count} 自增而不新增行 —— 客服连点 不会产生两条审计，但重试次数看得见。
     */
    @Update(
            "INSERT INTO play_op_record (op_no, idempotent_key, play_biz_record_no, subject_id,"
                    + " activity_id, op_type, op_seq, status, operator, reason, retry_count)"
                    + " VALUES (#{opNo}, #{idempotentKey}, #{bizNo}, #{subjectId}, #{activityId},"
                    + " 'MANUAL_REPAIR', #{opSeq}, #{status}, #{operator}, #{reason}, 0)"
                    + " ON DUPLICATE KEY UPDATE retry_count = retry_count + 1,"
                    + " operator = VALUES(operator), reason = VALUES(reason)")
    int upsertManualRepair(
            @Param("opNo") String opNo,
            @Param("idempotentKey") String idempotentKey,
            @Param("bizNo") String bizNo,
            @Param("subjectId") String subjectId,
            @Param("activityId") String activityId,
            @Param("opSeq") String opSeq,
            @Param("status") String status,
            @Param("operator") String operator,
            @Param("reason") String reason);

    /** 某单某类操作的操作人，审计断言用。 */
    @org.apache.ibatis.annotations.Select(
            "SELECT operator FROM play_op_record WHERE play_biz_record_no = #{bizNo}"
                    + " AND op_type = #{opType} LIMIT 1")
    String selectOperator(@Param("bizNo") String bizNo, @Param("opType") String opType);

    /** 回写终态。查单类操作也走这里更新原记录，不新建行。 */
    @Update(
            "UPDATE play_op_record SET status = #{status}, downstream_result = #{downstreamResult},"
                    + " finish_time = NOW(3) WHERE play_biz_record_no = #{bizNo} AND op_type ="
                    + " #{opType} AND op_seq = #{opSeq}")
    int finish(
            @Param("bizNo") String bizNo,
            @Param("opType") String opType,
            @Param("opSeq") String opSeq,
            @Param("status") String status,
            @Param("downstreamResult") String downstreamResult);
}
