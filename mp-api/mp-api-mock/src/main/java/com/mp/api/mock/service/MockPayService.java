package com.mp.api.mock.service;

import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;

/**
 * mock 支付。
 *
 * <p><b>不自动回调</b> —— createPay 只返回 tradeNo，不异步触发 payCallback。 回调由集成测试或 curl
 * 外部触发，使「已付款未履约」这个中间态可被观察， 且 V2 注入乱序、重复投递时有下手处。
 */
public interface MockPayService {

    /** V1 固定成功。 */
    PayCreateResp createPay(PayCreateReq req);
}
