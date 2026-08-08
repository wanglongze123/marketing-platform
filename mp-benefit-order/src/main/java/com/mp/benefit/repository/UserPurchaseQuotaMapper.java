package com.mp.benefit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.benefit.entity.UserPurchaseQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 限购额度访问。与库存同构：原子扣减 + {@code affected_rows} 判定。
 *
 * <p><b>「建行」与「扣减」拆成两步，且第一步幂等</b>：额度行是运行时数据，用户首次下单时才存在， 无法预先 seed。若写成「查不到就 insert」，两个并发请求会同时查不到、同时
 * insert，第二个撞 {@code uk_quota}。故建行走 {@code INSERT ... ON DUPLICATE KEY UPDATE id = id}，冲突即视为已存在。
 */
@Mapper
public interface UserPurchaseQuotaMapper extends BaseMapper<UserPurchaseQuota> {

    /**
     * 确保额度行存在。已存在则什么都不做，<b>不覆盖 {@code used_qty}</b>。
     *
     * <p>写成 {@code ON DUPLICATE KEY UPDATE limit_qty = #{limitQty}} 会顺手把运营调整后的限额 同步过来，看似合理 ——
     * 但那也意味着每次下单都在改这一行，而该行正被并发扣减，平添一处热点写。限额变更属运营动作，不该由下单路径捎带。
     */
    @Update(
            "INSERT INTO user_purchase_quota (user_id, activity_id, sku_id, period_key, used_qty,"
                    + " limit_qty) VALUES (#{userId}, #{activityId}, #{skuId}, #{periodKey}, 0,"
                    + " #{limitQty}) ON DUPLICATE KEY UPDATE id = id")
    int ensureRow(
            @Param("userId") String userId,
            @Param("activityId") String activityId,
            @Param("skuId") String skuId,
            @Param("periodKey") String periodKey,
            @Param("limitQty") int limitQty);

    /**
     * 原子扣减额度。超限则 {@code affected_rows = 0}。
     *
     * <p>谓词 {@code used_qty + qty <= limit_qty} 直接读<b>行内</b>的 {@code limit_qty}，不用应用传进来的值 ——
     * 传进来的那份是下单时从 SKU 读的快照，与本行可能不一致（运营刚调过限额）。以行内为准， 「限额是多少」就只有一个来源。
     */
    @Update(
            "UPDATE user_purchase_quota SET used_qty = used_qty + #{qty}"
                    + " WHERE user_id = #{userId} AND activity_id = #{activityId}"
                    + " AND sku_id = #{skuId} AND period_key = #{periodKey}"
                    + " AND used_qty + #{qty} <= limit_qty")
    int tryConsume(
            @Param("userId") String userId,
            @Param("activityId") String activityId,
            @Param("skuId") String skuId,
            @Param("periodKey") String periodKey,
            @Param("qty") int qty);

    /**
     * 返还额度（关单 / 支付失败）。
     *
     * <p><b>只有「交易未成立」才返还，退款不返还</b>（技术方案 §3.4）：限购是为了防单用户过度占用 营销资源，若「买了再退」能刷回额度，限购即被绕过 ——
     * 这是薅羊毛的标准手法。库存则相反， 退款要回补，因为商品本身可以再卖给别人。这个不对称是有意为之。
     */
    @Update(
            "UPDATE user_purchase_quota SET used_qty = used_qty - #{qty}"
                    + " WHERE user_id = #{userId} AND activity_id = #{activityId}"
                    + " AND sku_id = #{skuId} AND period_key = #{periodKey}"
                    + " AND used_qty >= #{qty}")
    int tryRelease(
            @Param("userId") String userId,
            @Param("activityId") String activityId,
            @Param("skuId") String skuId,
            @Param("periodKey") String periodKey,
            @Param("qty") int qty);
}
