package com.mp.mock.fault;

import com.mp.api.mock.dto.FaultMode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 故障注入控制面：运行期切换 mock 行为。
 *
 * <p><b>不做成 Spring Profile 或测试作用域 Bean</b>：手工验证在运行中的实例上进行，需要在不重启的
 * 前提下切换模式；自动化测试与人工验证也应共用同一入口，否则「测试里验过的」与「演示时跑的」 是两套代码（《分阶段方案》§5.3）。
 *
 * <p>状态是进程内的，重启即回到 {@link FaultMode#SUCCESS} —— mock 无状态重启本就允许。
 */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    /** {@code PROCESSING} 模式下，第几次查单转为成功 */
    private static final int DEFAULT_PROCESSING_TURNS = 2;

    private final AtomicReference<FaultMode> providerMode =
            new AtomicReference<>(FaultMode.SUCCESS);
    private final AtomicReference<FaultMode> payMode = new AtomicReference<>(FaultMode.SUCCESS);

    /** {@code PROCESSING} 模式下已发生的查单次数，达阈值后转成功 */
    private final AtomicInteger processingQueries = new AtomicInteger();

    private final AtomicInteger processingTurns = new AtomicInteger(DEFAULT_PROCESSING_TURNS);

    public FaultMode providerMode() {
        return providerMode.get();
    }

    public FaultMode payMode() {
        return payMode.get();
    }

    public void setProviderMode(FaultMode mode) {
        providerMode.set(mode);
        processingQueries.set(0);
        log.warn("fault injection: provider mode -> {}", mode);
    }

    public void setPayMode(FaultMode mode) {
        payMode.set(mode);
        log.warn("fault injection: pay mode -> {}", mode);
    }

    /** 供测试收紧 {@code PROCESSING} 的转换轮次，避免等满默认轮数。 */
    public void setProcessingTurns(int turns) {
        processingTurns.set(Math.max(1, turns));
        processingQueries.set(0);
    }

    /**
     * {@code PROCESSING} 模式下的查单：累计到阈值即转成功。
     *
     * @return true 表示本次查单应返回成功
     */
    public boolean processingShouldSucceedNow() {
        return processingQueries.incrementAndGet() >= processingTurns.get();
    }

    /** 全部复位。测试之间互不影响，靠的是每个用例自己设模式而非依赖顺序。 */
    public void reset() {
        providerMode.set(FaultMode.SUCCESS);
        payMode.set(FaultMode.SUCCESS);
        processingQueries.set(0);
        processingTurns.set(DEFAULT_PROCESSING_TURNS);
    }
}
