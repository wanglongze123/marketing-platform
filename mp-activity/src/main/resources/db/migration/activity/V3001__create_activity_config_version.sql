-- 活动配置版本快照：一经发布不可变，历史单据据此履约（BR-C-03、BR-C-05）
--
-- 无 update_time：带上它会暗示这行可以原地修改，与「不可变快照」的语义冲突
-- （《开发规范》§4.2 的例外条款）。改配置只能发布新版本，不得覆盖历史版本。
CREATE TABLE activity_config_version (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  activity_id   VARCHAR(64) NOT NULL,
  version       INT         NOT NULL COMMENT '发布递增',
  play_config   JSON        NOT NULL COMMENT '玩法私有配置快照',
  reward_config JSON        NOT NULL COMMENT '奖励/价格/有效期/退款规则快照',
  create_time   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_activity_version (activity_id, version) COMMENT 'L3：同活动同版本至多一条，并发发布不产生重复版本'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动配置版本快照，一经发布不可变';
