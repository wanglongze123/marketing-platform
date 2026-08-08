-- 裂变操作记录，与关系状态变更同事务。
--
-- 两道唯一索引各挡一类重入：uk_idempotent 挡「同一个幂等键发两次」（网络重传、任务
-- 重试）；uk_biz_op 挡「同一业务语义生成了两个不同幂等键」（如重试时重新派生流水号）。
-- 唯一索引保护的是幂等键的唯一性，不是业务动作的唯一性，后者必须由单据级唯一约束承载。
--
-- status 与 downstream_result 拆列：前者是本地执行态，后者是下游返回的四分类结果。
-- 合并会导致「下游 PROCESSING」被写成本地 UNKNOWN，两者处置策略不同（§6.6）。
CREATE TABLE fission_op_record (
  id                      BIGINT       NOT NULL AUTO_INCREMENT,
  op_no                   VARCHAR(64)  NOT NULL COMMENT '操作单号',
  idempotent_key          VARCHAR(64)  NOT NULL COMMENT '幂等键=out_flow_no',
  out_biz_no              VARCHAR(64)  NOT NULL,
  activity_id             VARCHAR(64)  NOT NULL,
  subject_id              VARCHAR(64)  NOT NULL COMMENT '操作主体用户ID',
  op_type                 VARCHAR(32)  NOT NULL COMMENT 'FOLLOWER_JOIN/FOLLOWER_DONE/SPONSOR_DONE',
  op_seq                  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '至多一次的操作恒空串；可多次的取外部单号，严禁内部自增',
  status                  VARCHAR(16)  NOT NULL COMMENT '本地执行态 INIT/PROCESSING/SUCCESS/FAILED/UNKNOWN',
  downstream_result       VARCHAR(16)  NULL COMMENT '下游四分类结果 SUCCESS/FAIL/PROCESSING/UNKNOWN，与本地态分离',
  parent_op_no            VARCHAR(64)  NULL COMMENT '父操作单号',
  reward_back_out_flow_no VARCHAR(64)  NULL COMMENT '师傅返奖流水，派生自本流水',
  downstream_no           VARCHAR(64)  NULL COMMENT '奖励平台单号',
  error_code              VARCHAR(32)  NULL,
  retry_count             INT          NOT NULL DEFAULT 0,
  req_digest              VARCHAR(512) NULL,
  resp_digest             VARCHAR(512) NULL,
  recover_context         JSON         NULL,
  operator                VARCHAR(64)  NULL COMMENT '人工处置操作人（BR-C-27）',
  reason                  VARCHAR(256) NULL COMMENT '人工处置原因',
  version                 INT          NOT NULL DEFAULT 0,
  create_time             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  finish_time             DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idempotent (idempotent_key) COMMENT 'L3：同幂等键至多一条',
  UNIQUE KEY uk_biz_op (out_biz_no, op_type, op_seq) COMMENT 'L3：每业务每类操作至多一次，挡「不同幂等键、同业务语义」的重入',
  KEY idx_out_biz (out_biz_no),
  KEY idx_parent (parent_op_no) COMMENT '从父查子',
  KEY idx_downstream (downstream_no) COMMENT '按下游单号反查业务（BR-C-26）',
  KEY idx_subject_activity (subject_id, activity_id, create_time) COMMENT '用户+活动查奖励（FR-F10）',
  KEY idx_status_recover (status, update_time) COMMENT '非终态收敛扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变操作记录，与关系状态变更同事务';
