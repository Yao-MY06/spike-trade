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

for order_id in "$STOCK_ORDER_ID" "$UNIQUE_ORDER_ID"; do
  wait_outbox_sent "$order_id" 60
done

for _ in $(seq 1 60); do
  [[ "$(queue_messages seckillDlq)" -ge 2 ]] && break
  sleep 1
done
DLQ_COUNT="$(queue_messages seckillDlq)"
assert_eq 2 "$DLQ_COUNT" "DLQ message count"
assert_eq 0 "$(queue_messages seckillQueue)" "main queue messages"

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
pass "two non-retryable messages reached DLQ with exception headers"
