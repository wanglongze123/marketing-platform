-- 裂变组 = 某师傅在某活动中的一轮邀请实例。
--
-- 两道唯一键各挡一类，缺一不可：
--
--   uk_activity_sponsor_round (activity_id, sponsor_id, round_no)
--     挡「同一轮次号被建两次」。
--
--   uk_activity_sponsor_active (activity_id, sponsor_id, active_flag)
--     挡「同时存在两轮进行中」（BR-F-04），机制与 fission_relation.active_flag 相同。
--
-- 只有前者不足以保证 BR-F-04：轮次号由「读 MAX(round_no) 再 +1」算出，并发时线程 A 建
-- 轮 1 提交后，线程 B 读到 1 并算出轮 2 —— 两条记录的 round_no 不同，唯一键根本不冲突，
-- 于是同一师傅在同一活动下有了两轮进行中。技术方案 §3.3 把「并发进场不创建重复轮次」
-- 的兜底职责记在 uk_activity_sponsor_round 上，实测不成立（见《分阶段方案》§6.6）。
--
-- active_flag 做「部分唯一」，与 fission_relation 同构：RUNNING 时恒为 'ACTIVE'（受唯一
-- 键约束，至多一条），进终态时置为该行的 group_id（天然唯一，等于把这行移出约束范围）。
-- 三条终态路径（DONE/EXPIRED/CANCEL）必须全部释放，漏一条则该师傅永远开不了下一轮。
--
-- config_version + target_count 固化配置快照，使活动改配置后存量轮次仍按进场时的
-- 规则结算（BR-C-05、BR-C-02）。
CREATE TABLE fission_group (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  group_id       VARCHAR(64) NOT NULL COMMENT '裂变组号',
  activity_id    VARCHAR(64) NOT NULL,
  sponsor_id     VARCHAR(64) NOT NULL COMMENT '师傅平台用户ID',
  round_no       INT         NOT NULL DEFAULT 1 COMMENT '轮次',
  active_flag    VARCHAR(64) NOT NULL DEFAULT 'ACTIVE' COMMENT 'RUNNING 恒为 ACTIVE；进终态时置为 group_id 以释放唯一性',
  status         VARCHAR(16) NOT NULL COMMENT 'RUNNING/DONE/EXPIRED/CANCEL',
  progress       INT         NOT NULL DEFAULT 0 COMMENT '有效徒弟计数',
  target_count   INT         NOT NULL COMMENT '达标所需徒弟数，快照自配置',
  config_version INT         NOT NULL COMMENT '配置版本快照（BR-C-05）',
  expire_time    DATETIME(3) NOT NULL,
  version        INT         NOT NULL DEFAULT 0,
  create_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_group_id (group_id),
  UNIQUE KEY uk_activity_sponsor_round (activity_id, sponsor_id, round_no) COMMENT 'L3：同一轮次号不被建两次',
  UNIQUE KEY uk_activity_sponsor_active (activity_id, sponsor_id, active_flag) COMMENT 'L3：同师傅同活动至多一轮进行中（BR-F-02/F-04）',
  KEY idx_activity_sponsor (activity_id, sponsor_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变组=某师傅一轮邀请实例';
