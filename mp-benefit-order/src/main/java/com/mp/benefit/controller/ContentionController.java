package com.mp.benefit.controller;

import com.mp.benefit.lock.ContentionMetrics;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L3 冲突计数端点，退出标准第 15 条（去锁对照组）的数据来源。V4 从 gateway 迁入。
 *
 * <p><b>为什么随 benefit-order 走</b>：{@link ContentionMetrics} 是进程内 {@code LongAdder}，三个计数分别发生在本模块的建单（撞
 * {@code uk_idempotent}）、主单条件更新、库存预占三条路径上。 拆服务后 gateway 里没有这个对象，读到的会是一个恒为 0 的空计数器 ——
 * <b>而这种失效不会报错</b>， 对照组的数字会变成「两组都是 0」，看起来像「锁没有区别」，恰好把结论读反。
 *
 * <p>路径保持 {@code /api/fault/contention} 不变，k6 的 {@code run-seckill.sh} 与 {@code contention.js}
 * 无需改动。
 */
@RestController
@RequestMapping("/api/fault")
public class ContentionController {

    private final ContentionMetrics contention;

    public ContentionController(ContentionMetrics contention) {
        this.contention = contention;
    }

    /**
     * L3 冲突计数快照。
     *
     * <p>两组压测各跑一轮后比对这三个数：开锁组应当<b>明显更低</b> —— 锁把并发串行化在锁上， 走到唯一索引与条件更新的请求因此更少。正确性结果两组则应完全一致。
     *
     * <p><b>不看锁自身的竞争计数</b>：移除锁后那个指标必然为 0，只说明锁代码没运行，而非没有冲突。
     */
    @GetMapping("/contention")
    public ApiResponse<Map<String, Object>> contention() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("duplicateKey", contention.duplicateKeyCount());
        data.put("conditionalUpdateMiss", contention.conditionalUpdateMissCount());
        data.put("stockInsufficient", contention.stockInsufficientCount());
        return ok(data);
    }

    /** 计数清零。压测开始前调用，使两组的数字可比。 */
    @DeleteMapping("/contention")
    public ApiResponse<Map<String, Object>> resetContention() {
        contention.reset();
        return contention();
    }

    private static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = ApiResponse.ok(data);
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
