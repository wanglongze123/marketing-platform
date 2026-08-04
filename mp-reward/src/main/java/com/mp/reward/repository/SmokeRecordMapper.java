package com.mp.reward.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mp.reward.entity.SmokeRecord;
import org.apache.ibatis.annotations.Mapper;

/** V0 冒烟。V1 结束时删除。 */
@Mapper
public interface SmokeRecordMapper extends BaseMapper<SmokeRecord> {}
