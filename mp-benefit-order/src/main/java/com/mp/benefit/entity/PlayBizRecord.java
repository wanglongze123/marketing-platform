package com.mp.benefit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
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

    /** 本单库存处置态，库存类任务每单幂等的承重点。取值见 {@code StockStatus} */
    private String stockStatus;

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

    private LocalDateTime expireTime;

    @Version private Integer version;

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

    public String getStockStatus() {
        return stockStatus;
    }

    public void setStockStatus(String stockStatus) {
        this.stockStatus = stockStatus;
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
