package com.mp.api.benefit.service;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.OpRecordItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.PreConsultReq;
import com.mp.api.benefit.dto.PreConsultResp;
import com.mp.api.benefit.dto.QueryOrderPageReq;
import com.mp.api.benefit.dto.QueryOrderPageResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.QuerySkuResp;
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
