CREATE TABLE reward_grant_item (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  op_no             VARCHAR(64) NOT NULL,
  item_seq          INT         NOT NULL COMMENT '奖励项序号，确定性下标',
  reward_type       VARCHAR(32) NOT NULL,
  provider_type     VARCHAR(32) NOT NULL COMMENT '每项可属不同供应方',
  provider_order_no VARCHAR(64) NULL COMMENT '该项的下游单号',
  result            VARCHAR(16) NOT NULL COMMENT '每项独立四分类结果',
  error_code        VARCHAR(32) NULL,
  create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_op_item (op_no, item_seq),
  KEY idx_provider_order (provider_order_no) COMMENT '按供应方单号反查（BR-C-26）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发奖明细，一项一行，支撑多供应方与下游单号索引';
