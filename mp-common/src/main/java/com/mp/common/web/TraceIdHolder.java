package com.mp.common.web;

import org.slf4j.MDC;

/** traceId 的 MDC 存取。V3 接入 SkyWalking 后改为使用其 traceId，MDC 键名保持不变。 */
public final class TraceIdHolder {

    public static final String KEY = "traceId";

    private TraceIdHolder() {}

    public static void set(String traceId) {
        MDC.put(KEY, traceId);
    }

    public static String get() {
        return MDC.get(KEY);
    }

    public static void clear() {
        MDC.remove(KEY);
    }
}
