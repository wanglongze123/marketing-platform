package com.mp.api.benefit.service;

import com.mp.api.benefit.dto.ConvergenceResp;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.benefit.dto.QueryOrderResp;
import com.mp.common.enums.RetStatus;

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
     * <p>推进到 PAY_SUCCESS 时在同一事务内落 {@code GRANT} 任务，履约由调度器驱动 —— 本方法返回时 {@code grantStatus} 仍是 {@code
     * NOT_START}。{@code affected_rows=0} 直接 ACK，不抛异常不重试。
     */
    RetStatus payCallback(PayCallbackReq req);

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
}
