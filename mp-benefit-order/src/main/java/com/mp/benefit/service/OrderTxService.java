package com.mp.benefit.service;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.benefit.config.BenefitTx;
import com.mp.benefit.entity.PlayBizRecord;
import com.mp.benefit.repository.BenefitFulfillmentRecordMapper;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.repository.MarketingStockMapper;
import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.repository.PlayOpRecordMapper;
import com.mp.benefit.repository.UserPurchaseQuotaMapper;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import com.mp.common.enums.StockStatus;
import com.mp.common.enums.TaskType;
import com.mp.common.exception.BizException;
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
    private final MarketingStockMapper stockMapper;
    private final UserPurchaseQuotaMapper quotaMapper;

    public OrderTxService(
            PlayBizRecordMapper bizRecordMapper,
            PlayOpRecordMapper opRecordMapper,
            BenefitTaskMapper taskMapper,
            BenefitFulfillmentRecordMapper fulfillmentMapper,
            MarketingStockMapper stockMapper,
            UserPurchaseQuotaMapper quotaMapper) {
        this.bizRecordMapper = bizRecordMapper;
        this.opRecordMapper = opRecordMapper;
        this.taskMapper = taskMapper;
        this.fulfillmentMapper = fulfillmentMapper;
        this.stockMapper = stockMapper;
        this.quotaMapper = quotaMapper;
    }

    /**
     * 建单 + 写操作记录 + <b>原子预占库存与限购额度，同一本地事务</b>。
     *
     * <p>「500 并发抢 100 库存恰好成 100 单」（PRD AC-03）就实现在这里，靠的是两条带条件的 UPDATE， 不是分布式锁：{@code WHERE total -
     * locked - consumed >= qty} 由行锁串行化，{@code affected_rows} 即判定成败。锁只是减少走到这一步的冲突（L2），正确性由 DB
     * 保证（L3）。
     *
     * <p><b>预占与建单必须同事务</b>：分开则存在「扣了库存但单没建成」的漏 —— 那部分库存再也没人 释放，可售余量永久少一份。反过来「建了单没扣库存」则直接超卖。
     *
     * <p><b>两处扣减的顺序是库存在前、限购在后</b>，理由是失败率：库存是全局竞争、更容易不足，先扣 它可以让多数失败请求少做一次写。顺序不影响正确性 —— 任一失败都整体回滚。
     *
     * @throws BizException 库存不足 {@code 1712} / 超出限购 {@code 1713}，两者均使整个事务回滚
     */
    @BenefitTx
    public PlayBizRecord createOrder(
            CreateTradeReq req,
            String bizNo,
            long salePrice,
            int configVersion,
            int purchaseLimitQty,
            String priceSnapshot,
            String benefitSnapshot) {
        int qty = req.getQuantity();

        // ① 预占库存。affected_rows=0 即余量不足
        if (stockMapper.tryLock(StockKeys.stockKey(req.getSkuId()), qty) == 0) {
            // 抛异常而非返回 false：本方法要么整体成功，要么什么都没发生。
            // 返回 false 会让调用方有机会「忽略失败继续建单」，那就是超卖
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH, "库存不足: " + req.getSkuId());
        }

        // ② 扣减限购额度。limitQty=0 表示不限购，跳过 —— 建一行 limit_qty=0 的记录会让
        // 后续每次扣减都撞 used_qty + qty <= 0 而失败，等于「不限购」被实现成「一件都不能买」
        if (purchaseLimitQty > 0) {
            consumeQuota(req, qty, purchaseLimitQty);
        }

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
        // 预占已在本事务开头完成，故建单即 LOCKED。它是后续「只处置一次」的前置状态
        record.setStockStatus(StockStatus.LOCKED.name());
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
     * 扣减限购额度：先确保行存在，再原子扣减。
     *
     * <p>额度行是运行时数据，用户首次下单时才存在，无法预先 seed。建行走幂等 upsert —— 写成 「查不到就 insert」会让两个并发的首单同时查不到、同时
     * insert，第二个撞 {@code uk_quota}。
     */
    private void consumeQuota(CreateTradeReq req, int qty, int limitQty) {
        String periodKey = StockKeys.periodKey();
        quotaMapper.ensureRow(
                req.getUserId(), req.getActivityId(), req.getSkuId(), periodKey, limitQty);

        if (quotaMapper.tryConsume(
                        req.getUserId(), req.getActivityId(), req.getSkuId(), periodKey, qty)
                == 0) {
            throw new BizException(
                    ErrorCode.QUOTA_EXCEEDED, "超出限购额度: " + req.getSkuId() + " 限 " + limitQty);
        }
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
            // 库存转消耗同样落任务而非在此直接改库存（技术方案 §7.4）：同一 SKU 的库存是单行，
            // 500 QPS 的支付通知加上下单预占全部争抢它，持锁时间还会把事务内其余三写一起拖长。
            // 移出后回调事务只写 op_record + pay_status + 两条任务，热点行不在同步路径上
            enqueueStockTask(bizNo, TaskType.STOCK_CONSUME);
        } else if (target == PayStatus.PAY_FAILED) {
            // 支付失败即交易未成立：库存与限购额度都要还回去（技术方案 §3.4 的口径表）。
            // 两者由 STOCK_RELEASE 一条任务承接 —— 它们需要同一道幂等闸（主单库存态的条件更新），
            // 拆成两条任务则那道闸只能被其中一条用掉，另一条必然跳过而漏掉自己那半件事。
            //
            // 与转消耗互斥：两者都以本次条件更新 affected_rows=1 为前提，
            // 而 WAIT_PAY 只能被推进一次，故两条分支不可能同时落任务
            enqueueStockTask(bizNo, TaskType.STOCK_RELEASE);
        }

        log.info("payCallback advanced, bizNo={}, WAIT_PAY -> {}", bizNo, target);
        return true;
    }

    /**
     * 库存类任务入队。
     *
     * <p><b>{@code op_no} 必须取 {@code bizNo + '_' + taskType}，不能留空串</b>（技术方案 §7.4）。每单幂等 完全由 {@code
     * uk_biz_type_op} 承担 —— 库存 SQL 的下界 {@code WHERE locked >= ?} 提供不了： {@code locked} 是该 {@code
     * stock_key} 下所有订单共享的计数器，A 单重复释放两次时它因别的订单 占用仍远大于 0，下界根本不会拦，结果是 A 释放了别人的预占，可售余量凭空多一份。
     *
     * <p>留空串则同一单可插入无数条释放任务，唯一键形同虚设 —— 这正是 §3.3 警告过的 {@code NOT NULL DEFAULT ''} 陷阱。
     */
    private void enqueueStockTask(String bizNo, TaskType taskType) {
        taskMapper.enqueue(
                BizNoGenerator.taskNo(),
                bizNo,
                taskType.name(),
                bizNo + "_" + taskType.name(),
                0,
                "{}");
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

    // ------------------------------------------------------------------
    // 库存类任务的执行体
    // ------------------------------------------------------------------

    /**
     * 预占转消耗。<b>先推进主单库存态，推不动就跳过</b>。
     *
     * <p>{@code advanceStockStatus} 的 {@code affected_rows = 0} 意味着本单库存已处置过 —— 这是每单幂等的 唯一承重点，见
     * {@link PlayBizRecordMapper#advanceStockStatus}。跳过后仍返回 {@code SUCCESS}：任务重跑
     * 本就是预期路径，判失败会让它一直重试到死信，而事情早已做完。
     *
     * <p><b>不能用库存 SQL 的 {@code affected_rows} 代替这道判断</b>：{@code WHERE locked >= qty} 在别的订单占着
     * 库存时照常通过，重复执行会吃掉别人的预占。
     */
    @BenefitTx
    public RetStatus consumeStock(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();
        if (bizRecordMapper.advanceStockStatus(
                        bizNo, StockStatus.LOCKED.name(), StockStatus.CONSUMED.name())
                == 0) {
            log.info("stock already settled, skip consume, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }
        stockMapper.tryConsume(StockKeys.stockKey(order.getSkuId()), order.getQuantity());
        return RetStatus.SUCCESS;
    }

    /**
     * 释放预占，<b>并在同一次状态推进里返还限购额度</b>。
     *
     * <p>两件事合并为一个任务，是因为它们需要<b>同一道闸</b>：额度行按 {@code (user, activity, sku, period)}
     * 聚合，同一用户的另一笔单占着额度时，重复返还会吃掉那一笔的 —— 与库存的 {@code locked} 完全同构，{@code used_qty >= qty} 这个下界同样拦不住。
     *
     * <p>拆成两个任务则要么各带一道闸（两次状态推进，语义上没有第二个状态可推）、要么共用一道 （谁先跑谁推进，另一个必然跳过而漏掉自己那半件事）。合并后只需一道，且「交易未成立」本就是
     * 一个事件，库存与额度同进同退。
     *
     * <p><b>退款不返还额度</b> —— 限购是为了防单用户过度占用营销资源，若「买了再退」能刷回额度， 限购形同虚设。库存则相反，退款要回补，商品可以再卖给别人。这个不对称写在技术方案
     * §3.4 的 口径表里，是有意为之。退款属 V3，此处只处置「未成立」。
     */
    @BenefitTx
    public RetStatus releaseStock(PlayBizRecord order) {
        String bizNo = order.getPlayBizRecordNo();
        if (bizRecordMapper.advanceStockStatus(
                        bizNo, StockStatus.LOCKED.name(), StockStatus.RELEASED.name())
                == 0) {
            log.info("stock already settled, skip release, bizNo={}", bizNo);
            return RetStatus.SUCCESS;
        }

        stockMapper.tryRelease(StockKeys.stockKey(order.getSkuId()), order.getQuantity());
        // 不限购的单没有额度行，affected_rows=0 属正常，不必区分
        quotaMapper.tryRelease(
                order.getUserId(),
                order.getActivityId(),
                order.getSkuId(),
                StockKeys.periodKey(),
                order.getQuantity());
        return RetStatus.SUCCESS;
    }
}
