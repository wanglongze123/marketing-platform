package com.mp.benefit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.benefit.entity.BenefitFulfillmentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BenefitFulfillmentRecordMapper extends BaseMapper<BenefitFulfillmentRecord> {

    /**
     * 幂等写入履约明细：命中 {@code uk_biz_item} 时更新为最新结果，不新增行。
     *
     * <p>grantBenefit 重入时会再次走到这里，写成普通 insert 会抛 DuplicateKeyException。
     */
    @Update(
            "INSERT INTO benefit_fulfillment_record (fulfillment_no, play_biz_record_no,"
                    + " benefit_item_id, provider_type, provider_product_id, provider_order_no,"
                    + " grant_op_no, grant_status) VALUES (#{fulfillmentNo}, #{bizNo}, #{benefitItemId},"
                    + " #{providerType}, #{providerProductId}, #{providerOrderNo}, #{grantOpNo},"
                    + " #{grantStatus}) ON DUPLICATE KEY UPDATE provider_order_no ="
                    + " VALUES(provider_order_no), grant_status = VALUES(grant_status), grant_op_no ="
                    + " VALUES(grant_op_no)")
    int upsert(
            @Param("fulfillmentNo") String fulfillmentNo,
            @Param("bizNo") String bizNo,
            @Param("benefitItemId") String benefitItemId,
            @Param("providerType") String providerType,
            @Param("providerProductId") String providerProductId,
            @Param("providerOrderNo") String providerOrderNo,
            @Param("grantOpNo") String grantOpNo,
            @Param("grantStatus") String grantStatus);

    /**
     * 查单收敛后按发奖幂等号回写明细态。
     *
     * <p>只推进未终结的行：已 SUCCESS 的不被后到的结果改写。
     */
    @Update(
            "UPDATE benefit_fulfillment_record SET grant_status = #{grantStatus},"
                    + " provider_order_no = COALESCE(#{providerOrderNo}, provider_order_no)"
                    + " WHERE play_biz_record_no = #{bizNo} AND grant_op_no = #{grantOpNo}"
                    + " AND grant_status NOT IN ('SUCCESS', 'FAILED')")
    int settleByGrantOpNo(
            @Param("bizNo") String bizNo,
            @Param("grantOpNo") String grantOpNo,
            @Param("grantStatus") String grantStatus,
            @Param("providerOrderNo") String providerOrderNo);

    /** 未终结的履约明细数。主单能否置终态以此为判据，不另建计数。 */
    @Select(
            "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no = #{bizNo}"
                    + " AND grant_status NOT IN ('SUCCESS', 'FAILED')")
    int countUnresolved(@Param("bizNo") String bizNo);

    @Select(
            "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no = #{bizNo}"
                    + " AND grant_status = #{grantStatus}")
    int countByStatus(@Param("bizNo") String bizNo, @Param("grantStatus") String grantStatus);

    /**
     * 回收成功后落回收单号与回收时间（BR-B-32/34）。V3 PR-7。
     *
     * <p><b>{@code revoke_time} 由库时钟取（{@code NOW(3)}），不由应用传</b>：它与 {@code createRefund} 落的
     * 操作记录时间要能比较先后 —— 两端出自不同时钟时，「先回收后退款」这条顺序在审计时可能反过来。
     *
     * <p><b>只回收已发放成功的明细</b>：{@code grant_status = 'SUCCESS'} 是回收对象的定义。漏掉这个 谓词会把发放失败的明细也标上回收单号 ——
     * 对账时看起来「回收过」，而供应方那边根本没有对应 的发放。
     *
     * <p>幂等：{@code revoke_no IS NULL} 使重复回收 {@code affected_rows = 0}，不覆盖首次的回收时间。
     */
    @Update(
            "UPDATE benefit_fulfillment_record SET revoke_no = #{revokeNo}, revoke_time = NOW(3),"
                    + " usage_status = #{usageStatus}"
                    + " WHERE play_biz_record_no = #{bizNo} AND grant_status = 'SUCCESS'"
                    + " AND revoke_no IS NULL")
    int markRevoked(
            @Param("bizNo") String bizNo,
            @Param("revokeNo") String revokeNo,
            @Param("usageStatus") String usageStatus);

    /** 该单已发放成功的明细，回收时逐条取原发奖单号。 */
    @Select(
            "SELECT * FROM benefit_fulfillment_record WHERE play_biz_record_no = #{bizNo}"
                    + " AND grant_status = 'SUCCESS'")
    java.util.List<BenefitFulfillmentRecord> selectGranted(@Param("bizNo") String bizNo);

    /** 已落回收单号的明细数，测试与对账断言「回收留痕」用。 */
    @Select(
            "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no = #{bizNo}"
                    + " AND revoke_no IS NOT NULL")
    int countRevoked(@Param("bizNo") String bizNo);
}
