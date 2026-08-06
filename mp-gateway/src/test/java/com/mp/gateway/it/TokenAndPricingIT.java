package com.mp.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mp.api.benefit.dto.CreateTradeReq;
import com.mp.api.benefit.dto.PreConsultReq;
import com.mp.api.benefit.dto.PreConsultResp;
import com.mp.common.enums.ErrorCode;
import com.mp.common.exception.BizException;
import com.mp.common.security.ConsultTokenSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1 凭证签名与服务端比价，对应《分阶段方案》§5.7 退出标准 16。
 *
 * <p>两道校验挡的是<b>两类不同的失效</b>，缺任一道另一道都补不上：
 *
 * <ul>
 *   <li><b>验签 + 逐字段比对</b>挡伪造与越权 —— 拿别人的凭证、拿别的商品的凭证
 *   <li><b>服务端重算比价</b>挡「凭证合法但价格已过时」—— 凭证是平台自己签的、字段也全对， 只是运营在咨询之后改了价
 * </ul>
 *
 * <p>每个断言都同时验证「拒绝了」与「没建单」：只断言抛异常不足够 —— 先建单再校验同样会抛， 而那已经占了库存、留了脏单（V2 尚无库存，PR-5 之后代价更实）。
 */
class TokenAndPricingIT extends AbstractMySqlIT {

    @Autowired private ConsultTokenSigner signer;

    /** 用例可能改 SKU 价格，跑完恢复，否则后续用例的比价会莫名失配。 */
    @AfterEach
    void restorePrice() {
        benefitJdbc.update(
                "UPDATE benefit_sku SET sale_price = ? WHERE sku_id = ?", SALE_PRICE, SKU_ID);
    }

    // ------------------------------------------------------------------
    // 正向：咨询签发的价格与下单收款的金额一致
    // ------------------------------------------------------------------

    /**
     * 咨询给出的成交价，就是主单最终的应付金额。
     *
     * <p>这条看似平凡，挡的却是最难查的一类事故：两处各自算价、算法不同步，用户看到 9.9 元、 主单落 19.9 元。比价只保证「凭证价 == 重算价」，不保证「重算价 ==
     * 落库金额」。
     */
    @Test
    void quotedPriceIsWhatTheOrderCharges() {
        PreConsultResp quote = consult("quote");

        assertThat(quote.getDealPrice()).isEqualTo(SALE_PRICE);
        assertThat(quote.getOriginPrice()).isGreaterThan(quote.getDealPrice());
        assertThat(quote.getConsultToken()).isNotBlank();
        // 端上据此判断是否需要重新咨询，与凭证里签着的必须是同一个值
        assertThat(quote.getExpireAt()).isGreaterThan(System.currentTimeMillis());

        CreateTradeReq req = newTradeReq("quote");
        req.setConsultToken(quote.getConsultToken());
        String bizNo = benefitOrderService.createTrade(req).getBizNo();

        assertThat(orderField("order_amount", bizNo))
                .isEqualTo(String.valueOf(quote.getDealPrice()));
        // 配置版本冻结进主单，履约与退款一律读它
        assertThat(orderField("config_version", bizNo))
                .isEqualTo(String.valueOf(quote.getConfigVersion()));
    }

    /** 预咨询是只读的：不建单、不落操作记录（PRD FR-B01 后置条件）。 */
    @Test
    void preConsultLeavesNoTrace() {
        int ordersBefore = count(benefitJdbc, "SELECT COUNT(*) FROM play_biz_record");
        int opsBefore = count(benefitJdbc, "SELECT COUNT(*) FROM play_op_record");

        consult("readonly");
        consult("readonly");

        assertThat(count(benefitJdbc, "SELECT COUNT(*) FROM play_biz_record"))
                .isEqualTo(ordersBefore);
        assertThat(count(benefitJdbc, "SELECT COUNT(*) FROM play_op_record")).isEqualTo(opsBefore);
    }

    // ------------------------------------------------------------------
    // 反向一：验签与逐字段比对
    // ------------------------------------------------------------------

