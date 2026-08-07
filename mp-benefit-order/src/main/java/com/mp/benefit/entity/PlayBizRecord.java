package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 权益业务主单。
 *
 * <p><b>三子状态并存</b>：pay / grant / refund 三条线独立推进，展示态由三者派生、不落库。
 */
@TableName("play_biz_record")
public class PlayBizRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务主单号，同时作为支付侧商户订单号 */
    private String playBizRecordNo;

    private String activityId;
    private String skuId;
    private String userId;

    /** 客户端请求号，参与业务幂等键 */
    private String clientReqNo;

    private Integer quantity;

    /** 下单城市，参与资格判定与分维度库存（V3）。V2 不写入 */
    private String cityCode;

    /** 来源渠道，参与资格判定与归因（V3）。V2 不写入 */
    private String sourceChannel;

    /** 本单库存处置态，库存类任务每单幂等的承重点。取值见 {@code StockStatus} */
    private String stockStatus;

    /**
     * 本单限购额度处置态，额度返还的每单幂等承重点。取值见 {@code QuotaStatus}。
     *
     * <p>与 {@code stockStatus} 分列：库存对每一单都预占，额度只在 SKU 配了限购时才扣。
     */
    private String quotaStatus;

    private String payStatus;
    private String grantStatus;
    private String refundStatus;

    /** 应付，分 */
    private Long orderAmount;

    /** 实付，分 */
    private Long payAmount;

    private String currency;

    /** 下单时冻结的配置版本，履约与退款一律读快照 */
    private Integer configVersion;

    private String priceSnapshot;

    /** 权益项快照，履约据此，不再查配置表 */
    private String benefitSnapshot;

    /** 支付单号。可为 NULL —— 建单时支付方尚未返回 */
    private String tradeNo;

    /**
     * 退款单号（V3）。V2 不写入。
     *
     * <p>与 {@code refundAmount} 一同映射，尽管 V2 无退款链路：表中已有这两列，实体不映射则写 退款代码时不会注意到位置已经留好，容易另加一张表或另起字段名。
     */
    private String refundNo;

    /** 退款金额，分（V3）。V2 不写入 */
    private Long refundAmount;

    private LocalDateTime expireTime;

    /**
     * 表中的 {@code version} 列，<b>当前不做乐观锁</b>，仅为映射完整而保留。
     *
     * <p>原带 {@code @Version} 注解，但全仓未注册 {@code OptimisticLockerInnerInterceptor}，注解不生效；
     * 且状态变更一律走带前置状态的条件更新，不用 {@code updateById}，乐观锁本就无从触发。留着注解 会让人以为它在保护并发写，进而写出依赖它的代码。
     *
     * <p>不做乐观锁是有意的：条件更新的谓词（{@code WHERE pay_status = ?}）比版本号比对更强 —— 前者表达
     * 「只有处于该状态才允许推进」，后者只表达「这行没被别人改过」。见 {@code V2102__create_marketing_stock.sql} 对两张库存表的同一取舍。
     */
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlayBizRecordNo() {
        return playBizRecordNo;
    }

    public void setPlayBizRecordNo(String playBizRecordNo) {
        this.playBizRecordNo = playBizRecordNo;
    }

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getClientReqNo() {
        return clientReqNo;
    }

    public void setClientReqNo(String clientReqNo) {
        this.clientReqNo = clientReqNo;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(String sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public String getStockStatus() {
        return stockStatus;
    }

    public void setStockStatus(String stockStatus) {
        this.stockStatus = stockStatus;
    }

    public String getQuotaStatus() {
        return quotaStatus;
    }

    public void setQuotaStatus(String quotaStatus) {
        this.quotaStatus = quotaStatus;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public String getGrantStatus() {
        return grantStatus;
    }

    public void setGrantStatus(String grantStatus) {
        this.grantStatus = grantStatus;
    }

    public String getRefundStatus() {
        return refundStatus;
    }

    public void setRefundStatus(String refundStatus) {
        this.refundStatus = refundStatus;
    }

    public Long getOrderAmount() {
        return orderAmount;
    }

    public void setOrderAmount(Long orderAmount) {
        this.orderAmount = orderAmount;
    }

    public Long getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(Long payAmount) {
        this.payAmount = payAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Integer configVersion) {
        this.configVersion = configVersion;
    }

    public String getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(String priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public String getBenefitSnapshot() {
        return benefitSnapshot;
    }

    public void setBenefitSnapshot(String benefitSnapshot) {
        this.benefitSnapshot = benefitSnapshot;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public Long getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(Long refundAmount) {
        this.refundAmount = refundAmount;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
