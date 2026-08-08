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

    /** 已转消耗。支付成功后的终态 */
    CONSUMED,

    /** 已释放。支付失败 / 关单后的终态 */
    RELEASED
}
