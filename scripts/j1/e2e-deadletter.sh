#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-deadletter
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 1
trap 'cleanup_voucher; finish_evidence' EXIT

TOKEN_VALUE="$(require_token)"
USER_ID="$(token_user_id "$TOKEN_VALUE")"
STOCK_USER=$((USER_ID + 1))
EXISTING_USER=$((USER_ID + 2))
EXISTING_ORDER_ID="$(new_order_id)"
STOCK_ORDER_ID=$((EXISTING_ORDER_ID + 1))
UNIQUE_ORDER_ID=$((EXISTING_ORDER_ID + 2))

mysql_exec "
  UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=$VOUCHER_ID;
  INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES ($EXISTING_ORDER_ID,$EXISTING_USER,$VOUCHER_ID);
  INSERT INTO tb_seckill_outbox(id,user_id,voucher_id,payload,status,attempts,resend_count,next_retry_at)
  VALUES
    ($STOCK_ORDER_ID,$STOCK_USER,$VOUCHER_ID,'{\"id\":$STOCK_ORDER_ID,\"userId\":$STOCK_USER,\"voucherId\":$VOUCHER_ID}',0,0,0,NOW(3)),
    ($UNIQUE_ORDER_ID,$EXISTING_USER,$VOUCHER_ID,'{\"id\":$UNIQUE_ORDER_ID,\"userId\":$EXISTING_USER,\"voucherId\":$VOUCHER_ID}',0,0,0,NOW(3));
"
mqctl purge_queue seckillDlq >/dev/null
[[ -f "${APP_LOG:-}" ]] || fail "APP_LOG must point to the running app's log (with org.springframework.retry at DEBUG)"
APP_LOG_START="$(wc -l < "$APP_LOG")"

for order_id in "$STOCK_ORDER_ID" "$UNIQUE_ORDER_ID"; do
  wait_outbox_sent "$order_id" 60
done

for _ in $(seq 1 60); do
  [[ "$(queue_messages seckillDlq)" -ge 2 ]] && break
  sleep 1
done
DLQ_COUNT="$(queue_messages seckillDlq)"
MAIN_QUEUE_COUNT="$(queue_messages seckillQueue)"
echo "injected=2 dlq_count=$DLQ_COUNT main_queue=$MAIN_QUEUE_COUNT"
assert_eq 2 "$DLQ_COUNT" "DLQ message count"
assert_eq 0 "$MAIN_QUEUE_COUNT" "main queue messages"

# 重试分类以真实监听器的 RetryTemplate 日志为准：不可重试消息每条只应执行一次。
RETRY_LOG="$(tail -n +"$((APP_LOG_START + 1))" "$APP_LOG")"
FINISHED_FIRST="$(printf '%s\n' "$RETRY_LOG" | grep -a -c 'Retry failed last attempt: count=1' || true)"
RETRIED="$(printf '%s\n' "$RETRY_LOG" | grep -a -c 'Retry: count=1' || true)"
echo "listener_finished_on_first_attempt=$FINISHED_FIRST listener_retried=$RETRIED"
assert_eq 2 "$FINISHED_FIRST" "messages finished on first attempt"
assert_eq 0 "$RETRIED" "listener retries"

DLQ_JSON_FILE="$(mktemp)"
trap 'rm -f "$DLQ_JSON_FILE"; cleanup_voucher; finish_evidence' EXIT
mq_admin get queue=seckillDlq count=2 ackmode=ack_requeue_true --format=raw_json > "$DLQ_JSON_FILE"
python -c '
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
messages = data if isinstance(data, list) else data.get("messages", [])
assert len(messages) >= 2, messages
for item in messages[:2]:
    headers = item.get("properties", {}).get("headers", {})
    assert headers.get("x-exception-message"), headers
    assert headers.get("x-original-exchange") == "seckillExchange", headers
print("dead_letter_headers_ok=2")
' "$DLQ_JSON_FILE"
assert_identity "$VOUCHER_ID"
pass "two non-retryable messages reached DLQ with exception headers"
