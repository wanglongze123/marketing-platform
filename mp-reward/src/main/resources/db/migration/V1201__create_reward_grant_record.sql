CREATE TABLE reward_grant_record (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  op_no             VARCHAR(64) NOT NULL COMMENT '调用方操作单号=幂等键',
  biz_order_no      VARCHAR(64) NOT NULL COMMENT '调用方业务单号，支撑按业务单查发奖',
  play_type         VARCHAR(32) NOT NULL,
  activity_id       VARCHAR(64) NOT NULL,
  receiver_id       VARCHAR(64) NOT NULL,
  result            VARCHAR(16) NOT NULL COMMENT '汇总结果 SUCCESS/FAIL/PROCESSING/UNKNOWN',
  error_code        VARCHAR(32) NULL,
  version           INT         NOT NULL DEFAULT 0,
  create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_op_no (op_no) COMMENT '同操作单号重复调用返回同结果',
  KEY idx_biz_order (biz_order_no),
  KEY idx_receiver_activity (receiver_id, activity_id, create_time) COMMENT '对账检出重复发奖'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一发奖幂等记录（主表）';
