package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.CreateTradeResp;
import com.mp.common.enums.ErrorCode;
import com.mp.common.enums.GrantStatus;
import com.mp.common.enums.PayStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * 三个只读查询端点。
 *
 * <p>验的是「只读接口确实只读」与「分页真的分页」两件事：
 *
 * <ul>
 *   <li>查询前后主单状态、操作记录行数、履约行数均不变 —— 只读接口若意外落了操作记录， 会污染幂等键空间与对账口径
 *   <li>{@code selectPage} 在未注册 {@code PaginationInnerInterceptor} 时不报错而是返回全表、 {@code total} 恒
 *       0。seed 数据只有几行时人眼看不出，故用「造 3 单取 size=2」断言
 * </ul>
 *
 * <p>{@code RANDOM_PORT} 起真实 servlet 容器：查询参数绑定、枚举校验、响应壳序列化都在 Web 层， MockMvc 测不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadOnlyQueryIT extends AbstractMySqlIT {

    @Autowired private TestRestTemplate rest;

    // ------------------------------------------------------------------
    // 订单列表
    // ------------------------------------------------------------------

    /** 按 userId 过滤，且分页真实生效（size 生效、total 为过滤后总数而非本页行数）。 */
    @Test
    void orderListFiltersByUserAndPaginates() {
        String user = "U_roList";
        for (int i = 1; i <= 3; i++) {
            CreateTradeReq req = newTradeReq("roList" + i);
            req.setUserId(user); // 同一用户三笔，clientReqNo 不同故不触发幂等
            benefitOrderService.createTrade(req);
        }

        ResponseEntity<JsonNode> resp =
                rest.getForEntity(
                        "/api/benefit/orders?userId=" + user + "&page=1&size=2", JsonNode.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = resp.getBody();
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("traceId").asText()).isNotBlank();

        JsonNode data = body.get("data");
        // size=2 生效：未注册分页插件时此处会是 3
        assertThat(data.get("items")).hasSize(2);
        // total 是过滤后总数，不受 size 限制
        assertThat(data.get("total").asLong()).isEqualTo(3);
        assertThat(data.get("page").asInt()).isEqualTo(1);
        assertThat(data.get("size").asInt()).isEqualTo(2);

        // 列表行只含主单信息，不含履约明细
        JsonNode first = data.get("items").get(0);
        assertThat(first.has("fulfillments")).isFalse();
        assertThat(first.get("payStatus").asText()).isEqualTo(PayStatus.WAIT_PAY.name());
        assertThat(first.get("grantStatus").asText()).isEqualTo(GrantStatus.NOT_START.name());
        assertThat(first.get("orderAmount").asLong()).isEqualTo(SALE_PRICE);

        // 第二页拿到剩下的一笔，且与第一页不重复
        ResponseEntity<JsonNode> p2 =
                rest.getForEntity(
                        "/api/benefit/orders?userId=" + user + "&page=2&size=2", JsonNode.class);
        assertThat(p2.getBody().get("data").get("items")).hasSize(1);
        String p1First = first.get("bizNo").asText();
        String p2First = p2.getBody().get("data").get("items").get(0).get("bizNo").asText();
        assertThat(p2First).isNotEqualTo(p1First);
    }

    /** 三条子状态线各自返回，不合并成单一 biz_status。 */
    @Test
    void orderListReturnsThreeSubStatusesSeparately() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("roSub"));

        JsonNode item =
                rest.getForEntity("/api/benefit/orders?userId=U_roSub", JsonNode.class)
                        .getBody()
                        .get("data")
                        .get("items")
                        .get(0);

        assertThat(item.get("bizNo").asText()).isEqualTo(created.getBizNo());
        assertThat(item.has("payStatus")).isTrue();
        assertThat(item.has("grantStatus")).isTrue();
        assertThat(item.has("refundStatus")).isTrue();
        // 派生的展示态不落库、不由后端返回
        assertThat(item.has("bizStatus")).isFalse();
    }

    /**
     * 非法状态取值返回 4001，不当作「查不到」回空列表。
     *
     * <p>回空列表会让端侧把拼错的枚举名误读成「该状态下没有订单」，问题被数据掩盖。
     */
    @Test
    void orderListRejectsIllegalStatusValue() {
        ResponseEntity<JsonNode> resp =
                rest.getForEntity("/api/benefit/orders?payStatus=NOT_A_STATUS", JsonNode.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("code").asInt())
                .isEqualTo(Integer.parseInt(ErrorCode.INVALID_PARAM));
    }

    /** 主单发放态用带 GRANT_ 前缀的取值筛选；明细态的 SUCCESS 不是主单的合法取值。 */
    @Test
    void orderListGrantStatusUsesPrefixedEnum() {
        assertThat(
                        rest.getForEntity(
                                        "/api/benefit/orders?grantStatus=NOT_START", JsonNode.class)
                                .getBody()
                                .get("code")
                                .asInt())
                .isZero();

        // SUCCESS 属 ItemGrantStatus，主单枚举里没有 —— 必须被拒绝，否则串用不会被发现
        assertThat(
                        rest.getForEntity("/api/benefit/orders?grantStatus=SUCCESS", JsonNode.class)
                                .getBody()
                                .get("code")
                                .asInt())
                .isEqualTo(Integer.parseInt(ErrorCode.INVALID_PARAM));
    }

    // ------------------------------------------------------------------
    // 商品详情
    // ------------------------------------------------------------------

    /** 返回 seed 中的 SKU 与两个分属不同供应方的权益项，按 grantOrder 升序。 */
    @Test
    void skuDetailReturnsPackageItemsInGrantOrder() {
        ResponseEntity<JsonNode> resp =
                rest.getForEntity("/api/benefit/sku/" + SKU_ID, JsonNode.class);

        JsonNode data = resp.getBody().get("data");
        assertThat(resp.getBody().get("code").asInt()).isZero();
        assertThat(data.get("skuId").asText()).isEqualTo(SKU_ID);
        assertThat(data.get("activityId").asText()).isEqualTo(ACTIVITY_ID);
        assertThat(data.get("salePrice").asLong()).isEqualTo(SALE_PRICE);
        assertThat(data.get("listPrice").asLong()).isEqualTo(19900L);
        assertThat(data.get("saleStatus").asText()).isEqualTo("ON_SALE");

        JsonNode items = data.get("items");
        assertThat(items).hasSize(2);
        // 两项刻意分属不同供应方，履约才会真的走到分组逻辑
        assertThat(items.get(0).get("providerType").asText()).isEqualTo("PROVIDER_A");
        assertThat(items.get(1).get("providerType").asText()).isEqualTo("PROVIDER_B");
        assertThat(items.get(0).get("core").asBoolean()).isTrue();
        assertThat(items.get(1).get("core").asBoolean()).isFalse();
    }

    @Test
    void skuDetailRejectsUnknownSku() {
        ResponseEntity<JsonNode> resp =
                rest.getForEntity("/api/benefit/sku/SKU_NOT_EXIST", JsonNode.class);

        assertThat(resp.getBody().get("code").asInt())
                .isEqualTo(Integer.parseInt(ErrorCode.INVALID_PARAM));
    }

    // ------------------------------------------------------------------
    // 操作记录
    // ------------------------------------------------------------------

    /** 建单 + 支付 + 履约后，三类操作记录齐全，且本地态与下游四分类分列返回。 */
    @Test
    void opRecordsExposeLocalStatusAndDownstreamResultSeparately() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("roOp"));
        String bizNo = created.getBizNo();
        benefitOrderService.payCallback(
                newPayCallback(bizNo, created.getTradeNo(), "NS_roOp", "SUCCESS"));

        JsonNode records =
                rest.getForEntity("/api/benefit/order/" + bizNo + "/op-records", JsonNode.class)
                        .getBody()
                        .get("data");

        assertThat(records.size()).isGreaterThanOrEqualTo(3);

        JsonNode grant = null;
        for (JsonNode r : records) {
            if ("GRANT_BENEFIT".equals(r.get("opType").asText())) {
                grant = r;
            }
        }
        assertThat(grant).isNotNull();
        // status 是本地执行态（OpStatus.SUCCESS），downstreamResult 是下游四分类（RetStatus.SUCCESS）。
        // 两者分列，合并展示会掩盖「本地记为失败、下游实际成功」这类差异
        assertThat(grant.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(grant.get("downstreamResult").asText()).isEqualTo("SUCCESS");
        assertThat(grant.has("retryCount")).isTrue();
    }

    /** 单不存在时报 4001，不回空列表 —— 空列表分不清「单不存在」与「单存在但无记录」。 */
    @Test
    void opRecordsRejectUnknownOrder() {
        ResponseEntity<JsonNode> resp =
                rest.getForEntity("/api/benefit/order/BZ_NOT_EXIST/op-records", JsonNode.class);

        assertThat(resp.getBody().get("code").asInt())
                .isEqualTo(Integer.parseInt(ErrorCode.INVALID_PARAM));
    }

    // ------------------------------------------------------------------
    // 只读性
    // ------------------------------------------------------------------

    /**
     * 三个查询接口均无副作用。
     *
     * <p>只读接口若意外落了操作记录，会污染幂等键空间与对账口径 —— 对账按操作记录比对， 多出来的行会被当成真实业务动作。
     */
    @Test
    void queriesHaveNoSideEffects() {
        CreateTradeResp created = benefitOrderService.createTrade(newTradeReq("roPure"));
        String bizNo = created.getBizNo();
        benefitOrderService.payCallback(
                newPayCallback(bizNo, created.getTradeNo(), "NS_roPure", "SUCCESS"));

        String payBefore = orderField("pay_status", bizNo);
        String grantBefore = orderField("grant_status", bizNo);
        int opsBefore =
                count("SELECT COUNT(*) FROM play_op_record WHERE play_biz_record_no = ?", bizNo);
        int ffBefore = fulfillmentCount(bizNo);
        // 取实际值而非硬编码：发奖记录数 = 供应方组数（每组一个 grantOpNo），
        // 权益项数变化时硬编码的期望值会失准，而这里要验的是「查询前后不变」
        int grantRecordsBefore = grantRecordCount(bizNo);
        int grantItemsBefore = grantItemCount(bizNo);

        // 每个只读端点各调两次，重复调用同样不得产生任何写入
        for (int i = 0; i < 2; i++) {
            rest.getForEntity("/api/benefit/orders?userId=U_roPure", JsonNode.class);
            rest.getForEntity("/api/benefit/sku/" + SKU_ID, JsonNode.class);
            rest.getForEntity("/api/benefit/order/" + bizNo + "/op-records", JsonNode.class);
            rest.getForEntity("/api/benefit/order/" + bizNo, JsonNode.class);
        }

        assertThat(orderField("pay_status", bizNo)).isEqualTo(payBefore);
        assertThat(orderField("grant_status", bizNo)).isEqualTo(grantBefore);
        assertThat(count("SELECT COUNT(*) FROM play_op_record WHERE play_biz_record_no = ?", bizNo))
                .isEqualTo(opsBefore);
        assertThat(fulfillmentCount(bizNo)).isEqualTo(ffBefore);
        // 发奖侧同样不得因查询而多出记录
        assertThat(grantRecordCount(bizNo)).isEqualTo(grantRecordsBefore);
        assertThat(grantItemCount(bizNo)).isEqualTo(grantItemsBefore);
        // 履约确实发生过，否则上面几条「不变」断言在空数据上也会通过
        assertThat(ffBefore).isEqualTo(2);
        assertThat(grantRecordsBefore).isEqualTo(2); // 两个供应方各一次调用、各一个 grantOpNo
    }

    /** size 超上限时被收口到 100，不透传到 SQL。 */
    @Test
    void oversizedPageSizeIsCapped() {
        JsonNode data =
                rest.getForEntity("/api/benefit/orders?size=100000", JsonNode.class)
                        .getBody()
                        .get("data");

        assertThat(data.get("code")).isNull();
        assertThat(data.get("size").asInt()).isEqualTo(100);
    }
}
