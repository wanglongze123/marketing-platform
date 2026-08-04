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

    /** 发放态推进。 */
    @Update(
            "UPDATE play_biz_record SET grant_status = #{toStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND grant_status = #{fromStatus}")
    int advanceGrantStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** 回填支付单号。建单后第二个短事务，不改任何状态。 */
    @Update("UPDATE play_biz_record SET trade_no = #{tradeNo} WHERE play_biz_record_no = #{bizNo}")
    int fillTradeNo(@Param("bizNo") String bizNo, @Param("tradeNo") String tradeNo);
}
