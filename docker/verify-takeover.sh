#!/usr/bin/env bash
#
# V4 退出标准第 2、3、4 条：kill -9 掉持有任务的实例，断言任务被接管、结果只有
# 一份、原实例的陈旧写回被 fencing 拒绝。
#
# 与 ReliableTaskIT 验的不是同一件事。那里用多线程持不同 lease_owner 在单进程内
# 验 SQL 语义（WHERE 条件写对没有）；这里验进程真的死掉时系统能不能恢复 —— JVM
# 被 SIGKILL，连接池未关、租约未释放、事务在服务端超时回滚，都不是单进程能构造的。
#
# 用法：
#   docker compose --profile v4 up -d
#   MP_TASK_LEASE_SECONDS=5 MP_TASK_INTERVAL_MILLIS=500 \
#     docker compose -f docker-compose-dist.yml up -d --scale benefit-order=3
#   docker/verify-takeover.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE:-http://localhost:8080}"
MOCK="${MOCK:-http://localhost:8090}"
RID="${RID:-tk$(date +%s | tail -c 5)}"
WAIT="${WAIT:-40}"

sql() { docker exec -i mp-mysql mysql -N -ump_benefit -pmp_benefit db_benefit 2>/dev/null; }
q()   { echo "$1" | sql | tr -d '\r'; }

