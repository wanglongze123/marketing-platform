package com.mp.mock.service.impl;

import com.mp.api.mock.dto.FaultMode;
import com.mp.api.mock.dto.ProviderGrantReq;
import com.mp.api.mock.dto.ProviderGrantResp;
import com.mp.api.mock.dto.ProviderRevokeReq;
import com.mp.api.mock.dto.ProviderRevokeResp;
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

        // 先记到达再分流：所有模式都算，包括抛超时的那两个 —— 请求确实到达了下游。
        // 这与账本是两个数：账本记「发了几次」，本计数记「平台发起了几次」，幂等生效时二者背离
        ledger.recordGrantAttempt(opNo);

        // 定向延迟：模拟一个慢供应方。它使「fail-fast 取消其余组」可被观察 ——
        // 全部瞬时返回时，取消发出去的时候其余组早已跑完，两种实现的结果一模一样
        long delay = ledger.delayOf(req.getProviderProductId());
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                // 被扇出取消：恢复中断标志并抛出 —— 平台侧据此判 UNKNOWN。
                // 真实供应方不会「被调用方取消」，这里是 mock 对 fail-fast 的可观察点
                Thread.currentThread().interrupt();
                log.warn("mock provider grant interrupted while delayed, opNo={}", opNo);
                throw new IllegalStateException("模拟慢供应方被中断: " + opNo);
            }
        }

        // 定向失败：它是「这个商品发不出来」，与「整个供应方挂了」不是一回事。退出标准第 19 条
        // （一组失败其余组照常完成）需要前者 —— 全局注入会让所有组一起失败，那种场景下
        // fail-fast 与逐个失败的表现完全一样，用例分辨不出两种实现
        if (ledger.isFailingProduct(req.getProviderProductId())) {
            log.info(
                    "mock provider rejected by product-level injection, opNo={}, product={}",
                    opNo,
                    req.getProviderProductId());
            return resp(RetStatus.FAIL, null, ErrorCode.INVALID_PARAM);
        }

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
            if (injector.processingShouldSucceedNow(opNo)) {
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

    /**
     * 回收：<b>「未使用才回收」由本方法原子判定</b>（BR-B-30）。
     *
     * <p>判定与动作在 {@link ProviderLedger#revokeIfUnused} 的一次 {@code compute} 内完成。平台侧 「先查 usageStatus
     * 再决定要不要回收」在两步之间存在窗口：查到 {@code UNUSED}、用户随即核销、 平台再发起回收 —— 券已用掉而平台以为回收成功、退了钱。
     *
     * <p><b>故障注入沿用 {@code providerMode}，不另设开关</b>：回收与发放是同一个供应方，它挂了
     * 两个方法一起挂。分设两个开关会让测试布置出「发放正常但回收超时」这种在真实系统里对应不到 任何故障的场景。
     */
    @Override
    public ProviderRevokeResp revoke(ProviderRevokeReq req) {
        String revokeNo = req.getRevokeNo();
        String grantOpNo = req.getGrantOpNo();
        FaultMode mode = injector.providerMode();

        // 先记到达再分流：抛超时的那两个也算 —— 请求确实到达了下游
        ledger.recordRevokeAttempt(revokeNo);

        switch (mode) {
            case TIMEOUT_AFTER_COMMIT -> {
                // 关键场景：下游已回收成功，但调用方收不到结果。此时平台若把 UNKNOWN 误判为
                // FAIL 而拒绝退款，用户的权益已被收走却拿不到钱 —— 与重复发放对称的资损
                String orderNo = ledger.revokeIfUnused(revokeNo, grantOpNo);
                log.warn(
                        "mock provider revoked then timed out, revokeNo={}, orderNo={}",
                        revokeNo,
                        orderNo);
                throw new IllegalStateException("模拟回收超时（已回收）: " + revokeNo);
            }
            case TIMEOUT_BEFORE_COMMIT -> {
                log.warn("mock provider revoke timed out before commit, revokeNo={}", revokeNo);
                throw new IllegalStateException("模拟回收超时（未回收）: " + revokeNo);
            }
            case PROCESSING -> {
                log.info("mock provider revoke accepted, still processing, revokeNo={}", revokeNo);
                return revokeResp(RetStatus.PROCESSING, ledger.usageOf(grantOpNo), null, null);
            }
            case FAIL -> {
                log.info("mock provider revoke rejected, revokeNo={}", revokeNo);
                return revokeResp(
                        RetStatus.FAIL, ledger.usageOf(grantOpNo), null, ErrorCode.INVALID_PARAM);
            }
            default -> {
                String orderNo = ledger.revokeIfUnused(revokeNo, grantOpNo);
                String usage = ledger.usageOf(grantOpNo);
                if (orderNo == null) {
                    // 已核销 / 已过期：回收失败，且这是**确定的**失败 —— 重试拿到同一答案。
                    // usageStatus 与 retStatus 分列，调用方据此区分「不能回收」与「回收出错」
                    log.info(
                            "mock provider cannot revoke, already {}, revokeNo={}",
                            usage,
                            revokeNo);
                    return revokeResp(RetStatus.FAIL, usage, null, ErrorCode.BENEFIT_ALREADY_USED);
                }
                log.info(
                        "mock provider revoked, revokeNo={}, grantOpNo={}, orderNo={}",
                        revokeNo,
                        grantOpNo,
                        orderNo);
                return revokeResp(RetStatus.SUCCESS, "REVOKED", orderNo, null);
            }
        }
    }

    private static ProviderRevokeResp revokeResp(
            RetStatus status, String usageStatus, String orderNo, String errorCode) {
        ProviderRevokeResp r = new ProviderRevokeResp();
        r.setRetStatus(status);
        r.setUsageStatus(usageStatus);
        r.setProviderOrderNo(orderNo);
        r.setErrorCode(errorCode);
        return r;
    }

    private static ProviderGrantResp resp(RetStatus status, String orderNo, String errorCode) {
        ProviderGrantResp r = new ProviderGrantResp();
        r.setRetStatus(status);
        r.setProviderOrderNo(orderNo);
        r.setErrorCode(errorCode);
        return r;
    }
}
