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
import com.mp.common.enums.TaskType;
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
 * <p>写成测试而非人工核对：这些约束在 V2/V3 每次改动时都需重新成立。若有人去掉 {@code advanceGrantStatus} 的 {@code WHERE}，此处会红。
 *
 * <p>命名为 {@code *Test} 而非 {@code *IT}：纯静态检查，不依赖数据库。
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
     * <p>若同名，两者互串编译期与运行期均不报错，要到对账发现「本地记为失败、下游实际成功」时才暴露。
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
     * {@code GrantRewardReq} 的字段须对两个玩法都成立。掺进 orderId / tradeNo / skuId，裂变接入时就要加一批可空字段或另开方法，
     * 使同一能力出现两个入口。
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

        assertThat(tx).contains("@BenefitTx");
        for (String forbidden :
                List.of("rewardService", "mockPayService", "activityService", "Thread.sleep")) {
            assertThat(tx).as("事务类内不得出现 %s", forbidden).doesNotContain(forbidden);
        }
    }

    /**
     * 事务边界必须绑定到具体的库，不得使用裸 {@code @Transactional}（《分阶段方案》§5.6 ②）。
     *
     * <p>四套数据源下不存在「默认」事务管理器：不带 {@code transactionManager} 属性的注解按类型 注入取到别库的管理器，本库的写各自自动提交 ——
     * 不报错、不回滚。这与 V1 缺陷 ①（同类内部 调用注解不生效）同属一族，失效形态都是「没有事务」而非「事务出错」。
     *
     * <p>本检查只能证明「写了限定」，证明不了「限定指向正确的库」—— 后者由 {@code TransactionBindingIT} 的回滚用例验证，二者缺一不可。
     */
    @Test
    void txServicesUseSchemaBoundAnnotationsInsteadOfBareTransactional() {
        try (Stream<Path> files = Files.walk(REPO)) {
            List<Path> txServices =
                    files.filter(p -> p.toString().contains("/src/main/java/"))
                            .filter(p -> !p.toString().contains("/target/"))
                            .filter(p -> p.getFileName().toString().endsWith("TxService.java"))
                            .toList();
            assertThat(txServices).as("至少应存在 OrderTxService").isNotEmpty();

            for (Path p : txServices) {
                // 只看代码行：类注释里解释「为什么不用裸 @Transactional」是应当鼓励的，不该判红
                List<String> bare =
                        Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                                .map(String::strip)
                                .filter(line -> !line.startsWith("*") && !line.startsWith("//"))
                                .filter(line -> line.startsWith("@Transactional"))
                                .toList();
                // 组合注解自身携带 transactionManager，业务类里不应再出现原始注解
                assertThat(bare).as("%s 应使用带库限定的组合注解，不得出现裸 @Transactional", p).isEmpty();
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码失败", e);
        }
    }

    /**
     * 任务领取的锁子句必须直接作用于目标表，不得塞进派生表。
     *
     * <p>为绕开 MySQL 错误 1093 很容易写成 {@code UPDATE ... WHERE id IN (SELECT id FROM (SELECT ... FOR
     * UPDATE SKIP LOCKED) t)}。本地在 MySQL 8.4.11 上实测：该写法不重复领取，而是退化为 <b>互相阻塞</b>（A 持锁时 B 等到 {@code
     * Lock wait timeout}）—— 与技术方案 §7.3 记录的 「锁失效导致重复领取」不是同一种失效，但同样不可接受。
     *
     * <p><b>只能静态检查</b>：阻塞形态在并发用例里表现为变慢而非出错，{@code ReliableTaskIT} 的 「每条任务仅一个 owner」断言照常通过 ——
     * 已实测确认。运行期测不出来的约束，就得在 源码层面拦住。
     */
    @Test
    void taskClaimLocksTheTargetTableDirectlyNotADerivedTable() {
        String mapper =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/repository/BenefitTaskMapper.java");

        // 取 SQL 字符串字面量部分，避开注释里对错误写法的引用
        List<String> sqlLines =
                mapper.lines()
                        .map(String::strip)
                        .filter(line -> line.startsWith("\"") || line.startsWith("+ \""))
                        .toList();
        String sql = String.join(" ", sqlLines);

        assertThat(sql).as("领取须使用 FOR UPDATE SKIP LOCKED").contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).as("锁子句不得包在派生表里 —— 会退化为互相阻塞，且并发用例测不出来").doesNotContain("FROM (SELECT");
    }

    /**
     * 每库的 {@code SqlSessionFactory} 必须自己开驼峰映射，且 yml 里不得再留 {@code mybatis-plus} 配置。
     *
     * <p>自建 {@code SqlSessionFactory} 后，Spring Boot 的 mybatis-plus 自动配置不再生效， {@code
     * application.yml} 里的 {@code map-underscore-to-camel-case} 变成死配置 —— 把它改成 {@code false}
     * 全部测试照常通过（PR-1 自查时已实测确认）。留着比删掉更危险：下一个人会以为 改那里能生效。
     *
     * <p>驼峰映射漏配的失效形态是字段静默为 null，不报错 —— 与本类其余检查同族。
     */
    @Test
    void eachSqlSessionFactoryEnablesCamelCaseMappingItself() {
        for (String file :
                List.of(
                        "mp-activity/src/main/java/com/mp/activity/config/ActivityDataSourceConfig.java",
                        "mp-benefit-order/src/main/java/com/mp/benefit/config/BenefitDataSourceConfig.java",
                        "mp-reward/src/main/java/com/mp/reward/config/RewardDataSourceConfig.java")) {
            assertThat(read(file))
                    .as("%s 未开启驼峰映射，字段会静默为 null", file)
                    .contains("setMapUnderscoreToCamelCase(true)");
        }

        assertThat(read("mp-gateway/src/main/resources/application.yml"))
                .as("yml 的 mybatis-plus 配置在自建 SqlSessionFactory 后不生效，不得保留")
                .doesNotContain("mybatis-plus");
    }

    /**
     * 死信阈值按《分阶段方案》§5.6 ⑤ 分两类：查单 10 次、执行 5 次。
     *
     * <p>取值本身是判断，写错不会报错也不会有任何测试变红 —— 阈值调大只表现为坏任务多重试几轮， 调小则表现为偶发故障被过早判死。PR-2 自查时 {@code GRANT} 就被误写成
     * 10（它是执行类）。
     */
    @Test
    void deadLetterThresholdsMatchTheDocumentedSplit() {
        for (TaskType type : TaskType.values()) {
            int expected = type.isQuery() ? 10 : 5;
            assertThat(type.getMaxRetry())
                    .as("%s 是%s类，阈值应为 %s", type, type.isQuery() ? "查单" : "执行", expected)
                    .isEqualTo(expected);
        }

        // 查单类与执行类都得有，否则上面的循环可能只覆盖了其中一类
        assertThat(Arrays.stream(TaskType.values()).filter(TaskType::isQuery).toList())
                .isNotEmpty();
        assertThat(Arrays.stream(TaskType.values()).filter(t -> !t.isQuery()).toList())
                .isNotEmpty();
    }

    /**
     * 续租必须有生产调用点，不能只有 SQL。
     *
     * <p>《分阶段方案》§5.6 ④ 定的「执行超租约三分之二即续租」需要有人调 {@code renewLease}。 PR-2 初稿里它只被 {@code
     * ShapeFreezeTest} 与 IT 引用 —— fencing 检查照常通过，因为那只 验证 SQL 长什么样，不验证有没有人调用它。
     */
    @Test
    void leaseRenewalIsWiredIntoTheScheduler() {
        String scheduler =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/task/BenefitTaskScheduler.java");

        assertThat(scheduler).as("调度器必须调用 renewLease，否则长任务的租约会在执行中途到期被接管").contains("renewLease(");
    }

    /** 任务写回一律带 lease_owner 校验，否则过期持有者能覆盖接管者的结果。 */
    @Test
    void everyTaskWriteBackCarriesLeaseOwnerFencing() {
        String mapper =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/repository/BenefitTaskMapper.java");

        // 完成、失败重排、死信、续租四类，缺任一类即存在被过期持有者覆盖的窗口
        for (String method : List.of("markDone", "markRetry", "markDead", "renewLease")) {
            String sql = sqlOf(mapper, method);
            assertThat(sql)
                    .as("%s 的写回必须带 lease_owner fencing（《分阶段方案》§5.6 ③）", method)
                    .contains("lease_owner = #{owner}");
        }
    }

    /** 组合注解自身必须指定管理器，否则上一条检查只是把裸注解换了个名字。 */
    @Test
    void eachTxAnnotationDeclaresItsTransactionManager() {
        for (String file :
                List.of(
                        "mp-activity/src/main/java/com/mp/activity/config/ActivityTx.java",
                        "mp-benefit-order/src/main/java/com/mp/benefit/config/BenefitTx.java",
                        "mp-reward/src/main/java/com/mp/reward/config/RewardTx.java")) {
            String src = read(file);
            assertThat(src)
                    .as("%s 必须绑定具体的事务管理器并对全部异常回滚", file)
                    .contains("transactionManager = ")
                    .contains("rollbackFor = Exception.class");
        }
    }

    /**
     * 标准 21 的 V2 形态：四分类逐类处置，未定态不得被压成失败。
     *
     * <p>V1 只实现 SUCCESS，其余三类显式抛未实现 —— 那是「宁可不做也不做错」的占位。PR-3 补齐 实现后该检查换成：{@code UNKNOWN} 与 {@code
     * PROCESSING} 必须各有自己的分支，且不得出现 把它们映射到失败的写法。
     *
     * <p>UNKNOWN 落到 FAIL 分支会触发补偿，而下游可能已发放成功 —— 一笔权益发两次、一笔钱赔两次。 这是四分类存在的全部理由。
     */
    @Test
    void everyDownstreamClassificationHasItsOwnBranch() {
        for (String file :
                List.of(
                        "mp-reward/src/main/java/com/mp/reward/service/impl/RewardServiceImpl.java",
                        "mp-benefit-order/src/main/java/com/mp/benefit/service/impl/BenefitOrderServiceImpl.java")) {
            String src = read(file);
            assertThat(src)
                    .as("%s 必须区分 UNKNOWN 与 PROCESSING，不能只判 SUCCESS", file)
                    .contains("RetStatus.UNKNOWN")
                    .contains("RetStatus.PROCESSING");
        }

        // 占位已被真实实现取代：留着会让人以为这些分支还没写
        assertThat(
                        read(
                                "mp-reward/src/main/java/com/mp/reward/service/impl/RewardServiceImpl.java"))
                .as("四分类已实现，不应再有未实现占位")
                .doesNotContain("UnsupportedOperationException");
    }

    /**
     * 下游查单的「查无」必须返回 {@code UNKNOWN}，不得返回 {@code FAIL}。
     *
     * <p>查无可能只是提交在途（三段式的中间态落库与调用方读之间存在窗口），判 {@code FAIL} 会让 调用方据此走补偿。V1 的 {@code queryGrant} 在此返回
     * {@code FAIL}，是 §7.3 记录的缺陷 9。
     *
     * <p>静态检查只能证明「没有把查无写成 FAIL」，收敛行为由 {@code FaultInjectionIT} 验证。
     */
    @Test
    void queryWithNoRecordReturnsUnknownNotFail() {
        String reward =
                read("mp-reward/src/main/java/com/mp/reward/service/impl/RewardServiceImpl.java");
        int idx = reward.indexOf("public GrantRewardResp queryGrant(");
        assertThat(idx).as("未找到 queryGrant").isGreaterThan(0);

        // 截取方法体前段：记录不存在的分支就在开头
        String body = reward.substring(idx, Math.min(reward.length(), idx + 800));
        assertThat(body)
                .as("queryGrant 查无应返回 UNKNOWN")
                .contains("RetStatus.UNKNOWN")
                .doesNotContain("RetStatus.FAIL");

        String mock =
                read(
                        "mp-mock-downstream/src/main/java/com/mp/mock/service/impl/"
                                + "MockProviderServiceImpl.java");
        assertThat(mock).as("mock 查单的查无同样返回 UNKNOWN").contains("RetStatus.UNKNOWN");
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
