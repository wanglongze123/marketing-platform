package com.mp.reward.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.reward.entity.RewardGrantRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RewardGrantRecordMapper extends BaseMapper<RewardGrantRecord> {

    /**
     * 回写终态，条件更新限定前置为 {@code PROCESSING}。
     *
     * <p>已收敛的记录不被后到的结果覆盖：查单与重发可能同时在途，先到的终态作数。 {@code affected_rows=0} 表示已被别的路径收敛，不是错误。
     *
     * <p>{@code UNKNOWN} 不应传进来 —— 它是中间态，记录保持 {@code PROCESSING} 等查单。
     */
    @Update(
            "UPDATE reward_grant_record SET result = #{result}"
                    + " WHERE op_no = #{opNo} AND result = 'PROCESSING'")
    int finishIfProcessing(@Param("opNo") String opNo, @Param("result") String result);
}
