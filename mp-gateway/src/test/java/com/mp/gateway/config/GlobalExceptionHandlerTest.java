package com.mp.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import com.mp.common.web.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 错误码到响应体 {@code code} 的映射。
 *
 * <p>{@code ErrorCode} 是 {@code String} 常量而响应体的 {@code code} 是 {@code int}，端侧按号段分区 判断处置方式。转换处若直接
 * {@code parseInt}，一个非数字的码会<b>在异常处理器内部抛异常</b>， 端上收到不带 traceId 的裸 500。本类验的是该转换不会二次抛出。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void numericCodeIsMappedAsIs() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.onBiz(new BizException(ErrorCode.PRICE_MISMATCH, "价格已变化"));

        assertThat(resp.getStatusCode().value()).as("业务拒绝走 HTTP 200 + 业务码").isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(1711);
        assertThat(resp.getBody().getMessage()).isEqualTo("价格已变化");
    }

    /** 现有全部错误码都能映射 —— 逐个验，避免新增码时漏掉本约束。 */
    @Test
    void everyDefinedErrorCodeIsNumeric() {
        for (String code :
                new String[] {
                    ErrorCode.PRICE_MISMATCH,
                    ErrorCode.STOCK_NOT_ENOUGH,
                    ErrorCode.QUOTA_EXCEEDED,
                    ErrorCode.PAY_AMOUNT_MISMATCH,
                    ErrorCode.ORDER_ALREADY_PAID,
                    ErrorCode.INVALID_PARAM,
                    ErrorCode.INVALID_TOKEN,
                    ErrorCode.PAY_NOTIFY_SIGN_INVALID,
                    ErrorCode.DOWNSTREAM_UNKNOWN,
                    ErrorCode.CONCURRENT_CONFLICT
                }) {
            assertThat(handler.onBiz(new BizException(code, "x")).getBody().getCode())
                    .as("错误码 %s 应能映射为数字", code)
                    .isEqualTo(Integer.parseInt(code));
        }
    }

    /**
     * 非数字错误码降级为 {@code 5001}，不向外抛。
     *
     * <p>取 {@code 5001} 而非 {@code 4001}：解析不了说明是平台自身的错误码定义有问题，属系统异常。 判成入参非法会让调用方以为是自己传错了参数。
     */
    @Test
    void nonNumericCodeFallsBackToSystemErrorInsteadOfThrowing() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.onBiz(new BizException("B1001", "将来某个非数字码"));

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(5001);
        // 消息原样透出：降级的是 code，不是把失败原因也一并抹掉
        assertThat(resp.getBody().getMessage()).isEqualTo("将来某个非数字码");
    }

    /** 码为 null 时同样不抛。 */
    @Test
    void nullCodeFallsBackToSystemError() {
        assertThat(handler.onBiz(new BizException(null, "无码")).getBody().getCode()).isEqualTo(5001);
    }
}
