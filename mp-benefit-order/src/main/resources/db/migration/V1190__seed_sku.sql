-- V1 演示数据：SKU、权益包、权益项
-- V1 不提供商品管理接口，配置由本脚本初始化（《分阶段方案》§4.6）

INSERT INTO benefit_package
  (benefit_package_id, package_version, package_name, grant_policy)
VALUES
  ('PKG_DEMO_001', 1, '演示权益包', 'COMBINE');

-- 两个权益项刻意分属不同 provider_type：
-- grantBenefit 按 provider_type 分组、每组一个 grantOpNo，只有存在两组时
-- 分组逻辑才会被真实走到。若两项同属一个供应方，分组代码写错 V1 也测不出来。
INSERT INTO benefit_item
  (benefit_item_id, benefit_package_id, package_version, benefit_type,
   provider_type, provider_product_id, is_core, grant_order, rollback_supported)
VALUES
  ('ITEM_DEMO_A', 'PKG_DEMO_001', 1, 'MONTH_CARD', 'PROVIDER_A', 'PROD_A_001', 1, 1, 1),
  ('ITEM_DEMO_B', 'PKG_DEMO_001', 1, 'COUPON',     'PROVIDER_B', 'PROD_B_001', 0, 2, 1);

INSERT INTO benefit_sku
  (sku_id, activity_id, sku_name, sku_type, sale_status,
   list_price, sale_price, benefit_package_id, package_version)
VALUES
  ('SKU_DEMO_001', 'ACT_DEMO_001', '演示权益包·月卡+券', 'SINGLE_PACK', 'ON_SALE',
   19900, 9900, 'PKG_DEMO_001', 1);
