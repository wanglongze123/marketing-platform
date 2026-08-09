package com.mp.common.util;

/**
 * 幂等键派生：<b>确定性可重算，同一操作重试必须得到同一个值</b>。
 *
 * <p>与 {@link BizNoGenerator} 规则相反 —— 后者必须用随机源，此处禁止。两条铁律：
 *
 * <ol>
 *   <li>UNKNOWN / 超时重试<b>必须复用原键</b>。任务表建任务时即固化 opNo，重试只读不生成
 *   <li>键的来源必须是<b>外部输入或已落库的稳定值</b>。禁止内部自增序列、时间戳、UUID、随机数参与
 * </ol>
 *
 * <p>一律确定性字符串拼接，不用 hash —— 避免碰撞，且可读、可对账。
 *
 * <p><b>每一把派生出的键都在此处校验长度</b>（{@link ReqFields#requireKeyFits}）。校验放在派生处而非各调用方： 键列一律 {@code
 * VARCHAR(64)}，而拼进键里的上游取值（工单号、通知序号、外部流水号）长度平台管不着 —— 漏校验一处就是一条溢出路径，而<b>溢出的表现是异常被四分类吞成
 * UNKNOWN</b>，与下游超时无从区分。 放在这里，新增一把键时不可能忘。
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
        return ReqFields.requireKeyFits(bizNo + "_G_" + providerType, "grantOpNo");
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
        return ReqFields.requireKeyFits(bizNo + "_" + notifySeq, "payCallbackKey");
    }

    /**
     * 订单关闭。<b>一单至多一次关单</b>，故键里不含触发来源。
     *
     * <p>不把「谁触发的」（超时任务 / 用户取消 / 运营清理）编进键：那会让同一笔单被不同来源各关一次， 而每次关单都尝试释放库存。重复关单必须幂等（BR-B-18），键相同才能命中
     * {@code uk_idempotent}。
     */
    public static String closeOrder(String bizNo) {
        return ReqFields.requireKeyFits(bizNo + "_CLOSE", "closeOrderKey");
    }

    /**
     * 徒弟发奖。<b>与师傅返奖同源派生自 {@code outFlowNo}</b>（技术方案 §5.1）。
     *
     * <p>一次确权派生两把确定性键，重试时可重算 —— 这是「超时重试必须复用原键」的前提：任务表 只存 {@code outFlowNo}，两把发奖键在每次执行时现算，不落库也不会漂移。
     *
     * <p><b>不各自生成随机号</b>：那样重试会派生出新键，同一次确权变成两笔发放。
     *
     * @param outFlowNo 上游流水号，标识本次确权
     */
    public static String followerGrantNo(String outFlowNo) {
        return ReqFields.requireKeyFits(outFlowNo + "_FL", "followerGrantNo");
    }

    /**
     * 师傅返奖。同源派生，后缀与徒弟发奖不同。
     *
     * <p>两者<b>必须是不同的键</b>：同一个键会让师傅返奖被 {@code uk_idempotent} 当成徒弟发奖的重传 挡下 —— 师傅永远拿不到奖，且不报错。
     */
    public static String sponsorFlowNo(String outFlowNo) {
        return ReqFields.requireKeyFits(outFlowNo + "_SP", "sponsorFlowNo");
    }

    /**
     * 由徒弟发奖键反推 {@code outFlowNo}。
     *
     * <p>查单任务手上只有 {@code opNo}（即发奖键），而确权后置还需要 {@code outFlowNo} 与师傅返奖键。 三者同源，去掉后缀即可反推 ——
     * <b>反推而非把它们一并存进任务</b>：多存一份就多一处可能与 键规则漂移的副本，而后缀规则的事实来源只应有本类一处。
     */
    public static String outFlowNoOfFollowerGrant(String followerGrantNo) {
        if (followerGrantNo == null || !followerGrantNo.endsWith("_FL")) {
            throw new IllegalArgumentException("非徒弟发奖键: " + followerGrantNo);
        }
        return followerGrantNo.substring(0, followerGrantNo.length() - 3);
    }

    /**
     * 过期治理任务的操作号。<b>无下游单号的任务用确定性本地键填充</b>（技术方案 §3.3）。
     *
     * <p>不留空：{@code fission_task.op_no} 声明为 {@code NOT NULL DEFAULT ''} 正是因为 MySQL 唯一索引 不对 {@code
     * NULL} 去重 —— 允许为空则同一分片可无限重复入队，{@code uk_biz_type_op} 不起作用。
     *
     * <p>取 {@code bizNo + "_EXPIRE"} 而非空串：两者都能被唯一键去重，但日志与任务表里 {@code op_no}
     * 一栏为空时，读的人无从判断是「这类任务不需要」还是「有人忘了填」。
     *
     * @param shardBizNo 分片键，形如 {@code EXPIRE_SHARD_0_OF_1}
     */
    public static String expireOpNo(String shardBizNo) {
        return ReqFields.requireKeyFits(shardBizNo + "_EXPIRE", "expireOpNo");
    }

    /**
     * 权益回收。<b>与退款同源派生自上游退款请求号</b>，一退一收成对，便于审计先后（技术方案 §4.1）。
     *
     * <p><b>来源必须是上游请求号，不能是内部计数器</b>：内部计数器在重试时会递增，产生新键、绕过 唯一索引 —— 那正是「同一订单两个 revokeNo 回收两次」的成因。
     *
     * <p>与 {@link #grantOpNo} 不复用（BR-C-11）：复用会让回收撞上 {@code reward_grant_record.uk_op_no}， 被当成发奖重传吞掉
     * —— 权益实际没回收，而调用方拿到「成功」。
     *
     * @param refundReqNo 上游退款请求号，重试保持不变
     */
    public static String revokeNo(String bizNo, String refundReqNo) {
        return ReqFields.requireKeyFits(bizNo + "_V_" + refundReqNo, "revokeNo");
    }

    /**
     * 单个供应方的回收键。<b>粒度与 {@link #grantOpNo} 对齐</b>：一次发放调用对应一次回收调用。
     *
     * <p><b>由三个来源直接拼出，不拼接 {@code revokeNo} 与 {@code grantOpNo} 两把完整的键</b>。后者是 首版写法，实测在 {@code
     * VARCHAR(64)} 上溢出：{@code revokeNo} 约 40 字符、{@code grantOpNo} 约 50 字符，接起来 90+ 字符直接触发 {@code
     * Data too long}。
     *
     * <p><b>该失效的形态特别隐蔽</b>：插入异常被回收链路按「异常一律映射 UNKNOWN」捕获，于是主单进 {@code REVOKING}、落一条 {@code REVOKE}
     * 任务、对上游回报「结果未定」—— 看起来与供应方超时 一模一样，而实际上回收请求根本没发出去，重试多少次都是同一个结果。
     *
     * <p>教训是<b>幂等键的长度是它的一部分契约</b>：键由拼接构成时，每加一段都要回头核对列宽，而列宽 定在 DDL 里、拼接写在工具类里，两处不会互相提醒。
     *
     * @param providerType 供应方类型，与 {@code grantOpNo} 的第二维同源
     */
    public static String revokeItemNo(String bizNo, String refundReqNo, String providerType) {
        return ReqFields.requireKeyFits(
                bizNo + "_V_" + refundReqNo + "_" + providerType, "revokeItemNo");
    }

    /**
     * 由回收键反推上游退款请求号。
     *
     * <p>收敛链路手上只有主单与 {@code revokeNo}（从操作记录取），而重问下游需要按同一规则派生出 每个供应方的回收键。三者同源，去掉前缀即可反推 ——
     * <b>反推而非另存一份</b>：多存一份就多一处 可能与键规则漂移的副本，而<b>漂移的后果是二次回收</b>：下游按新键当成一次新的回收请求。
     *
     * <p>与 {@link #outFlowNoOfFollowerGrant} 是同一处置。
     */
    public static String refundReqNoOfRevoke(String revokeNo, String bizNo) {
        String prefix = bizNo + "_V_";
        if (revokeNo == null || !revokeNo.startsWith(prefix)) {
            throw new IllegalArgumentException("非该单的回收键: " + revokeNo);
        }
        return revokeNo.substring(prefix.length());
    }

    /**
     * 退款。与回收同源、后缀不同。
     *
     * <p>两者<b>必须是不同的键</b>：同键会让退款被回收记录的唯一索引挡下 —— 钱退不出去且不报错。
     */
    public static String refundNo(String bizNo, String refundReqNo) {
        return ReqFields.requireKeyFits(bizNo + "_R_" + refundReqNo, "refundNo");
    }
}
