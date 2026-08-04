package com.mp.gateway.freeze;

import static org.assertj.core.api.Assertions.assertThat;

import com.mp.api.reward.dto.GrantRewardReq;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.ItemGrantStatus;
import com.mp.common.enums.OpStatus;
import com.mp.common.enums.OpType;
import com.mp.common.enums.PayStatus;
import com.mp.common.enums.RefundStatus;
import com.mp.common.enums.RetStatus;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 八项形状冻结的机器化校验，对应《分阶段方案》§4.7 退出标准 21、23–27、29。
 *
 * <p><b>为什么写成测试而不是靠 review</b>：这些约束在 V2/V3 每次改动时都要重新成立，而人读代码 只在写的当天可靠。四个月后有人把 {@code
 * advanceGrantStatus} 的 {@code WHERE} 去掉，评审 未必看得出来 —— 但这里会红。
 *
 * <p>是 {@code *Test} 不是 {@code *IT}：纯静态检查，不需要数据库。
 */
class ShapeFreezeTest {

    /** 从 mp-gateway 模块根出发定位兄弟模块源码。surefire 的工作目录即模块 basedir。 */
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    // ------------------------------------------------------------------
    // 标准 23、24：五个结果类枚举各自独立，取值不互相沾染
    // ------------------------------------------------------------------

    /**
     * 失败值刻意拼写不同：{@code RetStatus.FAIL} 是下游回报，{@code OpStatus.FAILED} 是本地判断。
     *
     * <p>若两者同名，把下游的 UNKNOWN 误写进本地态、或反过来，编译期与运行期都不会报错， 要到对账发现「本地记为失败、下游其实成功」时才暴露 —— 那时钱已经赔出去了。拼写不同
     * 使这类错至少在 code review 里显形。
     */
    @Test
    void downstreamResultAndLocalStatusUseDifferentFailureSpelling() {
        assertThat(names(RetStatus.class))
                .containsExactly("SUCCESS", "FAIL", "PROCESSING", "UNKNOWN");
        assertThat(names(OpStatus.class))
                .containsExactly("INIT", "PROCESSING", "SUCCESS", "FAILED", "UNKNOWN");
        assertThat(names(RetStatus.class)).doesNotContain("FAILED");
        assertThat(names(OpStatus.class)).doesNotContain("FAIL");
    }

    /**
     * 主单发放态带 {@code GRANT_} 前缀，明细单项态不带。
     *
     * <p>两张表的列都叫 {@code grant_status}，是不同层级的枚举。前缀是唯一的区分手段 —— 去掉前缀后，把明细的 SUCCESS 写进主单不会有任何报错。
     */
    @Test
    void orderGrantStatusAndItemGrantStatusDoNotOverlapOnTerminalValues() {
        assertThat(names(GrantStatus.class))
                .containsExactly(
                        "NOT_START", "GRANTING", "GRANT_SUCCESS", "GRANT_FAILED", "GRANT_UNKNOWN");
        assertThat(names(ItemGrantStatus.class))
                .containsExactly("NOT_START", "GRANTING", "SUCCESS", "FAILED", "UNKNOWN");

        // 终态三值完全不重叠，误赋值会在 valueOf 处抛异常而非静默写错
        assertThat(names(GrantStatus.class)).doesNotContain("SUCCESS", "FAILED", "UNKNOWN");
        assertThat(names(ItemGrantStatus.class))
                .doesNotContain("GRANT_SUCCESS", "GRANT_FAILED", "GRANT_UNKNOWN");
    }

    /** 标准 24：三子状态枚举与《技术方案》§3.4 状态迁移表逐值对齐，含 V1 用不到的值。 */
    @Test
    void subStatusEnumsMatchTheDesignedStateMachine() {
        assertThat(names(PayStatus.class))
                .containsExactly("WAIT_PAY", "CLOSING", "PAY_SUCCESS", "PAY_FAILED", "CLOSED");
        assertThat(names(RefundStatus.class))
                .containsExactly(
                        "NONE",
                        "REVOKING",
                        "REVOKE_FAILED",
                        "REFUNDING",
                        "REFUND_SUCCESS",
                        "REFUND_FAILED");
    }

    /** {@code op_seq} 取值由 OpType 的 atMostOnce 决定，PAY_CALLBACK 必须为 false。 */
    @Test
    void payCallbackIsNotAtMostOnce() {
        assertThat(OpType.PAY_CALLBACK.isAtMostOnce()).isFalse();
        assertThat(OpType.REFUND_CALLBACK.isAtMostOnce()).isFalse();
        assertThat(OpType.MANUAL_REPAIR.isAtMostOnce()).isFalse();

        assertThat(OpType.CREATE_TRADE.isAtMostOnce()).isTrue();
        assertThat(OpType.GRANT_BENEFIT.isAtMostOnce()).isTrue();
        assertThat(OpType.CLOSE_ORDER.isAtMostOnce()).isTrue();
    }

    // ------------------------------------------------------------------
    // 标准 27：发奖契约不含玩法专有概念
    // ------------------------------------------------------------------

