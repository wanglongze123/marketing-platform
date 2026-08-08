-- 活动操作记录：配置变更审计（BR-C-27）
--
-- createActivity / publishActivity / changeActivityStatus 都是有副作用的写操作，
-- 按「任一有副作用动作都要留痕」的约定各建一条（技术方案 §3.2）。
CREATE TABLE activity_op_record (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  op_no          VARCHAR(64) NOT NULL COMMENT '操作单号',
  idempotent_key VARCHAR(64) NOT NULL COMMENT '幂等键，来自调用方',
  activity_id    VARCHAR(64) NOT NULL,
  op_type        VARCHAR(32) NOT NULL COMMENT 'CREATE_ACTIVITY/PUBLISH_ACTIVITY/CHANGE_STATUS',
  op_seq         VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'CREATE 恒空串（每活动仅建一次）；PUBLISH 取版本号；CHANGE_STATUS 取调用方请求号。严禁内部自增',
  status         VARCHAR(16) NOT NULL COMMENT '本地执行态 INIT/PROCESSING/SUCCESS/FAILED/UNKNOWN',
  from_status    VARCHAR(16) NULL COMMENT '状态变更前值，供审计回溯',
  to_status      VARCHAR(16) NULL COMMENT '状态变更后值',
  version_no     INT         NULL COMMENT '本次发布产生的配置版本号',
  error_code     VARCHAR(32) NULL,
  operator       VARCHAR(64) NULL COMMENT '操作人（BR-C-27）',
  reason         VARCHAR(256) NULL COMMENT '操作原因',
  create_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotent (idempotent_key) COMMENT 'L3：同幂等键至多一条，挡重传',
  UNIQUE KEY uk_biz_op (activity_id, op_type, op_seq) COMMENT 'L3：每活动每类操作至多一次，挡「不同幂等键、同业务语义」的重入',
  KEY idx_activity (activity_id, create_time) COMMENT '按活动查变更历史'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动操作记录，与配置变更同事务';
