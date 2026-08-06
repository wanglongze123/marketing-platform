package com.mp.mock.service.impl;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;
import com.mp.api.mock.service.MockProviderService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.RetStatus;
import com.mp.mock.fault.FaultInjector;
import com.mp.mock.fault.ProviderLedger;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * mock 奖励供应方，按 {@link FaultInjector} 的模式行事。
 *
 * <p><b>本类是「下游」，不是平台的一部分</b>。它持有自己的账本，平台无法读写 —— 这正是要的：
 * 「无重复发放」的最终判据必须跨过服务边界，否则只是在证明平台自己的记录没写重（§5.3）。
 */
@DubboService
@Service
public class MockProviderServiceImpl implements MockProviderService {

    private static final Logger log = LoggerFactory.getLogger(MockProviderServiceImpl.class);

    private final FaultInjector injector;
    private final ProviderLedger ledger;

    public MockProviderServiceImpl(FaultInjector injector, ProviderLedger ledger) {
        this.injector = injector;
        this.ledger = ledger;
    }

    @Override
    public ProviderGrantResp grant(ProviderGrantReq req) {
        String opNo = req.getOpNo();
        FaultMode mode = injector.providerMode();

        switch (mode) {
            case TIMEOUT_AFTER_COMMIT -> {
                // 关键场景：下游已执行成功，但调用方收不到结果。
                // 此时平台若把 UNKNOWN 误判为 FAIL 去补发，就是重复发放
                String orderNo = ledger.record(opNo);
                log.warn(
                        "mock provider committed then timed out, opNo={}, orderNo={}",
                        opNo,
                        orderNo);
                throw new IllegalStateException("模拟下游超时（已记账）: " + opNo);
            }
            case TIMEOUT_BEFORE_COMMIT -> {
                log.warn("mock provider timed out before commit, opNo={}", opNo);
                throw new IllegalStateException("模拟下游超时（未记账）: " + opNo);
            }
            case PROCESSING -> {
                // 受理但不完成：不记账，等查单时再转成功
                log.info("mock provider accepted, still processing, opNo={}", opNo);
                return resp(RetStatus.PROCESSING, null, null);
            }
            case FAIL -> {
                log.info("mock provider rejected, opNo={}", opNo);
                return resp(RetStatus.FAIL, null, ErrorCode.INVALID_PARAM);
            }
            default -> {
                String orderNo = ledger.record(opNo);
                log.info(
                        "mock provider granted, opNo={}, product={}, providerOrderNo={}",
                        opNo,
                        req.getProviderProductId(),
                        orderNo);
                return resp(RetStatus.SUCCESS, orderNo, null);
            }
        }
    }

    /**
     * 查单。
     *
     * <p><b>查无返回 {@code UNKNOWN}</b>：查无可能只是提交在途，判 {@code FAIL} 会让平台据此走补偿， 而下游可能实际已发放。
     *
     * <p>{@code PROCESSING} 模式下累计到阈值即转成功并记账 —— 模拟「下游确实在处理，一段时间后完成」。
     */
    @Override
    public ProviderGrantResp queryGrant(String opNo) {
        String existing = ledger.find(opNo);
        if (existing != null) {
            return resp(RetStatus.SUCCESS, existing, null);
        }

        if (injector.providerMode() == FaultMode.PROCESSING) {
            if (injector.processingShouldSucceedNow()) {
                String orderNo = ledger.record(opNo);
                log.info("mock provider processing completed on query, opNo={}", opNo);
                return resp(RetStatus.SUCCESS, orderNo, null);
            }
            return resp(RetStatus.PROCESSING, null, null);
        }

        if (injector.providerMode() == FaultMode.FAIL) {
            return resp(RetStatus.FAIL, null, ErrorCode.INVALID_PARAM);
        }

        // 账本查无：不是「确定没发」，而是「不知道」。平台据此继续查单，连续查无满阈值才重发
        log.info("mock provider query found nothing, opNo={}", opNo);
        return resp(RetStatus.UNKNOWN, null, null);
    }

    private static ProviderGrantResp resp(RetStatus status, String orderNo, String errorCode) {
        ProviderGrantResp r = new ProviderGrantResp();
        r.setRetStatus(status);
        r.setProviderOrderNo(orderNo);
        r.setErrorCode(errorCode);
        return r;
    }
}
