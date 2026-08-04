package com.mp.mock.service.impl;

import com.mp.api.mock.service.MockProviderService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;

/** V0 冒烟实现。V1 加 grant，返回结构必须是四分类形状。 */
@DubboService
@Service
public class MockProviderServiceImpl implements MockProviderService {

    @Override
    public String smoke(String bizNo) {
        return "mock";
    }
}
