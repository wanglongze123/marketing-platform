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
    private final AtomicLong seq = new AtomicLong();

    /**
     * 记账。同一 {@code opNo} 重复调用返回首次的单号，不新发。
     *
     * @return 供应方发放单号
     */
    public String record(String opNo) {
        return orderNoByOpNo.computeIfAbsent(opNo, k -> "PRV" + seq.incrementAndGet() + "_" + k);
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

    public void clear() {
        orderNoByOpNo.clear();
    }
}
