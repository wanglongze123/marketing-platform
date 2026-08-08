package com.mp.benefit.reconcile;

import com.mp.api.benefit.dto.ReconcileItem;
import com.mp.api.benefit.dto.ReconcileReport;
import com.mp.api.reward.dto.GrantRewardResp;
import com.mp.api.reward.service.RewardService;
import com.mp.benefit.repository.BenefitTaskMapper;
import com.mp.benefit.repository.ReconcileMapper;
import com.mp.benefit.service.StockKeys;
import com.mp.common.enums.TaskType;
import com.mp.common.util.BizNoGenerator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 权益侧对账（技术方案 §6.8）。V3 PR-10。
 *
 * <p><b>对账是防线之后的收口，不是防线本身</b>：四层防线拦住绝大多数问题，对账负责发现漏网的 （下游 bug、消息丢失、人为误操作）。故它的每一条处置都必须满足两个条件 ——
 * 幂等（BR-C-24）、 且<b>不覆盖状态</b>（BR-C-23，先查证再动）。
 *
 * <p><b>可自动补偿的项一律「补建任务」，不直接改业务状态</b>。这一条是整个对账最重要的形状：
 *
 * <ul>
 *   <li>补建任务是把单子重新推回既有的收敛通路，通路自带幂等闸（{@code uk_biz_type_op}、条件更新、 下游按 {@code opNo} 幂等），重复补建无害
 *   <li>直接改状态则绕过全部闸门 —— 对账自己成了第五条写入路径，而它是最少被测试的那条
 * </ul>
 *
 * <p><b>只告警的项一律不改数</b>（金额、库存计数、额度计数）：它们的正确值取决于历史，直接改会把 一次错误固化成新的基线，此后对账再也看不出它错过。判据挂在 {@link
 * ReconcileItem#isAutoRepair()} 上， 不写在本类的 {@code if} 里。
 *
 * <p><b>跨库比对不做 JOIN</b>（§3.1）：第 3、11 项经 {@code batchQueryByOpNos} 分批拉取后在内存比对。 四个分库账号使跨库 JOIN 在运行期直接
 * {@code access denied} —— 这条 V2 立下的约束在此首次被真正用到。
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    /** 每项单轮扫描上限。对账是旁路任务，一轮扫不完的下一轮继续 —— 不追求单轮扫尽 */
    private static final int SCAN_LIMIT = 200;

    /** 跨库比对的分批大小。它会被拼进 {@code IN} 子句，过大时执行计划会退化 */
    private static final int BATCH_SIZE = 50;

    private final ReconcileMapper reconcileMapper;
    private final BenefitTaskMapper taskMapper;
    private final ReconcileMetrics metrics;

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference
    @Autowired private RewardService rewardService;

    /**
     * 差异判定的时间下界（秒）。
     *
     * <p><b>它不是性能调优参数，而是「差异」这个概念的定义的一部分</b>：对账查的是「长期未收敛」。 取 0 则一笔刚下单还没来得及履约的单立刻被判为差异 ——
     * 而正常业务里这种单持续存在，告警随即 变成噪音，资损哨兵失去意义。
     *
     * <p>生产取值应显著长于「发放 + 查单收敛」的正常耗时（§5.8 实测 153s），测试压到 0 以便构造。
     */
    private final int staleSeconds;

    public ReconcileService(
            ReconcileMapper reconcileMapper,
            BenefitTaskMapper taskMapper,
            ReconcileMetrics metrics,
            @Value("${mp.reconcile.stale-seconds:300}") int staleSeconds) {
        this.reconcileMapper = reconcileMapper;
        this.taskMapper = taskMapper;
        this.metrics = metrics;
        this.staleSeconds = staleSeconds;
    }

    /**
     * 跑一轮权益侧对账，返回本轮报告。
     *
     * <p>每一项独立执行、独立捕获异常：<b>一项扫描失败不得让整轮中断</b> —— 十五项之间没有依赖，而 中断会让后面的项在本轮完全不执行， 表现为「对账跑了但某几项从来没扫过」。
     */
    public ReconcileReport reconcileOnce() {
        ReconcileReport report = new ReconcileReport();

        run(report, ReconcileItem.PAID_NOT_GRANTED, this::repairPaidNotGranted);
        run(report, ReconcileItem.REFUNDED_NOT_REVOKED, this::repairRefundedNotRevoked);
        run(report, ReconcileItem.OP_UNRESOLVED, this::repairUnresolvedOps);
        run(report, ReconcileItem.CLOSED_HOLDING_STOCK, this::repairClosedHoldingStock);
        run(report, ReconcileItem.STUCK_CLOSING, this::repairStuckClosing);
        run(report, ReconcileItem.GRANT_MISSING_DOWNSTREAM, this::checkGrantMissingDownstream);
        run(report, ReconcileItem.AMOUNT_MISMATCH, this::checkAmountMismatch);
        run(report, ReconcileItem.STOCK_MISMATCH, this::checkStockMismatch);
        run(report, ReconcileItem.QUOTA_MISMATCH, this::checkQuotaMismatch);
        run(report, ReconcileItem.DUPLICATE_GRANT, this::checkDuplicateGrant);

        log.info(
                "reconcile round done, diffs={}, repaired={}",
                report.getDiffs(),
                report.getRepaired());
        return report;
    }

    /** 一项的执行壳：统计差异、记指标、隔离异常。 */
    private void run(ReconcileReport report, ReconcileItem item, ItemRunner runner) {
        try {
            Outcome outcome = runner.run();
            report.addDiff(item, outcome.diff());
            report.addRepaired(item, outcome.repaired());
            metrics.onDiff(item, outcome.diff());
            if (outcome.diff() > 0 && !item.isAutoRepair()) {
                // 只告警的项：检出即须人工介入，故打 ERROR 而非 INFO
                log.error(
                        "reconcile diff needs manual handling, item={}, count={}",
                        item,
                        outcome.diff());
            }
        } catch (Exception e) {
            // 一项失败不影响其余项：十五项之间无依赖，中断会让后面的项本轮完全不执行
            log.error("reconcile item failed, item={}", item, e);
        }
    }

    // ------------------------------------------------------------------
    // 可自动补偿的项：一律补建任务，不直接改状态
    // ------------------------------------------------------------------

    /**
     * 第 1 项：已收款未履约 → 补建 {@code GRANT} 任务。
     *
     * <p>{@code op_no} 取 {@code bizNo + "_GRANT"}，与支付回调落的那条<b>同键</b>：若那条任务还在（只是没跑到）， 命中 {@code
     * uk_biz_type_op} 不会产生第二条；若它进了 {@code DONE} / {@code DEAD}，{@code enqueue} 的 upsert 会把它复活 ——
     * 这正是「补建任务」能自愈的机制。
     */
    private Outcome repairPaidNotGranted() {
        List<String> bizNos = reconcileMapper.scanPaidNotGranted(staleSeconds, SCAN_LIMIT);
        for (String bizNo : bizNos) {
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(),
                    bizNo,
                    TaskType.GRANT.name(),
                    bizNo + "_GRANT",
                    0,
                    "{}");
            log.warn("reconcile repaired paid-not-granted, bizNo={}", bizNo);
        }
        return new Outcome(bizNos.size(), bizNos.size());
    }

    /**
     * 第 2 项：已退款权益未回收 → 补建 {@code REVOKE} 任务 + 告警。
     *
     * <p><b>这一项检出即为已发生的资损</b>：钱已经退了而权益还在用户手里。补建任务只是尽力回收， 告警不可省 —— 券可能已被核销，那时回收不回来，须人工追。
     *
     * <p>{@code op_no} 取原 {@code revokeNo}（从操作记录读），不新造 —— 新造键会让下游把它当成一次新的 回收请求。
     */
    private Outcome repairRefundedNotRevoked() {
        List<String> bizNos = reconcileMapper.scanRefundedNotRevoked(staleSeconds, SCAN_LIMIT);
        int repaired = 0;
        for (String bizNo : bizNos) {
            String revokeNo = reconcileMapper.selectRevokeOpNo(bizNo);
            if (revokeNo == null) {
                // 没有原回收单号：这单从未走过准入，补不出正确的键。交人工
                log.error("reconcile found refunded order without revoke op, bizNo={}", bizNo);
                continue;
            }
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(), bizNo, TaskType.REVOKE.name(), revokeNo, 0, "{}");
            log.error(
                    "reconcile repaired refunded-not-revoked, bizNo={}, revokeNo={}",
                    bizNo,
                    revokeNo);
            repaired++;
        }
        return new Outcome(bizNos.size(), repaired);
    }

    /**
     * 第 4 项：操作记录长期非终态 → 补建对应的查单任务。
     *
     * <p>按主单当前状态决定补哪一种查单：这是「先查证再动」（BR-C-23）的体现 —— 对账不假定它卡在哪一步， 而是读当前状态再决定。
     */
    private Outcome repairUnresolvedOps() {
        List<String> bizNos = reconcileMapper.scanUnresolvedOps(staleSeconds, SCAN_LIMIT);
        int repaired = 0;
        for (String bizNo : bizNos) {
            var order = reconcileMapper.selectSnapshot(bizNo);
            if (order == null) {
                continue;
            }
            String payStatus = order.getPayStatus();
            String grantStatus = order.getGrantStatus();
            if ("CLOSING".equals(payStatus)) {
                taskMapper.enqueue(
                        BizNoGenerator.taskNo(),
                        bizNo,
                        TaskType.QUERY_CLOSE.name(),
                        bizNo + "_" + TaskType.QUERY_CLOSE.name(),
                        0,
                        "{}");
                repaired++;
            } else if ("GRANT_UNKNOWN".equals(grantStatus) || "GRANTING".equals(grantStatus)) {
                for (String opNo : reconcileMapper.selectGrantOpNos(bizNo)) {
                    taskMapper.enqueue(
                            BizNoGenerator.taskNo(),
                            bizNo,
                            TaskType.QUERY_GRANT.name(),
                            opNo,
                            0,
                            "{}");
                }
                repaired++;
            }
        }
        return new Outcome(bizNos.size(), repaired);
    }

    /**
     * 第 9 项：已关闭单仍占库存 → 补建 {@code STOCK_RELEASE} 任务。
     *
     * <p>释放任务自带每单幂等闸（主单 {@code stock_status} 的条件更新），故重复补建无害 —— 第二次执行时 条件更新命中 0 行，跳过实际的库存
     * UPDATE。<b>这正是「补建任务」而非「直接改库存」的理由</b>： 后者没有任何东西拦得住重复执行，而 {@code locked} 是所有订单共享的计数器。
     */
    private Outcome repairClosedHoldingStock() {
        List<String> bizNos = reconcileMapper.scanClosedStillHoldingStock(staleSeconds, SCAN_LIMIT);
        for (String bizNo : bizNos) {
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(),
                    bizNo,
                    TaskType.STOCK_RELEASE.name(),
                    bizNo + "_" + TaskType.STOCK_RELEASE.name(),
                    0,
                    "{}");
            log.warn("reconcile repaired closed-holding-stock, bizNo={}", bizNo);
        }
        return new Outcome(bizNos.size(), bizNos.size());
    }

    /**
     * 第 14 项：关单中间态未收敛 → 强制 {@code QUERY_CLOSE} 查单。
     *
     * <p>本项是 §6.4「任何中间态都必须同时具备准入谓词的入边与对账的一行」中的那一行。缺了它，一笔 关单 RPC 超时后又没被查单收敛的单永久停在 {@code CLOSING} ——
     * 而前十三项无一覆盖（第 1 项扫 {@code PAY_SUCCESS}、第 9 项扫 {@code CLOSED}）。
     */
    private Outcome repairStuckClosing() {
        List<String> bizNos = reconcileMapper.scanStuckClosing(staleSeconds, SCAN_LIMIT);
        for (String bizNo : bizNos) {
            taskMapper.enqueue(
                    BizNoGenerator.taskNo(),
                    bizNo,
                    TaskType.QUERY_CLOSE.name(),
                    bizNo + "_" + TaskType.QUERY_CLOSE.name(),
                    0,
                    "{}");
            log.warn("reconcile repaired stuck-closing, bizNo={}", bizNo);
        }
        return new Outcome(bizNos.size(), bizNos.size());
    }

    // ------------------------------------------------------------------
    // 只告警的项：一律不改数
    // ------------------------------------------------------------------

    /**
     * 第 3 项：平台有履约明细而 reward 侧查无 → 告警人工核，<b>不自动补发</b>。
     *
     * <p><b>跨库比对，分批拉取后在内存比对，不做 JOIN</b>（§3.1）。
     *
     * <p><b>不自动补发的理由</b>：查无有两种可能 —— 发放记录真的没落成（该补发），或对账读到的是一个
     * 尚未提交的窗口（不该补）。自动补发在第二种情况下就是重复发放，而这一项的检出量本就极低， 人工核一遍的成本远低于误补的代价。
     */
    private Outcome checkGrantMissingDownstream() {
        // 扫「已发放成功」的单，不是「还没发完」的单 —— 首版复用 scanPaidNotGranted 是错的，
        // 那个集合里的单本来就没有发奖记录，查无是正常的、不构成差异（PR-10 review 修）
        List<String> bizNos = reconcileMapper.scanGrantedOrders(staleSeconds, SCAN_LIMIT);
        int diff = 0;
        for (String bizNo : bizNos) {
            List<String> opNos = reconcileMapper.selectSucceededGrantOpNos(bizNo);
            for (int i = 0; i < opNos.size(); i += BATCH_SIZE) {
                List<String> batch = opNos.subList(i, Math.min(i + BATCH_SIZE, opNos.size()));
                Map<String, GrantRewardResp> downstream = rewardService.batchQueryByOpNos(batch);
                for (String opNo : batch) {
                    if (!downstream.containsKey(opNo)) {
                        log.error(
                                "reconcile found grant op missing downstream, bizNo={}, opNo={}",
                                bizNo,
                                opNo);
                        diff++;
                    }
                }
            }
        }
        return new Outcome(diff, 0);
    }

    /** 第 5 项：金额不一致 → P0 告警，<b>禁止自动改单</b>。 */
    private Outcome checkAmountMismatch() {
        List<String> bizNos = reconcileMapper.scanAmountMismatch(SCAN_LIMIT);
        for (String bizNo : bizNos) {
            log.error("reconcile found amount mismatch, bizNo={} — 禁止自动改单，须人工核", bizNo);
        }
        return new Outcome(bizNos.size(), 0);
    }

    /**
     * 第 6 项：库存与单据数比对 + 超卖检出 → 告警，产出 {@code stock_oversold_total}。
     *
     * <p>超卖数直接问库（{@code locked + consumed > total}），<b>不依赖任何业务代码的判断</b> —— 这正是 哨兵指标必须由对账产出的理由。
     */
    private Outcome checkStockMismatch() {
        int diff = 0;
        int oversold = reconcileMapper.countOversoldRows();
        if (oversold > 0) {
            metrics.onStockOversold(oversold);
            log.error("reconcile found oversold rows: {}", oversold);
            diff += oversold;
        }
        for (String skuId : reconcileMapper.selectPaidSkuIds()) {
            Integer consumed = reconcileMapper.selectConsumed(StockKeys.stockKey(skuId));
            if (consumed == null) {
                continue;
            }
            int orderedQty = reconcileMapper.sumConsumedQuantity(skuId);
            if (consumed != orderedQty) {
                log.error(
                        "reconcile found stock/order mismatch, skuId={}, consumed={}, ordered={}",
                        skuId,
                        consumed,
                        orderedQty);
                diff++;
            }
        }
        return new Outcome(diff, 0);
    }

    /**
     * 第 15 项：限购额度与单据数比对 → 告警，<b>禁止自动改 {@code used_qty}</b>。
     *
     * <p>与第 6 项是同一类兜底的两个计数器。<b>凡是有一个共享计数器、又要按单增减的地方，就要有一条 对账项</b> —— V2
     * 之前只有库存那一条，理由曾是「额度由库存那道闸一并保证」，而该假设不成立： 两者「是否占用过」并不同步（不限购的 SKU 不扣额度）。
     */
    private Outcome checkQuotaMismatch() {
        int diff = 0;
        for (var row : reconcileMapper.selectQuotaRows(SCAN_LIMIT)) {
            int used = row.getUsedQty() == null ? 0 : row.getUsedQty();
            int holding =
                    reconcileMapper.sumQuotaHoldingQuantity(
                            row.getUserId(), row.getActivityId(), row.getSkuId());
            if (used != holding) {
                log.error(
                        "reconcile found quota mismatch, user={}, sku={}, used={}, holding={}",
                        row.getUserId(),
                        row.getSkuId(),
                        used,
                        holding);
                diff++;
            }
        }
        return new Outcome(diff, 0);
    }

    /**
     * 第 11 项：重复发奖检出 → P0 告警，产出 {@code reward_duplicate_total}。
     *
     * <p>判据是「同一 {@code (bizNo, benefitItemId)} 存在两条成功的履约明细」。{@code uk_biz_item} 本该挡住它， 故本项检出非 0
     * 意味着唯一索引被绕过或数据被人改过 —— 这正是哨兵要盯的。
     */
    private Outcome checkDuplicateGrant() {
        int dup = reconcileMapper.countDuplicateGrantedItems();
        if (dup > 0) {
            metrics.onRewardDuplicate(dup);
            log.error("reconcile found duplicate grant rows: {}", dup);
        }
        return new Outcome(dup, 0);
    }

    /** 一项的执行结果：检出多少、修复多少。两者分列，理由见 {@code ReconcileReport}。 */
    private record Outcome(int diff, int repaired) {}

    @FunctionalInterface
    private interface ItemRunner {
        Outcome run() throws Exception;
    }
}