    /**
     * {@code GrantRewardReq} 的字段必须对两个玩法都成立。
     *
     * <p>掺进 orderId / tradeNo / skuId，裂变接入时就得加一批可空字段或另开一个方法 —— 而「同一个能力有两个入口」正是中台化想避免的东西。
     */
    @Test
    void grantRewardRequestCarriesNoPlaySpecificConcept() {
        List<String> fields =
                Arrays.stream(GrantRewardReq.class.getDeclaredFields())
                        .filter(f -> !f.isSynthetic())
                        .map(Field::getName)
                        .toList();

        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "playType",
                        "activityId",
                        "bizOrderNo",
                        "opNo",
                        "receiverId",
                        "rewardItems");
        assertThat(fields)
                .noneSatisfy(
                        name ->
                                assertThat(name.toLowerCase())
                                        .containsAnyOf("trade", "sku", "pay", "benefit", "refund"));
    }

    // ------------------------------------------------------------------
    // 标准 21、25、26、29：源码级约束
    // ------------------------------------------------------------------

    /**
     * 标准 25：状态变更一律条件更新，{@code WHERE} 必须带前置状态。
     *
     * <p>谓词就是幂等三道闸的第三道。去掉它，乱序到达的迟到通知会把已支付订单改回去。
     */
    @Test
    void everyStateAdvanceCarriesAPreconditionInWhere() {
        String mapper =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/repository/PlayBizRecordMapper.java");

        for (String method : List.of("advancePayStatus", "advanceGrantStatus")) {
            String sql = sqlOf(mapper, method);
            assertThat(sql).as("%s 的条件更新必须带前置状态谓词", method).contains("#{fromStatus}");
        }

        // 主单不得走 MyBatis-Plus 的无谓词更新
        assertThat(
                        read(
                                "mp-benefit-order/src/main/java/com/mp/benefit/service/OrderTxService.java"))
                .doesNotContain("updateById");
    }

    /**
     * 标准 26：事务方法内不得有 RPC。
     *
     * <p>事务里发 RPC 会把网络耗时算进锁持有时间，且下游成功而本地回滚时无法撤销。 全部事务边界收在 OrderTxService，故只需检查这一个类。
     */
    @Test
    void transactionalMethodsContainNoRemoteCall() {
        String tx =
                read("mp-benefit-order/src/main/java/com/mp/benefit/service/OrderTxService.java");

        assertThat(tx).contains("@Transactional");
        for (String forbidden :
                List.of("rewardService", "mockPayService", "activityService", "Thread.sleep")) {
            assertThat(tx).as("事务类内不得出现 %s", forbidden).doesNotContain(forbidden);
        }
    }

    /**
     * 标准 21：处理下游返回值的分支，非 SUCCESS 三类必须显式未实现，不得静默当失败。
     *
     * <p>V1 只实现 SUCCESS。留一个 {@code else → FAIL} 看似无害，但 UNKNOWN 落到 FAIL 分支就会 触发补偿，而下游可能已发放成功 ——
     * 一笔权益发两次、一笔钱赔两次。宁可抛未实现。
     */
    @Test
    void nonSuccessDownstreamBranchesAreExplicitlyUnimplemented() {
        for (String file :
                List.of(
                        "mp-reward/src/main/java/com/mp/reward/service/impl/RewardServiceImpl.java",
                        "mp-benefit-order/src/main/java/com/mp/benefit/service/impl/BenefitOrderServiceImpl.java")) {
            String src = read(file);
            assertThat(src)
                    .as("%s 判下游结果处应显式抛未实现，而非默认走失败", file)
                    .contains("RetStatus.SUCCESS", "UnsupportedOperationException");
        }
    }

    /**
     * 标准 29：不存在 {@code catch (Exception) → FAIL} 的路径。
     *
     * <p>未预期异常的正确映射是 UNKNOWN：异常可能发生在 RPC 发出之后，下游未必没执行。 映射成 FAIL 等于替下游断言「没做」，而这个断言没有依据。
     */
    @Test
    void noCatchAllMapsToFailure() {
        try (Stream<Path> files = Files.walk(REPO)) {
            List<Path> sources =
                    files.filter(p -> p.toString().endsWith(".java"))
                            .filter(p -> p.toString().contains("/src/main/java/"))
                            .filter(p -> !p.toString().contains("/target/"))
                            .toList();
            assertThat(sources).isNotEmpty();

            for (Path p : sources) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                int idx = src.indexOf("catch (Exception");
                while (idx >= 0) {
                    // 取 catch 块起始后的一段，检查其中没有把结果判为失败
                    String block = src.substring(idx, Math.min(src.length(), idx + 400));
                    assertThat(block)
                            .as("%s 的 catch (Exception) 不得把结果判为失败，应为 UNKNOWN 或原样抛出", p)
                            .doesNotContain("RetStatus.FAIL")
                            .doesNotContain("OpStatus.FAILED");
                    idx = src.indexOf("catch (Exception", idx + 1);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码失败", e);
        }
    }

    /** V0 脚手架必须已清除 —— 留着会让读者以为它是链路的一部分（标准 32）。 */
    @Test
    void v0ScaffoldingIsRemoved() {
        try (Stream<Path> files = Files.walk(REPO)) {
            List<Path> leftovers =
                    files.filter(p -> !p.toString().contains("/target/"))
                            .filter(p -> !p.toString().contains("/.git/"))
                            .filter(
                                    p -> {
                                        String n = p.getFileName().toString();
                                        return n.equals("SmokeIT.java")
                                                || n.contains("Smoke") && n.endsWith(".java");
                                    })
                            .toList();
            assertThat(leftovers).isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码失败", e);
        }
    }

    // ------------------------------------------------------------------

    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    private static String read(String relative) {
        try {
            return Files.readString(REPO.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + relative, e);
        }
    }

    /** 截取某方法声明之前的注解块文本，即其 SQL。 */
    private static String sqlOf(String source, String method) {
        int end = source.indexOf(" " + method + "(");
        assertThat(end).as("未找到方法 %s", method).isGreaterThan(0);
        int start = source.lastIndexOf("@Update", end);
        assertThat(start).as("%s 应由 @Update 手写 SQL 定义", method).isGreaterThan(0);
        return source.substring(start, end);
    }
}
