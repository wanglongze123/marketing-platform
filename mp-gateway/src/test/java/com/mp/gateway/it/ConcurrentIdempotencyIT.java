package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.api.reward.dto.RewardItem;
import com.mp.api.reward.service.RewardService;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.PayStatus;
import com.mp.common.exception.BizException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 幂等三道闸在<b>并发下</b>的验证，对应《分阶段方案》§5.7 退出标准 10、能力清单第 3 项。
 *
 * <p><b>与 {@code IdempotencyIT} 验的不是同一件事。</b> 那里是单线程重复调用，验「第二次调用返回 原结果」；这里是 N
 * 个线程同时进入同一分支，验「唯一索引真的生效」。二者的失效模式不同：
 *
 * <ul>
 *   <li>「先查后插」在串行下完全正确 —— 第二次查得到，直接返回原结果
 *   <li>并发下两个线程同时查不到、同时插入，第二个撞唯一索引。若代码没有捕获 {@code DuplicateKeyException}，用户看到的是 500 而非原单
 * </ul>
 *
 * <p><b>L2 锁不参与这里的正确性。</b> 本类的断言在 {@code mp.lock.enabled=false} 时同样成立 —— 那正是退出标准第 15 条要证明的：锁减少走到 L3
 * 的冲突，L3 才是兜底。故此处不断言「锁生效」， 只断言「结果唯一」。
 *
 * <p><b>但本类单独存在是不够的，必须与 {@link LockDisabledCorrectnessIT} 成对。</b> PR-7 自查时实测：
 * 把「幂等命中返回原单」这段代码去掉（即不捕获 {@code DuplicateKeyException} 分流），<b>本类四条 用例全绿，只有去锁组变红</b> ——
 * 因为锁把并发串行化了，第二个线程进临界区时第一笔单已提交， 走的是「先查后得」而非「唯一索引冲突」，那条分支根本没被执行到。
 *
 * <p>换句话说：<b>开了锁的并发测试，测不到锁本该保护的那条路径。</b> 这不是本类的缺陷，而是并发 测试的固有性质 —— 锁越有效，越难压出底层的竞争。真正验证 L3 的是去锁组。
 */
class ConcurrentIdempotencyIT extends AbstractMySqlIT {

    @Autowired private RewardService rewardService;

    private static final int THREADS = 12;

    @BeforeEach
    void resetStock() {
        benefitJdbc.update(
                "UPDATE marketing_stock SET total = 1000, locked = 0, consumed = 0"
                        + " WHERE stock_key = ?",
                "sku:" + SKU_ID);
        benefitJdbc.update("DELETE FROM user_purchase_quota");
    }

