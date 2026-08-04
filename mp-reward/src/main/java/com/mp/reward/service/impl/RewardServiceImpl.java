package com.mp.reward.service.impl;

import com.mp.api.mock.service.MockProviderService;
import com.mp.api.reward.service.RewardService;
import com.mp.reward.entity.SmokeRecord;
import com.mp.reward.repository.SmokeRecordMapper;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** V0 冒烟实现：调下游 + 写库。V1 结束时删除，届时改为 grantReward / queryGrant。 */
@DubboService
@Service
public class RewardServiceImpl implements RewardService {

    private static final Logger log = LoggerFactory.getLogger(RewardServiceImpl.class);

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private MockProviderService mockProviderService;

    private final SmokeRecordMapper smokeRecordMapper;

    public RewardServiceImpl(SmokeRecordMapper smokeRecordMapper) {
        this.smokeRecordMapper = smokeRecordMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String smoke(String bizNo) {
        String downstream = mockProviderService.smoke(bizNo);

        SmokeRecord record = new SmokeRecord();
        record.setBizNo(bizNo);
        smokeRecordMapper.insert(record);

        log.info("smoke reward done, bizNo={}, downstream={}", bizNo, downstream);
        return "reward," + downstream;
    }
}
