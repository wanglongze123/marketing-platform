-- 分库账号：每个账号只被授权自身 schema。
--
-- 四个 schema 位于同一 MySQL 实例时，多数据源只能拦截不带库名限定的表引用；
-- 带库名限定的 JOIN（db_reward.x JOIN db_benefit.y）在同一连接上照常执行。
-- 权限隔离让《开发规范》§4.5「禁止跨库 JOIN」在运行期成为约束而非约定，
-- 且 V3 拆实例后连接形态不变 —— 届时只改 host，不改权限模型。

CREATE USER IF NOT EXISTS 'mp_activity'@'%' IDENTIFIED BY 'mp_activity';
GRANT ALL PRIVILEGES ON db_activity.* TO 'mp_activity'@'%';

CREATE USER IF NOT EXISTS 'mp_benefit'@'%' IDENTIFIED BY 'mp_benefit';
GRANT ALL PRIVILEGES ON db_benefit.* TO 'mp_benefit'@'%';

CREATE USER IF NOT EXISTS 'mp_reward'@'%' IDENTIFIED BY 'mp_reward';
GRANT ALL PRIVILEGES ON db_reward.* TO 'mp_reward'@'%';

CREATE USER IF NOT EXISTS 'mp_fission'@'%' IDENTIFIED BY 'mp_fission';
GRANT ALL PRIVILEGES ON db_fission.* TO 'mp_fission'@'%';

FLUSH PRIVILEGES;