    /**
     * 无凭证不得下单。
     *
     * <p>这是 L1 是否真的接线的判据：若 {@code createTrade} 根本没读凭证字段，本用例是唯一会红的。
     */
    @Test
    void createTradeWithoutTokenIsRejected() {
        CreateTradeReq req = newTradeReq("noToken");
        req.setConsultToken(null);

        assertRejectedWithoutOrder(req, ErrorCode.INVALID_TOKEN);
    }

    /** 伪造的凭证不得下单 —— 攻击者不知道密钥，签不出能验过的凭证。 */
    @Test
    void forgedTokenIsRejected() {
        CreateTradeReq req = newTradeReq("forged");
        req.setConsultToken("ZmFrZQ.ZmFrZXNpZw");

        assertRejectedWithoutOrder(req, ErrorCode.INVALID_TOKEN);
    }

    /**
     * <b>拿别人的合法凭证下单被拒。</b>
     *
     * <p>这张凭证由平台签发、签名正确、也没过期 —— 验签这一关它<b>完全通过</b>。只有逐字段比对 才拦得住。若实现只验签不比对，本用例是唯一会红的。
     */
    @Test
    void anotherUsersValidTokenIsRejected() {
        String victimToken = consultToken("U_victim", ACTIVITY_ID, SKU_ID);

        // 前置：这张凭证本身是合法的，被拒不是因为它无效
        assertThat(signer.verify(victimToken).userId()).isEqualTo("U_victim");

        CreateTradeReq req = newTradeReq("attacker");
        req.setConsultToken(victimToken);

        assertRejectedWithoutOrder(req, ErrorCode.INVALID_TOKEN);
    }

    /**
     * <b>拿别的商品的凭证下单被拒。</b>
     *
     * <p>比价拦不住这一类：凭证里的价格与它自己那件商品的重算价本来就相等。若只比价不比 {@code skuId}， 拿低价商品的凭证买高价商品会一路通过。
     */
    @Test
    void tokenIssuedForAnotherSkuIsRejected() {
        // 另建一件同活动、更便宜的在售商品
        benefitJdbc.update(
                "INSERT INTO benefit_sku (sku_id, activity_id, sku_name, sku_type, sale_status,"
                        + " list_price, sale_price, benefit_package_id, package_version)"
                        + " VALUES (?, ?, '廉价商品', 'SINGLE_PACK', 'ON_SALE', 200, 100,"
                        + " 'PKG_DEMO_001', 1)",
                "SKU_CHEAP_001",
                ACTIVITY_ID);

        String cheapToken = consultToken("U_swap", ACTIVITY_ID, "SKU_CHEAP_001");
        assertThat(signer.verify(cheapToken).dealPrice()).isEqualTo(100L);

        // 拿 1 元商品的凭证去买 99 元商品
        CreateTradeReq req = newTradeReq("swap");
        req.setUserId("U_swap");
        req.setConsultToken(cheapToken);

        assertRejectedWithoutOrder(req, ErrorCode.INVALID_TOKEN);
    }

    /**
     * 过期凭证被拒。
     *
     * <p>用负有效期签一张「出生即过期」的，而不是等真实时间流逝 —— 后者要么等 15 分钟，要么把 有效期压到与执行耗时同量级，那会让正常用例随机变红。
     */
    @Test
    void expiredTokenIsRejected() {
        String expired = signer.sign("U_expired", ACTIVITY_ID, SKU_ID, SALE_PRICE, 1, -60);

        CreateTradeReq req = newTradeReq("expired");
        req.setUserId("U_expired");
        req.setConsultToken(expired);

        assertRejectedWithoutOrder(req, ErrorCode.INVALID_TOKEN);
    }

    // ------------------------------------------------------------------
    // 反向二：服务端重算比价
    // ------------------------------------------------------------------

