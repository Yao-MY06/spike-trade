#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-duplicate
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 1
trap 'cleanup_voucher; finish_evidence' EXIT

TOKEN_VALUE="$(require_token)"
USER_ID="$(token_user_id "$TOKEN_VALUE")"
ORDER_ID="$(new_order_id)"
PAYLOAD="{\"id\":$ORDER_ID,\"userId\":$USER_ID,\"voucherId\":$VOUCHER_ID}"

redis_cmd SET "seckill:stock:$VOUCHER_ID" 0 >/dev/null
redis_cmd ZADD "seckill:resv:$VOUCHER_ID" "$(date +%s)" "$USER_ID" >/dev/null
mysql_exec "INSERT INTO tb_seckill_outbox(id,user_id,voucher_id,payload,status,attempts,resend_count,next_retry_at)
VALUES ($ORDER_ID,$USER_ID,$VOUCHER_ID,'$PAYLOAD',0,0,0,NOW(3));"

wait_outbox_sent "$ORDER_ID" 30
wait_order_count "$VOUCHER_ID" 1 30

for _ in 1 2 3 4; do
  mysql_exec "UPDATE tb_seckill_outbox SET status=0,next_retry_at=NOW(3) WHERE id=$ORDER_ID;"
  wait_outbox_sent "$ORDER_ID" 30
  sleep 1
done

assert_eq 1 "$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")" "duplicate order count"
assert_eq 0 "$(db_scalar "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")" "database stock"
assert_eq "0:1" "$(snapshot "$VOUCHER_ID")" "redis snapshot"
assert_identity "$VOUCHER_ID"
pass "same message published five times produced one order"
