package com.mp.common.util;

import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;

/**
 * 入参必填校验，统一抛 {@code 4001}。
 *
 * <p>参与幂等键、锁键或维度键派生的字段必须在此拦截。这类字段为空不会立即失败，而是拼进键里 继续执行：{@code null} 与空串都能参与字符串拼接，得到的键在不同订单之间可能相同 ——
 * 支付回调 的幂等键 {@code tradeNo + "_" + notifySeq} 在 {@code tradeNo} 为空时，两笔订单只要 {@code notifySeq} 相同即撞上
 * {@code play_op_record.uk_idempotent}，第二笔的操作记录被 upsert 吞掉且不留痕迹。
 *
 * <p>不引入 Bean Validation：仅为少数几个字段引入一层注解与 starter 依赖，收益不足，且注解校验 只覆盖入口，而这些字段在任务重放等非入口路径上同样需要保证。
 */
public final class ReqFields {

    private ReqFields() {}

    /**
     * 要求字段非空且非空白。
     *
     * @param name 字段名，写入错误信息供定位
     * @return 原值，便于在赋值处内联使用
     */
    public static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "必填参数缺失: " + name);
        }
        return value;
    }
}
