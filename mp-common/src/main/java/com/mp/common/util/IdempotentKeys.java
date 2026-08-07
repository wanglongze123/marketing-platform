package com.mp.common.util;

/**
 * 幂等键派生：<b>确定性可重算，同一操作重试必须得到同一个值</b>。
 *
 * <p>与 {@link BizNoGenerator} 规则相反 —— 后者必须用随机源，此处恰恰禁止。两条铁律：
 *
 * <ol>
 *   <li>UNKNOWN / 超时重试<b>必须复用原键</b>。任务表建任务时即固化 opNo，重试只读不生成
 *   <li>键的来源必须是<b>外部输入或已落库的稳定值</b>。禁止内部自增序列、时间戳、UUID、随机数参与
 * </ol>
 *
 * <p>一律确定性字符串拼接，不用 hash —— 避免碰撞，且可读、可对账。
 */
public final class IdempotentKeys {

    private IdempotentKeys() {}

    /**
     * 履约发奖。<b>粒度定死为「一次调用 = 一个供应方」</b>：组合权益跨多个供应方时天然拆成 N 次调用、N 个幂等键。
     *
     * @param bizNo 业务主单号
     * @param providerType 供应方类型，取自订单快照
     */
    public static String grantOpNo(String bizNo, String providerType) {
        return bizNo + "_G_" + providerType;
    }

    /**
     * 支付回调。<b>payStatus 不入键</b> —— 入了键，「先到 SUCCESS、后到 CLOSED」的乱序通知两条都能插入， 第二条会把已支付订单关闭。乱序由主单条件更新拦截。
     *
     * <p>第一维取 {@code bizNo}（通知里的 {@code outTradeNo}）而非 {@code tradeNo}：后者在「支付下单成功 但回填前进程崩溃」的窗口内为
     * {@code NULL}，此时两笔订单只要 {@code notifySeq} 相同即派生出同一个 键，撞上 {@code play_op_record.uk_idempotent}
     * —— 第二笔的操作记录被 upsert 当成重复通知吞掉。 定位主单用的既然是 {@code outTradeNo}，幂等键取同一个值，两者的口径才一致。
     *
     * @param bizNo 主单号，即通知中的 {@code outTradeNo}
     * @param notifySeq 回调携带，重传时保持不变
     */
    public static String payCallback(String bizNo, String notifySeq) {
        return bizNo + "_" + notifySeq;
    }

    /**
     * 订单关闭。<b>一单至多一次关单</b>，故键里不含触发来源。
     *
     * <p>不把「谁触发的」（超时任务 / 用户取消 / 运营清理）编进键：那会让同一笔单被不同来源各关一次， 而每次关单都尝试释放库存。重复关单必须幂等（BR-B-18），键相同才能命中
     * {@code uk_idempotent}。
     */
    public static String closeOrder(String bizNo) {
        return bizNo + "_CLOSE";
    }

    // V3 补：refundNo、revokeNo、followerGrantNo、sponsorFlowNo
}
