package com.mp.mock.fault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * mock 支付方的账本：这笔支付单到底是什么状态。
 *
 * <p><b>关单必须基于支付方自己的状态，而不是平台传过来的状态。</b> 与 PR-3 的 {@code ProviderLedger} 同一个道理：若 mock
 * 只按注入模式机械返回，那「已支付的单不能关」这条 就无从验证 —— 平台问「能关吗」，mock 答「能」，而它根本不知道这笔付没付。
 *
 * <p>「已支付」这一事实必须建模在<b>服务边界的另一侧</b>：平台的 {@code pay_status} 只能证明平台知道 什么，证明不了支付方那边发生了什么。BR-B-17
 * 「关闭与支付并发时以支付系统结果为准」正是这个意思。
 *
 * <p>进程内结构，重启丢失可接受 —— mock 无状态重启本就允许。
 */
@Component
public class PayLedger {

    /** 支付单当前状态，键为 {@code outTradeNo}（= 平台 bizNo） */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** 支付方视角的状态。与平台的 {@code PayStatus} 刻意不同名 —— 它们是两侧各自的记录。 */
    public enum State {
        /** 已下单待支付 */
        CREATED,
        /** 已支付 */
        PAID,
        /** 已关闭 */
        CLOSED
    }

    /** 下单即建账。重复下单不覆盖 —— 已支付的单不能被一次重复下单打回待支付。 */
    public void onCreated(String outTradeNo) {
        states.putIfAbsent(outTradeNo, State.CREATED);
    }

    /**
     * 标记为已支付。<b>供演示与测试构造「关单时对方已收款」的场景。</b>
     *
     * <p>真实链路里这一步由用户在收银台完成，mock 没有收银台，故留一个显式入口。
     */
    public void markPaid(String outTradeNo) {
        states.put(outTradeNo, State.PAID);
    }

    /**
     * 尝试关闭。
     *
     * @return 关闭后的状态；已支付则原样返回 {@link State#PAID}，调用方据此拒绝关闭
     */
    public State tryClose(String outTradeNo) {
        // 未见过的单据视为可关：关单幂等要求「关一个不存在的单」也返回成功，
        // 而不是报错 —— 否则平台侧建单失败后的补偿关单会一直重试
        return states.compute(
                outTradeNo, (k, cur) -> cur == State.PAID ? State.PAID : State.CLOSED);
    }

    public State find(String outTradeNo) {
        return states.get(outTradeNo);
    }

    // ---- 退款（V3 PR-8） ----

    /** 退款账本，键为 {@code refundNo}。与支付单状态分开 —— 一笔支付对应至多一笔退款，但键不同 */
    private final Map<String, String> refundOrderNoByRefundNo = new ConcurrentHashMap<>();

    /** 每个 {@code refundNo} 收到过几次退款请求 */
    private final Map<String, Integer> refundAttemptsByRefundNo = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong refundSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /** 记一次退款请求的到达，无论本次是否记账。与 {@code ProviderLedger.recordGrantAttempt} 同一用途。 */
    public void recordRefundAttempt(String refundNo) {
        refundAttemptsByRefundNo.merge(refundNo, 1, Integer::sum);
    }

    public int refundAttempts(String refundNo) {
        return refundAttemptsByRefundNo.getOrDefault(refundNo, 0);
    }

    /**
     * 记退款账。<b>同一 {@code refundNo} 重复调用返回首次的单号，不二次退款</b>。
     *
     * <p>{@code computeIfAbsent} 语义等价于唯一索引 —— 这是「重复退款 = 0」在下游侧的最终判据。
     * 平台侧的三道闸都在平台自己的库里，只能证明平台没重复受理；<b>钱有没有退两次，只有支付方数得准</b>。
     */
    public String recordRefund(String refundNo) {
        return refundOrderNoByRefundNo.computeIfAbsent(
                refundNo, k -> "RFD" + refundSeq.incrementAndGet() + "_" + k);
    }

    /** 查退款账。返回 {@code null} 表示查无 —— 调用方须据此返回 {@code UNKNOWN} 而非 {@code FAIL}。 */
    public String findRefund(String refundNo) {
        return refundOrderNoByRefundNo.get(refundNo);
    }

    public boolean containsRefund(String refundNo) {
        return refundOrderNoByRefundNo.containsKey(refundNo);
    }

    /** 退款账本条目数。测试断言「一笔单只退了一次」的下游侧口径。 */
    public int refundSize() {
        return refundOrderNoByRefundNo.size();
    }

    public void clear() {
        states.clear();
        refundOrderNoByRefundNo.clear();
        refundAttemptsByRefundNo.clear();
    }
}
