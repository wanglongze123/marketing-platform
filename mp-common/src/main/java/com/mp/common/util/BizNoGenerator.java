package com.mp.common.util;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * 业务单号生成器：<b>全局唯一，每次调用必须不同</b>。
 *
 * <p>与 {@link IdempotentKeys} 规则相反 —— 后者要求确定性可重算，禁止随机源。两者刻意分开定义。
 *
 * <p>用 UUIDv7 而非「时间戳 + 8 位随机」：后者随机空间仅 32 bit，同一秒约 7.7 万次调用即有 50% 碰撞概率。 UUIDv7 前 48 bit
 * 为毫秒时间戳，既近似有序（降低页分裂）又有足够随机空间。
 *
 * <p>生成器只是概率保证，唯一索引才是确定性保证：插入路径须捕获 {@code DuplicateKeyException} 后重新生成重试。
 */
public final class BizNoGenerator {

    private BizNoGenerator() {}

    /** 业务主单号，落 {@code play_biz_record.play_biz_record_no}。同时作为支付侧的商户订单号。 */
    public static String bizNo() {
        return gen("BZ");
    }

    /** 履约明细号，落 {@code benefit_fulfillment_record.fulfillment_no}。 */
    public static String fulfillmentNo() {
        return gen("FF");
    }

    /** 可靠任务号，落 {@code benefit_task.task_no}。 */
    public static String taskNo() {
        return gen("TK");
    }

    /** 裂变关系号（V3）。 */
    public static String fissionRelationNo() {
        return gen("FR");
    }

    /** 裂变组号，落 {@code fission_group.group_id}（V3）。 */
    public static String fissionGroupNo() {
        return gen("FG");
    }

    /** 活动操作单号，落 {@code activity_op_record.op_no}（V3）。 */
    public static String activityOpNo() {
        return gen("AO");
    }

    /** 活动业务号，落 {@code marketing_activity.activity_id}（V3）。 */
    public static String activityId() {
        return gen("ACT");
    }

    private static String gen(String prefix) {
        return prefix + UuidCreator.getTimeOrderedEpoch().toString().replace("-", "");
    }
}
