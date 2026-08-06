-- 压测后校验（《分阶段方案》§5.7 退出标准 14）。
--
-- k6 的 check 只能证明「接口没报错」，证明不了「没超卖」—— 超卖的失效形态恰恰是
-- 全部成功。判据必须来自数据库。
--
-- 用法：
--   docker exec -i mp-mysql mysql -ump_benefit -pmp_benefit db_benefit \
--     -e "SET @run_id='k6run';" < k6/verify.sql
-- 或直接把 @run_id 换成本轮的取值。

-- ① 库存终值：三个数必须自洽，且可售余量不为负
SELECT
  stock_key,
  total,
  locked,
  consumed,
  total - locked - consumed                    AS available,
  IF(total - locked - consumed >= 0, 'OK', '✗ 负库存') AS check_no_negative,
  IF(locked + consumed <= total, 'OK', '✗ 超卖')       AS check_no_oversell
FROM marketing_stock
WHERE stock_key = 'sku:SKU_DEMO_001';

-- ② 售出数：口径精确到活动 + SKU，且只算「占着库存」的单。
--    不能简单 COUNT(*) —— 已关闭/支付失败的单不占库存，算进去会虚高
SELECT
  COUNT(*) AS sold_orders,
  IF(COUNT(*) = (SELECT locked + consumed FROM marketing_stock
                  WHERE stock_key = 'sku:SKU_DEMO_001'),
     'OK', '✗ 订单数与库存占用不一致') AS check_orders_match_stock
FROM play_biz_record
WHERE activity_id = 'ACT_DEMO_001'
  AND sku_id = 'SKU_DEMO_001'
  AND client_req_no LIKE CONCAT('REQ_', @run_id, '_%')
  AND pay_status IN ('WAIT_PAY', 'PAY_SUCCESS');

-- ③ 幂等：同一 client_req_no 不得建出两笔单
SELECT
  COUNT(*) AS duplicated_req_no,
  IF(COUNT(*) = 0, 'OK', '✗ 同一幂等键建出多笔单') AS check_idempotent
FROM (
  SELECT client_req_no
  FROM play_biz_record
  WHERE client_req_no LIKE CONCAT('REQ_', @run_id, '_%')
  GROUP BY client_req_no
  HAVING COUNT(*) > 1
) dup;

-- ④ 操作记录数应等于成功建单数 —— 多出来意味着有单据没能回滚干净
SELECT
  COUNT(*) AS create_op_records
FROM play_op_record
WHERE op_type = 'CREATE_TRADE'
  AND idempotent_key LIKE CONCAT('%_REQ_', @run_id, '_%');

-- ⑤ L3 负载侧指标（退出标准第 15 条）不在数据库里，走 HTTP 端点取：
--      curl -s localhost:8080/api/fault/contention
--    压测前先 DELETE 同一路径清零，两组的数字才可比。
--    锁的价值只能从这三个数读出：开锁组应明显更低，而正确性结果两组完全一致。
