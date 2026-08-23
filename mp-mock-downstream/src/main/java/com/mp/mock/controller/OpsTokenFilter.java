package com.mp.mock.controller;

import com.mp.common.web.TraceIdHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运维端点的令牌校验。V4 第 9 项。
 *
 * <p><b>为什么必须有</b>：{@code /api/fault/**} 能改变下游行为，其中两个签名端点能签发支付通知与 供应方通知 ——
 * <b>能签发通知等于能伪造收款、伪造发放成功</b>。而伪造的发放成功尤其隐蔽：它让发放 记录进终态，此后不再被查单推进，且不像重复发奖那样能被对账数出来（记录数是对的）。
 *
 * <p>V2 单进程仅本地跑时这个风险可接受，文档三处记为「V4 拆分布式后必须下线或加鉴权」 （《分阶段方案》§5.4 末段、§5.6 ⑥、§6A.1 第 9
 * 项）。拆开后它们暴露在服务间网络上， 且现在分布在 gateway（8080）与 mock（8090）两个端口，风险面比单机时更大。
 *
 * <p><b>选加鉴权而非下线</b>：压测与演示都要用这些端点，下线了自己也用不了。令牌是最轻的 拦法，且它把「谁能改下游行为」从「能连上端口的任何人」收窄为「知道令牌的人」。
 *
 * <p><b>本副本守的是 mock 服务侧的 {@code /api/fault/**}</b>：V4 把故障注入控制面按「控制面随 状态走」迁到了这里（见 {@code
 * MockFaultController}），令牌校验必须跟着来 —— 只在 gateway 上 设防等于把大门锁了、侧门敞着，而侧门后面正是能改变下游行为的那些端点。
 *
 * <p><b>用 {@link MessageDigest#isEqual} 而非 {@code equals}</b>：后者按字符逐个比较，第一个不同 就返回 ——
 * 比较耗时与「猜对了几个字符」相关，理论上可被逐位试探。这里的令牌不是高价值凭据， 但常数时间比较的成本是零，没有理由不用。
 */
public class OpsTokenFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(OpsTokenFilter.class);

    /** 请求头名。不用 Authorization —— 那个头有既定语义，混用会让网关层的通用处理误判。 */
    public static final String HEADER = "X-Ops-Token";

    /** 受保护的路径前缀。 */
    // 本服务只有 /api/fault/**，没有 convergence 端点
    private static final List<String> PROTECTED = List.of("/api/fault/");

    private final byte[] expected;

    public OpsTokenFilter(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String path = request.getRequestURI();

        boolean guarded = PROTECTED.stream().anyMatch(path::startsWith);
        if (!guarded) {
            chain.doFilter(req, resp);
            return;
        }

        String given = request.getHeader(HEADER);
        if (given == null
                || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected)) {
            // 打 WARN 而非 INFO：运维端点被无令牌访问，要么是配置漏了，要么是有人在探
            log.warn("ops endpoint rejected, path={}, hasToken={}", path, given != null);
            HttpServletResponse response = (HttpServletResponse) resp;
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write(
                            "{\"code\":4030,\"message\":\"ops token required\",\"data\":null,"
                                    + "\"traceId\":\""
                                    + TraceIdHolder.get()
                                    + "\"}");
            return;
        }
        chain.doFilter(req, resp);
    }
}
