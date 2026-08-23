package com.mp.activity.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mp.api.activity.dto.ActivityConfResp;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 活动配置的进程内缓存。V4 第 10 项，技术方案 §7.7。
 *
 * <p><b>为什么要缓存</b>：{@code queryActivityConf} 在预咨询与师傅进场两条链路的最前面，目标吞吐 ≥1000 QPS（§8.3）。每次都查库时，一张
 * {@code marketing_activity} 表要扛住全站的读。
 *
 * <p><b>缓存的是配置，不是可用性判定</b>。这是本类唯一需要小心的地方 —— {@code selectWithAvailability} 返回的 {@code available} 由
 * <b>库时钟</b>与时间窗比较得出（{@code NOW(3)}），刻意不用 JVM 时钟，避免多实例时钟漂移让活动提前或延后开放。把它缓存住等于把
 * 「此刻是否在窗口内」这个判断冻结了：活动到点结束后，缓存里仍写着 {@code available=true}， 直到 TTL 到期才纠正 ——
 * 而那段时间里下的单，是在一个已经结束的活动上下的。
 *
 * <p>故缓存只存<b>不随时间变化的部分</b>（名称、玩法、场景、状态、当前版本号），{@code available}
 * 每次回库现算。这让本类的收益从「省掉一次查询」降为「省掉大部分字段的传输与反序列化」—— 查询次数没降。
 *
 * <p><b>真正省掉查询的是命中期内的重复请求</b>：秒级 TTL 内同一活动的第二次及以后的请求， 若调用方只需要配置而不需要可用性（{@code
 * queryActivityConfCached}），完全不碰库。 下单链路需要可用性，走不缓存的那条；纯读配置的场景走缓存这条。
 *
 * <p><b>发布时主动失效</b>：{@code publishActivity} 成功后调 {@link #invalidate}。不依赖 TTL 自然过期 ——
 * 运营发布后立刻查，看到的应当是新版本号，而不是「等几秒就好了」。
 */
@Component
public class ActivityConfCache {

    private static final Logger log = LoggerFactory.getLogger(ActivityConfCache.class);

    private final Cache<String, ActivityConfResp> cache;

    public ActivityConfCache(
            @Value("${mp.activity.cache.ttl-seconds:5}") long ttlSeconds,
            @Value("${mp.activity.cache.max-size:1000}") long maxSize,
            MeterRegistry registry) {
        this.cache =
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                        .maximumSize(maxSize)
                        // 开统计才能观测命中率。没有它，「缓存有没有用」只能靠猜 ——
                        // 而命中率过低时，缓存反而是纯开销
                        .recordStats()
                        .build();

        registry.gauge("activity_cache_size", cache, Cache::estimatedSize);
        registry.gauge("activity_cache_hit_rate", cache, c -> c.stats().hitRate());
        log.info("activity conf cache ready, ttl={}s, maxSize={}", ttlSeconds, maxSize);
    }

    /** 取配置，未命中则回源。返回值<b>不含可用性</b>，调用方需要时自行现算。 */
    public ActivityConfResp get(String activityId, Function<String, ActivityConfResp> loader) {
        return cache.get(activityId, loader);
    }

    /**
     * 失效一个活动的缓存。发布、状态变更后调用。
     *
     * <p>多实例部署下这只失效<b>本实例</b>的那份，其余实例等 TTL 自然过期 —— 秒级窗口内不同实例 可能返回不同版本号。这是「秒级最终一致」的字面含义，也是 §7.7
     * 明确接受的取舍：营销配置容忍 秒级延迟，而存量单认下单时冻结的快照，不受配置变更影响。
     */
    public void invalidate(String activityId) {
        cache.invalidate(activityId);
        log.info("activity conf cache invalidated, activityId={}", activityId);
    }
}
