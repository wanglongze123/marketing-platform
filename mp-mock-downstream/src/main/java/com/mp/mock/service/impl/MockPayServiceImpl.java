package com.mp.mock.service.impl;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.mock.dto.PayCloseResp;
import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.service.MockPayService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RetStatus;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.PayLedger;
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
    private final PayLedger ledger;

    public MockPayServiceImpl(FaultInjector injector, PayLedger ledger) {
        this.injector = injector;
        this.ledger = ledger;
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
        ledger.onCreated(req.getOutTradeNo());
        log.info(
                "mock pay created, outTradeNo={}, amount={}, tradeNo={}",
                req.getOutTradeNo(),
                req.getAmount(),
                resp.getTradeNo());
        return resp;
    }

    /**
     * 关单，按注入模式与<b>账本实际状态</b>返回四分类。
     *
     * <p>判据取账本而非注入模式：注入决定「这次调用能不能拿到结果」，账本决定「结果是什么」。 二者混为一谈的话，「已支付的单不能关」就无从验证 —— mock 会按模式机械答「能关」，而它
     * 根本不知道这笔付没付（《分阶段方案》§5.3 对 mock 独立账本的要求，关单侧同理）。
     */
    @Override
    public PayCloseResp closePay(String outTradeNo) {
        FaultMode mode = injector.payMode();
        PayCloseResp resp = new PayCloseResp();

        if (mode == FaultMode.TIMEOUT_AFTER_COMMIT) {
            // 先关掉再抛超时：调用方收不到结果，而对方其实已经关了。
            // 这是四分类里最关键的一类 —— 误判为「没关成」去重试无害，误判为 FAIL
            // （对方已支付）则会让一笔本可关闭的单永远停在 WAIT_PAY 占着库存
            PayLedger.State after = ledger.tryClose(outTradeNo);
            log.warn(
                    "mock close committed then timed out, outTradeNo={}, state={}",
                    outTradeNo,
                    after);
            throw new IllegalStateException("模拟关单超时: " + outTradeNo);
        }
        if (mode == FaultMode.TIMEOUT_BEFORE_COMMIT) {
            log.warn("mock close timed out before commit, outTradeNo={}", outTradeNo);
            throw new IllegalStateException("模拟关单超时: " + outTradeNo);
        }
        if (mode == FaultMode.PROCESSING) {
            // 受理但未完成：平台进 CLOSING 并继续查单
            resp.setRetStatus(RetStatus.PROCESSING);
            resp.setPayState(String.valueOf(ledger.find(outTradeNo)));
            log.info("mock close accepted, still processing, outTradeNo={}", outTradeNo);
            return resp;
        }

        PayLedger.State after = ledger.tryClose(outTradeNo);
        if (after == PayLedger.State.PAID) {
            // 对方已收款 —— 关不掉。这是 FAIL 的唯一合法来源（BR-B-16）
            resp.setRetStatus(RetStatus.FAIL);
            resp.setErrorCode(ErrorCode.ORDER_ALREADY_PAID);
            resp.setPayState(after.name());
            log.info("mock close rejected, already paid, outTradeNo={}", outTradeNo);
            return resp;
        }

        resp.setRetStatus(RetStatus.SUCCESS);
        resp.setPayState(after.name());
        log.info("mock close done, outTradeNo={}", outTradeNo);
        return resp;
    }
}
