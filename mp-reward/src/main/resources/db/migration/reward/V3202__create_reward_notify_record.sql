-- 供应方异步通知的幂等记录（V3 PR-9，技术方案 §4.3 providerCallback）。
--
-- 为什么通知要单独一张表，而不是只靠 reward_grant_record 的条件更新去重：
--
-- 条件更新（result = 'PROCESSING' 才推进）只能保证「终态不被覆盖」，保证不了「同一条通知
-- 只处理一次」。二者不是一回事：同一个 op_no 会收到多条语义不同的通知（先 PROCESSING 后
-- SUCCESS，或供应方补发），它们各自都该留痕；而同一条通知重复投递（MQ 至少一次、供应方
-- 重试）必须被识别成重传。判据是通知流水 notify_seq，不是 op_no。
--
-- 只按 op_no 去重则第二条真实通知被当成重传丢弃，供应方后来补发的成功结果永远进不来；
-- 完全不去重则每次重投都发一次事件，消费侧要独自承担全部去重压力——而消费侧有两个
-- （benefit-order 与 fission），压力要各扛一遍。
--
-- uk_notify 取 (op_no, notify_seq) 两维：notify_seq 是供应方的流水号，同一供应方内唯一，
-- 但不同 op_no 之间可能重号，故不能单独作唯一键。
CREATE TABLE reward_notify_record (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  op_no             VARCHAR(64) NOT NULL COMMENT '发奖幂等键，通知据它定位原发放',
  notify_seq        VARCHAR(64) NOT NULL COMMENT '供应方通知流水，重传保持不变',
  provider_order_no VARCHAR(64) NULL,
  result            VARCHAR(16) NOT NULL COMMENT '本条通知携带的四分类结果',
  error_code        VARCHAR(32) NULL,
  create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify (op_no, notify_seq) COMMENT 'L3：同一条通知重复投递只处理一次',
  KEY idx_op_no (op_no) COMMENT '按发奖单查其全部通知，供对账与排查'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应方异步通知幂等记录';
