package com.mp.common.exception;

/**
 * 业务规则不通过时抛出，携带 1xxx / 4xxx 错误码。
 *
 * <p>facade 层捕获后映射为 {@code RetStatus.FAIL}；<b>其余未预期异常一律映射为 UNKNOWN</b>， 因为未预期异常映射成
 * FAIL，调用方会走补偿路径，而下游可能已执行成功。
 */
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
