package com.mp.common.enums;

/**
 * 单笔订单的限购额度处置态，落 {@code play_biz_record.quota_status}。
 *
 * <p><b>与 {@link StockStatus} 分成两列，不合并</b>：两者由同一条 {@code STOCK_RELEASE} 任务承接， 但「是否占用过」并不同步 ——
 * 库存对每一单都预占，额度只在 SKU 配了限购时才扣。合成一列则 不限购的单会以 {@code LOCKED} 进入释放，把不属于它的额度还掉。
 *
 * <p>失效形态与 {@code StockStatus} 修掉的那个完全同构：额度行按 {@code (user, activity, sku, period)}
 * 聚合，是同一用户所有订单<b>共享</b>的计数器。下界 {@code WHERE used_qty >= qty} 在该用户另有 订单占着额度时照常通过 —— 拦不住跨单误还。
 *
 * <p>没有 {@code CONSUMED}：额度与库存在这一点上不对称。库存的预占在支付成功后要转消耗（两个 计数器此消彼长），额度则是<b>买了就算用掉</b>，支付成功时无事可做。技术方案
 * §3.4 的口径表 记着这个不对称 —— 退款也不返还额度，否则「买了再退」能刷回额度，限购即被绕过。
 */
public enum QuotaStatus {

    /** 未占用。SKU 不限购（{@code purchase_limit_qty = 0}）时的终态，释放时跳过 */
    NONE,

    /** 已占用。限购单下单成功后的初始态 */
    LOCKED,

    /** 已返还。交易未成立（支付失败 / 关单）后的终态 */
    RELEASED
}
