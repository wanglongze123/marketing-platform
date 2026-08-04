package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PayCallbackReq;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.PayStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 对外 HTTP 端点。
 *
 * <p>验的是 gateway 这一层做的事：<b>把内部四分类语义收敛成统一响应壳，且 traceId 一路可追</b>。 业务正确性由其余 IT 覆盖，此处不重复。
 *
 * <p>{@code webEnvironment = RANDOM_PORT} 起真实 servlet 容器 —— 用 MockMvc 测不到 序列化、异常处理器与过滤器的实际链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpEndpointIT extends AbstractMySqlIT {

    @Autowired private TestRestTemplate rest;

    /** 三个端点串起来跑通，响应壳字段齐全。 */
    @Test
    void endpointsReturnUnifiedEnvelope() {
        CreateTradeReq req = newTradeReq("http");

        ResponseEntity<JsonNode> created =
                rest.postForEntity("/api/benefit/trade", req, JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        JsonNode body = created.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("message").asText()).isEqualTo("success");
        // traceId 必须回传，否则线上按端上报的 ID 反查不到服务端日志
        assertThat(body.get("traceId").asText()).isNotBlank();

        String bizNo = body.get("data").get("bizNo").asText();
        String tradeNo = body.get("data").get("tradeNo").asText();
        assertThat(bizNo).isNotBlank();

        PayCallbackReq callback = newPayCallback(bizNo, tradeNo, "NS_http_1", "SUCCESS");
        ResponseEntity<JsonNode> paid =
                rest.postForEntity("/api/benefit/pay-callback", callback, JsonNode.class);
        assertThat(paid.getBody().get("code").asInt()).isZero();
        // 四分类只在 data.status 里出现，不映射成 HTTP 状态码或顶层 code
        assertThat(paid.getBody().get("data").get("status").asText()).isEqualTo("SUCCESS");

        ResponseEntity<JsonNode> queried =
                rest.getForEntity("/api/benefit/order/" + bizNo, JsonNode.class);
        JsonNode data = queried.getBody().get("data");
        assertThat(data.get("payStatus").asText()).isEqualTo(PayStatus.PAY_SUCCESS.name());
        assertThat(data.get("grantStatus").asText()).isEqualTo(GrantStatus.GRANT_SUCCESS.name());
        assertThat(data.get("fulfillments")).hasSize(2);
    }

    /** 业务拒绝走 200 + 业务码，不是 HTTP 4xx —— 端侧据 code 分支，HTTP 状态留给传输层故障。 */
    @Test
    void businessRejectionReturnsHttp200WithBizCode() {
        CreateTradeReq req = newTradeReq("httpQty");
        req.setQuantity(2);

        ResponseEntity<JsonNode> resp =
                rest.postForEntity("/api/benefit/trade", req, JsonNode.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("code").asInt())
                .isEqualTo(Integer.parseInt(ErrorCode.INVALID_PARAM));
        assertThat(resp.getBody().get("data").isNull()).isTrue();
        assertThat(resp.getBody().get("traceId").asText()).isNotBlank();
    }

    /**
     * grantBenefit 不对外暴露：它是内部编排入口，V2 由调度器驱动。
     *
     * <p>顺带验证传输层失败不被兜底分支吞成 5001 —— 5xxx 的语义是「结果未知，按 UNKNOWN 查单收敛」， 一个路由不存在的请求若报
     * 5001，调用方会为一件根本没发生的事发起重试与对账。
     */
    @Test
    void grantBenefitIsNotExposedOverHttp() {
        ResponseEntity<JsonNode> resp =
                rest.postForEntity("/api/benefit/grant/BZ_ANY", null, JsonNode.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        // 状态码之外，响应壳与 traceId 仍要完整 —— 排障时 404 和 200 一样需要按 traceId 反查
        assertThat(resp.getBody().get("code").asInt())
                .isEqualTo(Integer.parseInt(ErrorCode.INVALID_PARAM));
        assertThat(resp.getBody().get("traceId").asText()).isNotBlank();
    }

    /** 报文不可解析同样属传输层失败，不得记为 5001。 */
    @Test
    void malformedBodyIsClientErrorNotSystemError() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> resp =
                rest.exchange(
                        "/api/benefit/trade",
                        HttpMethod.POST,
                        new HttpEntity<>("{ not json", headers),
                        JsonNode.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        assertThat(resp.getBody().get("code").asInt())
                .isNotEqualTo(Integer.parseInt(ErrorCode.DOWNSTREAM_UNKNOWN));
    }
}
