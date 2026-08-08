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

    /**
     * 供应方通知驱动的整单回写：把该 {@code opNo} 下<b>尚未终结</b>的项推进到通知结果。V3 PR-9。
     *
     * <p><b>限定「未终结」不可省</b>：已 {@code SUCCESS} 的项不被后到的通知改写。供应方补发的旧通知、 查单与通知并发到达，都会让一条较早的结果后到 ——
     * 没有这个谓词就是「后到的覆盖先到的」，一笔 已确认成功的发放可能被一条迟到的失败通知翻掉。与 {@code settleByGrantOpNo} 是同一处置。
     *
     * <p><b>整单而非逐项</b>：通知报文不带 {@code itemSeq}，供应方视角里一次调用就是一笔。逐项回写要 平台自己拆，而拆的依据（哪几项属于这次通知）并不存在。
     */
    @Update(
            "UPDATE reward_grant_item SET result = #{result},"
                    + " provider_order_no = COALESCE(#{providerOrderNo}, provider_order_no)"
                    + " WHERE op_no = #{opNo} AND result NOT IN ('SUCCESS', 'FAIL')")
    int settleUnresolved(
            @Param("opNo") String opNo,
            @Param("result") String result,
            @Param("providerOrderNo") String providerOrderNo);

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
