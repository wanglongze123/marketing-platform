package com.mp.api.mock.service;

import com.mp.api.mock.dto.PayCloseResp;
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

    /**
     * 关闭支付单，返回四分类。
     *
     * <p><b>「关单」与「查单」问的是同一个问题</b>：关单本身就要先确认对方是否已支付 —— 已支付则不能关。故 {@code QUERY_CLOSE}
     * 收敛时复用本方法，不另开查询接口：分成两个接口则两处的 判定逻辑迟早会漂移，而它们必须给出一致的答案。
     *
     * <p>幂等：同一 {@code outTradeNo} 重复调用返回同一结果。关单是对终态的确认，不是一次性动作。
     */
    PayCloseResp closePay(String outTradeNo);
}
