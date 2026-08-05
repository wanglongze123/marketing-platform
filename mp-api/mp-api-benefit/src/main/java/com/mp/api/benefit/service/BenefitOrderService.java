package com.mp.api.benefit.service;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.OpRecordItem;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.QueryOrderPageReq;
import com.mp.api.benefit.dto.QueryOrderPageResp;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.api.benefit.dto.QuerySkuResp;
import com.mp.common.enums.RetStatus;
import java.util.List;

/** 权益售卖（玩法层）。方法名对应 play_op_record 的操作类型。 */
public interface BenefitOrderService {

    /**
     * 下单：组装权益快照、建主单、写操作记录，事务外调支付下单并回填 trade_no。
     *
     * <p>同 {@code clientReqNo} 重复请求返回原单，由 {@code uk_idempotent} 保证。
     */
    CreateTradeResp createTrade(CreateTradeReq req);

    /**
     * 支付结果通知：验金额后按 payStatus 分支做主单条件更新。
     *
     * <p>仅推进到 PAY_SUCCESS 时触发履约。{@code affected_rows=0} 直接 ACK，不抛异常不重试。
     */
    RetStatus payCallback(PayCallbackReq req);

    /**
     * 履约编排：读快照按 provider_type 分组，每组派生 grantOpNo 调 reward。
     *
     * <p>V1 由 payCallback 在事务提交后同步调用；V2 改由 GRANT 任务驱动。 重入时三处走 upsert，不抛 DuplicateKeyException。
     */
    RetStatus grantBenefit(String bizNo);

    /** 订单查询：三子状态 + 履约明细。 */
    QueryOrderResp queryOrder(String bizNo);

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
