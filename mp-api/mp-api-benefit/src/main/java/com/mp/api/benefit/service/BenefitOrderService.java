package com.mp.api.benefit.service;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.ManualRepairReq;
import com.mp.api.benefit.dto.ManualRepairResp;
import com.mp.api.benefit.dto.OpRecordItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.PreConsultReq;
import com.mp.api.benefit.dto.PreConsultResp;
import com.mp.api.benefit.dto.QueryOrderPageReq;
import com.mp.api.benefit.dto.QueryOrderPageResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.QuerySkuResp;
import com.mp.api.benefit.dto.ReconcileReport;
import com.mp.api.benefit.dto.RevokeAdmitReq;
import com.mp.api.benefit.dto.RevokeAdmitResp;
import com.mp.common.enums.RetStatus;
import java.util.List;

/** 权益售卖（玩法层）。方法名对应 play_op_record 的操作类型。 */
public interface BenefitOrderService {

    /**
     * 预咨询试算：服务端算价并签发咨询凭证。
     *
     * <p><b>只读，无业务单据副作用</b>（PRD FR-B01）—— 不占库存、不建单、不落操作记录。咨询通过 不代表下单必然通过（BR-B-02），资格与库存在 {@code
     * createTrade} 里重新校验。
     */
    PreConsultResp preConsult(PreConsultReq req);

    /**
     * 下单：验凭证 + 比价，随后组装权益快照、建主单、写操作记录，事务外调支付下单并回填 trade_no。
     *
     * <p>同 {@code clientReqNo} 重复请求返回原单，由 {@code uk_idempotent} 保证。
     *
     * <p>V2 起前置两道校验（技术方案 §5.2 ①、④.5）：验签验时效并逐字段比对（不符 {@code 4003}）、 服务端重算价与凭证成交价比对（不等 {@code
     * 1711}）。两道都在建单之前，拒绝时不留任何单据。
     */
    CreateTradeResp createTrade(CreateTradeReq req);

    /**
     * 支付结果通知：验金额后按 payStatus 分支做主单条件更新。
     *
     * <p>推进到 PAY_SUCCESS 时在同一事务内落 {@code GRANT} 任务，履约由调度器驱动 —— 本方法返回时 {@code grantStatus} 仍是 {@code
     * NOT_START}。{@code affected_rows=0} 直接 ACK，不抛异常不重试。
     */
    RetStatus payCallback(PayCallbackReq req);

    /**
     * 关闭订单：先问支付方能否关，按四分类分支处置。
     *
     * <p>触发来源有三：超时（{@code CLOSE_ORDER} 任务）、用户取消、运营清理 —— 三者走同一段代码， 幂等只需证明一次（BR-B-18）。
     *
     * <p><b>已支付的单拒绝关闭</b>（{@code 1741}，BR-B-16）；关单 RPC 结果未定则进 {@code CLOSING}
     * 并落查单任务，<b>此阶段不释放库存与额度</b> —— 结果未定就释放，等于把额度让给别人，而钱可能已经收了。
     *
     * @param opSeq 操作序号，留痕用。超时任务传空串
     * @return 关单本身的四分类结果，供调度器决定退避
     */
    RetStatus closeOrder(String bizNo, String opSeq);

    /**
     * 收敛 {@code CLOSING}：再问支付方，推进到 {@code CLOSED} 或 {@code PAY_SUCCESS}。
     *
     * <p>由 {@code QUERY_CLOSE} 任务驱动。确认已支付时转入正常履约并补建 {@code GRANT} 任务 ——
     * 「关单受理后用户其实付款成功了」是必须能收敛的路径，否则钱已收而订单永停中间态。
     */
    RetStatus reconcileClose(String bizNo);

    /**
     * 履约编排：读快照按 provider_type 分组，每组派生 grantOpNo 调 reward。
     *
     * <p>V1 由 payCallback 在事务提交后同步调用；V2 起由 {@code GRANT} 任务驱动，V3 还会由对账补偿触发 ——
     * 三条路径走同一段代码，幂等只需证明一次。重入时三处走 upsert，不抛 DuplicateKeyException。
     */
    RetStatus grantBenefit(String bizNo);

    /**
     * 退款准入 + 权益回收（FR-B08、BR-B-30）。V3 PR-7 引入。
     *
     * <p><b>准入判据是「发放结果是否确定」，不是「是否成功」</b>（技术方案 §7.5）：
     *
     * <table>
     *   <tr><th>grant 状态</th><th>处置</th><th>依据</th></tr>
     *   <tr><td>{@code NOT_START}</td><td>直接退，无需回收</td><td>权益从未发放</td></tr>
     *   <tr><td>{@code GRANT_FAILED}</td><td>直接退，无需回收</td><td>发放已确定失败，无权益在外</td></tr>
     *   <tr><td>{@code GRANT_SUCCESS}</td><td><b>先回收再退</b></td><td>BR-B-30</td></tr>
     *   <tr><td>{@code GRANTING}</td><td>拒绝 {@code 1751}</td><td>结果未定，回收对象不明</td></tr>
     *   <tr><td>{@code GRANT_UNKNOWN}</td><td>拒绝 {@code 1751}</td><td>BR-B-29 未知态不退款</td></tr>
     * </table>
     *
     * <p><b>不能写成「grant 未成功就不允许退款」</b> —— 那会把 {@code NOT_START} 与 {@code GRANT_FAILED}
     * 这两类最需要退款的单永久锁死，而「已支付未履约」正是对账要自动补偿的头号场景，退不了款 则收敛率必然破防。
     *
     * <p>回收 {@code UNKNOWN} 时准入通过但<b>不得推进退款</b>：权益可能已被收走也可能没有，此时 退款要么「退了钱权益还在」，要么用户既没权益也没钱。
     */
    RevokeAdmitResp revokeAndAdmit(RevokeAdmitReq req);

