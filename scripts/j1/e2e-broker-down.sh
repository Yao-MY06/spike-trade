#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-broker-down
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 3
TMP_DIR="$(mktemp -d)"
cleanup() {
  docker start "$MQ_CONTAINER" >/dev/null 2>&1 || true
  cleanup_voucher
  rm -rf "$TMP_DIR"
}
trap 'cleanup; finish_evidence' EXIT

TOKENS=()
for suffix in 1 2 3; do
  TOKENS+=("$(login_token "1390000820$suffix")")
done

clear_rate_limit
OUTAGE_START_MS="$(date +%s%3N)"
docker stop "$MQ_CONTAINER" >/dev/null
for i in 0 1 2; do
  curl -fsS -X POST "$BASE_URL/voucher-order/seckill/$VOUCHER_ID" \
    -H "authorization: ${TOKENS[$i]}" > "$TMP_DIR/$i.json"
  cat "$TMP_DIR/$i.json"
  assert_eq true "$(json_get success < "$TMP_DIR/$i.json")" "broker-down request $i success"
done

mysql_exec "SELECT id,status FROM tb_seckill_outbox WHERE voucher_id=$VOUCHER_ID ORDER BY id;"
RESTART_MS="$(date +%s%3N)"
docker start "$MQ_CONTAINER" >/dev/null
wait_mq_ready 120
wait_order_count "$VOUCHER_ID" 3 120
for order_id in $(db_query "SELECT id FROM tb_seckill_outbox WHERE voucher_id=$VOUCHER_ID"); do
  wait_outbox_sent "$order_id" 120
done
END_MS="$(date +%s%3N)"
RECOVERY_MS=$((END_MS - RESTART_MS))
echo "outage_total_ms=$((END_MS - OUTAGE_START_MS))"
echo "recovery_elapsed_ms=$RECOVERY_MS"
[[ "$RECOVERY_MS" -le 120000 ]] || fail "recovery exceeded 120s: $RECOVERY_MS ms"
assert_eq 3 "$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")" "recovered order count"
assert_eq 3 "$(db_scalar "SELECT COUNT(*) FROM tb_seckill_outbox WHERE voucher_id=$VOUCHER_ID AND status=1")" "sent outbox count"
assert_identity "$VOUCHER_ID"
pass "broker outage recovered ${RECOVERY_MS} ms after restart"
