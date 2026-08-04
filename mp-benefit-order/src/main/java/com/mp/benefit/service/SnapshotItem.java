package com.mp.benefit.service;

/**
 * 权益项快照，序列化后存 {@code play_biz_record.benefit_snapshot}。
 *
 * <p>下单时冻结，履约与退款一律读快照、不再查配置表 —— 运营改权益包不影响存量单。
 */
public record SnapshotItem(
        String benefitItemId,
        String benefitType,
        String providerType,
        String providerProductId,
        boolean core) {}
