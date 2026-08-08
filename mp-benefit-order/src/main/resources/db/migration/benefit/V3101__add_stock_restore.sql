-- 退款回补库存：补 stock_status 的 RESTORED 取值与 STOCK_RESTORE 任务类型（PR-10 后置 review）。
--
-- 缺陷本身：技术方案 §3.4 的口径表要求「退款回补 consumed、但不返还限购额度」，而实现里
-- settleRefund 只推主单状态，既没有回补语句也不落任何库存任务 —— consumed 永远还不回去。
--
-- 它的表现不是超卖，而是**对账每轮报一次假差异**：第 6 项比对 consumed 与「已支付且未退款
-- 成功」的份数（sumConsumedQuantity 的谓词含 refund_status <> 'REFUND_SUCCESS'），退款后
-- 分母减少而 consumed 不动。§3.4 原话：「退款/关单后的口径必须定死，否则对账第 6 项在任何
-- 含退款的压测里都会报差异」。而假告警会让资损哨兵失效 —— 那正是 §6.8 要避免的。
--
-- 为什么新增 RESTORED 而不复用 RELEASED：两者归还的是不同的计数器。RELEASED 表示「预占
-- 已还」（locked 减，交易未成立），RESTORED 表示「已售已还」（consumed 减，成立后反悔）。
-- 合并成一个值，回补的条件更新就要同时接受 LOCKED 与 CONSUMED 两个前置态 —— 于是一笔关单
-- 释放过的单还能再被退款回补一次，而那次回补减掉的是别的订单的 consumed，可售余量凭空
-- 多出一份，直接超卖。这与 quota_status 当初不能复用 stock_status 是同一条理由。
--
-- 为什么回补是任务而不是在 settleRefund 事务里直接改库存：与 STOCK_CONSUME 移出支付回调
-- 事务同理（§7.4）—— stock_key 是单行热点，事务内更新会把持锁时间摊到退款链路上。
--
-- 额度不返还，故本次不新增 quota_status 的取值：买了就算用掉，否则「买了再退」能刷回额度，
-- 限购形同虚设。库存与额度在退款场景下的不对称是有意设计（§3.4）。
--
-- 本迁移只改列注释，不改数据：RESTORED 是新增取值，存量行不可能持有它。
ALTER TABLE play_biz_record
  MODIFY COLUMN stock_status VARCHAR(16) NOT NULL DEFAULT 'LOCKED'
  COMMENT '本单库存处置态 NONE/LOCKED/CONSUMED/RELEASED/RESTORED，每单幂等的承重点；RELEASED 还 locked（未成立），RESTORED 还 consumed（退款）';

ALTER TABLE benefit_task
  MODIFY COLUMN task_type VARCHAR(32) NOT NULL
  COMMENT 'GRANT/QUERY_GRANT/REFUND/QUERY_REFUND/REVOKE/CLOSE_ORDER/QUERY_CLOSE/STOCK_CONSUME/STOCK_RELEASE/STOCK_RESTORE/QUOTA_RELEASE';
