package com.mp.mock.service.impl;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.service.MockPayService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RetStatus;
import com.mp.mock.fault.FaultInjector;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * mock 支付。
 *
 * <p><b>不自动回调</b>：createPay 只返回 tradeNo，支付成功通知由外部触发 —— 保持真实支付平台的形状，使中间态可被观察。
 *
 * <p>与供应方共用 {@link FaultInjector}：《分阶段方案》§5.3 要求注入「作用于 mock 供应方与 mock 支付两侧」。 支付侧的 {@code UNKNOWN}
 * 目前由 {@code createTrade} 抛出未实现 —— 建单链路的四分类收敛属 PR-6 关单范围， 此处先让模式真的生效，避免端点切了却没有任何效果。
 */
@DubboService
@Service
public class MockPayServiceImpl implements MockPayService {

    private static final Logger log = LoggerFactory.getLogger(MockPayServiceImpl.class);

    private final AtomicLong seq = new AtomicLong();

    private final FaultInjector injector;

    public MockPayServiceImpl(FaultInjector injector) {
        this.injector = injector;
    }

    @Override
    public PayCreateResp createPay(PayCreateReq req) {
        FaultMode mode = injector.payMode();
        if (mode == FaultMode.TIMEOUT_AFTER_COMMIT || mode == FaultMode.TIMEOUT_BEFORE_COMMIT) {
            log.warn("mock pay timed out, outTradeNo={}, mode={}", req.getOutTradeNo(), mode);
            throw new IllegalStateException("模拟支付下单超时: " + req.getOutTradeNo());
        }
        if (mode == FaultMode.FAIL) {
            log.info("mock pay rejected, outTradeNo={}", req.getOutTradeNo());
            PayCreateResp failed = new PayCreateResp();
            failed.setRetStatus(RetStatus.FAIL);
            failed.setErrorCode(ErrorCode.INVALID_PARAM);
            return failed;
        }
        if (mode == FaultMode.PROCESSING) {
            log.info("mock pay accepted, still processing, outTradeNo={}", req.getOutTradeNo());
            PayCreateResp processing = new PayCreateResp();
            processing.setRetStatus(RetStatus.PROCESSING);
            return processing;
        }

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
