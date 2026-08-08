-- 裂变可靠任务表（本地消息表）。
--
-- biz_no 对 SPONSOR_REWARD 取 relation_id 而非 group_id：一个组下有 N 条关系、每条
-- 关系各自触发一次师傅返奖，取 group_id 则唯一键让一师傅仅一条任务 → 漏发。
--
-- op_no 为何 NOT NULL DEFAULT ''：MySQL 唯一索引不对 NULL 去重，若允许为空，无下游
-- 单号的任务（如 RELATION_EXPIRE）可无限重复插入，唯一键不起作用。无下游单的任务用
-- 确定性本地键填充（如 biz_no + '_EXPIRE'）。
--
-- 唯一键为何加第三维：原 uk_biz_type (biz_no, task_type) 过紧 —— 组合发放时多个权益项
-- 各自 UNKNOWN，需要多条 QUERY_GRANT 任务，会被唯一键拒绝；且任务进 DONE 后仍占用
-- 唯一键，导致对账「补建任务」被静默吞掉。
CREATE TABLE fission_task (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  task_no      VARCHAR(64) NOT NULL,
  biz_no       VARCHAR(64) NOT NULL COMMENT '关联业务号：SPONSOR_REWARD 取 relation_id（非 group_id），否则一师傅仅一条任务→漏发',
  task_type    VARCHAR(32) NOT NULL COMMENT 'SPONSOR_REWARD/RELATION_EXPIRE/QUERY_GRANT',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变可靠任务表（本地消息表）';
