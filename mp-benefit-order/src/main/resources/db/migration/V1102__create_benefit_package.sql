CREATE TABLE benefit_package (
  id                 BIGINT      NOT NULL AUTO_INCREMENT,
  benefit_package_id VARCHAR(64) NOT NULL,
  package_version    INT         NOT NULL,
  package_name       VARCHAR(128) NOT NULL,
  grant_policy       VARCHAR(16) NOT NULL COMMENT 'ATOMIC 原子 / COMBINE 组合',
  create_time        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_package_version (benefit_package_id, package_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权益包';

CREATE TABLE benefit_item (
  id                 BIGINT      NOT NULL AUTO_INCREMENT,
  benefit_item_id    VARCHAR(64) NOT NULL,
  benefit_package_id VARCHAR(64) NOT NULL,
  package_version    INT         NOT NULL,
  benefit_type       VARCHAR(32) NOT NULL COMMENT 'DISCOUNT/COUPON/MONTH_CARD',
  provider_type      VARCHAR(32) NOT NULL COMMENT '供应方类型',
  provider_product_id VARCHAR(64) NOT NULL,
  is_core            TINYINT     NOT NULL DEFAULT 0 COMMENT '是否核心权益',
  grant_order        INT         NOT NULL DEFAULT 0,
  rollback_supported TINYINT     NOT NULL DEFAULT 0 COMMENT '是否支持回收',
  create_time        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_item (benefit_item_id, package_version),
  KEY idx_package (benefit_package_id, package_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权益项';
