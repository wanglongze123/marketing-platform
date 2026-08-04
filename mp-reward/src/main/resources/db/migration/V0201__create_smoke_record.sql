-- V0 · 发奖库 · 冒烟表
-- 用途：验证 Flyway + MyBatis-Plus + 事务链路可用
-- V1 结束时由 V1203__drop_smoke_record.sql 删除，它是脚手架不是功能
CREATE TABLE smoke_record (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  biz_no      VARCHAR(64) NOT NULL COMMENT '业务号',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_biz_no (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V0 冒烟表，V1 结束时删除';
