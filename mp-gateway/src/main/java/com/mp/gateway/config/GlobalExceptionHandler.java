package com.mp.gateway.config;

import com.mp.common.exception.BizException;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常处理：对外只输出 {@code {code, message, data, traceId}}，不暴露内部四分类语义。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> onBiz(BizException e) {
        log.warn("biz rejected, code={}, msg={}", e.getCode(), e.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(Integer.parseInt(e.getCode()), e.getMessage());
        body.setTraceId(TraceIdHolder.get());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception e) {
        log.error("unexpected error", e);
        ApiResponse<Void> body = ApiResponse.fail(5001, "system error");
        body.setTraceId(TraceIdHolder.get());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