    /**
     * <b>咨询之后运营改价，旧凭证下单被拒（1711）。</b>
     *
     * <p>这张凭证签名正确、字段全对、也没过期 —— 前面所有校验它都通过。挡住它的只有比价。
     *
     * <p>拒绝而不是取新价，也不是取较低价（PRD BR-B-08）：取新价则用户看到 9.9 却被扣 19.9， 取低价则平台每次调价都被旧凭证薅一轮。
     */
    @Test
    void priceChangedAfterConsultIsRejectedWithoutOrder() {
        PreConsultResp quote = consult("priceUp");
        assertThat(quote.getDealPrice()).isEqualTo(SALE_PRICE);

        // 运营涨价
        benefitJdbc.update(
                "UPDATE benefit_sku SET sale_price = ? WHERE sku_id = ?", SALE_PRICE * 2, SKU_ID);

        CreateTradeReq req = newTradeReq("priceUp");
        req.setConsultToken(quote.getConsultToken());

        assertRejectedWithoutOrder(req, ErrorCode.PRICE_MISMATCH);
    }

    /** 降价同样拒绝：比价是等值判定，不是「不超过即可」。 */
    @Test
    void priceDropAfterConsultIsAlsoRejected() {
        PreConsultResp quote = consult("priceDown");

        benefitJdbc.update(
                "UPDATE benefit_sku SET sale_price = ? WHERE sku_id = ?", SALE_PRICE / 2, SKU_ID);

        CreateTradeReq req = newTradeReq("priceDown");
        req.setConsultToken(quote.getConsultToken());

        assertRejectedWithoutOrder(req, ErrorCode.PRICE_MISMATCH);
    }

    /**
     * 改价后<b>重新咨询即可正常下单</b>，且按新价收款。
     *
     * <p>没有这一条，「一律拒绝」可能被实现成「改过价的商品从此卖不出去」—— 那不是防线，是故障。
     */
    @Test
    void reConsultingAfterPriceChangeSucceedsAtTheNewPrice() {
        consult("reconsult");

        long newPrice = SALE_PRICE * 2;
        benefitJdbc.update(
                "UPDATE benefit_sku SET sale_price = ? WHERE sku_id = ?", newPrice, SKU_ID);

        PreConsultResp fresh = consult("reconsult");
        assertThat(fresh.getDealPrice()).isEqualTo(newPrice);

        CreateTradeReq req = newTradeReq("reconsult");
        req.setConsultToken(fresh.getConsultToken());
        String bizNo = benefitOrderService.createTrade(req).getBizNo();

        assertThat(orderField("order_amount", bizNo)).isEqualTo(String.valueOf(newPrice));
    }

    // ------------------------------------------------------------------

    private PreConsultResp consult(String tag) {
        PreConsultReq req = new PreConsultReq();
        req.setUserId("U_" + tag);
        req.setActivityId(ACTIVITY_ID);
        req.setSkuId(SKU_ID);
        return benefitOrderService.preConsult(req);
    }

    /**
     * 被拒 + 未建单。
     *
     * <p><b>「没建单」与「拒绝了」必须同时断言</b>：先建单再校验也会抛出同样的异常，只断言异常 则那种实现照样全绿 —— 而它已经占了库存、留了脏单。
     */
    private void assertRejectedWithoutOrder(CreateTradeReq req, String expectedCode) {
        assertThatThrownBy(() -> benefitOrderService.createTrade(req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expectedCode);

        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_biz_record WHERE user_id = ?"
                                        + " AND client_req_no = ?",
                                req.getUserId(),
                                req.getClientReqNo()))
                .as("校验不通过时不得留下任何单据")
                .isZero();
        // 列名是 subject_id 而非 user_id —— 操作记录的主体是「做这个操作的人」，
        // 与主单的 user_id 是两个概念（人工处置时二者不同）
        assertThat(
                        count(
                                benefitJdbc,
                                "SELECT COUNT(*) FROM play_op_record WHERE subject_id = ?"
                                        + " AND op_type = 'CREATE_TRADE'",
                                req.getUserId()))
                .as("校验不通过时不得留下操作记录")
                .isZero();
    }
}
