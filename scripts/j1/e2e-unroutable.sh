#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-unroutable
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 1

BINDING_KEY='seckill.#'
bind_queue() {
  mq_admin declare binding source=seckillExchange destination_type=queue \
    destination=seckillQueue "routing_key=$BINDING_KEY" >/dev/null
}
trap 'bind_queue || true; cleanup_voucher; finish_evidence' EXIT

TOKEN_VALUE="$(require_token)"
mq_admin delete binding source=seckillExchange destination_type=queue \
  destination=seckillQueue "properties_key=$BINDING_KEY" >/dev/null

RESPONSE="$(api_seckill "$TOKEN_VALUE" "$VOUCHER_ID")"
printf '%s\n' "$RESPONSE"
ORDER_ID="$(printf '%s' "$RESPONSE" | json_get data)"
[[ -n "$ORDER_ID" ]] || fail "order id missing"

LAST_ERROR=""
for _ in $(seq 1 30); do
  LAST_ERROR="$(db_scalar "SELECT IFNULL(last_error,'') FROM tb_seckill_outbox WHERE id=$ORDER_ID")"
  [[ "$LAST_ERROR" == RETURNED* ]] && break
  sleep 1
done
STATUS="$(db_scalar "SELECT status FROM tb_seckill_outbox WHERE id=$ORDER_ID")"
echo "while_unbound status=$STATUS last_error=$LAST_ERROR"
assert_eq 0 "$STATUS" "outbox stays NEW while unroutable"
[[ "$LAST_ERROR" == RETURNED* ]] || fail "return not recorded: [$LAST_ERROR]"
assert_eq 0 "$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")" "order count while unbound"

bind_queue
wait_outbox_sent "$ORDER_ID" 120
wait_order_count "$VOUCHER_ID" 1 60
assert_identity "$VOUCHER_ID"
pass "unroutable message was retried and delivered after the binding returned"
