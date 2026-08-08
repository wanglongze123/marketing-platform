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
     * 记账。同一 {@code opNo} 重复调用返回首次的单号，不新发。
     *
     * @return 供应方发放单号
     */
    public String record(String opNo) {
        return orderNoByOpNo.computeIfAbsent(opNo, k -> "PRV" + seq.incrementAndGet() + "_" + k);
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
        grantAttemptsByOpNo.clear();
        usageByGrantOpNo.clear();
        revokeOrderNoByRevokeNo.clear();
        revokeAttemptsByRevokeNo.clear();
    }
}
