#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
EVIDENCE_DIR="${EVIDENCE_DIR:-$ROOT/evidence/J1}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-spike_trade}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${TRADE_DB_PASSWORD:-${DB_PASSWORD:-}}"
REDIS_PASSWORD="${TRADE_REDIS_PASSWORD:-${REDIS_PASSWORD:-}}"
MQ_USERNAME="${TRADE_MQ_USERNAME:-${MQ_USERNAME:-}}"
MQ_PASSWORD="${TRADE_MQ_PASSWORD:-${MQ_PASSWORD:-}}"
MQ_VHOST="${TRADE_MQ_VHOST:-/}"
REDIS_CONTAINER="${REDIS_CONTAINER:-}"
MQ_CONTAINER="${MQ_CONTAINER:-}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

[[ -n "$DB_PASSWORD" ]] || { echo "TRADE_DB_PASSWORD is required" >&2; exit 1; }
[[ -n "$REDIS_PASSWORD" ]] || { echo "TRADE_REDIS_PASSWORD is required" >&2; exit 1; }
[[ -n "$REDIS_CONTAINER" ]] || { echo "REDIS_CONTAINER is required" >&2; exit 1; }

export MYSQL_PWD="$DB_PASSWORD"
export REDISCLI_AUTH="$REDIS_PASSWORD"

redis_cmd() {
  docker exec -e REDISCLI_AUTH="$REDISCLI_AUTH" "$REDIS_CONTAINER" redis-cli --no-auth-warning "$@"
}

redis_eval_file() {
  local script="$1"; shift
  redis_cmd EVAL "$(cat "$script")" "$@"
}

mysql_exec() {
  "$MYSQL_BIN" --default-character-set=utf8mb4 --host="$DB_HOST" --port="$DB_PORT" \
    --user="$DB_USER" --database="$DB_NAME" --execute="$1"
}

db_query() {
  "$MYSQL_BIN" --default-character-set=utf8mb4 --host="$DB_HOST" --port="$DB_PORT" \
    --user="$DB_USER" --database="$DB_NAME" --batch --skip-column-names --execute="$1"
}

db_scalar() {
  db_query "$1" | tr -d '\r' | head -n 1
}

mqctl() {
  [[ -n "$MQ_CONTAINER" ]] || { echo "MQ_CONTAINER is required" >&2; exit 1; }
  docker exec "$MQ_CONTAINER" rabbitmqctl "$@"
}

mq_admin() {
  [[ -n "$MQ_CONTAINER" ]] || { echo "MQ_CONTAINER is required" >&2; exit 1; }
  [[ -n "$MQ_USERNAME" ]] || { echo "TRADE_MQ_USERNAME is required" >&2; exit 1; }
  [[ -n "$MQ_PASSWORD" ]] || { echo "TRADE_MQ_PASSWORD is required" >&2; exit 1; }
  MSYS2_ARG_CONV_EXCL='*' docker exec -e RABBITMQ_PASSWORD="$MQ_PASSWORD" "$MQ_CONTAINER" \
    rabbitmqadmin --username="$MQ_USERNAME" --password="$MQ_PASSWORD" --vhost="$MQ_VHOST" "$@"
}

filter_log() {
  local line word encoded
  local -a words=()
  for encoded in aG1kcA== 6buR6ams 6JmO5ZOl 54K56K+E; do
    word="$(printf '%s' "$encoded" | base64 -d)"
    words+=("$word")
  done
  while IFS= read -r line || [[ -n "$line" ]]; do
    for word in "${words[@]}"; do
      line="${line//$word/<REDACTED>}"
    done
    line="${line//$ROOT/<WORKSPACE>}"
    if [[ "$REDIS_CONTAINER" == "redis" ]]; then
      line="$(printf '%s\n' "$line" | sed -E 's/(docker[[:space:]]+(start|exec)[[:space:]]+)redis([^A-Za-z0-9_-]|$)/\1<REDIS_CONTAINER>\3/g')"
    elif [[ -n "$REDIS_CONTAINER" ]]; then
      line="${line//$REDIS_CONTAINER/<REDIS_CONTAINER>}"
    fi
    if [[ "$MQ_CONTAINER" == "rabbitmq" ]]; then
      line="$(printf '%s\n' "$line" | sed -E 's/(docker[[:space:]]+(start|exec)[[:space:]]+)rabbitmq([^A-Za-z0-9_-]|$)/\1<MQ_CONTAINER>\3/g')"
    elif [[ -n "$MQ_CONTAINER" ]]; then
      line="${line//$MQ_CONTAINER/<MQ_CONTAINER>}"
    fi
    [[ -n "$MQ_USERNAME" ]] && line="${line//$MQ_USERNAME/<MQ_USERNAME>}"
    printf '%s\n' "$line"
  done
}

start_evidence() {
  local name="$1"
  mkdir -p "$EVIDENCE_DIR"
  exec > >(filter_log | tee "$EVIDENCE_DIR/$name.log") 2>&1
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "PASS: $*"
}

assert_eq() {
  local expected="$1" actual="$2" label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label: expected [$expected], got [$actual]"
}

json_get() {
  local path="$1"
  python -c '
import json, sys
obj = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    if part == "":
        continue
    if isinstance(obj, list):
        obj = obj[int(part)]
    else:
        obj = obj.get(part)
    if obj is None:
        break
if isinstance(obj, bool):
    print("true" if obj else "false")
elif obj is not None:
    print(obj)
' "$path"
}

json_assert_success() {
  local value
  value="$(json_get success)"
  [[ "$value" == "true" ]] || fail "response success=false"
}

wait_url() {
  local url="$1" timeout="${2:-60}" i
  for ((i=0; i<timeout; i++)); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  fail "URL not ready in ${timeout}s: $url"
}

