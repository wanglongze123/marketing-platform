package com.mp.activity.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code db_activity} 的事务边界。业务代码只用本注解，不用裸 {@code @Transactional}。
 *
 * <p>把库的绑定收进注解，而不是让每个方法各写一遍 {@code transactionManager = "..."}：后者 漏写不报错，事务静默落到别库的管理器上（《分阶段方案》§5.6
 * ②）。{@code ShapeFreezeTest} 静态禁止 {@code *TxService} 中出现裸注解。
 *
 * <p><b>本注解当前尚无使用方</b>，使用方是V3 活动配置发布（activity_op_record / activity_task）。四库各备一个组合注解是有意的
 * 对称结构：先有注解、后有事务类，写事务类的人才会顺手用上它；反过来则容易直接写裸 {@code @Transactional} —— 而静态检查只拦 {@code *TxService}
 * 命名的类，拦不住别处。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Transactional(transactionManager = "activityTransactionManager", rollbackFor = Exception.class)
public @interface ActivityTx {}
