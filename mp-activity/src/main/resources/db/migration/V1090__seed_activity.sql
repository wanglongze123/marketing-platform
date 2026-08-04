-- V1 演示数据：活动配置
-- V1 不提供活动管理接口，配置由本脚本初始化（《分阶段方案》§4.6）
--
-- cur_version = 1：V1 不建 activity_config_version 表，但主单要冻结 config_version，
-- 故此处给定 1，V3 建版本表时补一行 version=1 的快照即可对上，无需数据迁移。

INSERT INTO marketing_activity
  (activity_id, name, play_type, scene, status, start_time, end_time, cur_version, operator)
VALUES
  ('ACT_DEMO_001', '权益售卖演示活动', 'BENEFIT_SELL', 'BENEFIT_SELL_DEMO', 'ONLINE',
   '2025-01-01 00:00:00.000', '2030-12-31 23:59:59.999', 1, 'system');
