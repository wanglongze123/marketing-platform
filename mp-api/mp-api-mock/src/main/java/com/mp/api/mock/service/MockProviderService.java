package com.mp.api.mock.service;

import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;

/**
 * mock 奖励供应方。
 *
 * <p><b>它在服务边界的另一侧</b>：平台侧的 {@code reward_grant_record} 在调用它之前就已落 {@code
 * PROCESSING}（三段式），对那张表计数只能证明平台幂等，证明不了供应方实际发了几次。 「下游已执行」必须建模在这一侧，故 mock 持有自己的账本（《分阶段方案》§5.3）。
 */
public interface MockProviderService {

    ProviderGrantResp grant(ProviderGrantReq req);

    /**
     * 按原幂等号查单。
     *
     * <p><b>查无返回 {@code UNKNOWN}，不返回 {@code FAIL}</b>：查无可能只是提交在途，判失败会 触发误补偿。这与平台侧 {@code
     * queryGrant} 的处置同源。
     */
    ProviderGrantResp queryGrant(String opNo);
}
