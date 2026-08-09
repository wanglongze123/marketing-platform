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
 * <p><b>长度同理，且更隐蔽</b>：为空至少还有唯一索引兜底，而过长是直接溢出 {@code VARCHAR(64)}。 见 {@link #requireKeyFits}。
 *
 * <p>不引入 Bean Validation：仅为少数几个字段引入一层注解与 starter 依赖，收益不足，且注解校验 只覆盖入口，而这些字段在任务重放等非入口路径上同样需要保证。
 */
public final class ReqFields {

    private ReqFields() {}

    /**
     * 幂等键列的宽度，四个库一致（技术方案 §4.1、§3.3）。
     *
     * <p><b>键长度是键契约的一部分，不是存储细节</b>：派生规则可以改，这个上限改不了 —— 改它要同时 迁移四个库的十余张表。
     */
    public static final int KEY_MAX_LEN = 64;

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

    /**
     * 要求<b>派生出的幂等键</b>不超过 {@code VARCHAR(64)}。
     *
     * <p><b>校验的是键本身，不是某个上游字段的长度</b>。这个区别是实测逼出来的：起初按「上游字段最长 16 字符」写， 而键的形状并不统一——退款键是 {@code bizNo +
     * 分隔符 + refundReqNo}（本地单号先占 34 字符），建单键却是 {@code userId + activityId + skuId + clientReqNo}
     * 四个上游字段拼成，两者留给单个字段的余量差着数倍。<b>一刀切的单字段上限对前者太松、对后者太紧</b>，实测直接判红了一条正常用例。
     *
     * <p>改为校验成品键之后，各处按自己的算术自然成立，日后键多一段后缀也不用回头调参数。
     *
     * <p><b>为什么必须在入口拦而不是让数据库拦</b>：数据库拦下时事务已经开始、部分写已经发生，而异常在 四分类里的归属并不确定 —— PR-7/8 那次同类的溢出被「异常一律
     * UNKNOWN」吞掉，表现与供应方超时 完全一样：主单进中间态、落任务、回报结果未定，<b>而请求根本没发出去</b>。入口校验把它变成一个 确定的入参错误（{@code
     * 4001}），调用方立刻知道该换个短的单号。
     *
     * <p>实测：一个 40 字符的客服工单号（比 UUID 的 36 位还短）会让 {@code revokeNo} 达到 77 字符、 {@code revokeItemNo} 达到 88
     * 字符，双双溢出。
     *
     * @param key 已派生出的完整幂等键
     * @param name 键的名字，写入错误信息供定位
     * @return 原值，便于在赋值处内联使用
     */
    public static String requireKeyFits(String key, String name) {
        required(key, name);
        if (key.length() > KEY_MAX_LEN) {
            throw new BizException(
                    ErrorCode.INVALID_PARAM,
                    "幂等键过长: "
                            + name
                            + "，上限 "
                            + KEY_MAX_LEN
                            + " 字符，实际 "
                            + key.length()
                            + " —— 请缩短上游单号");
        }
        return key;
    }
}
