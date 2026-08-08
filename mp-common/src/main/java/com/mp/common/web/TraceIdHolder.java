package com.mp.common.web;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * traceId 的 MDC 存取。V3 接入 SkyWalking 后改为使用其 traceId，MDC 键名保持不变。
 *
 * <p>MDC 基于 ThreadLocal，<b>不跨线程传递</b>。HTTP 入口由 {@code TraceIdFilter} 设置，而定时任务、 线程池提交的工作不经过 Filter
 * —— 需各自在入口处调用 {@link #newTrace()}，否则该链路的日志 全部不带 traceId。异步化之后这恰是最需要串联的部分：一笔单的下单、支付、履约分散在三个
 * 线程里，缺了它就只能靠 bizNo 逐行 grep。
 */
public final class TraceIdHolder {

    public static final String KEY = "traceId";

    private TraceIdHolder() {}

    public static void set(String traceId) {
        MDC.put(KEY, traceId);
    }

    /**
     * 生成并设置一个新 traceId，返回其值。
     *
     * <p>供非 HTTP 入口（定时任务、消息消费）使用。调用方须在结束时 {@link #clear()} —— 线程池的 线程会被复用，不清理则下一个任务继承上一个的
     * traceId，日志里两笔无关的业务被串成一条链。
     */
    public static String newTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        set(traceId);
        return traceId;
    }

    public static String get() {
        return MDC.get(KEY);
    }

    public static void clear() {
        MDC.remove(KEY);
    }
}
