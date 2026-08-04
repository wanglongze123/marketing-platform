package com.mp.common.enums;

/**
 * 履约明细<b>单项</b>发放态，落 {@code benefit_fulfillment_record.grant_status}。
 *
 * <p>取值不带前缀，与主单汇总态 {@link GrantStatus} 区分。二者是不同层级： 主单是「这一单整体发放到哪一步」，明细是「这一个权益项发放到哪一步」。
 */
public enum ItemGrantStatus {
    NOT_START,
    GRANTING,
    SUCCESS,
    FAILED,
    UNKNOWN
}
