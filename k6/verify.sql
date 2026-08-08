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
--    不能简单 COUNT(*) —— 已关闭/支付失败的单不占库存，算进去会虚高。
--
--    判据取 stock_status 而非 pay_status。两者不同步：CLOSING 表示关单结果未定，
--    此阶段刻意不释放库存（结果未定就释放等于把额度让给别人，而钱可能已经收了），
--    故该状态的单仍占着 locked。按 pay_status IN ('WAIT_PAY','PAY_SUCCESS') 统计会
--    漏掉它们，使本该相等的两个数对不上 —— 表现为压测误报超卖，而实际没有。
--
--    stock_status 就是「本单是否占着库存」的权威记录，直接问它。
SELECT
  COUNT(*) AS sold_orders,
  IF(COUNT(*) = (SELECT locked + consumed FROM marketing_stock
                  WHERE stock_key = 'sku:SKU_DEMO_001'),
     'OK', '✗ 订单数与库存占用不一致') AS check_orders_match_stock
FROM play_biz_record
WHERE activity_id = 'ACT_DEMO_001'
  AND sku_id = 'SKU_DEMO_001'
  AND client_req_no LIKE CONCAT('REQ_', @run_id, '%')
  AND stock_status IN ('LOCKED', 'CONSUMED');

-- ③ 幂等：同一业务幂等键不得建出两笔单。
--
--    分组维度必须与 uk_idempotent 完全一致，即四元组 (user_id, activity_id, sku_id,
--    client_req_no)。只按 client_req_no 分组是在验一个比唯一索引更强的约束 —— 该列上
--    并无唯一索引，不同用户用相同 client_req_no 本就合法（contention.js 让多个 VU 共用
--    clientReqNo 时也共用 userId，故当前恰好不触发，但这是脚本的巧合而非约束）。
--
--    维度写窄的后果是压测误报幂等失效；写宽则漏检。以唯一索引为准。
SELECT
  COUNT(*) AS duplicated_idempotent_key,
  IF(COUNT(*) = 0, 'OK', '✗ 同一幂等键建出多笔单') AS check_idempotent
FROM (
  SELECT user_id, activity_id, sku_id, client_req_no
  FROM play_biz_record
  WHERE client_req_no LIKE CONCAT('REQ_', @run_id, '%')
  GROUP BY user_id, activity_id, sku_id, client_req_no
  HAVING COUNT(*) > 1
) dup;

-- ④ 操作记录数应等于成功建单数 —— 多出来意味着有单据没能回滚干净
SELECT
  COUNT(*) AS create_op_records
FROM play_op_record
WHERE op_type = 'CREATE_TRADE'
  AND idempotent_key LIKE CONCAT('%REQ_', @run_id, '%');

-- ⑤ L3 负载侧指标（退出标准第 15 条）不在数据库里，走 HTTP 端点取：
--      curl -s localhost:8080/api/fault/contention
--    压测前先 DELETE 同一路径清零，两组的数字才可比。
--    锁的价值只能从这三个数读出：开锁组应明显更低，而正确性结果两组完全一致。
