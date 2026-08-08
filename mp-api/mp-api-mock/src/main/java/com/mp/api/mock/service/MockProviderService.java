package com.mp.api.mock.service;

import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;
import com.mp.api.mock.dto.ProviderRevokeReq;
import com.mp.api.mock.dto.ProviderRevokeResp;

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

    /**
     * 回收已发放的权益。V3 PR-7 引入。
     *
     * <p><b>「仅当未使用才回收」在这一侧原子判定</b>（BR-B-30）：平台先查再回收存在窗口 —— 查完到
     * 回收之间用户可以把券花掉，于是券已核销而平台以为回收成功、退了钱。判定与动作必须在持有 该券的一方原子完成，这正是把它建模在服务边界另一侧的理由。
     *
     * <p>幂等：同一 {@code revokeNo} 重复调用返回同一结果，不二次回收。
     */
    ProviderRevokeResp revoke(ProviderRevokeReq req);
}
