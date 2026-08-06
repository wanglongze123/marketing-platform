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
}
