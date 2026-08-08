package com.mp.benefit.service;

/**
 * 一笔<b>确实回收成功</b>的履约明细，供留痕使用。V3 PR-7/8 review 引入。
 *
 * <p>粒度与 {@code grantOpNo} 对齐 —— 一次发放调用对应一次回收调用，也对应一行留痕。
 *
 * <p><b>只装成功的那些</b>：回收按供应方逐笔发起，一单跨多个供应方时各项结果可以不同。整笔汇总为 失败或未定时，已经收走的那几件仍然收走了，留痕不能因为汇总结果而丢 —— 否则对账第 2
 * 项（已退款 权益未回收）会把它们判成差异，而那条项是资损哨兵，假阳性会让它失效。
 *
 * @param grantOpNo 被回收的原发奖操作单号，留痕的定位键
 * @param usageStatus 回收当时供应方回传的真实使用态（BR-B-30）
 */
public record RevokedItem(String grantOpNo, String usageStatus) {}
