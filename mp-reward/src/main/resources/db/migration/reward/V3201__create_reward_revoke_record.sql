-- 统一回收幂等记录（V3 PR-7，技术方案 §3.5）。
--
-- 为什么必须单独一张表、而不是在 reward_grant_record 上加几列：
--
-- 发奖、回收、退款各用独立幂等键，且**每一个都要有唯一索引承载**（BR-C-11）。回收若
-- 复用发奖的 uk_op_no，回收请求会撞上原发奖那一行被当成「发奖重传」吞掉 —— 权益实际
-- 没有回收，而调用方拿到「成功」。这是「退了钱权益还在」这类资损的一条具体成因。
--
-- 三者缺一不可：只为发奖建幂等表时，回收的 revokeNo 无索引落地，回收 RPC 超时重试
-- 即可能二次回收。
--
-- usage_status 记录**回收当时**供应方回传的真实使用态（BR-B-30）。它与 result 分列：
-- 前者是「这张券当时什么状态」，后者是「这次回收操作成没成」。已核销的券回收失败时
-- 两者分别是 USED 与 FAIL —— 合成一列则无从区分「不能回收」与「回收出错」，而前者
-- 不该重试、后者该。
CREATE TABLE reward_revoke_record (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  revoke_no         VARCHAR(64) NOT NULL COMMENT '回收幂等键，与发奖 op_no 不复用（BR-C-11）',
  biz_order_no      VARCHAR(64) NOT NULL,
  op_no             VARCHAR(64) NULL COMMENT '被回收的原发奖操作单号',
  receiver_id       VARCHAR(64) NOT NULL,
  usage_status      VARCHAR(16) NULL COMMENT '回收当时供应方回传的真实使用态，与 result 分列',
  result            VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAIL/PROCESSING/UNKNOWN',
  provider_order_no VARCHAR(64) NULL,
  error_code        VARCHAR(32) NULL,
  version           INT         NOT NULL DEFAULT 0,
  create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_revoke_no (revoke_no) COMMENT 'L3：同回收单号重复调用不二次回收',
  KEY idx_biz_order (biz_order_no),
  KEY idx_op_no (op_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一回收幂等记录';
