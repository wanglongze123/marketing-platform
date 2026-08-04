package com.mp.api.mock.service;

import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;

/** mock 奖励供应方。V1 固定返回 SUCCESS，V2 加 timeout / PROCESSING / FAIL 注入开关。 */
public interface MockProviderService {

    ProviderGrantResp grant(ProviderGrantReq req);
}
