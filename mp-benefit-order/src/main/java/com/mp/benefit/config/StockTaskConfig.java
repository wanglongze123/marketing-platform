package com.mp.benefit.config;

import com.mp.benefit.repository.PlayBizRecordMapper;
import com.mp.benefit.service.OrderTxService;
import com.mp.benefit.task.StockTaskHandler;
import com.mp.common.enums.TaskType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 三类库存任务的处理器装配。
 *
 * <p>同一个 {@link StockTaskHandler} 按类型实例化三次，而不是写三个 {@code @Component} 类 —— 三者只差
 * 一个动作，抄三遍会让「加载订单」「订单不存在怎么办」有三份实现，改一处漏两处。
 *
 * <p>动作以方法引用传入，事务边界仍在 {@link OrderTxService}：处理器本身不带 {@code @BenefitTx}， 每次调用穿过 Spring 代理才生效。
 */
@Configuration
public class StockTaskConfig {

    @Bean
    public StockTaskHandler stockConsumeTaskHandler(
            PlayBizRecordMapper bizRecordMapper, OrderTxService tx) {
        return new StockTaskHandler(
                TaskType.STOCK_CONSUME, bizRecordMapper, tx, OrderTxService::consumeStock);
    }

    /**
     * 释放：库存与限购额度一并归还。
     *
     * <p>{@code QUOTA_RELEASE} 保留在 {@link TaskType} 里但<b>不注册处理器</b> —— 额度返还与库存释放
     * 需要同一道幂等闸（主单库存态的条件更新），拆成两个任务则那道闸只能被其中一个用掉。枚举值 留着是因为它在技术方案 §7.4 的任务清单里，V3 若出现「只还额度不还库存」的场景可再启用。
     */
    @Bean
    public StockTaskHandler stockReleaseTaskHandler(
            PlayBizRecordMapper bizRecordMapper, OrderTxService tx) {
        return new StockTaskHandler(
                TaskType.STOCK_RELEASE, bizRecordMapper, tx, OrderTxService::releaseStock);
    }

    /**
     * 回补：退款成功后把 {@code consumed} 还回可售，<b>额度不动</b>。
     *
     * <p>与释放分成两个 bean 而非在一个动作里判断，是因为两者的<b>前置态与归还对象都不同</b>：释放从 {@code LOCKED} 进、减 {@code
     * locked}、连带还额度；回补从 {@code CONSUMED} 进、减 {@code consumed}、 不还额度（技术方案 §3.4
     * 的口径表：商品可以再卖给别人，而「买了再退」不该刷回限购额度）。
     *
     * <p>写成一个动作靠 {@code stock_status} 分支的话，那道条件更新就要接受两个前置态 —— 于是一笔关单 释放过的单也能进回补分支，减掉别人的 {@code
     * consumed}。
     */
    @Bean
    public StockTaskHandler stockRestoreTaskHandler(
            PlayBizRecordMapper bizRecordMapper, OrderTxService tx) {
        return new StockTaskHandler(
                TaskType.STOCK_RESTORE, bizRecordMapper, tx, OrderTxService::restoreStock);
    }
}