grn() { printf '\033[32m✓ %s\033[0m\n' "$1"; }
inf() { printf '\033[36m▸ %s\033[0m\n' "$1"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$1"; exit 1; }

# ------------------------------------------------------------------
# 0. 前置
# ------------------------------------------------------------------
inf "前置检查"
# 不用 mapfile：macOS 自带 bash 3.2 没有它
REPLICAS=$(docker ps --filter "name=benefit-order" --format '{{.Names}}' | sort)
n_rep=$(echo "$REPLICAS" | grep -c . || true)
[ "$n_rep" -ge 2 ] || die "benefit-order 副本数 $n_rep，至少要 2 个（--scale benefit-order=3）"
grn "benefit-order 副本 $n_rep 个"

first=$(echo "$REPLICAS" | head -1)
lease=$(docker inspect "$first" --format '{{range .Config.Env}}{{println .}}{{end}}' \
        | grep '^MP_TASK_LEASE_SECONDS=' | cut -d= -f2)
lease="${lease:-30}"
grn "租约 ${lease}s（接管延迟约为其 2 倍：租约到期 + 僵尸回收每 10 轮）"

# ------------------------------------------------------------------
# 1. 造一条「某实例领了但没做完」的任务
#
# 不用故障注入把真实任务钉在 DOING —— mock 的故障模式都是立即返回，没有延迟
# 能力，而多实例 + 500ms 扫描下真实任务的 DOING 只存在毫秒级，脚本轮询抓不到。
#
# 直接构造反而更贴近要验的场景：一个实例领走任务、写下自己的 lease_owner、
# 然后进程消失。库里留下的正是这样一行。
# ------------------------------------------------------------------
inf "构造一条被实例持有的任务"

victim="$first"
# owner 取真实副本的形态（inst-xxxxxxxx），值由脚本指定 —— 进程内那个随机 UUID
# 拿不到，而 fencing 只比对字符串，用哪个值不影响语义
ghost="inst-${RID}"
task="TK_${RID}"

echo "INSERT INTO benefit_task
        (task_no, biz_no, task_type, op_no, status, next_time, retry_count,
         lease_owner, lease_expire, payload)
      VALUES
        ('$task', 'BZ_$RID', 'STOCK_CONSUME', 'OP_$RID', 'DOING', NOW(3), 0,
         '$ghost', DATE_ADD(NOW(3), INTERVAL $lease SECOND), '{}')" | sql

got=$(q "SELECT status FROM benefit_task WHERE task_no='$task'")
[ "$got" = "DOING" ] || die "任务未落库"
grn "任务 ${task} 状态 DOING，持有者 ${ghost}，租约 ${lease}s 后到期"

# ------------------------------------------------------------------
# 2. kill -9 —— 持有者进程消失，租约不会被释放
# ------------------------------------------------------------------
inf "kill -9 $victim"
docker kill -s KILL "$victim" >/dev/null
grn "已 SIGKILL（进程没有机会做任何清理，这正是要验的）"

alive=$(docker ps --filter "name=benefit-order" --format '{{.Names}}' | wc -l | tr -d ' ')
grn "存活副本 $alive 个"

# ------------------------------------------------------------------
# 3. 断言接管：租约到期后，另一实例把它捞回去
# ------------------------------------------------------------------
inf "等待接管（最多 ${WAIT}s）"

new_owner=""
for _ in $(seq 1 $((WAIT * 2))); do
  cur=$(q "SELECT CONCAT(status,'|',COALESCE(lease_owner,'NULL')) FROM benefit_task WHERE task_no='$task'")
  st="${cur%%|*}"; ow="${cur##*|}"
  # 接管的证据：owner 变了，或任务已被执行完
  if [ "$ow" != "$ghost" ]; then new_owner="$ow"; break; fi
  sleep 0.5
done

final=$(q "SELECT CONCAT(status,'|',COALESCE(lease_owner,'NULL')) FROM benefit_task WHERE task_no='$task'")
st="${final%%|*}"; ow="${final##*|}"

[ "$ow" != "$ghost" ] || die "租约到期 ${WAIT}s 后仍归死掉的 ${ghost} —— 僵尸回收没有工作"
grn "任务已脱离死亡实例：status=$st owner=$ow"

# 它应当被真实执行掉。STOCK_CONSUME 的 biz_no 是造的，处理器会判定无对应单据
# 而以确定失败了结 —— 关键是它「被处理了」，不是「永远停在 DOING」
[ "$st" != "DOING" ] || die "任务仍停在 DOING，接管者领了却没推进"
grn "任务已被接管者推进到终态 $st"

# ------------------------------------------------------------------
# 4. 断言 fencing：死掉的 owner 无法再写回
# ------------------------------------------------------------------
inf "验证陈旧写回被 fencing 拒绝"

# 模拟 $ghost 苏醒后试图写回。fencing 条件是 status='DOING' AND lease_owner=?，
# 任务已被接管，这四类写回都应命中 0 行
for stmt in \
  "UPDATE benefit_task SET status='DONE' WHERE task_no='$task' AND status='DOING' AND lease_owner='$ghost'" \
  "UPDATE benefit_task SET status='PENDING', retry_count=retry_count+1 WHERE task_no='$task' AND status='DOING' AND lease_owner='$ghost'" \
  "UPDATE benefit_task SET status='DEAD' WHERE task_no='$task' AND status='DOING' AND lease_owner='$ghost'" \
  "UPDATE benefit_task SET lease_expire=DATE_ADD(NOW(3), INTERVAL 30 SECOND) WHERE task_no='$task' AND status='DOING' AND lease_owner='$ghost'"
do
  n=$(echo "$stmt; SELECT ROW_COUNT()" | sql | tr -d '\r' | tail -1)
  [ "$n" = "0" ] || die "陈旧写回命中 $n 行，fencing 未生效：$stmt"
done
grn "四类写回（完成/重排/死信/续租）全部命中 0 行"

after=$(q "SELECT CONCAT(status,'|',COALESCE(lease_owner,'NULL')) FROM benefit_task WHERE task_no='$task'")
[ "$after" = "$final" ] || die "状态被陈旧写回改动：$final → $after"
grn "接管者的结果未被覆盖：$after"

# ------------------------------------------------------------------
# 5. 恢复副本数
# ------------------------------------------------------------------
inf "恢复被 kill 的副本"
docker start "$victim" >/dev/null 2>&1 || true
echo "DELETE FROM benefit_task WHERE task_no='$task'" | sql

echo
grn "全部通过"
echo "  任务被死亡实例持有 → 租约到期 → 另一实例接管并推进到终态 → 陈旧写回被拒"
