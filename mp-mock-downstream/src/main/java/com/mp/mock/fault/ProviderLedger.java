package com.mp.mock.fault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * mock 供应方的自有账本，键为 {@code opNo}。
 *
 * <p><b>为什么不落业务库</b>：这是服务边界另一侧的存储，不属于平台 schema。平台侧的 {@code reward_grant_record} 在调用 mock 之前就已落
 * {@code PROCESSING}（三段式），对它计数只能 证明「平台没有重复受理」，证明不了「供应方只发了一次」—— 而资损发生在后者（《分阶段方案》§5.3）。
 *
 * <p>进程内结构即可，重启丢失可接受：mock 无状态重启本就是允许的。
 *
 * <p>{@code putIfAbsent} 语义等价于唯一索引：同一 {@code opNo} 第二次发放不产生新单号，返回首次的 结果。这正是「幂等键复用」在下游侧的兜底 ——
 * 平台重发时机判早了也不构成资损。
 */
@Component
public class ProviderLedger {

    private final Map<String, String> orderNoByOpNo = new ConcurrentHashMap<>();
    private final Map<String, Integer> grantAttemptsByOpNo = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 已发放权益的使用态，键为发放 {@code opNo}。默认 {@code UNUSED} */
    private final Map<String, String> usageByGrantOpNo = new ConcurrentHashMap<>();

    /** 回收账本，键为 {@code revokeNo}。与发放账本分开 —— 两者是不同的键空间 */
    private final Map<String, String> revokeOrderNoByRevokeNo = new ConcurrentHashMap<>();

    /** 每个 {@code revokeNo} 收到过几次回收请求 */
    private final Map<String, Integer> revokeAttemptsByRevokeNo = new ConcurrentHashMap<>();

    /**
     * 按商品号定向失败的集合。V3 PR-9 引入，供退出标准第 19 条使用。
     *
     * <p><b>为什么需要它，而不是复用全局的 {@code FaultInjector}</b>：第 19 条要验的是「一组供应方失败时 其余组照常完成」——
     * 全局注入会让所有组一起失败，那种场景下 fail-fast 与逐个失败的表现完全一样， 用例分辨不出两种实现。必须能让 A 组失败而 B 组成功。
     *
     * <p>粒度取商品号而非供应方类型：{@code ProviderGrantReq} 携带的是 {@code providerProductId}，mock 这一侧
     * 看不到「供应方类型」—— 那是平台的分组概念。
     */
    private final Map<String, Boolean> failingProducts = new ConcurrentHashMap<>();

    /** 让该商品的发放一律返回失败。测试布置用，与全局注入正交。 */
    public void failProduct(String providerProductId) {
        failingProducts.put(providerProductId, Boolean.TRUE);
    }

    /** 该商品是否被定向标记为失败。 */
    public boolean isFailingProduct(String providerProductId) {
        return providerProductId != null && failingProducts.containsKey(providerProductId);
    }

    /** 清空定向失败设置。 */
    public void clearFailingProducts() {
        failingProducts.clear();
    }

    /**
     * 按商品号定向延迟的毫秒数。V3 PR-9 引入，供退出标准第 19 条使用。
     *
     * <p><b>为什么非有它不可</b>：第 19 条要验「一组失败不得取消其余组」，而 mock 全部瞬时返回时， <b>fail-fast 与逐个跑完的结果完全一样</b> ——
     * 取消发出去的时候，其余组早就跑完了。注入自查实测 确认：把扇出改成 fail-fast 后 10 条用例全绿，那条约束根本不可观测。
     *
     * <p>必须让一组慢到「取消真的能打断它」，才能分辨两种实现。这与 PR-6 的「数据量不足一页」是同一 族的遮蔽 —— 那里靠加数据破除，这里靠加耗时。
     */
    private final Map<String, Long> delayMillisByProduct = new ConcurrentHashMap<>();

    /** 让该商品的发放阻塞指定毫秒。测试布置用，模拟一个慢供应方。 */
    public void delayProduct(String providerProductId, long millis) {
        delayMillisByProduct.put(providerProductId, millis);
    }

    /** 该商品被布置的延迟，未布置即 0。 */
    public long delayOf(String providerProductId) {
        return providerProductId == null
                ? 0L
                : delayMillisByProduct.getOrDefault(providerProductId, 0L);
    }

    /** 清空定向延迟设置。 */
    public void clearDelays() {
        delayMillisByProduct.clear();
    }

    /**
     * 记账。同一 {@code opNo} 重复调用返回首次的单号，不新发。
     *
     * @return 供应方发放单号
     */
    public String record(String opNo) {
        return orderNoByOpNo.computeIfAbsent(opNo, k -> "PRV" + seq.incrementAndGet() + "_" + k);
    }

    /** 每个 {@code opNo} 发出去的份数。键取首次记账时的值 —— 重复请求不覆盖，与单号同一口径 */
    private final Map<String, Integer> grantedQtyByOpNo = new ConcurrentHashMap<>();