    /**
     * <b>第一道闸：同一 {@code clientReqNo} 并发建单，只产生一笔。</b>
     *
     * <p>N 个线程持完全相同的请求同时下单。允许的结果只有两种：拿到同一个 {@code bizNo}，或被 锁挡下（{@code
     * 5002}，上游按未知态重试）。<b>不允许</b>的是两个不同的 {@code bizNo} —— 那是同一次购买意图建出了两笔单。
     */
    @Test
    void concurrentCreateTradeProducesExactlyOneOrder() throws Exception {
        CreateTradeReq req = newTradeReq("concCreate");

        List<String> bizNos = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger busy = new AtomicInteger();

        runConcurrently(
                THREADS,
                i -> {
                    try {
                        // 每个线程独立构造请求对象，避免共享可变状态干扰
                        CreateTradeReq own = newTradeReq("concCreate");
                        bizNos.add(benefitOrderService.createTrade(own).getBizNo());
                    } catch (BizException e) {
                        // 抢不到锁是允许的结果 —— 它不是失败，是「稍后重试」
                        assertThat(e.getCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
                        busy.incrementAndGet();
                    }
                    return null;
                });

        assertThat(bizNos).as("至少要有一个线程成功建单").isNotEmpty();
        assertThat(bizNos.stream().distinct().toList())
                .as("同一 clientReqNo 并发建单只能产生一个 bizNo")
                .hasSize(1);
        assertThat(bizNos.size() + busy.get()).isEqualTo(THREADS);

        // 数据库才是最终判据：接口返回同一个号，也可能是两行记录里读出来的
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE client_req_no = ?",
                                "REQ_concCreate"))
                .as("主单表只应有一行")
                .isEqualTo(1);
        // 库存也只应被占一份 —— 建出两笔单的另一种表现是多占库存
        assertThat(lockedOf()).as("并发建单只占一份库存").isEqualTo(1);
    }

    /**
     * <b>第二道闸：同一 {@code notifySeq} 并发投递支付通知，只推进一次。</b>
     *
     * <p>支付平台重试时会并发投递同一条通知。{@code uk_biz_op(bizNo, 'PAY_CALLBACK', notifySeq)} 保证 操作记录只有一行；主单条件更新保证
     * {@code WAIT_PAY} 只被推进一次。
     *
     * <p>关键断言是<b>任务只落一条</b>：GRANT 任务若落两条，调度器会发两次货 —— 虽然 reward 侧按 {@code opNo} 幂等挡得住，但那是把幂等责任推给了下游。
     */
    @Test
    void concurrentPayCallbackAdvancesOnlyOnce() throws Exception {
        String bizNo = benefitOrderService.createTrade(newTradeReq("concPay")).getBizNo();
        String tradeNo = "PAY1_" + bizNo;

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();

        runConcurrently(
                THREADS,
                i -> {
                    try {
                        PayCallbackReq cb = newPayCallback(bizNo, tradeNo, "NS_conc", "SUCCESS");
                        benefitOrderService.payCallback(cb);
                        ok.incrementAndGet();
                    } catch (BizException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
                        busy.incrementAndGet();
                    }
                    return null;
                });

        assertThat(ok.get()).as("至少一条通知被受理").isPositive();
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());

        // 同一 notifySeq 只留一条操作记录（uk_biz_op）
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK"))
                .as("同一 notifySeq 并发投递只应留一条操作记录")
                .isEqualTo(1);
        // 履约任务只落一条 —— 落两条意味着调度器会发两次货
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = 'GRANT'",
                                bizNo))
                .as("GRANT 任务只应有一条")
                .isEqualTo(1);
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ?"
                                        + " AND task_type = 'STOCK_CONSUME'",
                                bizNo))
                .isEqualTo(1);
    }

    /**
     * <b>第三道闸：不同 {@code notifySeq} 的乱序通知并发到达，条件更新决定谁赢。</b>
     *
     * <p>与上一条的区别是<b>每条通知携带不同的 {@code notifySeq}</b> —— 这是真实的乱序场景（支付方 先后发出 SUCCESS 与
     * CLOSED，网络乱序后同时到达）。它们各自成键、各自留痕，{@code uk_biz_op} 拦不住任何一条：<b>唯一的拦截点是主单条件更新</b>。
     *
     * <p>PR-7 自查时补。原先只压同一 {@code notifySeq}，那种情况下唯一索引把第二条挡在条件更新 <b>之前</b> —— 压的其实是 {@code
     * uk_biz_op}，不是谓词。<b>并发用例压的是哪一道闸，取决于请求 怎么构造，不取决于并发本身。</b>
     *
     * <p><b>本用例并不能单独证明谓词有效</b>：{@code SUCCESS} 与 {@code CLOSED} 竞争时，无论谁赢断言 都成立。真正锁死谓词的是 {@code
     * CloseOrderIT.lateClosedNotificationIsRejectedAfterPaySuccess} ——
     * 它先确定终态、再投递迟到通知，去掉谓词立刻变红（已实测）。此处补的是<b>并发形态</b>下 「两类库存任务不能同时落」这一条，与那边互补。
     */
    @Test
    void concurrentOutOfOrderNotificationsAreSettledByConditionalUpdate() throws Exception {
        String bizNo = benefitOrderService.createTrade(newTradeReq("concOoo")).getBizNo();
        String tradeNo = "PAY1_" + bizNo;

        // 一半投 SUCCESS、一半投 CLOSED，各自带不同的 notifySeq
        runConcurrently(
                THREADS,
                i -> {
                    String status = i % 2 == 0 ? "SUCCESS" : "CLOSED";
                    try {
                        benefitOrderService.payCallback(
                                newPayCallback(bizNo, tradeNo, "NS_ooo_" + i, status));
                    } catch (BizException e) {
                        // 锁冲突可接受；其余异常应当冒出来
                        assertThat(e.getCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
                    }
                    return null;
                });

        // 终态必须是二者之一，且此后不再变 —— 条件更新保证「先到的赢」
        String finalStatus = orderField("pay_status", bizNo);
        assertThat(finalStatus)
                .as("终态只能是 PAY_SUCCESS 或 CLOSED，不能是中间态或被反复改写")
                .isIn(PayStatus.PAY_SUCCESS.name(), PayStatus.CLOSED.name());

        // 每条通知各自留痕（op_seq 取 notifySeq），但只有一条推进了状态
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK")).as("每条通知都应留痕，数量等于实际投递成功的条数").isPositive();

        // 关键：赢的那一方决定后续任务，两类任务不能同时存在 ——
        // 既转消耗又释放，等于同一笔库存被处置了两次
        int consume =
                count(
                        benefitJdbc,
                        "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type ="
                                + " 'STOCK_CONSUME'",
                        bizNo);
        int release =
                count(
                        benefitJdbc,
                        "SELECT COUNT(*) FROM benefit_task WHERE biz_no = ? AND task_type ="
                                + " 'STOCK_RELEASE'",
                        bizNo);
        assertThat(consume + release).as("转消耗与释放只能落其一 —— 两者都落意味着支付态被推进了两次").isEqualTo(1);
        if (PayStatus.PAY_SUCCESS.name().equals(finalStatus)) {
            assertThat(consume).isEqualTo(1);
        } else {
            assertThat(release).isEqualTo(1);
        }
    }

    /**
     * <b>第三道闸：同一 {@code opNo} 并发发奖，只产生一条发放记录。</b>
     *
     * <p>这是资损防线的最后一道 —— 它挡不住的话，一笔权益会真的发两次。发奖侧<b>没有 L2 锁</b> （锁在订单侧），故这里验的是纯粹的 L3：{@code uk_op_no}
     * 唯一索引 + 三段式的幂等出口。
     *
     * <p><b>断言 {@code providerOrderNo} 全程一致</b>：记录只有一条还不够 —— 若两个线程都真的调了
     * 下游、只是第二条插入失败，下游账本会有两笔。同一个下游单号才证明只发了一次。
     */
    @Test
    void concurrentGrantRewardProducesExactlyOneRecord() throws Exception {
        String opNo = "OP_CONC_" + System.nanoTime();

        List<String> providerOrderNos = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(
                THREADS,
                i -> {
                    GrantRewardReq req = new GrantRewardReq();
                    req.setPlayType("BENEFIT_SELL");
                    req.setActivityId(ACTIVITY_ID);
                    req.setBizOrderNo("BZ_CONC_GRANT");
                    req.setOpNo(opNo);
                    req.setReceiverId("U_concGrant");
                    RewardItem item = new RewardItem();
                    item.setItemSeq(0);
                    item.setRewardType("MONTH_CARD");
                    item.setProviderType("PROVIDER_A");
                    item.setProviderProductId("PROD_A_001");
                    item.setQty(1);
                    item.setCore(true);
                    req.setRewardItems(List.of(item));

                    var resp = rewardService.grantReward(req);
                    if (resp.getItems() != null && !resp.getItems().isEmpty()) {
                        String no = resp.getItems().get(0).getProviderOrderNo();
                        if (no != null) {
                            providerOrderNos.add(no);
                        }
                    }
                    return null;
                });

        assertThat(
                        count(
                                rewardJdbc,
                                "SELECT COUNT(*) FROM reward_grant_record WHERE op_no = ?",
                                opNo))
                .as("同一 opNo 并发发奖只应有一条发放记录")
                .isEqualTo(1);
        assertThat(
                        count(
                                rewardJdbc,
                                "SELECT COUNT(*) FROM reward_grant_item WHERE op_no = ?",
                                opNo))
                .as("明细同样只应有一条")
                .isEqualTo(1);

        // 下游单号全程一致 —— 记录唯一还不够，要证明下游也只发了一次
        assertThat(providerOrderNos.stream().distinct().toList())
                .as("providerOrderNo 必须全程一致，不一致即意味着下游发生过第二次发放")
                .hasSizeLessThanOrEqualTo(1);
    }

    /**
     * <b>并发建单 + 并发支付通知交织</b>：两道闸同时受压。
     *
     * <p>单独压某一道闸时，另一道的状态是干净的。真实流量里它们互相交织 —— 建单还没提交完，支付 通知就到了。此用例让两批线程同时跑，断言最终状态仍唯一。
     */
    @Test
    void interleavedCreateAndCallbackStillConverge() throws Exception {
        // 先建单拿到 bizNo，再让「重复建单」与「重复回调」同时压
        String bizNo = benefitOrderService.createTrade(newTradeReq("interleave")).getBizNo();
        String tradeNo = "PAY1_" + bizNo;

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS * 2)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(
                        () -> {
                            try {
                                benefitOrderService.createTrade(newTradeReq("interleave"));
                            } catch (BizException ignored) {
                                // 锁冲突或幂等命中都可接受
                            }
                            return null;
                        });
                tasks.add(
                        () -> {
                            try {
                                benefitOrderService.payCallback(
                                        newPayCallback(bizNo, tradeNo, "NS_inter", "SUCCESS"));
                            } catch (BizException ignored) {
                                // 同上
                            }
                            return null;
                        });
            }
            for (Future<Void> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                f.get();
            }
        }

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE client_req_no = ?",
                                "REQ_interleave"))
                .as("交织压力下主单仍只有一行")
                .isEqualTo(1);
        assertThat(opRecordCount(bizNo, "PAY_CALLBACK")).isEqualTo(1);
        assertThat(orderField("pay_status", bizNo)).isEqualTo(PayStatus.PAY_SUCCESS.name());
    }

    // ------------------------------------------------------------------

    private void runConcurrently(int threads, ThrowingIntFunction body) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                int idx = i;
                tasks.add(() -> body.apply(idx));
            }
            // 逐个 get：invokeAll 不抛出任务内的异常，不 get 则断言失败会被静默吞掉
            for (Future<Void> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                f.get();
            }
        }
    }

    private interface ThrowingIntFunction {
        Void apply(int i) throws Exception;
    }

    private int lockedOf() {
        return num(
                benefitJdbc,
                "SELECT locked FROM marketing_stock WHERE stock_key = ?",
                "sku:" + SKU_ID);
    }
}
