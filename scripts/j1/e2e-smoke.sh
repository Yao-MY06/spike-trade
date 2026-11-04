#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-smoke
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 1
TMP_DIR="$(mktemp -d)"
cleanup() { cleanup_voucher; rm -rf "$TMP_DIR"; }
trap 'cleanup; finish_evidence' EXIT

clear_rate_limit
HTTP_CODE="$(curl -sS -o "$TMP_DIR/unauth.json" -w '%{http_code}' \
  -X POST "$BASE_URL/voucher-order/seckill/$VOUCHER_ID")"
assert_eq 401 "$HTTP_CODE" "unauthenticated HTTP status"

TOKEN_VALUE="$(require_token)"
RESPONSE="$(api_seckill "$TOKEN_VALUE" "$VOUCHER_ID")"
printf '%s\n' "$RESPONSE"
assert_eq true "$(printf '%s' "$RESPONSE" | json_get success)" "seckill response success"
ORDER_ID="$(printf '%s' "$RESPONSE" | json_get data)"
[[ -n "$ORDER_ID" ]] || fail "order id missing"

wait_outbox_sent "$ORDER_ID" 60
wait_order_count "$VOUCHER_ID" 1 60
OUTBOX_ID="$(db_scalar "SELECT id FROM tb_seckill_outbox WHERE id=$ORDER_ID")"
ORDER_ROW_ID="$(db_scalar "SELECT id FROM tb_voucher_order WHERE id=$ORDER_ID")"
assert_eq "$ORDER_ID" "$OUTBOX_ID" "response/outbox order id"
assert_eq "$ORDER_ID" "$ORDER_ROW_ID" "response/order row id"
assert_eq "0:1" "$(snapshot "$VOUCHER_ID")" "redis snapshot"
assert_identity "$VOUCHER_ID"
pass "HTTP 401 verified; order id spans response, outbox and order row"