    /**
     * 记账并记下<b>发了几份</b>。
     *
     * <p><b>不记份数就无法验证「买 N 份发 N 份」</b>：平台侧的履约明细只有状态没有数量，对账十五项 也不比对这个维度 —— 于是一个把份数写死成 1
     * 的实现，在平台侧看来处处正常（明细 {@code SUCCESS}、 账本一条、金额收足），<b>只有供应方数得清到底发了几份</b>。
     *
     * <p>这与「重复发奖 = 0 的判据取下游账本」是同一条理由：跨过服务边界的事实，只能在边界另一侧断言。
     */
    public String record(String opNo, int qty) {
        String orderNo = record(opNo);
        grantedQtyByOpNo.putIfAbsent(opNo, qty);
        return orderNo;
    }

    /** 该 {@code opNo} 实际发出的份数；未发过返回 0。 */
    public int grantedQty(String opNo) {
        return grantedQtyByOpNo.getOrDefault(opNo, 0);
    }

    /**
     * 记一次发放请求的到达，无论本次是否记账。
     *
     * <p>与 {@link #record} 分开：后者是「发了几次」，本计数是「平台发起了几次」。二者在幂等 生效时必然背离 —— 重复发起被 {@code putIfAbsent}
     * 挡下，账本仍是一条。**正因如此，只看账本 条数无法发现「白跑的下游调用」**：重试对不对，要看发起次数。
     */
    public void recordGrantAttempt(String opNo) {
        grantAttemptsByOpNo.merge(opNo, 1, Integer::sum);
    }

    /** 该 {@code opNo} 收到过几次发放请求。测试断言「没有多余的重试」。 */
    public int grantAttempts(String opNo) {
        return grantAttemptsByOpNo.getOrDefault(opNo, 0);
    }

    /** 查账。返回 null 表示查无 —— 调用方须据此返回 {@code UNKNOWN} 而非 {@code FAIL}。 */
    public String find(String opNo) {
        return orderNoByOpNo.get(opNo);
    }

    /** 该 {@code opNo} 是否已发放。测试断言「无重复发放」的下游侧口径。 */
    public boolean contains(String opNo) {
        return orderNoByOpNo.containsKey(opNo);
    }

    /** 账本条目数。测试用于断言总量。 */
    public int size() {
        return orderNoByOpNo.size();
    }

    // ---- 回收（V3 PR-7） ----

    /** 测试布置：把某笔已发放的权益标为已使用，用于验「已核销不可回收」。 */
    public void markUsage(String grantOpNo, String usageStatus) {
        usageByGrantOpNo.put(grantOpNo, usageStatus);
    }

    /** 该笔发放当前的使用态，未标注即 {@code UNUSED}。 */
    public String usageOf(String grantOpNo) {
        return usageByGrantOpNo.getOrDefault(grantOpNo, "UNUSED");
    }

    /** 记一次回收请求的到达，无论本次是否记账。与 {@link #recordGrantAttempt} 同一用途。 */
    public void recordRevokeAttempt(String revokeNo) {
        revokeAttemptsByRevokeNo.merge(revokeNo, 1, Integer::sum);
    }

    public int revokeAttempts(String revokeNo) {
        return revokeAttemptsByRevokeNo.getOrDefault(revokeNo, 0);
    }

    /**
     * <b>原子回收</b>：仅当该笔发放未被使用时才回收成功，并把使用态置为 {@code REVOKED}。
     *
     * <p>「判定 + 动作」在一次 {@code compute} 内完成，这是 BR-B-30 要的原子性 —— 平台侧「先查 usageStatus
     * 再决定要不要回收」在两步之间存在窗口：查到 {@code UNUSED}、用户随即核销、 平台再发起回收，于是券已用掉而平台以为回收成功、退了钱。
     *
     * <p>幂等由 {@code revokeNo} 承载：同一回收单号重复调用返回首次的单号，不二次回收。
     *
     * @return 回收单号；{@code null} 表示该权益不可回收（已使用/已过期）
     */
    public String revokeIfUnused(String revokeNo, String grantOpNo) {
        String existing = revokeOrderNoByRevokeNo.get(revokeNo);
        if (existing != null) {
            // 幂等命中：已回收过，返回首次的单号
            return existing;
        }
        String[] issued = new String[1];
        usageByGrantOpNo.compute(
                grantOpNo,
                (k, usage) -> {
                    String current = usage == null ? "UNUSED" : usage;
                    if ("UNUSED".equals(current)) {
                        issued[0] = "RVK" + seq.incrementAndGet() + "_" + revokeNo;
                        return "REVOKED";
                    }
                    // 已使用 / 已过期 / 已回收：保持原状态，回收失败
                    return current;
                });
        if (issued[0] != null) {
            revokeOrderNoByRevokeNo.put(revokeNo, issued[0]);
        }
        return issued[0];
    }

    /** 该 {@code revokeNo} 是否已回收成功。测试断言「无重复回收」的下游侧口径。 */
    public boolean containsRevoke(String revokeNo) {
        return revokeOrderNoByRevokeNo.containsKey(revokeNo);
    }

    /** 回收账本条目数。 */
    public int revokeSize() {
        return revokeOrderNoByRevokeNo.size();
    }

    public void clear() {
        orderNoByOpNo.clear();
        grantedQtyByOpNo.clear();
        grantAttemptsByOpNo.clear();
        usageByGrantOpNo.clear();
        revokeOrderNoByRevokeNo.clear();
        revokeAttemptsByRevokeNo.clear();
        failingProducts.clear();
        delayMillisByProduct.clear();
    }
}
