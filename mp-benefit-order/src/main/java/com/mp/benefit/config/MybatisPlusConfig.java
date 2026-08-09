package com.mp.benefit.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分页插件。
 *
 * <p><b>不注册此拦截器时 {@code selectPage} 不报错，而是静默退化</b>：SQL 不带 LIMIT，返回全表， 且 {@code getTotal()} 恒为
 * 0。这类缺陷在 seed 数据只有几行时完全看不出来，要到线上单量 上万后才表现为「列表页拉全表」。故与分页查询同时引入。
 *
 * <p>放在 {@code mp-benefit-order} 而非 gateway：分页是本模块查询的需要，配置应与用它的代码同处 一个模块。V2 拆多数据源时此配置随模块一起走。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 请求页码超出总页数时返回空列表，不回绕到第一页 ——
        // 回绕会让「第 999 页」这种越界请求返回第 1 页数据，调用方会误认为数据重复
        pagination.setOverflow(false);
        // 单页上限二道闸：service 层已按 MAX_PAGE_SIZE 收口，此处防其他调用方绕过
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
