package com.mp.reward.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.reward.entity.RewardNotifyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RewardNotifyRecordMapper extends BaseMapper<RewardNotifyRecord> {

    /**
     * 该 {@code opNo} 下的通知条数，测试与对账断言「重复投递没有多留一条」用。
     *
     * <p>断言取条数而非「有没有报错」：重复投递的正确表现是<b>静默返回成功</b>（ACK 了才不会被 供应方一直重投），故报错与否说明不了任何事。
     */
    @Select("SELECT COUNT(*) FROM reward_notify_record WHERE op_no = #{opNo}")
    int countByOpNo(@Param("opNo") String opNo);
}
