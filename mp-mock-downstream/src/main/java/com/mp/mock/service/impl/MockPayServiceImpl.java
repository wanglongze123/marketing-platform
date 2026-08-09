package com.mp.mock.service.impl;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.mock.dto.PayCloseResp;
import com.mp.api.mock.dto.PayCreateReq;
import com.mp.api.mock.dto.PayCreateResp;
import com.mp.api.mock.dto.PayRefundResp;
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
        ledger.onCreated(req.getOutTradeNo(), resp.getTradeNo());
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

    /**
     * 退款，按注入模式与<b>账本实际状态</b>返回四分类。
     *
     * <p>幂等由 {@link PayLedger#recordRefund} 的 {@code computeIfAbsent} 承载：同一 {@code refundNo}
     * 重复调用返回首次的单号，不二次退款。<b>这是「重复退款 = 0」的最终判据</b> —— 平台的三道闸都在 平台自己的库里，只能证明平台没重复受理；钱有没有退两次，只有支付方数得准。
     *
     * <p>{@code TIMEOUT_AFTER_COMMIT} 是本方法最关键的一类：钱已经退了但调用方收不到结果。此时 平台若把 {@code UNKNOWN} 误判为 {@code
     * FAIL} 而重发，就是重复退款。
     */
    @Override
    public PayRefundResp refund(String outTradeNo, String refundNo, long amount) {
        FaultMode mode = injector.payMode();

        // 先记到达再分流：抛超时的那两个也算 —— 请求确实到达了支付方
        ledger.recordRefundAttempt(refundNo);

        if (mode == FaultMode.TIMEOUT_AFTER_COMMIT) {
            String orderNo = ledger.recordRefund(refundNo);
            log.warn(
                    "mock refund committed then timed out, refundNo={}, orderNo={}",
                    refundNo,
                    orderNo);
            throw new IllegalStateException("模拟退款超时（已退款）: " + refundNo);
        }
        if (mode == FaultMode.TIMEOUT_BEFORE_COMMIT) {
            log.warn("mock refund timed out before commit, refundNo={}", refundNo);
            throw new IllegalStateException("模拟退款超时（未退款）: " + refundNo);
        }
        if (mode == FaultMode.PROCESSING) {
            // 受理但未完成：平台进 REFUNDING 并继续查单
            log.info("mock refund accepted, still processing, refundNo={}", refundNo);
            return refundResp(RetStatus.PROCESSING, null, null);
        }
        if (mode == FaultMode.FAIL) {
            log.info("mock refund rejected, refundNo={}", refundNo);
            return refundResp(RetStatus.FAIL, null, ErrorCode.INVALID_PARAM);
        }

        // 未收款的单退不了。判据取账本而非平台传来的状态 —— 与关单同一条理由
        if (ledger.find(outTradeNo) != PayLedger.State.PAID) {
            log.info("mock refund rejected, not paid, outTradeNo={}", outTradeNo);
            return refundResp(RetStatus.FAIL, null, ErrorCode.INVALID_PARAM);
        }

        String orderNo = ledger.recordRefund(refundNo);
        log.info("mock refund done, refundNo={}, orderNo={}, amount={}", refundNo, orderNo, amount);
        return refundResp(RetStatus.SUCCESS, orderNo, null);
    }

    /**
     * 退款查单。
     *
     * <p><b>查无返回 {@code UNKNOWN}</b>：查无可能只是提交在途，判 {@code FAIL} 会让平台重发 —— 而重发
     * 一笔可能已成功的退款就是重复退款。与发放侧查单一字不差。
     */
    @Override
    public PayRefundResp queryRefund(String refundNo) {
        String existing = ledger.findRefund(refundNo);
        if (existing != null) {
            return refundResp(RetStatus.SUCCESS, existing, null);
        }
        if (injector.payMode() == FaultMode.FAIL) {
            return refundResp(RetStatus.FAIL, null, ErrorCode.INVALID_PARAM);
        }
        log.info("mock refund query found nothing, refundNo={}", refundNo);
        return refundResp(RetStatus.UNKNOWN, null, null);
    }

    /**
     * 支付方的对账文件，供对账第 8 项。
     *
     * <p><b>不受注入模式影响</b>：注入模拟的是「这次调用能不能拿到结果」，而对账文件在真实链路里是 T+1
     * 落盘推送的，与在线接口的可用性无关。让它随注入一起失败会把两种故障混为一谈。
     */
    @Override
    public java.util.List<com.mp.api.mock.dto.PaidTradeRow> listPaidTrades() {
        return ledger.listPaidTrades();
    }

    private static PayRefundResp refundResp(RetStatus status, String orderNo, String errorCode) {
        PayRefundResp r = new PayRefundResp();
        r.setRetStatus(status);
        r.setRefundOrderNo(orderNo);
        r.setErrorCode(errorCode);
        return r;
    }
}
