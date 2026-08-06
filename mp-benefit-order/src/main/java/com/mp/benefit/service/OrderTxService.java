package com.mp.benefit.service;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.benefit.config.BenefitTx;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.BenefitFulfillmentRecordMapper;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import com.mp.common.util.IdempotentKeys;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订单的事务边界，独立成 bean。
 *
 * <p><b>为什么不放在 BenefitOrderServiceImpl 内部</b>：{@code @Transactional} 依赖 Spring 代理，
 * 同类内部调用不经过代理，注解静默失效 —— 事务看起来配了、实际没开。这类缺陷不报错， 要到「状态改了但操作记录没落库」时才暴露，而那正是可靠任务表最怕的缺口。
 *
 * <p>本类的每个方法即一个事务单元，严格遵守《开发规范》§7.4：事务内只有 DB 操作， 无 RPC、无发消息、无 sleep。
 *
 * <p><b>只用 {@link BenefitTx} 而非裸 {@code @Transactional}</b>：四套数据源下不存在「默认」 事务管理器，不带 {@code
 * transactionManager} 属性的注解按类型注入会取到别库的管理器，{@code db_benefit} 的写 各自自动提交 ——
 * 同样不报错。这与上一段的代理失效同属一族：事务问题的失效形态是「没有事务」 而非「事务出错」（《分阶段方案》§5.6 ②）。
 */
@Service
public class OrderTxService {

    private static final Logger log = LoggerFactory.getLogger(OrderTxService.class);

    /** V1 支付有效期，V2 随关单任务一并配置化 */
    private static final int PAY_EXPIRE_MINUTES = 30;

    private final PlayBizRecordMapper bizRecordMapper;
    private final PlayOpRecordMapper opRecordMapper;
    private final BenefitTaskMapper taskMapper;
    private final BenefitFulfillmentRecordMapper fulfillmentMapper;

    public OrderTxService(
            PlayBizRecordMapper bizRecordMapper,
            PlayOpRecordMapper opRecordMapper,
            BenefitTaskMapper taskMapper,
            BenefitFulfillmentRecordMapper fulfillmentMapper) {
        this.bizRecordMapper = bizRecordMapper;
        this.opRecordMapper = opRecordMapper;
        this.taskMapper = taskMapper;
        this.fulfillmentMapper = fulfillmentMapper;
    }

    /** 建单 + 写操作记录。 */
    @BenefitTx
    public PlayBizRecord createOrder(
            CreateTradeReq req,
            String bizNo,
            long salePrice,
            int configVersion,
            String priceSnapshot,
            String benefitSnapshot) {
        PlayBizRecord record = new PlayBizRecord();
        record.setPlayBizRecordNo(bizNo);
        record.setActivityId(req.getActivityId());
        record.setSkuId(req.getSkuId());
        record.setUserId(req.getUserId());
        record.setClientReqNo(req.getClientReqNo());
        record.setQuantity(req.getQuantity());
        record.setPayStatus(PayStatus.WAIT_PAY.name());
        record.setGrantStatus(GrantStatus.NOT_START.name());
        record.setRefundStatus(RefundStatus.NONE.name());
        record.setOrderAmount(salePrice);
        record.setCurrency("CNY");
        record.setConfigVersion(configVersion);
        record.setPriceSnapshot(priceSnapshot);
        record.setBenefitSnapshot(benefitSnapshot);
        record.setExpireTime(LocalDateTime.now().plusMinutes(PAY_EXPIRE_MINUTES));
        bizRecordMapper.insert(record);

        opRecordMapper.upsert(
                bizNo + "_CREATE",
                req.getUserId()
                        + "_"
                        + req.getActivityId()
                        + "_"
                        + req.getSkuId()
                        + "_"
                        + req.getClientReqNo(),
                bizNo,
                req.getUserId(),
                req.getActivityId(),
                OpType.CREATE_TRADE.name(),
                "",
                OpStatus.SUCCESS.name());
        return record;
    }

