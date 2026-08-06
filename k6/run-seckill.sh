#!/usr/bin/env bash
#
# 秒杀压测一轮：重置库存 → 清零计数 → 跑 k6 → 查库校验 → 读冲突指标。
#
# 用法：
#   k6/run-seckill.sh [RUN_ID] [STOCK]
#
# 两组对照（退出标准第 15 条）分别跑：
#   开锁组：应用以默认配置启动（mp.lock.enabled=true）
#   去锁组：应用以 -Dmp.lock.enabled=false 启动
# 两轮用不同的 RUN_ID，数据互不干扰。
set -euo pipefail

RUN_ID="${1:-k6run}"
STOCK="${2:-100}"
BASE="${BASE:-http://localhost:8080}"
MYSQL="docker exec -i mp-mysql mysql -N -ump_benefit -pmp_benefit db_benefit"

cd "$(dirname "$0")/.."

echo "=== 本轮 RUN_ID=${RUN_ID}，库存置为 ${STOCK} ==="
$MYSQL -e "UPDATE marketing_stock SET total=$STOCK, locked=0, consumed=0
           WHERE stock_key='sku:SKU_DEMO_001';" 2>/dev/null

# 清掉上一轮的单据，否则「售出数」会把历史数据算进去
$MYSQL -e "DELETE FROM play_op_record WHERE play_biz_record_no IN
             (SELECT play_biz_record_no FROM play_biz_record
               WHERE client_req_no LIKE 'REQ_${RUN_ID}_%');
           DELETE FROM benefit_task WHERE biz_no IN
             (SELECT play_biz_record_no FROM play_biz_record
               WHERE client_req_no LIKE 'REQ_${RUN_ID}_%');
           DELETE FROM play_biz_record WHERE client_req_no LIKE 'REQ_${RUN_ID}_%';
           DELETE FROM user_purchase_quota WHERE user_id LIKE 'U_${RUN_ID}_%';" 2>/dev/null

echo "=== 清零冲突计数（两组数字才可比）==="
curl -s -X DELETE "$BASE/api/fault/contention" > /dev/null

echo "=== 锁开关状态 ==="
curl -s "$BASE/api/fault/contention" | python3 -m json.tool 2>/dev/null || true

echo
echo "=== 跑 k6 ==="
RUN_ID="$RUN_ID" BASE="$BASE" k6 run k6/seckill.js

echo
echo "=== 查库校验（真正的判据）==="
# k6 的 check 只能证明「接口没报错」，证明不了「没超卖」—— 超卖的失效形态
# 恰恰是全部成功。判据必须来自数据库
docker exec -i mp-mysql mysql -ump_benefit -pmp_benefit db_benefit <<SQL 2>/dev/null
SET @run_id='$RUN_ID';
SELECT stock_key, total, locked, consumed, total-locked-consumed AS available,
       IF(total-locked-consumed >= 0, 'OK', 'x NEGATIVE') AS check_no_negative,
       IF(locked+consumed <= total, 'OK', 'x OVERSELL')   AS check_no_oversell
  FROM marketing_stock WHERE stock_key='sku:SKU_DEMO_001';
SELECT COUNT(*) AS sold_orders,
       IF(COUNT(*) = (SELECT locked+consumed FROM marketing_stock
                       WHERE stock_key='sku:SKU_DEMO_001'),
          'OK', 'x MISMATCH') AS check_orders_match_stock
  FROM play_biz_record
 WHERE activity_id='ACT_DEMO_001' AND sku_id='SKU_DEMO_001'
   AND client_req_no LIKE CONCAT('REQ_', @run_id, '_%')
   AND pay_status IN ('WAIT_PAY','PAY_SUCCESS');
SELECT COUNT(*) AS duplicated_req_no,
       IF(COUNT(*) = 0, 'OK', 'x DUP') AS check_idempotent
  FROM (SELECT client_req_no FROM play_biz_record
         WHERE client_req_no LIKE CONCAT('REQ_', @run_id, '_%')
         GROUP BY client_req_no HAVING COUNT(*) > 1) dup;
SQL

echo
echo "=== L3 冲突指标（退出标准第 15 条的对照数据）==="
curl -s "$BASE/api/fault/contention" | python3 -m json.tool
