#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-rollback
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 1
trap cleanup_voucher EXIT

TOKEN_VALUE="$(require_token)"
USER_ID="$(token_user_id "$TOKEN_VALUE")"
EXISTING_ID="$(new_order_id)"
PAYLOAD="{\"id\":$EXISTING_ID,\"userId\":$USER_ID,\"voucherId\":$VOUCHER_ID}"
mysql_exec "INSERT INTO tb_seckill_outbox(id,user_id,voucher_id,payload,status,attempts,resend_count,next_retry_at)
VALUES ($EXISTING_ID,$USER_ID,$VOUCHER_ID,'$PAYLOAD',2,0,0,NOW(3));"

RESPONSE="$(api_seckill "$TOKEN_VALUE" "$VOUCHER_ID")"
printf '%s\n' "$RESPONSE"
assert_eq false "$(printf '%s' "$RESPONSE" | json_get success)" "seckill response success"
assert_eq "1:0" "$(snapshot "$VOUCHER_ID")" "reservation rolled back"
assert_eq 1 "$(db_scalar "SELECT COUNT(*) FROM tb_seckill_outbox WHERE voucher_id=$VOUCHER_ID")" "outbox row count"
assert_eq 0 "$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")" "order count"
assert_identity "$VOUCHER_ID"
pass "outbox unique conflict rolled back the Redis reservation"