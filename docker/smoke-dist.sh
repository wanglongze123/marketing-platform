#!/usr/bin/env bash
#
# 分布式形态的全链路冒烟：下单 → 支付 → 履约 → 查单。
#
# 与 injvm 形态跑的是同一条链路、同一份业务代码，差别只在每一跳都跨了进程：
#   HTTP → gateway →(tri) benefit-order →(tri) activity / reward →(tri) mock
#
# V4 退出标准第 1 条要的「两套 profile 全链路 e2e 均通过」，injvm 那侧由 34 个
# 集成测试覆盖，这侧由本脚本覆盖。
#
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
MOCK="${MOCK:-http://localhost:8090}"
MYSQL="docker exec -i mp-mysql mysql -N -ump_benefit -pmp_benefit db_benefit"
RID="${RID:-sm$(date +%s | tail -c 5)}"

grn() { printf '\033[32m✓ %s\033[0m\n' "$1"; }
inf() { printf '\033[36m▸ %s\033[0m\n' "$1"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$1"; exit 1; }

jq_() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

inf "复位故障注入"
curl -sf -X POST "$MOCK/api/fault/reset" >/dev/null || die "mock 不可达"

inf "① 预咨询（gateway → benefit-order → activity）"
token=$(curl -sf -X POST "$BASE/api/benefit/consult" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"U_$RID\",\"activityId\":\"ACT_DEMO_001\",\"skuId\":\"SKU_DEMO_001\"}" \
  | jq_ 'd["data"]["consultToken"]')
[ -n "$token" ] || die "未取到 consultToken"
grn "凭证已签发"

inf "② 下单"
biz=$(curl -sf -X POST "$BASE/api/benefit/trade" -H 'Content-Type: application/json' \
  -d "{\"userId\":\"U_$RID\",\"activityId\":\"ACT_DEMO_001\",\"skuId\":\"SKU_DEMO_001\",
       \"clientReqNo\":\"REQ_$RID\",\"quantity\":1,\"consultToken\":\"$token\"}" \
  | jq_ 'd["data"]["bizNo"]')
[ -n "$biz" ] || die "下单失败"
grn "订单 $biz"

trade=$($MYSQL -e "SELECT trade_no FROM play_biz_record WHERE play_biz_record_no='$biz'" | tr -d '\r')

inf "③ 取支付通知签名"
sign=$(curl -sf -X POST "$BASE/api/fault/pay-notify/sign" -H 'Content-Type: application/json' \
  -d "{\"outTradeNo\":\"$biz\",\"tradeNo\":\"$trade\",\"notifySeq\":\"NS_$RID\",
       \"payStatus\":\"SUCCESS\",\"payAmount\":9900,\"currency\":\"CNY\",\"merchantId\":\"MCH_LOCAL_DEMO\"}" \
  | jq_ 'd["data"]["sign"]')
[ -n "$sign" ] || die "签名端点无响应"
grn "签名已取得"

inf "④ 支付结果通知"
curl -sf -X POST "$BASE/api/benefit/pay-callback" -H 'Content-Type: application/json' \
  -d "{\"outTradeNo\":\"$biz\",\"tradeNo\":\"$trade\",\"notifySeq\":\"NS_$RID\",
       \"payStatus\":\"SUCCESS\",\"payAmount\":9900,\"currency\":\"CNY\",
       \"merchantId\":\"MCH_LOCAL_DEMO\",\"sign\":\"$sign\"}" >/dev/null
grn "已通知"

inf "⑤ 等待履约收敛（benefit-order 的调度器 → reward → mock 供应方）"
for _ in $(seq 1 60); do
  st=$($MYSQL -e "SELECT grant_status FROM play_biz_record WHERE play_biz_record_no='$biz'" | tr -d '\r')
  [ "$st" = "GRANT_SUCCESS" ] && break
  sleep 1
done
[ "$st" = "GRANT_SUCCESS" ] || die "60 秒未收敛，当前 grant_status=$st"
grn "grant_status = GRANT_SUCCESS"

n=$($MYSQL -e "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no='$biz'" | tr -d '\r')
[ "$n" = "2" ] || die "履约明细 $n 条，期望 2"
grn "履约明细 2 条（两个供应方各一条）"

inf "⑥ 查单"
pay=$(curl -sf "$BASE/api/benefit/order/$biz" | jq_ 'd["data"]["payStatus"]')
[ "$pay" = "PAY_SUCCESS" ] || die "查单 payStatus=$pay"
grn "查单 payStatus = PAY_SUCCESS"

echo
grn "分布式形态全链路通过：$biz"
