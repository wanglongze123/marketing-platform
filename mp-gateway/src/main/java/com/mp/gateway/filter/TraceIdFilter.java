package com.mp.gateway.filter;

import com.mp.common.web.TraceIdHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 生成 traceId 写入 MDC，供日志与响应体使用。
 *
 * <p>放在 V0 而非 V1：横切能力，V1 一旦开始写业务代码再补，每个已写的 facade 都要回头改。
 */
@Component
@Order(1)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        try {
            TraceIdHolder.newTrace();
            chain.doFilter(req, resp);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
