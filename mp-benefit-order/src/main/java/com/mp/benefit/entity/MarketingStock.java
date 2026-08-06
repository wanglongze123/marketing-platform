package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 营销库存。可售余量 = {@code total - locked - consumed}。
 *
 * <p><b>三个数只由条件更新改，不由应用读出来算完再写回</b>：后者是「读-改-写」，两个线程同时读到 同一个 {@code locked} 就会各自加一次、少扣一次。判定成败一律看
 * {@code affected_rows}。
 */
@TableName("marketing_stock")
public class MarketingStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 维度键：{@code sku:SKU_XXX}。热点时可加 bucket 后缀分桶（技术方案 §7.4） */
    private String stockKey;

    private Long total;

    /** 预占：已下单未支付 */
    private Long locked;

    /** 消耗：已支付成功 */
    private Long consumed;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStockKey() {
        return stockKey;
    }

    public void setStockKey(String stockKey) {
        this.stockKey = stockKey;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getLocked() {
        return locked;
    }

    public void setLocked(Long locked) {
        this.locked = locked;
    }

    public Long getConsumed() {
        return consumed;
    }

    public void setConsumed(Long consumed) {
        this.consumed = consumed;
    }
}