    /**
     * 支付回调：条件更新 + 操作记录同事务。
     *
     * @return 是否真的推进了状态；false 表示条件不满足（重复或乱序通知）
     */
    @BenefitTx
    public boolean applyPayCallback(PayCallbackReq req, PlayBizRecord order, PayStatus target) {
        String bizNo = order.getPlayBizRecordNo();

        int rows =
                bizRecordMapper.advancePayStatus(
                        bizNo,
                        PayStatus.WAIT_PAY.name(),
                        target.name(),
                        req.getPayAmount(),
                        req.getTradeNo());

        // op_seq 取 notifySeq 而非空串：同一订单会收到多条语义不同的通知，各自都要留痕。
        // 取空串会让第二条在 uk_biz_op 上冲突被拒，执行不到上面的条件更新。
        opRecordMapper.upsert(
                bizNo + "_PAY_" + req.getNotifySeq(),
                IdempotentKeys.payCallback(req.getTradeNo(), req.getNotifySeq()),
                bizNo,
                order.getUserId(),
                order.getActivityId(),
                OpType.PAY_CALLBACK.name(),
                req.getNotifySeq(),
                OpStatus.SUCCESS.name());

        if (rows == 0) {
            // 幂等三道闸的第三道生效，不是错误：不抛异常、不重试、不打 ERROR
            log.info(
                    "payCallback rejected by conditional update, bizNo={}, target={}",
                    bizNo,
                    target);
            return false;
        }

        // 本地消息表：收款与「发起履约」绑同一事务。ACK 支付前 GRANT 任务已落库，
        // 此后任何一点崩溃，调度器重启后续跑 —— 这是「已收款必履约」的根。
        // 若改为提交后同步调用，「改状态」与「触发履约」之间存在无法消除的崩溃窗口。
        if (target == PayStatus.PAY_SUCCESS) {
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(),
                    bizNo,
                    TaskType.GRANT.name(),
                    // 一单一次履约编排，用确定性本地键而非留空 —— 唯一索引不对 NULL 去重
                    bizNo + "_GRANT",
                    // 立即可执行
                    0,
                    "{}");
        }

