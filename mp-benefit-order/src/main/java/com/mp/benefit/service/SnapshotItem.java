package com.mp.benefit.service;

/**
 * 权益项快照，序列化后存 {@code play_biz_record.benefit_snapshot}。
 *
 * <p>下单时冻结，履约与退款一律读快照、不再查配置表 —— 运营改权益包不影响存量单。
 *
 * <p><b>增删字段须保证双向兼容</b>，因为写入与读出可能相隔数月，其间发过版也可能回滚过：
 *
 * <ul>
 *   <li>旧版读新版数据：由反序列化器关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES} 承担，多出的字段被忽略
 *   <li>新版读旧版数据：由字段默认值承担，缺失的字段为 {@code null} 或 {@code 0}
 * </ul>
 *
 * <p>因此<b>新增字段不能把「缺失」与「取值为零」当成不同语义</b> —— 存量快照里它一律是后者。 需要区分时用包装类型并显式判 {@code null}。
 *
 * <p>删除字段则始终安全：旧数据中的该字段变成未知字段，被忽略。
 */
public record SnapshotItem(
        String benefitItemId,
        String benefitType,
        String providerType,
        String providerProductId,
        boolean core) {}
