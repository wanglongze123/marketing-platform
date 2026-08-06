package com.mp.benefit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.benefit.entity.PlayBizRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 主单访问。
 *
 * <p><b>状态变更一律用条件更新，不用 updateById</b>：{@code WHERE} 带前置状态是幂等三道闸的第三道， 也是乱序回调的拦截点。{@code
 * affected_rows=0} 即拒绝，不抛异常不重试。
 *
 * <p>禁止「先 SELECT 判状态再 UPDATE」—— 两条语句之间存在并发窗口。
 */
@Mapper
public interface PlayBizRecordMapper extends BaseMapper<PlayBizRecord> {

    /** 支付态推进。谓词限定前置状态，乱序到达的迟到通知在此被拒。 */
    @Update(
            "UPDATE play_biz_record SET pay_status = #{toStatus}, pay_amount = #{payAmount},"
                    + " trade_no = COALESCE(trade_no, #{tradeNo})"
                    + " WHERE play_biz_record_no = #{bizNo} AND pay_status = #{fromStatus}")
    int advancePayStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("payAmount") long payAmount,
            @Param("tradeNo") String tradeNo);

    /**
     * 从「可重新发起」的状态推进到 {@code GRANTING}。
     *
     * <p>入边有两条：{@code NOT_START}（首次履约）与 {@code GRANT_UNKNOWN}（查单判定原调用未到达后 的重发）。只认前者会让重发的 GRANT
     * 任务推不动状态 —— 条件更新 {@code affected_rows=0}， 主单永远停在 {@code GRANT_UNKNOWN}，且不报错。
     *
     * <p>{@code GRANT_SUCCESS} / {@code GRANT_FAILED} 不在入边内：终态不可重新发起。
     */
    @Update(
            "UPDATE play_biz_record SET grant_status = 'GRANTING'"
                    + " WHERE play_biz_record_no = #{bizNo}"
                    + " AND grant_status IN ('NOT_START', 'GRANT_UNKNOWN')")
    int startGranting(@Param("bizNo") String bizNo);

    /** 发放态推进。 */
    @Update(
            "UPDATE play_biz_record SET grant_status = #{toStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND grant_status = #{fromStatus}")
    int advanceGrantStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /**
     * 库存处置态推进，<b>库存类任务每单幂等的承重点</b>。
     *
     * <p>{@code affected_rows = 0} 即本单库存已处置过，调用方据此跳过实际的库存 UPDATE。
     *
     * <p>这道谓词不可由别处替代。库存 SQL 的下界 {@code WHERE locked >= ?} 防的是「总数被减成负值」， 而 {@code locked} 是该 {@code
     * stock_key} 下所有订单<b>共享</b>的计数器 —— A 单重复释放时它因别的 订单占用仍大于 0，下界照常放行，结果 A 释放掉了 B
     * 的预占，可售余量凭空多一份，直接超卖。 {@code benefit_task.uk_biz_type_op} 也替代不了：它防的是重复<b>入队</b>，而非同一条任务被
     * 重复<b>执行</b>（租约过期被接管、调度器重跑都会）。
     */
    @Update(
            "UPDATE play_biz_record SET stock_status = #{toStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND stock_status = #{fromStatus}")
    int advanceStockStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** 回填支付单号。建单后第二个短事务，不改任何状态。 */
    @Update("UPDATE play_biz_record SET trade_no = #{tradeNo} WHERE play_biz_record_no = #{bizNo}")
    int fillTradeNo(@Param("bizNo") String bizNo, @Param("tradeNo") String tradeNo);
}
