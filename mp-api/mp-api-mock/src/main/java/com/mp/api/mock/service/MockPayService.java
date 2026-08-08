package com.mp.api.mock.service;

import com.mp.api.mock.dto.PayCloseResp;
import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.dto.PayRefundResp;

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

    /**
     * 退款。V3 PR-8 引入。
     *
     * <p>幂等：同一 {@code refundNo} 重复调用返回首次的退款单号，<b>不二次退款</b>。这是「重复退款 = 0」 在下游侧的最终判据 ——
     * 平台的三道闸都在平台自己的库里，只能证明平台没重复受理；钱有没有退 两次，只有支付方数得准（《分阶段方案》§5.3 的同一条判断）。
     *
     * <p><b>与关单共用 {@code payMode} 注入</b>，不另设开关：退款与关单是同一个支付方，它挂了两个 方法一起挂。
     *
     * @param refundNo 退款幂等键，由上游退款请求号派生
     */
    PayRefundResp refund(String outTradeNo, String refundNo, long amount);

    /**
     * 按原退款单号查单，供 {@code UNKNOWN} 收敛。
     *
     * <p><b>查无返回 {@code UNKNOWN} 而非 {@code FAIL}</b>：查无可能只是提交在途，判失败会让平台重发 ——
     * 而重发一笔可能已成功的退款就是重复退款。与发放侧查单同一处置。
     */
    PayRefundResp queryRefund(String refundNo);
}
