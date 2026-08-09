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

    /**
     * 支付态推进。谓词限定前置状态，乱序到达的迟到通知在此被拒。
     *
     * <p><b>不写 {@code pay_amount}</b>：本方法承接的是「交易未成立」的推进（{@code PAY_FAILED}）。
     * 把通知里的金额记成实付，会让一笔根本没付成的单带着全额 {@code pay_amount} —— 对账按 「已收款」的口径扫到它即是一笔虚假的账。实付金额只在确认收款时写入，见
     * {@link #advanceToPaySuccess}。
     */
    @Update(
            "UPDATE play_biz_record SET pay_status = #{toStatus},"
                    + " trade_no = COALESCE(trade_no, #{tradeNo})"
                    + " WHERE play_biz_record_no = #{bizNo} AND pay_status = #{fromStatus}")
    int advancePayStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("tradeNo") String tradeNo);

    /**
     * 支付成功推进，<b>入边含 {@code CLOSING}</b>（技术方案 §6.4）。
     *
     * <p>这是 V2 新增路径中唯一「拦截了反而资损」的一条：订单到期 → 关单 RPC 超时 → 置 {@code CLOSING} → 用户最后一秒付款成功 → 验签与金额校验都通过 →
     * 若谓词只认 {@code WAIT_PAY}， 条件更新命中 0 行、被当成乱序通知 ACK 丢弃。结果是<b>已收款、订单永停 {@code CLOSING}、
     * 履约任务不建、库存不转消耗</b>，且对账前十三项无一覆盖。
     *
     * <p>语义上：{@code CLOSING} 表示「关单结果未定」，而<b>支付成功通知是比查单更强的证据</b>，必须放行。 这是「UNKNOWN 不等于失败」在状态机层面的体现 ——
     * 中间态不能吞掉后到的确定结果。
     *
     * <p><b>{@code payAmount} 可为 {@code null}，此时不写实付金额</b>：查单收敛（{@code CLOSING → PAY_SUCCESS}）
     * 走的是这条边，而它手上没有任何支付通知 —— 支付方只回答了「这笔已收款」，没说收了多少。 以应付额填充等于替支付方猜一个数，实付≠应付时对账口径就是错的。留空等通知回填，
     * 「实付金额未知」本身是可表达的状态（{@code pay_amount} 可空），而一个猜来的数不是。
     */
    @Update(
            "UPDATE play_biz_record SET pay_status = 'PAY_SUCCESS',"
                    + " pay_amount = COALESCE(#{payAmount}, pay_amount),"
                    + " trade_no = COALESCE(trade_no, #{tradeNo})"
                    + " WHERE play_biz_record_no = #{bizNo}"
                    + " AND pay_status IN ('WAIT_PAY', 'CLOSING')")
    int advanceToPaySuccess(
            @Param("bizNo") String bizNo,
            @Param("payAmount") Long payAmount,
            @Param("tradeNo") String tradeNo);

    /**
     * 关单受理：{@code WAIT_PAY → CLOSING}。关单 RPC 结果未定时进此中间态。
     *
     * <p><b>此阶段不得释放库存与额度</b>：结果未定就释放，等于把额度让给别人，而钱可能已经收了。 待 {@code QUERY_CLOSE} 收敛到 {@code CLOSED} 或
     * {@code PAY_SUCCESS} 后，由确定的那一方落任务。
     */
    @Update(
            "UPDATE play_biz_record SET pay_status = 'CLOSING'"
                    + " WHERE play_biz_record_no = #{bizNo} AND pay_status = 'WAIT_PAY'")
    int advanceToClosing(@Param("bizNo") String bizNo);

    /**
     * 关单确认：{@code WAIT_PAY / CLOSING → CLOSED}。
     *
     * <p>入边含 {@code WAIT_PAY} 是因为关单 RPC 直接成功时无需经过中间态；含 {@code CLOSING} 是查单
     * 收敛的那条边。<b>已支付的单不在入边内</b>（BR-B-16）—— 谓词本身就是「已支付不可关」的实现。
     */
    @Update(
            "UPDATE play_biz_record SET pay_status = 'CLOSED'"
                    + " WHERE play_biz_record_no = #{bizNo}"
                    + " AND pay_status IN ('WAIT_PAY', 'CLOSING')")
    int advanceToClosed(@Param("bizNo") String bizNo);

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
     * 退款态推进，<b>重复退款三道闸的第一道</b>（技术方案 §5.6）。
     *
     * <p>三道闸各挡一类，{@code refundNo} 只是其中最弱的一道：
     *
     * <ol>
     *   <li><b>本谓词</b> —— 挡状态非法与并发：两个线程同时进入退款，只有一个能把 {@code NONE} 推到 {@code REVOKING}
     *   <li>{@code uk_biz_op(bizNo, 'CREATE_REFUND', '')} —— 挡「两个不同 {@code refundNo} 退两次」（客服连点）
     *   <li>{@code refundNo} 唯一 —— 挡与支付系统对接时的同键重传
     * </ol>
     *
     * <p><b>第二道是 {@code refundNo} 挡不住的那一类</b>：客服连点两次生成两个不同的退款请求号，两把 键都是新的，唯一索引不冲突 ——
     * 只有「一单至多一次退款操作」这条单据级约束拦得住。
     */
    @Update(
            "UPDATE play_biz_record SET refund_status = #{toStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND refund_status = #{fromStatus}")
    int advanceRefundStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /**
     * 退款态推进，允许多个前置态。
     *
     * <p>准入时前置为 {@code NONE}（首次）或 {@code REVOKE_FAILED}（回收失败后人工重试）—— 后者 必须允许，否则一笔回收失败的单永远退不了款，只能改库。
     */
    @Update({
        "<script>",
        "UPDATE play_biz_record SET refund_status = #{toStatus}",
        " WHERE play_biz_record_no = #{bizNo}",
        " AND refund_status IN <foreach item='s' collection='fromStatuses' open='('"
                + " separator=',' close=')'>#{s}</foreach>",
        "</script>"
    })
    int advanceRefundStatusFrom(
            @Param("bizNo") String bizNo,
            @Param("fromStatuses") java.util.List<String> fromStatuses,
            @Param("toStatus") String toStatus);

    /** 落退款单号。退款受理后回填，供对账与查单按号反查。 */
    @Update(
            "UPDATE play_biz_record SET refund_no = #{refundNo}"
                    + " WHERE play_biz_record_no = #{bizNo} AND refund_no IS NULL")
    int fillRefundNo(@Param("bizNo") String bizNo, @Param("refundNo") String refundNo);

    /**
     * 库存处置态推进，<b>库存类任务每单幂等的承重点</b>。
     *
     * <p>{@code affected_rows = 0} 即本单库存已处置过，调用方据此跳过实际的库存 UPDATE。
     *
     * <p>这道谓词不可由别处替代。库存 SQL 的下界 {@code WHERE locked >= ?} 防的是「总数被减成负值」， 而 {@code locked} 是该 {@code
     * stock_key} 下所有订单<b>共享</b>的计数器 —— A 单重复释放时它因别的 订单占用仍大于 0，下界照常放行，结果 A 释放掉了 B 的预占，可售余量多出一份，直接超卖。
     * {@code benefit_task.uk_biz_type_op} 也替代不了：它防的是重复<b>入队</b>，而非同一条任务被
     * 重复<b>执行</b>（租约过期被接管、调度器重跑都会）。
     */
    @Update(
            "UPDATE play_biz_record SET stock_status = #{toStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND stock_status = #{fromStatus}")
    int advanceStockStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /**
     * 限购额度处置态推进，<b>额度返还每单幂等的承重点</b>。
     *
     * <p>与 {@link #advanceStockStatus} 分成两个谓词而非共用一个，是因为<b>「有没有占用过」这件事 两者并不同步</b>：库存对每一单都预占，额度只在 SKU
     * 配了限购时才扣。共用一列则不限购的单会 以 {@code LOCKED} 进入释放分支，把不属于它的额度还掉。
     *
     * <p>下界 {@code WHERE used_qty >= qty} 替代不了这道闸，理由与库存的 {@code locked >= ?} 一字不差 —— 额度行按 {@code
     * (user, activity, sku, period)} 聚合，是该用户所有订单<b>共享</b>的 计数器，另一笔单占着时下界照常放行，结果是这一单还掉了那一单的额度。
     */
    @Update(
            "UPDATE play_biz_record SET quota_status = #{toStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND quota_status = #{fromStatus}")
    int advanceQuotaStatus(
            @Param("bizNo") String bizNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    /** 回填支付单号。建单后第二个短事务，不改任何状态。 */
    @Update("UPDATE play_biz_record SET trade_no = #{tradeNo} WHERE play_biz_record_no = #{bizNo}")
    int fillTradeNo(@Param("bizNo") String bizNo, @Param("tradeNo") String tradeNo);
}
