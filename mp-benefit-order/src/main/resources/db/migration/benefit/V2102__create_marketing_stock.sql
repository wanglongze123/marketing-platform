-- 营销库存与用户限购额度（技术方案 §3.4、§7.4）
--
-- 两张表都不设 version 乐观锁：余量条件 total-locked-consumed>=? 与下界 locked>=? 本身
-- 就是更强的约束，且避免 CAS 失败重试放大热点。这也是 §3.1「以条件更新为主」的由来。

CREATE TABLE marketing_stock (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  stock_key    VARCHAR(96) NOT NULL COMMENT '维度键：activity/sku/city，热点时可加 bucket 后缀分桶',
  total        BIGINT      NOT NULL,
  locked       BIGINT      NOT NULL DEFAULT 0 COMMENT '预占',
  consumed     BIGINT      NOT NULL DEFAULT 0 COMMENT '消耗',
  create_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_key (stock_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营销库存';

-- 限购数量直接挂在 SKU 上。
--
-- benefit_sku 已有 purchase_limit_rule_id，但 V2 不建限购规则表 —— 规则表要承载周期、
-- 人群、叠加策略，那是 V3 运营配置化的范围。此处只补一个数量列，让 V2 的限购有确定的
-- 取值来源；V3 建规则表后，本列改由规则快照填充，扣减逻辑不变。
--
-- 0 表示不限购：NULL 会让「没配规则」和「配了 0」无法区分，而后者语义上是「一件都不能买」。
ALTER TABLE benefit_sku
  ADD COLUMN purchase_limit_qty INT NOT NULL DEFAULT 0
  COMMENT '每人限购数量，0=不限购。V3 改由限购规则表快照填充' AFTER purchase_limit_rule_id;

CREATE TABLE user_purchase_quota (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  user_id      VARCHAR(64) NOT NULL,
  activity_id  VARCHAR(64) NOT NULL,
  sku_id       VARCHAR(64) NOT NULL,
  period_key   VARCHAR(32) NOT NULL COMMENT '限购周期：TOTAL / D20260802 / W202631',
  used_qty     INT         NOT NULL DEFAULT 0,
  limit_qty    INT         NOT NULL COMMENT '快照自限购规则',
  create_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_quota (user_id, activity_id, sku_id, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户购买额度，支撑 FR-B02 限购';
