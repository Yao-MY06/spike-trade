#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-concurrent
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 3
TMP_DIR="$(mktemp -d)"
cleanup() { cleanup_voucher; rm -rf "$TMP_DIR"; }
trap 'cleanup; finish_evidence' EXIT

TOKEN_LIST="${TOKENS:-}"
TOKENS=() 
if [[ -n "$TOKEN_LIST" ]]; then
  read -r -a TOKENS <<< "$TOKEN_LIST"
else
  for suffix in 1 2 3 4 5; do
    TOKENS+=("$(login_token "1390000810$suffix")")
  done
fi
[[ "${#TOKENS[@]}" -ge 5 ]] || fail "five distinct tokens are required"

clear_rate_limit
PIDS=()
for i in 0 1 2 3 4; do
  (
    curl -fsS -X POST "$BASE_URL/voucher-order/seckill/$VOUCHER_ID" \
      -H "authorization: ${TOKENS[$i]}" > "$TMP_DIR/$i.json"
  ) &
  PIDS+=($!)
done
for pid in "${PIDS[@]}"; do
  wait "$pid"
done

SUCCESS_COUNT=0
for i in 0 1 2 3 4; do
  cat "$TMP_DIR/$i.json"
  if [[ "$(json_get success < "$TMP_DIR/$i.json")" == "true" ]]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
  fi
done

assert_eq 3 "$SUCCESS_COUNT" "successful requests"
wait_order_count "$VOUCHER_ID" 3 60
assert_eq 3 "$(db_scalar "SELECT COUNT(*) FROM tb_seckill_outbox WHERE voucher_id=$VOUCHER_ID AND status=1")" "sent outbox rows"
assert_eq 0 "$(db_scalar "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")" "database stock"
assert_eq "0:3" "$(snapshot "$VOUCHER_ID")" "redis snapshot"
assert_identity "$VOUCHER_ID"
pass "N=3 M=5 concurrent scenario created exactly three orders"
