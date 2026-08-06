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

    /**
     * 故障注入的两侧都必须真正读取模式，不能只有端点没有接线。
     *
     * <p>《分阶段方案》§5.3 要求注入「作用于 mock 供应方与 mock 支付两侧」。PR-3 初稿里 {@code payMode} 有端点、有存储、有日志，唯独 {@code
     * MockPayServiceImpl} 不读它 —— 切了模式没有 任何效果，且不报错。这与 PR-2 的 {@code renewLease} 同族：写了 SQL 没有调用点。
     */
    @Test
    void bothMockSidesActuallyReadTheInjectedMode() {
        assertThat(
                        read(
                                "mp-mock-downstream/src/main/java/com/mp/mock/service/impl/"
                                        + "MockProviderServiceImpl.java"))
                .as("供应方必须读注入模式")
                .contains("injector.providerMode()");

        assertThat(
                        read(
                                "mp-mock-downstream/src/main/java/com/mp/mock/service/impl/"
                                        + "MockPayServiceImpl.java"))
                .as("支付侧必须读注入模式，否则 /api/fault/pay 切了没有任何效果")
                .contains("injector.payMode()");
    }

    /**
     * 查无计数不得复用任务的 {@code retry_count}。
     *
     * <p>{@code retry_count} 由调度器对所有非终态结果自增，{@code PROCESSING} 也算在内 —— 于是「连续查无 3 次」退化成「查询 3
     * 次且最后一次查无」。下游回过 {@code PROCESSING} 即表明已受理， 此后重发是对一笔已受理的请求再发一次。
     *
     * <p>行为由 {@code FaultInjectionIT.processingInTheMiddleResetsTheMissStreak} 验证，此处防的是 有人图省事改回
     * {@code retry_count}。
     */
    @Test
    void missStreakIsCountedSeparatelyFromRetryCount() {
        String handler =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/task/"
                                + "QueryGrantTaskHandler.java");

        assertThat(handler)
                .as("查无计数应独立存储，且遇 PROCESSING 归零")
                .contains("setMissStreak")
                .contains("currentMissStreak");

        int idx = handler.indexOf("private RetStatus onMiss(");
        assertThat(idx).as("未找到 onMiss").isGreaterThan(0);
        String body = handler.substring(idx, Math.min(handler.length(), idx + 400));
        assertThat(body).as("onMiss 不得用 retry_count 计连续查无").doesNotContain("getRetryCount()");
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

    /**
     * L1 验签之后必须<b>逐字段比对</b>，不能只验签。
     *
     * <p>验签只证明凭证由平台签发且未被篡改，不证明它是发给<b>本次请求</b>的 —— 用户 A 的合法凭证 放进用户 B 的请求里同样验签通过。少比一个字段就漏一类越权：不比
     * {@code userId} 可拿他人 凭证下单，不比 {@code skuId} 可拿低价商品的凭证买高价商品（且比价照样过 —— 凭证价与它自己 那件商品的重算价本来就相等）。
     *
     * <p>行为由 {@code TokenAndPricingIT} 验证。此处防的是「只留 verify、把比对删掉」—— 那样删完
     * 所有正向用例照常绿，只有越权用例会红，而越权用例正是最容易被当成 flaky 删掉的那种。
     *
     * <p><b>断言的是比对表达式本身，不是字段名出现过。</b> 本检查初稿只查 {@code getUserId()} 在方法体里 出现过，而紧随其后的 {@code log.warn}
     * 恰好也带这三个 getter —— 把比对整体替换成 {@code true} 之后 检查照常通过（PR-4 自查注入验证）。「提到了某个字段」与「拿它做了判断」是两回事。
     */
    @Test
    void tokenVerificationIsFollowedByFieldLevelComparison() {
        String src =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/service/impl/"
                                + "BenefitOrderServiceImpl.java");

        int idx = src.indexOf("private ConsultTokenPayload verifyConsultToken(");
        assertThat(idx).as("未找到 verifyConsultToken").isGreaterThan(0);
        String body = src.substring(idx, Math.min(src.length(), idx + 1200));

        assertThat(body).as("必须验签").contains("tokenSigner.verify(");
        // 断言「凭证字段 equals 请求字段」这一表达式本身，而不是字段名出现过 ——
        // 紧随其后的 log.warn 也带这三个 getter，只查字段名的话把比对整体换成 true 照样通过
        String flat = normalize(body);
        for (String comparison :
                List.of(
                        "token.userId().equals(req.getUserId())",
                        "token.activityId().equals(req.getActivityId())",
                        "token.skuId().equals(req.getSkuId())")) {
            assertThat(flat)
                    .as("验签后必须比对 %s，否则可拿他人/他物的合法凭证下单", comparison)
                    .contains(normalize(comparison));
        }
        assertThat(body).as("比对不通过应判 4003").contains("ErrorCode.INVALID_TOKEN");

        // packageVersion 的比对需要读 SKU，故不在 verifyConsultToken 内，而与比价一起在
        // createTrade 里 —— 它决定权益包的内容，而换版时价格可以一分不动，比价挡不住
        assertThat(normalize(src))
                .as("必须比对 packageVersion —— 换版时价格可能一分不动，只比价会让用户按旧承诺付钱拿新内容")
                .contains(normalize("token.packageVersion() != sku.getPackageVersion()"));
    }

    /**
     * 比价的基准必须是<b>服务端重算价</b>，且不等一律拒绝。
     *
     * <p>PRD BR-B-04：下单时不信任客户端回传金额。故 {@code CreateTradeReq} 不得带价格字段 —— 带了就
     * 迟早有人拿它当应付金额。价格的唯一可信来源是凭证中被签名覆盖的那一份，且必须与服务端重算价 相等才放行。
     *
     * <p>「不等一律拒绝」而非取较低价（BR-B-08）：取新价则用户看到 9.9 却被扣 19.9，取低价则平台 每次调价都被旧凭证薅一轮。
     */
    @Test
    void priceComesFromTheServerAndMismatchIsRejected() {
        List<String> fields =
                Arrays.stream(com.mp.api.benefit.dto.CreateTradeReq.class.getDeclaredFields())
                        .filter(f -> !f.isSynthetic())
                        .map(Field::getName)
                        .toList();
        assertThat(fields)
                .as("下单入参不得带价格字段 —— 客户端回传金额一律不信任（PRD BR-B-04）")
                .noneSatisfy(
                        name ->
                                assertThat(name.toLowerCase())
                                        .containsAnyOf("price", "amount", "fee"));

        String src =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/service/impl/"
                                + "BenefitOrderServiceImpl.java");
        assertThat(src)
                .as("比价不通过必须判 1711，且比对的是凭证成交价与服务端重算价")
                .contains("ErrorCode.PRICE_MISMATCH")
                .contains("token.dealPrice() != recalcPrice");
    }

    /**
     * 签名密钥只从配置读，不得在代码里给默认值。
     *
     * <p>给了默认值，缺配置时应用照常启动、签名照常验过 —— 而那把密钥在仓库里人人可见，任何人都能 伪造凭证。<b>失效形态是「一切正常」</b>，与本类其余检查同族。
     *
     * <p>{@code @Value("${...:默认值}")} 的冒号形态即为违规。
     */
    @Test
    void consultTokenSecretHasNoInCodeDefault() {
        String config =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/config/ConsultTokenConfig.java");

        assertThat(config).contains("${mp.consult-token.secret}");
        assertThat(config)
                .as("密钥不得有代码内默认值 —— 缺配置应启动失败，而不是用一把公开的密钥跑起来")
                .doesNotContain("mp.consult-token.secret:");
    }

    /**
     * 库存扣减必须是<b>带条件的单条 UPDATE</b>，不得「先查余量再扣减」。
     *
     * <p>两条语句之间存在并发窗口：500 个线程可以同时查到「还剩 100」，然后各自扣一次。行锁只在 单条语句执行期间串行化，跨语句不成立。<b>这是 0 超卖的全部要点</b>。
     *
     * <p>行为由 {@code StockAndQuotaIT} 的并发用例验证。此处防的是有人为「加日志」或「先判断再报错」 把它拆成两步 ——
     * 拆完之后串行用例照常全绿，只有并发用例会红，而并发用例最容易被当成 flaky。
     */
    @Test
    void stockDeductionIsASingleConditionalUpdate() {
        String mapper =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/repository/"
                                + "MarketingStockMapper.java");

        // 预占：余量谓词。写成 locked + qty <= total 会漏掉已消耗的部分，把卖掉的再卖一遍
        assertThat(normalize(sqlOf(mapper, "tryLock")))
                .as("预占必须带余量谓词，否则直接超卖")
                .contains(normalize("total - locked - consumed >= #{qty}"));

        // 转消耗：locked 减、consumed 加，缺后者会让可售余量凭空多一份
        String consume = normalize(sqlOf(mapper, "tryConsume"));
        assertThat(consume)
                .as("转消耗须同时改 locked 与 consumed")
                .contains(normalize("locked = locked - #{qty}, consumed = consumed + #{qty}"));
        assertThat(consume).as("转消耗须带下界").contains(normalize("locked >= #{qty}"));

        assertThat(normalize(sqlOf(mapper, "tryRelease")))
                .as("释放须带下界，否则 locked 会被减成负值")
                .contains(normalize("locked >= #{qty}"));

        // 不得出现读出来再算的形态
        assertThat(mapper).as("库存不得走 MyBatis-Plus 的无谓词更新").doesNotContain("updateById");
    }

    /**
     * 限购扣减的上限谓词必须读<b>行内</b>的 {@code limit_qty}，不读应用传入的值。
     *
     * <p>传进来的那份是下单时从 SKU 读的快照，与本行可能不一致（运营刚调过限额）。以行内为准， 「限额是多少」就只有一个来源。写成 {@code <= #{limitQty}}
     * 则并发下两个线程可以带着不同的 限额值同时扣减。
     */
    @Test
    void quotaDeductionReadsTheLimitFromTheRow() {
        String mapper =
                read(
                        "mp-benefit-order/src/main/java/com/mp/benefit/repository/"
                                + "UserPurchaseQuotaMapper.java");

        assertThat(normalize(sqlOf(mapper, "tryConsume")))
                .as("限购上限谓词须读行内 limit_qty")
                .contains(normalize("used_qty + #{qty} <= limit_qty"));
        assertThat(normalize(sqlOf(mapper, "tryRelease")))
                .as("返还须带下界，否则 used_qty 会被减成负值")
                .contains(normalize("used_qty >= #{qty}"));
    }

    /**
     * 库存类任务的执行体必须以<b>主单库存态的条件更新</b>为闸。
     *
     * <p>这是每单幂等的唯一承重点，另外两样东西常被误当成它：
     *
     * <ul>
     *   <li>库存 SQL 的下界 {@code WHERE locked >= ?} —— 防的是「总数被减成负值」。{@code locked} 是该 {@code
     *       stock_key} 下所有订单<b>共享</b>的计数器，A 单重复释放时它因别的订单占用仍大于 0， 谓词照常放行，结果 A 释放掉了 B 的预占，可售余量凭空多一份
     *   <li>{@code uk_biz_type_op} —— 防的是重复<b>入队</b>，防不住同一条任务被重复<b>执行</b>
     * </ul>
     *
     * <p>PR-5 自查时这正是一处真实缺陷：原实现只有前两样，而暴露它的用例必须<b>另有一笔单占着 库存</b> —— 单笔单时释放后 {@code locked} 恰好为
     * 0，下界把第二次释放挡下了，于是测到的是 「下界生效」而非「不会动别人的预占」。
     */
    @Test
    void stockTaskExecutionIsGuardedByOrderStockStatus() {
        String tx =
                read("mp-benefit-order/src/main/java/com/mp/benefit/service/OrderTxService.java");

        for (String method : List.of("consumeStock", "releaseStock")) {
            int idx = tx.indexOf("public RetStatus " + method + "(");
            assertThat(idx).as("未找到 %s", method).isGreaterThan(0);
            String body = tx.substring(idx, Math.min(tx.length(), idx + 700));

            assertThat(normalize(body))
                    .as("%s 必须以主单库存态的条件更新为幂等闸，下界与唯一键都替代不了它", method)
                    .contains(normalize("advanceStockStatus("))
                    .contains(normalize("StockStatus.LOCKED.name()"));
        }

        // 条件更新自身必须带前置状态，否则它只是个无条件赋值
        assertThat(
                        normalize(
                                sqlOf(
                                        read(
                                                "mp-benefit-order/src/main/java/com/mp/benefit/"
                                                        + "repository/PlayBizRecordMapper.java"),
                                        "advanceStockStatus")))
                .as("库存态推进必须带前置状态谓词")
                .contains(normalize("stock_status = #{fromStatus}"));
    }

    /**
     * 库存类任务的 {@code op_no} 必须是确定性键，不得留空串。
     *
     * <p>每单幂等完全由 {@code uk_biz_type_op} 承担 —— 库存 SQL 的下界提供不了：{@code locked} 是该 {@code stock_key}
     * 下所有订单共享的计数器，A 单重复释放两次时它因别的订单占用仍远大于 0， 下界根本不会拦，结果是 A 释放了别人的预占，可售余量凭空多一份（技术方案 §7.4）。
     *
     * <p>留空串则同一单可插入无数条释放任务，唯一键形同虚设 —— 这正是 §3.3 警告过的 {@code NOT NULL DEFAULT ''} 陷阱。
     */
    @Test
    void stockTasksCarryADeterministicOpNo() {
        String tx =
                read("mp-benefit-order/src/main/java/com/mp/benefit/service/OrderTxService.java");

        int idx = tx.indexOf("private void enqueueStockTask(");
        assertThat(idx).as("未找到 enqueueStockTask").isGreaterThan(0);
        String body = tx.substring(idx, Math.min(tx.length(), idx + 500));

        assertThat(normalize(body))
                .as("库存任务的 op_no 须取 bizNo + '_' + taskType，留空串则唯一键形同虚设")
                .contains(normalize("bizNo + \"_\" + taskType.name()"));
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

    /** 压掉换行与缩进，让断言不受 spotless 折行位置影响 —— 否则改一次格式就要改一次断言。 */
    private static String normalize(String source) {
        return source.replaceAll("\\s+", "");
    }

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
