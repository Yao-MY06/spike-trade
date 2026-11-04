#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-orphan
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 1
trap cleanup_voucher EXIT

TOKEN_VALUE="$(require_token)"
USER_ID="$(token_user_id "$TOKEN_VALUE")"
redis_cmd SET "seckill:stock:$VOUCHER_ID" 0 >/dev/null
redis_cmd ZADD "seckill:resv:$VOUCHER_ID" "$(( $(date +%s) - 120 ))" "$USER_ID" >/dev/null

RESPONSE="$(api_reconcile "$TOKEN_VALUE" "$VOUCHER_ID")"
printf '%s\n' "$RESPONSE"
printf '%s' "$RESPONSE" | report_has_issue A REPAIRED
assert_eq "1:0" "$(snapshot "$VOUCHER_ID")" "orphan reservation rolled back"
assert_eq 1 "$(db_scalar "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")" "database stock"
assert_eq 0 "$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")" "order count"
assert_identity "$VOUCHER_ID"
pass "orphan reservation was repaired by reconcile"