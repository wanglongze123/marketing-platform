package com.mp.mock.fault;

import com.mp.api.mock.dto.FaultMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * {@code PROCESSING} 模式下每个 {@code opNo} 各自的查单次数，达阈值后转成功。
     *
     * <p><b>按 opNo 分别计数，不用全局计数器</b>：一单跨多个供应方时有多条查单任务共享同一个 计数器，「第 N 次查单转成功」就变成「这批任务合计查了 N 次」——
     * 转换时机取决于有几个 供应方在查，mock 的行为不再可预测。
     */
    private final Map<String, AtomicInteger> processingQueries = new ConcurrentHashMap<>();

    private final AtomicInteger processingTurns = new AtomicInteger(DEFAULT_PROCESSING_TURNS);

    public FaultMode providerMode() {
        return providerMode.get();
    }

    public FaultMode payMode() {
        return payMode.get();
    }

    public void setProviderMode(FaultMode mode) {
        providerMode.set(mode);
        processingQueries.clear();
        log.warn("fault injection: provider mode -> {}", mode);
    }

    public void setPayMode(FaultMode mode) {
        payMode.set(mode);
        log.warn("fault injection: pay mode -> {}", mode);
    }

    /** 供测试收紧 {@code PROCESSING} 的转换轮次，避免等满默认轮数。 */
    public void setProcessingTurns(int turns) {
        processingTurns.set(Math.max(1, turns));
        processingQueries.clear();
    }

    /**
     * {@code PROCESSING} 模式下的查单：该 {@code opNo} 累计到阈值即转成功。
     *
     * @return true 表示本次查单应返回成功
     */
    public boolean processingShouldSucceedNow(String opNo) {
        return processingQueries.computeIfAbsent(opNo, k -> new AtomicInteger()).incrementAndGet()
                >= processingTurns.get();
    }

    /** 全部复位。测试之间互不影响，靠的是每个用例自己设模式而非依赖顺序。 */
    public void reset() {
        providerMode.set(FaultMode.SUCCESS);
        payMode.set(FaultMode.SUCCESS);
        processingQueries.clear();
        processingTurns.set(DEFAULT_PROCESSING_TURNS);
    }
}
