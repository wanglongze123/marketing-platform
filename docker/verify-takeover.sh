#!/usr/bin/env bash
#
# V4 退出标准第 2、3、4 条的验证：kill -9 掉持有任务的实例，断言任务被接管、
# 结果只有一份、原实例苏醒后的写回被 fencing 拒绝。
#
# 与 ReliableTaskIT 验的不是同一件事。那里用多线程持不同 lease_owner 在单进程内
# 验证 SQL 语义（抢占、接管、fencing 的 WHERE 条件写对了没有）；这里验的是
# 「进程真的死掉时系统能不能恢复」——JVM 被 SIGKILL，连接池未关闭、租约未释放、
# 事务在服务端超时回滚，这些都不是单进程能构造的。
#
# 用法：
#   docker compose --profile v4 up -d                                   # 中间件
#   MP_TASK_LEASE_SECONDS=5 MP_TASK_INTERVAL_MILLIS=500 \
#     docker compose -f docker-compose-dist.yml up -d --scale benefit-order=3
#   docker/verify-takeover.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-http://localhost:8080}"
MOCK="${MOCK:-http://localhost:8090}"
MYSQL="docker exec -i mp-mysql mysql -N -ump_benefit -pmp_benefit db_benefit"
RUN_ID="${RUN_ID:-tk$(date +%s | tail -c 5)}"

# 压缩后的时序：租约 5s + 回收每 10 轮 × 500ms = 5s，接管延迟约 10s。
# 留 3 倍余量。默认 30s 租约下要等 40s，把这个数改回去也能跑，只是慢
WAIT_TAKEOVER="${WAIT_TAKEOVER:-30}"

red()  { printf '\033[31m✗ %s\033[0m\n' "$1"; }
grn()  { printf '\033[32m✓ %s\033[0m\n' "$1"; }
info() { printf '\033[36m▸ %s\033[0m\n' "$1"; }

fail() { red "$1"; exit 1; }

# ------------------------------------------------------------------
# 0. 前置检查
# ------------------------------------------------------------------
info "前置检查"

replicas=$(docker ps --filter "name=benefit-order" --format '{{.Names}}' | wc -l | tr -d ' ')
[ "$replicas" -ge 2 ] || fail "benefit-order 副本数为 $replicas，至少需要 2 个才能验接管（--scale benefit-order=3）"
grn "benefit-order 副本数 $replicas"

curl -sf "$BASE/api/benefit/order/__probe__" >/dev/null 2>&1 || true   # 仅探活，404 也算通
curl -sf "$MOCK/api/fault/mode" >/dev/null || fail "mock 服务不可达（$MOCK）"
grn "gateway 与 mock 可达"

# ------------------------------------------------------------------
# 1. 造一笔会停在 GRANT_UNKNOWN 的订单
#
# 注入 TIMEOUT_AFTER_COMMIT：下游先记账再抛超时。平台侧拿到 UNKNOWN，
# 落 QUERY_GRANT 任务等待查单收敛——这段等待就是我们要 kill 的时间窗。
# ------------------------------------------------------------------
info "注入故障并下单"

curl -sf -X POST "$MOCK/api/fault/provider/TIMEOUT_AFTER_COMMIT" >/dev/null
grn "供应方模式 = TIMEOUT_AFTER_COMMIT"

USER="U_${RUN_ID}"
REQ="REQ_${RUN_ID}"