        log.info("payCallback advanced, bizNo={}, WAIT_PAY -> {}", bizNo, target);
        return true;
    }

    /** 履约启动：置 GRANTING + 落操作记录中间态。必须先于 RPC。 */
    @BenefitTx
    public void startGrant(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();

        // affected_rows=0 表示已是 GRANTING（重入），继续走发放流程 ——
        // reward 侧按 opNo 幂等，重复调用返回原结果，不会重复发放。
        // 入边含 GRANT_UNKNOWN：查单判定原调用未到达后的重发要能把状态推回 GRANTING
        bizRecordMapper.startGranting(bizNo);

        opRecordMapper.upsert(
                bizNo + "_GRANT",
                bizNo + "_GRANT",
                bizNo,
                order.getUserId(),
                order.getActivityId(),
                OpType.GRANT_BENEFIT.name(),
                "",
                OpStatus.PROCESSING.name());
    }

    /**
     * 履约收尾：回写主单与操作记录终态，未收敛项同事务落查单任务。
     *
     * <p><b>查单任务与状态回写同事务</b>：置 GRANT_UNKNOWN 却没落成任务，这笔单就永远停在未知态 —— 没有任何机制会再看它一眼。这与支付回调落 GRANT
     * 任务是同一个理由。
     *
     * @param unresolvedOpNos 未收敛的发奖幂等号，每个落一条 {@code QUERY_GRANT} 任务
     */
    @BenefitTx
    public void finishGrant(String bizNo, GrantStatus target, List<String> unresolvedOpNos) {
        bizRecordMapper.advanceGrantStatus(bizNo, GrantStatus.GRANTING.name(), target.name());

        // 本地执行态与下游四分类分列两栏：前者是 SUCCESS/FAILED/UNKNOWN，后者是 SUCCESS/FAIL/UNKNOWN。
        // 混用会让「本地已收敛但下游结果未知」这一状态无法表达
        OpStatus opStatus =
                switch (target) {
                    case GRANT_SUCCESS -> OpStatus.SUCCESS;
                    case GRANT_FAILED -> OpStatus.FAILED;
                    default -> OpStatus.UNKNOWN;
                };
        RetStatus downstream =
                switch (target) {
                    case GRANT_SUCCESS -> RetStatus.SUCCESS;
                    case GRANT_FAILED -> RetStatus.FAIL;
                    default -> RetStatus.UNKNOWN;
                };
        opRecordMapper.finish(
                bizNo, OpType.GRANT_BENEFIT.name(), "", opStatus.name(), downstream.name());

        for (String opNo : unresolvedOpNos) {
            // 幂等入队：重入时命中 uk_biz_type_op 不产生第二条。
            // op_no 取发奖幂等号 —— 组合权益跨多供应方时各自一条查单任务
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(), bizNo, TaskType.QUERY_GRANT.name(), opNo, 0, "{}");
        }
    }

    /**
     * 查单收敛：推进履约明细与主单发放态。
     *
     * <p>主单条件更新的前置状态是 {@code GRANT_UNKNOWN} —— 只有停在未知态的单才由查单推进。 若前置状态已变（如另一条查单任务先收敛了），{@code
     * affected_rows=0}，不覆盖。
     *
     * <p><b>组合权益跨多供应方时，一条查单任务只能收敛自己那一组</b>：主单要等所有组都收敛后 才能置终态。此处的处理是「本组成功且主单再无未收敛的明细」才推进主单 ——
     * 判据取自明细表， 不另建计数。
     */
    @BenefitTx
    public void settleGrant(String bizNo, String grantOpNo, RetStatus downstream) {
        ItemGrantStatus itemStatus =
                downstream == RetStatus.SUCCESS ? ItemGrantStatus.SUCCESS : ItemGrantStatus.FAILED;
        fulfillmentMapper.settleByGrantOpNo(bizNo, grantOpNo, itemStatus.name(), null);

        // 仍有未终结的明细则主单继续停在 GRANT_UNKNOWN，等其余查单任务
        int unresolved = fulfillmentMapper.countUnresolved(bizNo);
        if (unresolved > 0) {
            log.info(
                    "settleGrant partial, {} items still unresolved, bizNo={}, opNo={}",
                    unresolved,
                    bizNo,
                    grantOpNo);
            return;
        }

        int failed = fulfillmentMapper.countByStatus(bizNo, ItemGrantStatus.FAILED.name());
        GrantStatus target = failed > 0 ? GrantStatus.GRANT_FAILED : GrantStatus.GRANT_SUCCESS;
        bizRecordMapper.advanceGrantStatus(bizNo, GrantStatus.GRANT_UNKNOWN.name(), target.name());

        boolean success = target == GrantStatus.GRANT_SUCCESS;
        opRecordMapper.finish(
                bizNo,
                OpType.GRANT_BENEFIT.name(),
                "",
                success ? OpStatus.SUCCESS.name() : OpStatus.FAILED.name(),
                success ? RetStatus.SUCCESS.name() : RetStatus.FAIL.name());
        log.info("settleGrant converged, bizNo={}, target={}", bizNo, target);
    }

    /**
     * 连续查无达阈值：以原 {@code opNo} 重发。
     *
     * <p><b>复用原 {@code opNo} 而非重新派生</b>：若原调用实际已到达，重发携带同一键会被下游账本 挡下并返回原单号 ——
     * 这正是「重发时机判早了不构成资损」的依据。重新派生则每次重发都是 一笔全新的发放。
     */
    @BenefitTx
    public void enqueueRegrant(String bizNo, String grantOpNo) {
        // 任务类型 GRANT、op_no 取原发奖幂等号：与支付回调落的那条（op_no = bizNo + "_GRANT"）
        // 不同键，故不会被 uk_biz_type_op 挡下，这是有意的 —— 它是针对某一供应方的定向重发
        taskMapper.enqueue(
                BizNoGenerator.taskNo(), bizNo, TaskType.GRANT.name(), grantOpNo, 0, "{}");
    }

    /** 回填支付单号。独立短事务，不改任何状态。 */
    @BenefitTx
    public void fillTradeNo(String bizNo, String tradeNo) {
        bizRecordMapper.fillTradeNo(bizNo, tradeNo);
    }
}
