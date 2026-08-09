-- V3 演示数据：裂变活动
--
-- 补这一行的原因：裂变轮次只能开在 play_type = 'FISSION' 且可用的活动上（FissionServiceImpl
-- 的 createRound 校验），而此前裂变的全部用例都借用 ACT_DEMO_001 —— 那是 V1090 建的
-- play_type = 'BENEFIT_SELL' 的权益售卖活动。
--
-- 借用能跑通，只是因为当时 createRound 只判「活动存在」。校验补上后，用例若继续借用
-- 权益活动就会红 —— 而它们本就不该绿：一个把裂变组开在权益售卖活动上的实现，测试却在
-- 为它背书。这正是「测试固化了错误行为」。
--
-- scene 取 FISSION_DEMO：与 FissionSponsorEntryIT 的请求场景一致。场景路由唯一是发布校验
-- 的六项之一（BR-C-04），不与 BENEFIT_SELL_DEMO 重名。
--
-- 时间窗与 ACT_DEMO_001 一致（2025-01-01 ~ 2030-12-31）：可用性由库判定 start/end 与 status，
-- 取同一段窗口使两个活动在测试期内同为可用，避免用例因日期漂移而变红。
INSERT INTO marketing_activity
  (activity_id, name, play_type, scene, status, start_time, end_time, cur_version, operator)
VALUES
  ('ACT_FISSION_001', '裂变演示活动', 'FISSION', 'FISSION_DEMO', 'ONLINE',
   '2025-01-01 00:00:00.000', '2030-12-31 23:59:59.999', 1, 'system');

-- cur_version = 1 必须有对应的快照行，理由同 V3090：否则裂变组冻结的 config_version
-- 指向一个不存在的版本，而 PR-4 的双向发奖要按它读裂变奖励配置。
--
-- 内容为空对象：V3 PR-2/PR-3 的裂变奖励配置尚未落在活动侧（DEFAULT_TARGET_COUNT 仍是
-- FissionServiceImpl 里的常量），快照如实反映当前的配置形态，不编造内容。
INSERT INTO activity_config_version (activity_id, version, play_config, reward_config)
VALUES ('ACT_FISSION_001', 1, '{}', '{}');
