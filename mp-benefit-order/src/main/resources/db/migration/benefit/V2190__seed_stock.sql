-- 演示库存与限购规则
--
-- 库存 100：对齐 AC-03「500 并发抢 100 库存成功订单恰好 100」，压测脚本不必另建数据。
--
-- 限购额度不在此处 seed：user_purchase_quota 是按 (user_id, activity_id, sku_id,
-- period_key) 一用户一行的运行时数据，预先枚举不出来。首次下单时按 SKU 的限购规则
-- upsert 建行，见 UserPurchaseQuotaMapper.tryConsume。

INSERT INTO marketing_stock (stock_key, total, locked, consumed)
VALUES ('sku:SKU_DEMO_001', 100, 0, 0);

-- 演示 SKU 每人限购 2 件：取 2 而非 1，限购用例才能区分「第二件放行」与「第三件拒绝」。
-- 取 1 的话「扣减后即达上限」与「扣减逻辑写错、一件都买不了」两种情况表现相同
UPDATE benefit_sku SET purchase_limit_qty = 2 WHERE sku_id = 'SKU_DEMO_001';
