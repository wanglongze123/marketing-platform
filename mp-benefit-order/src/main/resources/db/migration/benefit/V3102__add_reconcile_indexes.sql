-- 对账扫描的索引补齐（PR-10 后置 review 的 P2 项）。
--
-- §6.8 的收尾一句写着「对账扫描走 §3.4 的 idx_pay_grant 等索引，不全表扫」，而第 5、9、14 项
-- 的谓词是 pay_status + update_time，idx_pay_grant 的第二列是 grant_status —— 只有最左的
-- pay_status 用得上，update_time 落到回表后逐行过滤。
--
-- 这类缺陷不会被任何功能用例判红：几十行的测试表怎么扫都是毫秒级。它只在数据量长上来后
-- 表现为对账一轮跑不完 —— 而对账跑不完的后果不是「慢」，是**资损哨兵失灵**：第 5/6/11 项的
-- 告警指标由对账产出，一轮扫不完那几个指标就停止更新，监控上是一条平线，与「一切正常」同形。
--
-- 只加一个索引，不是三个。三条慢查询里：
--
--   第 5 项（金额一致性）  ─┐
--   第 9 项（关闭仍占库存）─┼ 谓词都是 pay_status + update_time，本索引一并覆盖
--   第 14 项（关单未收敛） ─┘
--
--   第 15 项（限购额度比对）谓词是 user_id + activity_id + sku_id + quota_status，
--   **已有 uk_idempotent(user_id, activity_id, sku_id, client_req_no) 覆盖前三列**，
--   EXPLAIN 实测 type=REF、key=uk_idempotent。故不新建索引 —— 唯一键的最左前缀本就是
--   一个可用的普通索引，再建一个 (user_id, activity_id, sku_id) 完全重复，白占写入开销。
--   本条原计划新建 idx_user_activity_sku，是 EXPLAIN 探针推翻了它。
--
-- 第 5 项同时补了 update_time 下界（见 ReconcileMapper）：它此前是十五项里唯一没有下界的
-- 扫描。下界不只是省开销，它是判据的一部分 —— pay_amount 由支付通知回填，一笔刚走完关单
-- 收敛的单在通知到达前 pay_amount 与 order_amount 本就可能不等，没有下界时这个正常的中间态
-- 每轮都会被报成金额差异。假告警会让资损哨兵失效，与本节其余条目同理。
--
-- 为什么不把 quota_status / stock_status 也塞进索引：两者各只有三四个取值，选择性极低。
-- 前缀列定位到的行本就极少，多一列宽度换不回什么。

-- 覆盖第 5、9、14 项：三者谓词都是 pay_status + update_time。
-- 与 idx_pay_grant 的差别在第二列 —— 那个是 grant_status，这三条一个都用不上，
-- 于是 update_time 只能回表后逐行比。有了本索引，EXPLAIN 从 REF 变为 RANGE
ALTER TABLE play_biz_record
  ADD KEY idx_pay_update (pay_status, update_time)
  COMMENT '对账扫描：按支付态 + 长期未更新捞单（§6.8 第 5、9、14 项）';
