package com.mp.benefit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.benefit.entity.MarketingStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 库存访问：三个动作全是<b>带条件的单条 UPDATE</b>，{@code affected_rows = 0} 即失败。
 *
 * <p><b>不做「先查余量再扣减」</b>：两条语句之间存在并发窗口 —— 500 个线程可以同时查到「还剩 100」， 然后各自扣一次。行锁只在单条语句执行期间串行化，跨语句不成立。这正是 0
 * 超卖的全部要点， 也是本项目不引入分布式锁做库存的理由（技术方案 §7.4：L2 是性能优化，L3 才是正确性兜底）。
 *
 * <p><b>下界保护不提供每单幂等</b>：{@code locked} 是该 {@code stock_key} 下所有订单共享的计数器。 A 单重复释放两次时，{@code locked}
 * 因别的订单占用仍远大于 0，{@code WHERE locked >= ?} 根本 不会拦 —— 结果是 A 释放了别人的预占，可售余量多出一份，直接超卖。每单幂等由 {@code
 * benefit_task.uk_biz_type_op} 承担，故库存类任务的 {@code op_no} 必须取 {@code biz_no + '_' + task_type} 而非留空串。
 */
@Mapper
public interface MarketingStockMapper extends BaseMapper<MarketingStock> {

    /**
     * 预占（下单）。余量不足则 {@code affected_rows = 0}。
     *
     * <p>谓词是 {@code total - locked - consumed >= qty} 而非 {@code locked + qty <= total}：后者
     * 漏掉了已消耗的部分，会把卖掉的库存再卖一遍。
     */
    @Update(
            "UPDATE marketing_stock SET locked = locked + #{qty}"
                    + " WHERE stock_key = #{stockKey} AND total - locked - consumed >= #{qty}")
    int tryLock(@Param("stockKey") String stockKey, @Param("qty") long qty);

    /**
     * 转消耗（支付成功）。
     *
     * <p>{@code locked} 减、{@code consumed} 加，总量不变 —— 这一步不改变可售余量，只是把「占着」 变成「卖掉了」。带 {@code locked >=
     * qty} 下界防止减成负数。
     */
    @Update(
            "UPDATE marketing_stock SET locked = locked - #{qty}, consumed = consumed + #{qty}"
                    + " WHERE stock_key = #{stockKey} AND locked >= #{qty}")
    int tryConsume(@Param("stockKey") String stockKey, @Param("qty") long qty);

    /** 释放（关单 / 支付失败）。可售余量因此回升。 */
    @Update(
            "UPDATE marketing_stock SET locked = locked - #{qty}"
                    + " WHERE stock_key = #{stockKey} AND locked >= #{qty}")
    int tryRelease(@Param("stockKey") String stockKey, @Param("qty") long qty);

    /**
     * 回补（退款成功）：{@code consumed} 减，可售余量回升。
     *
     * <p>技术方案 §3.4 的口径表明确要求这一步：<b>退款回补库存</b>（商品可以再卖给别人），而<b>不返还
     * 限购额度</b>（否则「买了再退」能刷回额度，限购被绕过）。这个不对称是有意的 —— 故本方法只动 {@code marketing_stock}，{@code
     * user_purchase_quota} 一律不碰。
     *
     * <p><b>缺了它，对账第 6 项在任何含退款的场景里都会报差异</b>：该项比对 {@code consumed} 与「已支付 且未退款成功」的份数（{@code
     * sumConsumedQuantity} 的谓词 {@code refund_status <> 'REFUND_SUCCESS'}）， 退款后分母减少而 {@code
     * consumed} 不动，每轮对账必报一次。而<b>假告警会让资损哨兵失效</b> —— §3.4 原话：「退款/关单后的口径必须定死，否则对账第 6
     * 项在任何含退款的压测里都会报差异」。
     *
     * <p><b>与 {@link #tryRelease} 是两个动作，不可合并</b>：那个减 {@code locked}（交易未成立，预占归还）， 这个减 {@code
     * consumed}（交易已成立后反悔，已售归还）。合并会让退款去减一个早已归零的 {@code locked} —— 下界谓词把它挡下，看起来「没报错」，而实际什么也没回补。
     *
     * <p>下界 {@code consumed >= qty} 防减成负值。<b>它同样不提供每单幂等</b>：{@code consumed} 是该 {@code stock_key}
     * 下所有订单共享的计数器，本单重复回补时它因别的订单占用仍大于 0，谓词照常通过 —— 每单幂等由主单 {@code stock_status} 的条件更新承担（{@code
     * CONSUMED → RESTORED}）。
     */
    @Update(
            "UPDATE marketing_stock SET consumed = consumed - #{qty}"
                    + " WHERE stock_key = #{stockKey} AND consumed >= #{qty}")
    int tryRestore(@Param("stockKey") String stockKey, @Param("qty") long qty);
}
