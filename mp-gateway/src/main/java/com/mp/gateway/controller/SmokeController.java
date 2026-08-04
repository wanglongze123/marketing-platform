package com.mp.gateway.controller;

import com.mp.api.benefit.service.BenefitOrderService;
import com.mp.common.web.ApiResponse;
import com.mp.common.web.TraceIdHolder;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * V0 冒烟链路：gateway → benefit-order → reward → mock，末端写一行 smoke_record。
 *
 * <p>验证多模块编译、Dubbo injvm、依赖方向、Flyway、MyBatis-Plus、Boot 3.2 + JDK 21、Testcontainers。
 *
 * <p><b>V1 结束时整个类删除</b>，它是脚手架不是功能。
 */
@RestController
public class SmokeController {

    // V0/V1 单进程用 Spring 注入；V3 拆服务后改回 @DubboReference(protocol="tri")
    @Autowired private BenefitOrderService benefitOrderService;

    @GetMapping("/smoke/{bizNo}")
    public ApiResponse<Map<String, Object>> smoke(@PathVariable String bizNo) {
        String chain = benefitOrderService.smoke(bizNo);
        ApiResponse<Map<String, Object>> resp =
                ApiResponse.ok(Map.of("bizNo", bizNo, "chain", ("gateway," + chain).split(",")));
        resp.setTraceId(TraceIdHolder.get());
        return resp;
    }
}
