package com.mp.mock.service.impl;

import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;
import com.mp.api.mock.service.MockProviderService;
import com.mp.common.enums.RetStatus;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * mock 奖励供应方。
 *
 * <p>V1 固定返回 SUCCESS。V2 加注入开关支持 timeout / PROCESSING / FAIL， 届时只改本实现，契约不变。
 */
@DubboService
@Service
public class MockProviderServiceImpl implements MockProviderService {

    private static final Logger log = LoggerFactory.getLogger(MockProviderServiceImpl.class);

    private final AtomicLong seq = new AtomicLong();

    @Override
    public ProviderGrantResp grant(ProviderGrantReq req) {
        ProviderGrantResp resp = new ProviderGrantResp();
        resp.setRetStatus(RetStatus.SUCCESS);
        resp.setProviderOrderNo("PRV" + seq.incrementAndGet() + "_" + req.getOpNo());
        log.info(
                "mock provider granted, opNo={}, product={}, providerOrderNo={}",
                req.getOpNo(),
                req.getProviderProductId(),
                resp.getProviderOrderNo());
        return resp;
    }
}
