package com.mp.benefit.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code db_benefit} 的事务边界。业务代码只用本注解，不用裸 {@code @Transactional}。
 *
 * <p>把库的绑定收进注解，而不是让每个方法各写一遍 {@code transactionManager = "..."}：后者 漏写不报错，事务静默落到别库的管理器上（《分阶段方案》§5.6
 * ②）。{@code ShapeFreezeTest} 静态禁止 {@code *TxService} 中出现裸注解。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Transactional(transactionManager = "benefitTransactionManager", rollbackFor = Exception.class)
public @interface BenefitTx {}
