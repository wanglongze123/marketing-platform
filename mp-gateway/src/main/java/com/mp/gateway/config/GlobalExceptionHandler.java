package com.mp.gateway.config;

import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一异常处理：对外只输出 {@code {code, message, data, traceId}}，不暴露内部四分类语义。
 *
 * <p><b>两类失败分开处置</b>：
 *
 * <ul>
 *   <li><b>业务拒绝</b> —— HTTP 200 + 业务码。端侧据 code 分支，HTTP 状态留给传输层
 *   <li><b>请求级失败</b>（路由不存在、方法不支持、报文不可读）—— 保留框架给出的 4xx。 请求根本没进业务逻辑，包成 200 会要求端侧解析响应体才知道自己调错了
 * </ul>
 *
 * <p><b>为什么继承 {@link ResponseEntityExceptionHandler}</b>：Spring 已为十余种 MVC 异常定好了 正确的状态码映射，自己用
 * {@code @ExceptionHandler} 逐个枚举既易漏又会随版本失准。 此处只覆写出口处的响应体包装，状态码沿用框架判断。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> onBiz(BizException e) {
        log.warn("biz rejected, code={}, msg={}", e.getCode(), e.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(Integer.parseInt(e.getCode()), e.getMessage());
        body.setTraceId(TraceIdHolder.get());
        return ResponseEntity.ok(body);
    }

    /**
     * 兜底：未预期异常。
     *
     * <p>只有真正到不了这里才对 —— 请求级异常应由父类分支拦下。5xxx 的语义是「结果未知， 按 UNKNOWN 查单收敛」，一个 URL 拼错的请求若报
     * 5001，调用方会为一件根本没发生的事 发起重试与对账。把客户端错误报成服务端错误，代价不只是状态码不准。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception e) {
        log.error("unexpected error", e);
        ApiResponse<Void> body =
                ApiResponse.fail(Integer.parseInt(ErrorCode.DOWNSTREAM_UNKNOWN), "system error");
        body.setTraceId(TraceIdHolder.get());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** 框架判定的请求级失败，统一套上响应壳，状态码不改。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // 客户端错误不打 ERROR —— 否则一次扫描器探测就能污染告警
        log.warn(
                "request rejected, status={}, type={}",
                status.value(),
                ex.getClass().getSimpleName());

        ApiResponse<Void> resp =
                ApiResponse.fail(
                        Integer.parseInt(ErrorCode.INVALID_PARAM),
                        HttpStatus.valueOf(status.value()).getReasonPhrase());
        resp.setTraceId(TraceIdHolder.get());
        return ResponseEntity.status(status).headers(headers).body(resp);
    }
}
