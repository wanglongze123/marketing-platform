package com.mp.reward.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.reward.entity.RewardGrantRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 批量按幂等号取发放记录，供对账拉取比对。V3 PR-10。
     *
     * <p><b>查无的键不会出现在结果里</b>，这是有意的：对账第 3 项（发奖单下游无记录）要找的正是 这批「平台有明细而 reward 侧查无」的键 ——
     * 若补成占位行，差异就被抹平了。
     *
     * <p>空列表由调用方拦截：MyBatis 的 {@code foreach} 会拼出 {@code IN ()}，MySQL 语法错误。
     */
    @Select({
        "<script>",
        "SELECT * FROM reward_grant_record",
        " WHERE op_no IN <foreach item='no' collection='opNos' open='(' separator=','"
                + " close=')'>#{no}</foreach>",
        "</script>"
    })
    List<RewardGrantRecord> selectByOpNos(@Param("opNos") List<String> opNos);
}
