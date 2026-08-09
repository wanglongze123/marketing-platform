package com.mp.reward.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.reward.entity.RewardRevokeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 回收幂等记录访问。与 {@link RewardGrantRecordMapper} 同构。 */
@Mapper
public interface RewardRevokeRecordMapper extends BaseMapper<RewardRevokeRecord> {

    /**
     * 回写终态，条件更新限定前置为 {@code PROCESSING}。
     *
     * <p>已收敛的记录不被后到的结果覆盖 —— 查单与重发可能同时在途，先到的终态作数。{@code affected_rows=0} 表示已被别的路径收敛，不是错误。
     *
     * <p><b>{@code usage_status} 与 {@code result} 一同回写</b>：两者出自供应方的同一次回答，分两次
     * 写会让「回收失败」与「失败时券是什么状态」在库里短暂不一致，而对账正是按这两列联合判定。
     */
    @Update(
            "UPDATE reward_revoke_record SET result = #{result}, usage_status = #{usageStatus},"
                    + " provider_order_no = #{providerOrderNo}, error_code = #{errorCode}"
                    + " WHERE revoke_no = #{revokeNo} AND result = 'PROCESSING'")
    int finishIfProcessing(
            @Param("revokeNo") String revokeNo,
            @Param("result") String result,
            @Param("usageStatus") String usageStatus,
            @Param("providerOrderNo") String providerOrderNo,
            @Param("errorCode") String errorCode);

    @Select("SELECT * FROM reward_revoke_record WHERE revoke_no = #{revokeNo}")
    RewardRevokeRecord selectByRevokeNo(@Param("revokeNo") String revokeNo);
}
