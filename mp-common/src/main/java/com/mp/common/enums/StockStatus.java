package com.mp.common.enums;

/**
 * 单笔订单的库存处置态，落 {@code play_biz_record.stock_status}。
 *
 * <p><b>它是库存类任务「每单幂等」的承重点</b>，这一点容易被两处东西冒名顶替：
 *
 * <ul>
 *   <li>库存 SQL 的下界 {@code WHERE locked >= ?} —— 它防的是「总数被减成负值」。{@code locked} 是该 {@code stock_key}
 *       下所有订单共享的计数器，A 单重复释放时它因别的订单占用仍大于 0，谓词照常 通过，结果是 A 释放掉了 B 的预占
 *   <li>{@code benefit_task.uk_biz_type_op} —— 它防的是「同一单同类任务重复<b>入队</b>」，防不住
 *       「同一条任务被重复<b>执行</b>」（租约过期被接管、调度器重跑都会）
 * </ul>
 *
 * <p>三者各挡一类失效，缺任一类都有对应的漏。真正保证「这一单的库存只动一次」的，是本枚举的 条件更新：{@code WHERE stock_status = 'LOCKED'}，{@code
 * affected_rows = 0} 即已处置过。
 */
public enum StockStatus {

    /** 未占用。不限购、或库存预占尚未发生 */
    NONE,

    /** 已预占。下单成功后的初始态 */
    LOCKED,

    /** 已转消耗。支付成功后的态；退款回补时由它进 {@link #RESTORED} */
    CONSUMED,

    /** 已释放。支付失败 / 关单后的终态 */
    RELEASED,

    /**
     * 已回补。退款成功后的终态，由 {@link #CONSUMED} 进入。
     *
     * <p><b>不复用 {@code RELEASED}</b>：两者归还的是不同的计数器 —— {@code RELEASED} 表示「预占已还」 （{@code locked}
     * 减），本值表示「已售已还」（{@code consumed} 减）。合并成一个值会让 {@code STOCK_RESTORE} 的条件更新既能从 {@code LOCKED} 进也能从
     * {@code CONSUMED} 进，于是一笔关单释放过的单还能再被退款回补一次， 而那次回补减的是别的订单的 {@code consumed}。
     *
     * <p>它也是「退款回补库存但不返还额度」这个不对称（技术方案 §3.4 口径表）在状态机上的落点： 库存有本值，而 {@code QuotaStatus} 没有对应项 ——
     * 额度买了就算用掉。
     */
    RESTORED
}
