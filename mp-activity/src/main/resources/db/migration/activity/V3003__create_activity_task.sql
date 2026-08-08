-- 活动可靠任务表（本地消息表）。结构同 benefit_task / fission_task。
--
-- 任务表必须与业务状态同库（技术方案 §1.3）：任务写入与状态变更须处于同一本地事务，
-- 跨库即无法保证「状态已变更则任务必存在」。故不复用 benefit_task。
--
-- task_type：ACTIVITY_ONLINE（SCHEDULED → ONLINE 定时推进）、ACTIVITY_END（到期结束）
CREATE TABLE activity_task (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  task_no      VARCHAR(64) NOT NULL,
  biz_no       VARCHAR(64) NOT NULL COMMENT '关联业务号，取 activity_id',
  task_type    VARCHAR(32) NOT NULL COMMENT 'ACTIVITY_ONLINE/ACTIVITY_END',
  op_no        VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联操作单号，任务建立时固化；重试只读不重生成',
  status       VARCHAR(16) NOT NULL COMMENT 'PENDING/DOING/DONE/DEAD',
  next_time    DATETIME(3) NOT NULL COMMENT '下次执行时间，退避',
  retry_count  INT         NOT NULL DEFAULT 0,
  lease_owner  VARCHAR(64) NULL COMMENT '当前持有实例标识',
  lease_expire DATETIME(3) NULL COMMENT '租约到期时间，过期即可被其他实例接管',
  payload      JSON        NOT NULL,
  version      INT         NOT NULL DEFAULT 0,
  create_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_type_op (biz_no, task_type, op_no) COMMENT '按操作单号去重，允许同业务多操作各自建任务',
  KEY idx_sched (status, next_time) COMMENT '主调度扫描',
  KEY idx_lease (status, lease_expire) COMMENT '僵尸任务回收扫描（与主扫描拆两条 SQL，避免 OR 触发 index merge）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动可靠任务表（本地消息表）';
