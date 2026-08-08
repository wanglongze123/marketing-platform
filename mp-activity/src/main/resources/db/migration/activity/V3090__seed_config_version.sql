-- V1 的 seed 活动 cur_version = 1，此处补上对应的版本快照行。
--
-- 不补则 V1/V2 建的存量单据 config_version = 1 指向一个不存在的版本 —— 「历史单据认版本快照」
-- 这条约束在 V3 建表后反而不成立了。V1090 的注释已预告本行（「V3 建版本表时补一行
-- version=1 的快照即可对上，无需数据迁移」）。
--
-- 内容为空对象：V1/V2 的权益配置实际落在 benefit_sku / benefit_package，活动侧当时不承载
-- 玩法与奖励配置。填空对象而非编造内容 —— 快照要如实反映当时的配置形态。
INSERT INTO activity_config_version (activity_id, version, play_config, reward_config)
VALUES ('ACT_DEMO_001', 1, '{}', '{}');