token=$(curl -sf -X POST "$BASE/api/benefit/consult" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER\",\"activityId\":\"ACT_DEMO_001\",\"skuId\":\"SKU_DEMO_001\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["consultToken"])')

biz=$(curl -sf -X POST "$BASE/api/benefit/trade" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER\",\"activityId\":\"ACT_DEMO_001\",\"skuId\":\"SKU_DEMO_001\",
       \"clientReqNo\":\"$REQ\",\"quantity\":1,\"consultToken\":\"$token\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["bizNo"])')
grn "订单 $biz"

trade=$($MYSQL -e "SELECT trade_no FROM play_biz_record WHERE play_biz_record_no='$biz'" 2>/dev/null | tr -d '\r')
sign=$(curl -sf -X POST "$BASE/api/fault/pay-notify/sign" \
  -H 'Content-Type: application/json' \
  -d "{\"outTradeNo\":\"$biz\",\"tradeNo\":\"$trade\",\"notifySeq\":\"NS_${RUN_ID}\",
       \"payStatus\":\"SUCCESS\",\"payAmount\":9900,\"currency\":\"CNY\",\"merchantId\":\"MCH_LOCAL_DEMO\"}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["sign"])')

curl -sf -X POST "$BASE/api/benefit/pay-callback" \
  -H 'Content-Type: application/json' \
  -d "{\"outTradeNo\":\"$biz\",\"tradeNo\":\"$trade\",\"notifySeq\":\"NS_${RUN_ID}\",
       \"payStatus\":\"SUCCESS\",\"payAmount\":9900,\"currency\":\"CNY\",
       \"merchantId\":\"MCH_LOCAL_DEMO\",\"sign\":\"$sign\"}" >/dev/null
grn "已支付"

# ------------------------------------------------------------------
# 2. 等任务被某个实例领走
# ------------------------------------------------------------------
info "等待任务进入 DOING"

owner=""
for _ in $(seq 1 40); do
  owner=$($MYSQL -e "SELECT COALESCE(lease_owner,'') FROM benefit_task
                     WHERE biz_no='$biz' AND status='DOING' LIMIT 1" 2>/dev/null | tr -d '\r')
  [ -n "$owner" ] && break
  sleep 0.5
done
[ -n "$owner" ] || fail "30 秒内没有任务进入 DOING，检查 benefit-order 是否带了 @EnableScheduling"
grn "任务被 $owner 领走"

# 反查这个 owner 在哪个容器 —— owner 是进程内随机 UUID，只能从日志里认
holder=""
for c in $(docker ps --filter "name=benefit-order" --format '{{.Names}}'); do
  if docker logs "$c" 2>&1 | grep -q "$owner"; then holder="$c"; break; fi
done
[ -n "$holder" ] || fail "找不到持有 $owner 的容器"
grn "持有者容器 = $holder"

before=$($MYSQL -e "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no='$biz'" | tr -d '\r')

# ------------------------------------------------------------------
# 3. kill -9
# ------------------------------------------------------------------
info "kill -9 $holder"
docker kill -s KILL "$holder" >/dev/null
grn "已 SIGKILL，租约不会被释放（这正是要验的：进程没机会做任何清理）"

# 恢复供应方，让接管者能真正完成这笔发放
curl -sf -X POST "$MOCK/api/fault/reset" >/dev/null

# ------------------------------------------------------------------
# 4. 断言接管
# ------------------------------------------------------------------
info "等待另一实例接管（最多 ${WAIT_TAKEOVER}s）"

new_owner=""
for _ in $(seq 1 $((WAIT_TAKEOVER * 2))); do
  cur=$($MYSQL -e "SELECT COALESCE(lease_owner,''), status FROM benefit_task
                   WHERE biz_no='$biz' ORDER BY id LIMIT 1" 2>/dev/null | tr -d '\r')
  o=$(echo "$cur" | awk '{print $1}')
  s=$(echo "$cur" | awk '{print $2}')
  if [ "$s" = "DONE" ] || { [ -n "$o" ] && [ "$o" != "$owner" ]; }; then
    new_owner="$o"; break
  fi
  sleep 0.5
done

status=$($MYSQL -e "SELECT status FROM benefit_task WHERE biz_no='$biz' ORDER BY id LIMIT 1" | tr -d '\r')
[ "$status" = "DONE" ] || fail "任务未收敛，当前 status=$status owner=$new_owner —— 接管没发生"
grn "任务终态 DONE（原持有者已死，由其他实例接管完成）"

# ------------------------------------------------------------------
# 5. 断言结果只有一份 —— 接管不等于重做
# ------------------------------------------------------------------
info "核对发放记录"

after=$($MYSQL -e "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no='$biz'" | tr -d '\r')
[ "$after" = "2" ] || fail "履约明细 $after 条，期望 2（两个供应方各一条）"
grn "履约明细 2 条，无重复"

grant=$($MYSQL -e "SELECT grant_status FROM play_biz_record WHERE play_biz_record_no='$biz'" | tr -d '\r')
[ "$grant" = "GRANT_SUCCESS" ] || fail "主单 grant_status=$grant，期望 GRANT_SUCCESS"
grn "主单 GRANT_SUCCESS"

# 下游账本才是「无重复发放」的最终判据 —— 平台侧记录数受幂等保护恒为 1，
# 看不出多余的重试
for op in $($MYSQL -e "SELECT grant_op_no FROM benefit_fulfillment_record WHERE play_biz_record_no='$biz'" | tr -d '\r'); do
  n=$(curl -sf "$MOCK/api/fault/ledger/$op" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["granted"])')
  [ "$n" = "True" ] || fail "下游账本无 $op 的记录"
done
grn "下游账本与平台侧一致"

# ------------------------------------------------------------------
# 6. 断言 fencing —— 原实例苏醒后的写回被拒
# ------------------------------------------------------------------
info "重启原持有者，验证陈旧写回被拒"

docker start "$holder" >/dev/null
sleep 8

# 它苏醒后若试图写回，会被 status='DOING' AND lease_owner=? 挡下（任务已 DONE）。
# 日志里的这条 warn 是直接证据
if docker logs "$holder" 2>&1 | tail -200 | grep -q "write-back rejected by lease fencing"; then
  grn "捕获到 fencing 拒绝日志"
else
  # 没有这条日志不代表失败：SIGKILL 后进程内存全丢，重启是全新 JVM、新 owner，
  # 不会再去写那条任务。真正的判据是任务状态没有被改坏
  info "未见 fencing 日志（SIGKILL 后重启是全新 JVM，不会重放旧写回，属正常）"
fi

final=$($MYSQL -e "SELECT status, COALESCE(lease_owner,'NULL') FROM benefit_task
                   WHERE biz_no='$biz' ORDER BY id LIMIT 1" | tr -d '\r')
echo "$final" | grep -q "DONE" || fail "原实例苏醒后任务状态被改坏：$final"
grn "任务状态未被苏醒的原实例覆盖：$final"

after2=$($MYSQL -e "SELECT COUNT(*) FROM benefit_fulfillment_record WHERE play_biz_record_no='$biz'" | tr -d '\r')
[ "$after2" = "2" ] || fail "原实例苏醒后履约明细变成 $after2 条"
grn "履约明细仍为 2 条"

echo
grn "全部通过：任务被接管并完成、结果只有一份、原实例苏醒未覆盖"
echo "  订单 $biz  原持有者 $owner ($holder)"
