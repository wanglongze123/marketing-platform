package com.mp.mock.service.impl;

import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.service.MockPayService;
import com.mp.common.enums.RetStatus;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * mock 支付。
 *
 * <p><b>不自动回调</b>：createPay 只返回 tradeNo，支付成功通知由外部触发 —— 保持真实支付平台的形状，使中间态可被观察。
 */
@DubboService
@Service
public class MockPayServiceImpl implements MockPayService {

    private static final Logger log = LoggerFactory.getLogger(MockPayServiceImpl.class);

    private final AtomicLong seq = new AtomicLong();

    @Override
    public PayCreateResp createPay(PayCreateReq req) {
        PayCreateResp resp = new PayCreateResp();
        resp.setRetStatus(RetStatus.SUCCESS);
        resp.setTradeNo("PAY" + seq.incrementAndGet() + "_" + req.getOutTradeNo());
        log.info(
                "mock pay created, outTradeNo={}, amount={}, tradeNo={}",
                req.getOutTradeNo(),
                req.getAmount(),
                resp.getTradeNo());
        return resp;
    }
}