    /**
     * 退款执行（FR-B08）。V3 PR-8 引入。<b>必须在 {@link #revokeAndAdmit} 之后调用</b>。
     *
     * <p>顺序不可颠倒（技术方案 §5.6）：先退款后回收会让「回收失败」发生在钱已经退出去之后 —— 那时权益还在用户手里，且没有任何机制能把钱要回来。
     *
     * <p><b>前置校验主单必须处于 {@code REVOKING}</b>：该状态只能由 {@code revokeAndAdmit} 置入，故它
     * 本身就是「准入已通过且回收已完成」的凭据。绕过准入直接调本方法会被这道谓词挡下。
     *
     * <p>重复退款由三道闸拦截，{@code refundNo} 只是最弱的一道：主单条件更新挡状态非法与并发、 {@code uk_biz_op(bizNo,
     * 'CREATE_REFUND', '')} 挡「两个不同 {@code refundNo} 退两次」（客服 连点）、{@code refundNo} 唯一挡同键重传。
     *
     * <p>退款走 {@code UNKNOWN} 收敛：结果未定时落 {@code QUERY_REFUND} 任务，<b>不重发</b> —— 重发一笔 可能已成功的退款就是重复退款。
     *
     * @param refundReqNo 上游退款请求号，须与准入时一致 —— 两把幂等键由它派生
     * @return 退款本身的四分类结果
     */
    RetStatus createRefund(String bizNo, String refundReqNo);

    /**
     * 收敛退款 {@code UNKNOWN}：按原 {@code refundNo} 查单，推进到终态。
     *
     * <p>由 {@code QUERY_REFUND} 任务驱动，<b>复用原退款单号</b>。
     */
    RetStatus reconcileRefund(String bizNo);

    /**
     * 跑一轮对账（FR-C06、技术方案 §6.8）。V3 PR-10 引入。
     *
     * <p><b>可自动补偿的项一律「补建任务」，不直接改业务状态</b>：补建任务把单子推回既有的收敛通路， 通路自带幂等闸；直接改状态则绕过全部闸门 ——
     * 对账自己成了一条写入路径，而它是最少被测试的那条。
     *
     * <p><b>金额、库存计数、额度计数三类只告警不改数</b>：它们的正确值取决于历史，直接改会把一次错误 固化成新基线，此后对账再也看不出它错过。
     *
     * <p>由运维触发或定时任务驱动。V3 提供显式入口以便演示与测试。
     */
    ReconcileReport reconcile();

    /**
     * 人工处置（FR-C07、BR-C-27）。V3 PR-10 引入。
     *
     * <p><b>前五类动作一律复用原幂等键，不新造</b>：新造键即绕开 {@code uk_biz_op} 与下游的 {@code opNo} 幂等，等于给人工处置开一个可以重复发奖的后门
     * —— 而人工处置是最容易被重复点击的入口。
     *
     * <p><b>{@code operator} / {@code reason} 必填</b>：不留操作人则人工干预与自动收敛在库里无从区分， 对账算不出真实的自动收敛率。
     */
    ManualRepairResp manualRepair(ManualRepairReq req);

    /**
     * 收敛回收 {@code UNKNOWN}：按原 {@code revokeNo} 重问供应方。
     *
     * <p>由 {@code REVOKE} 任务驱动。收敛为成功后主单停在 {@code REVOKING}，等 {@link #createRefund} 推进 ——
     * 回收任务不自动发起退款，那是两个独立的决定。
     */
    RetStatus reconcileRevoke(String bizNo);

    /** 订单查询：三子状态 + 履约明细。 */
    QueryOrderResp queryOrder(String bizNo);

    /**
     * 收敛过程快照：操作记录 + 可靠任务的当前值。
     *
     * <p>验收对象是<b>状态迁移过程</b>而非终态 —— {@code queryOrder} 只给当前值，无法区分「正确收敛」 与「未发生故障」（《分阶段方案》§5.4）。
     */
    ConvergenceResp queryConvergence(String bizNo);

    // ------------------------------------------------------------------
    // 以下为只读查询，供端侧列表与排查使用。
    //
    // 三者均无副作用：不写状态、不落操作记录、不调下游，故不进 OrderTxService、
    // 不携带幂等键。加只读接口不改变《分阶段方案》§4.6「活动/SKU 管理接口范围外」
    // 的结论 —— 那条约束的是配置写入，此处只读。
    // ------------------------------------------------------------------

    /** 订单列表：按用户/活动/状态筛选，分页。不含履约明细。 */
    QueryOrderPageResp queryOrderPage(QueryOrderPageReq req);

    /** 商品详情：SKU 及包内权益项配置。只读，端侧据此渲染商品页而不必硬编码。 */
    QuerySkuResp querySku(String skuId);

    /** 某单的操作记录，按创建时间升序。排查用：看这一单先后发生过什么。 */
    List<OpRecordItem> queryOpRecords(String bizNo);
}
