package com.mp.benefit.service;

/**
 * 库存与限购的维度键派生。
 *
 * <p>与 {@code IdempotentKeys} 同一条规矩：<b>确定性可重算</b>。预占用一个键、释放用另一个键，就会 「占了 A 的、还了 B
 * 的」——两边都不报错，只是可售余量多出一份或少一份，要到对账才发现。
 */
public final class StockKeys {

    private StockKeys() {}

    /**
     * 库存维度键。
     *
     * <p>V2 按 SKU 维度。技术方案 §3.4 允许键含 activity / city 维度、热点时加 bucket 后缀分桶 ——
     * 那些是同一函数的不同实现，改这里即可，调用方不动。
     */
    public static String stockKey(String skuId) {
        return "sku:" + skuId;
    }

    /**
     * 限购周期键。
     *
     * <p>V2 只有 {@code TOTAL}（活动期内累计）。日/周限购（{@code D20260802} / {@code W202631}）属 V3 运营 配置化范围。
     *
     * <p><b>不用当前日期拼</b>：那会让「下单时扣的额度」与「关单时还的额度」在跨天时落到不同的行 ——
     * 用户在23:59下单、次日00:01超时关单，还的是新一天的额度，旧的那天永远还不回来。真要做 日限购，周期键须随单据固化，而不是每次现算。
     */
    public static String periodKey() {
        return "TOTAL";
    }
}
