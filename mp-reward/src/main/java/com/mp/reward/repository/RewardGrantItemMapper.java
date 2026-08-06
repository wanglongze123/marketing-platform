package com.mp.reward.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.reward.entity.RewardGrantItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RewardGrantItemMapper extends BaseMapper<RewardGrantItem> {

    /**
     * 幂等写入：命中 {@code uk_op_item} 时更新为最新结果。
     *
     * <p>查单收敛会回写同一项（{@code UNKNOWN} → {@code SUCCESS}），写成普通 insert 会在此抛 {@code
     * DuplicateKeyException} 中断收敛 —— 而重入是预期路径，不是错误。
     *
     * <p>{@code provider_order_no} 用 {@code COALESCE} 保留已有值：查单查得的单号不该被后续 一次「查无」的空值抹掉。
     */
    @Update(
            "INSERT INTO reward_grant_item (op_no, item_seq, reward_type, provider_type,"
                    + " provider_order_no, result, error_code) VALUES (#{opNo}, #{itemSeq},"
                    + " #{rewardType}, #{providerType}, #{providerOrderNo}, #{result}, #{errorCode})"
                    + " ON DUPLICATE KEY UPDATE result = VALUES(result),"
                    + " provider_order_no = COALESCE(VALUES(provider_order_no), provider_order_no),"
                    + " error_code = VALUES(error_code)")
    int upsert(RewardGrantItem item);

    /** 查单驱动的单项结果回写，不改其余字段。 */
    @Update(
            "UPDATE reward_grant_item SET result = #{result},"
                    + " provider_order_no = COALESCE(#{providerOrderNo}, provider_order_no)"
                    + " WHERE op_no = #{opNo} AND item_seq = #{itemSeq}")
    int updateResult(
            @Param("opNo") String opNo,
            @Param("itemSeq") int itemSeq,
            @Param("result") String result,
            @Param("providerOrderNo") String providerOrderNo);
}
