package com.mp.api.mock.dto;

import java.io.Serializable;

/**
 * 支付方对账文件里的一行：一笔已收款的交易。V3 PR-10 后置补入（§6.8 第 8 项）。
 *
 * <p><b>字段刻意只有这三个</b>，因为真实的支付对账文件也只有这些 —— 支付方记的是「谁付了多少钱、 交易号是什么」，它<b>不知道</b>这笔钱对应平台的哪个活动、哪个
 * SKU、买了几份。
 *
 * <p>这个字段集合决定了第 8 项的处置只能是告警：查出「支付方有、本地无」之后，补记主单需要 {@code activity_id} / {@code sku_id} / {@code
 * price_snapshot} / {@code benefit_snapshot} 一整套业务数据，而 这里一个都没有。技术方案原文写「补记主单 +
 * 建履约任务」是设计阶段的构想，实施时降级为只告警 —— <b>凭占位值造出来的单会被后续履约当成真单发奖</b>，那比「本地无单」本身更糟。
 *
 * <p>用 record：它是一行只读数据，没有状态可变。
 *
 * @param outTradeNo 商户订单号，即平台的 {@code bizNo} —— 比对的连接键
 * @param tradeNo 支付方交易号
 * @param payAmount 实付金额，分
 */
public record PaidTradeRow(String outTradeNo, String tradeNo, long payAmount)
        implements Serializable {}
