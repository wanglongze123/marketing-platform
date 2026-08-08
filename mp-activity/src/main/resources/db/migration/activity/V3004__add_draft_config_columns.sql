-- 主表补草稿态的玩法与奖励配置。
--
-- 版本表存的是「已发布的快照」，不可变；草稿期的配置需要一个可反复修改的存放处，否则
-- createActivity 收到的 playConfig / rewardConfig 无处落地，发布校验也就没有校验对象 ——
-- 只能拿别的字段冒充，那会让「奖励配置为空」这条校验永远判在错的东西上。
--
-- 发布时把这两列整体快照进 activity_config_version，此后履约与退款一律读快照（BR-C-05）。
ALTER TABLE marketing_activity
  ADD COLUMN play_config   JSON NULL COMMENT '草稿态玩法私有配置，发布时快照进版本表',
  ADD COLUMN reward_config JSON NULL COMMENT '草稿态奖励/价格/退款规则，发布时快照进版本表';
