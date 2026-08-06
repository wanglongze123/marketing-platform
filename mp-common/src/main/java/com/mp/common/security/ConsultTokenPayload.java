package com.mp.common.security;

/**
 * 咨询凭证的载荷：签发时绑定、验签时逐字段比对的那些值。
 *
 * <p>每个字段都是<b>一道独立的闸</b>，漏比一个就漏一类越权：
 *
 * <ul>
 *   <li>{@code userId} —— 不比即可拿别人的凭证下单
 *   <li>{@code activityId} / {@code skuId} —— 不比即可拿 A 商品的低价凭证买 B 商品
 *   <li>{@code dealPrice} —— 服务端重算价的比对基准，不比即失去 1711 的依据
 * </ul>
 *
 * <p>{@code packageVersion} 是<b>第五道闸，且不可由前四道推出</b>：权益包的内容由它决定，而它换版时 价格可以一分不动 —— 用户按「月卡 + 券」咨询，运营把
 * SKU 改指向只剩券的新版，价格仍是 99 元， 于是前四道全过、比价也过，用户按月卡+券付了钱只拿到券。<b>价格没变不代表承诺没变。</b>
 *
 * <p>{@code configVersion} 不作为拒绝依据：它是活动级的版本，与权益内容无关（后者取决于 {@code
 * packageVersion}）。活动改版而价格与权益包都未变时，用户看到的承诺确实没有变化，据此拒绝只会 白白打断下单。它入签名是为了让「这张凭证签发时活动是哪个版本」可审计、不可篡改。
 *
 * <p>PRD FR-B01 的绑定项还含城市与渠道 —— V2 尚未建模 {@code ReqTerminalInfo}，故不列。
 * 补的时候直接加字段即可：字段进了签名，旧凭证在新版本下自然验签失败。
 *
 * @param expireAtEpochMilli 过期时刻。<b>必须在签名覆盖范围内</b> —— 放签名之外等于让持有者自行 决定有效期
 */
public record ConsultTokenPayload(
        String userId,
        String activityId,
        String skuId,
        long dealPrice,
        int packageVersion,
        int configVersion,
        long expireAtEpochMilli) {}