clear_rate_limit() {
  local key
  redis_cmd --scan --pattern 'limit/seckill*' | tr -d '\r' | while IFS= read -r key; do
    [[ -n "$key" ]] && redis_cmd DEL "$key" >/dev/null
  done
}

new_voucher_id() {
  echo $(( ( $(date +%s) % 100000000 ) * 1000 + ($$ % 1000) ))
}

new_order_id() {
  echo $(( $(date +%s) * 1000000 + ($$ % 1000000) ))
}

prepare_voucher() {
  local stock="$1"
  VOUCHER_ID="$(new_voucher_id)"
  cleanup_voucher
  mysql_exec "
    INSERT INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status,create_time,update_time)
    VALUES ($VOUCHER_ID,1,'J1测试券','可靠链路','仅测试',1,10,1,1,NOW(),NOW());
    INSERT INTO tb_seckill_voucher(voucher_id,stock,create_time,begin_time,end_time,update_time)
    VALUES ($VOUCHER_ID,$stock,NOW(),DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 DAY),NOW());
  "
  redis_cmd SET "seckill:stock:$VOUCHER_ID" "$stock" >/dev/null
  redis_cmd DEL "seckill:resv:$VOUCHER_ID" >/dev/null
  clear_rate_limit
}

cleanup_voucher() {
  [[ -n "${VOUCHER_ID:-}" ]] || return 0
  mysql_exec "
    DELETE FROM tb_seckill_outbox WHERE voucher_id=$VOUCHER_ID;
    DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;
    DELETE FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID;
    DELETE FROM tb_voucher WHERE id=$VOUCHER_ID;
  " >/dev/null 2>&1 || true
  redis_cmd DEL "seckill:stock:$VOUCHER_ID" "seckill:resv:$VOUCHER_ID" >/dev/null 2>&1 || true
  clear_rate_limit >/dev/null 2>&1 || true
}

login_token() {
  local phone="$1" code token
  curl -fsS -X POST "$BASE_URL/user/code?phone=$phone" >/dev/null
  code="$(redis_cmd GET "login:code:$phone" | tr -d '\r')"
  [[ -n "$code" ]] || fail "verification code missing for $phone"
  token="$(
    curl -fsS -X POST "$BASE_URL/user/login" \
      -H 'Content-Type: application/json' \
      -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" | json_get data
  )"
  [[ -n "$token" ]] || fail "token missing for $phone"
  printf '%s' "$token"
}

require_token() {
  local token="${TOKEN:-}"
  if [[ -z "$token" ]]; then
    token="$(login_token "${TOKEN_PHONE:-13900008001}")"
  fi
  printf '%s' "$token"
}

token_user_id() {
  local token="$1"
  curl -fsS "$BASE_URL/user/me" -H "authorization: $token" | json_get data.id
}

api_seckill() {
  local token="$1" voucher_id="$2"
  clear_rate_limit
  curl -fsS -X POST "$BASE_URL/voucher-order/seckill/$voucher_id" \
    -H "authorization: $token"
}

api_reconcile() {
  local token="$1" voucher_id="$2"
  curl -fsS "$BASE_URL/voucher-order/reconcile/$voucher_id" \
    -H "authorization: $token"
}

snapshot() {
  local voucher_id="$1"
  redis_eval_file "$ROOT/src/main/resources/seckill_snapshot.lua" 2 \
    "seckill:stock:$voucher_id" "seckill:resv:$voucher_id"
}

assert_identity() {
  local voucher_id="$1" snap redis_total db_stock order_count db_total
  snap="$(snapshot "$voucher_id")"
  redis_total=$(( ${snap%%:*} + ${snap##*:} ))
  db_stock="$(db_scalar "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$voucher_id")"
  order_count="$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$voucher_id")"
  db_total=$(( db_stock + order_count ))
  [[ "$redis_total" == "$db_total" ]] || fail "identity failed: R+Z=$redis_total D+O=$db_total"
  pass "identity R+Z = D+O = $redis_total"
}

wait_outbox_sent() {
  local order_id="$1" timeout="${2:-120}" i status
  for ((i=0; i<timeout; i++)); do
    status="$(db_scalar "SELECT status FROM tb_seckill_outbox WHERE id=$order_id")"
    [[ "$status" == "1" ]] && return 0
    sleep 1
  done
  fail "outbox not SENT in ${timeout}s: orderId=$order_id"
}

wait_order_count() {
  local voucher_id="$1" expected="$2" timeout="${3:-120}" i count
  for ((i=0; i<timeout; i++)); do
    count="$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$voucher_id")"
    [[ "$count" == "$expected" ]] && return 0
    sleep 1
  done
  fail "order count did not reach $expected in ${timeout}s"
}

wait_mq_ready() {
  local timeout="${1:-120}" i
  for ((i=0; i<timeout; i++)); do
    if mqctl ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "RabbitMQ not ready in ${timeout}s"
}

queue_messages() {
  local queue="$1"
  mqctl list_queues name messages | awk -v queue="$queue" '$1 == queue { print $2 }'
}
report_has_issue() {
  local type="$1" action="$2"
  python -c '
import json, sys
report = json.load(sys.stdin)["data"]
issues = report.get("issues", [])
ok = any(i.get("type") == sys.argv[1] and (sys.argv[2] == "-" or i.get("action") == sys.argv[2]) for i in issues)
raise SystemExit(0 if ok else 1)
' "$type" "$action"
}

report_types() {
  python -c '
import json, sys
report = json.load(sys.stdin)["data"]
print(" ".join(sorted({i.get("type") for i in report.get("issues", [])})))
'
}
