-- 裂变关系（师徒关系单据）。膨胀表，需过期治理。
--
-- 唯一键为何不含 out_biz_no：NFR-C-05 的不变量是「同组同师徒进行中关系 ≤ 1」。
-- out_biz_no 由上游传入，若编入唯一键，上游用两个不同业务号并发调用即可插出两条
-- JOINED 关系，同一徒弟触发两次双向发奖；且分享建 INVITED 时徒弟尚未加入、根本没有
-- out_biz_no。故降为普通列（默认空串），在 INVITED → CONNECTED/JOINED 时条件回填。
--
-- active_flag 做「部分唯一」：MySQL 无 partial index，而业务允许同一对师徒在上一轮
-- 终结后重新建立关系。约定非终态时 active_flag='ACTIVE'（受唯一键约束，至多一条），
-- 进入 DONE/EXPIRED/CANCEL 时置为该行的 relation_id（天然唯一，等于把这行移出唯一性
-- 约束）。三条终态路径必须全部释放，漏一条即静默失败 —— 该行仍占着
-- (group_id, follower_id, 'ACTIVE')，下一轮分享插入时唯一键冲突，而「先插后判」模式
-- 会把冲突当幂等命中，静默返回那条已过期的关系。
CREATE TABLE fission_relation (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  relation_id     VARCHAR(64) NOT NULL,
  group_id        VARCHAR(64) NOT NULL,
  activity_id     VARCHAR(64) NOT NULL,
  sponsor_id      VARCHAR(64) NOT NULL,
  follower_id     VARCHAR(64) NOT NULL COMMENT '徒弟平台用户ID',
  out_biz_no      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '上游业务号；INVITED 阶段尚无值，建联/加入时回填',
  active_flag     VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' COMMENT '非终态恒为 ACTIVE；进终态时置为 relation_id 以释放唯一性',
  status          VARCHAR(16) NOT NULL COMMENT 'INVITED/CONNECTED/JOINED/DONE/EXPIRED/CANCEL',
  granting_until  DATETIME(3) NULL COMMENT '发奖在途豁免截止时间：NULL=不在途；非空且未过期=过期治理须跳过（BR-F-26）；过期即允许接管并告警，防止行永生',
  reward_snapshot JSON        NULL COMMENT '徒弟/师傅奖励配置快照（BR-C-05）',
  share_method    VARCHAR(32) NULL COMMENT 'IM/QRCODE/PASSWORD/EXTERNAL',
  op_no           VARCHAR(64) NULL COMMENT '关联的完成操作单号',
  expire_time     DATETIME(3) NOT NULL COMMENT '关系有效期',
  version         INT         NOT NULL DEFAULT 0,
  create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_group_follower_active (group_id, follower_id, active_flag) COMMENT 'L3：同组同师徒进行中关系≤1（NFR-C-05）',
  KEY idx_group_follower_status (group_id, follower_id, status) COMMENT '好友过滤下推：follower_id IN(...) 转 index seek（§7.1）',
  KEY idx_group_status_id (group_id, status, id) COMMENT '关系列表游标分页，避免 filesort（BR-F-22）',
  KEY idx_expire (status, expire_time, id) COMMENT '过期治理分片扫描（FR-F09）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变关系，膨胀表，需过期治理';
