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
 * 统一异常处理：对外只输出 {@code {code, message, data, traceId}}，不暴露四分类语义。
 *
 * <p>两类失败分开处置：
 *
 * <ul>
 *   <li><b>业务拒绝</b> —— HTTP 200 + 业务码，端侧据 code 分支
 *   <li><b>请求级失败</b>（路由不存在、方法不支持、报文不可读）—— 保留框架 4xx。未进业务逻辑，包成 200 会迫使端侧解析响应体才知道调错了
 * </ul>
 *
 * <p>继承 {@link ResponseEntityExceptionHandler} 以复用 Spring 对十余种 MVC 异常的状态码映射，
 * 自行枚举既易漏又随版本失准。此处只覆写响应体包装。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> onBiz(BizException e) {
        log.warn("biz rejected, code={}, msg={}", e.getCode(), e.getMessage());
        ApiResponse<Void> body = ApiResponse.fail(numericCode(e.getCode()), e.getMessage());
        body.setTraceId(TraceIdHolder.get());
        return ResponseEntity.ok(body);
    }

    /**
     * 错误码转数字。<b>不可解析时退化为 {@code 5001}，不向外抛</b>。
     *
     * <p>{@code ErrorCode} 声明为 {@code String} 常量而响应体的 {@code code} 是 {@code int} —— 端侧按
     * 号段分区判断处置方式（{@code 1xxx} 业务拒绝、{@code 4xxx} 入参非法、{@code 5xxx} 结果未知），
     * 故必须是数字。当前十个码全为纯数字，但类型本身不作此保证：日后新增一个 {@code "B1001"} 形式的码，{@code Integer.parseInt}
     * 会<b>在异常处理器内部抛异常</b>，响应壳随之崩塌，端上收到的 是不带 traceId 的裸 500。
     *
     * <p>退化取 {@code 5001} 而非 {@code 4001}：解析不了说明是平台自身的错误码定义问题，属系统异常。 判成入参非法会让调用方以为是自己传错了参数。
     */
    private static int numericCode(String code) {
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException | NullPointerException ex) {
            log.error("错误码非数字，无法映射到响应体 code，降级为 5001: {}", code);
            return Integer.parseInt(ErrorCode.DOWNSTREAM_UNKNOWN);
        }
    }

    /**
     * 兜底：业务逻辑内的未预期异常。请求级异常由父类分支拦下，不应到达此处。
     *
     * <p>5xxx 语义为「结果未知，按 UNKNOWN 查单收敛」，故客户端错误不得落到这里 —— 调用方会为一件未发生的事发起重试与对账。
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
