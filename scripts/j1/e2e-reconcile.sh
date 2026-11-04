#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence e2e-reconcile
wait_url "$BASE_URL/shop-type/list"
prepare_voucher 98
trap 'cleanup_voucher; finish_evidence' EXIT

TOKEN_VALUE="$(require_token)"
USER_ID="$(token_user_id "$TOKEN_VALUE")"
A_USER=$((USER_ID + 11))
B_USER=$((USER_ID + 12))
C_USER=$((USER_ID + 13))
D_USER=$((USER_ID + 14))
D_ORDER_ID="$(new_order_id)"
B_ORDER_ID=$((D_ORDER_ID + 1))
C_ORDER_ID=$((D_ORDER_ID + 2))

mysql_exec "
  INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES ($D_ORDER_ID,$D_USER,$VOUCHER_ID);
  INSERT INTO tb_seckill_outbox(id,user_id,voucher_id,payload,status,attempts,resend_count,next_retry_at,create_time,update_time)
  VALUES
    ($B_ORDER_ID,$B_USER,$VOUCHER_ID,'{\"id\":$B_ORDER_ID,\"userId\":$B_USER,\"voucherId\":$VOUCHER_ID}',0,0,0,DATE_ADD(NOW(),INTERVAL 1 DAY),DATE_SUB(NOW(),INTERVAL 2 MINUTE),DATE_SUB(NOW(),INTERVAL 2 MINUTE)),
    ($C_ORDER_ID,$C_USER,$VOUCHER_ID,'{\"id\":$C_ORDER_ID,\"userId\":$C_USER,\"voucherId\":$VOUCHER_ID}',1,0,0,NOW(3),DATE_SUB(NOW(),INTERVAL 2 MINUTE),DATE_SUB(NOW(),INTERVAL 2 MINUTE));
"
redis_cmd SET "seckill:stock:$VOUCHER_ID" 97 >/dev/null
redis_cmd ZADD "seckill:resv:$VOUCHER_ID" "$(( $(date +%s) - 120 ))" "$A_USER" >/dev/null

RESPONSE="$(api_reconcile "$TOKEN_VALUE" "$VOUCHER_ID")"
printf '%s\n' "$RESPONSE"
TYPES="$(printf '%s' "$RESPONSE" | report_types)"
echo "reconcile_types=$TYPES"
for type in A B C D E; do
  printf '%s' "$RESPONSE" | report_has_issue "$type" -
done
printf '%s' "$RESPONSE" | report_has_issue A REPAIRED
printf '%s' "$RESPONSE" | report_has_issue C REQUEUED

assert_eq "" "$(redis_cmd ZRANGE "seckill:resv:$VOUCHER_ID" 0 -1 | tr -d '\r')" "A reservation after repair"
assert_eq 1 "$(db_scalar "SELECT resend_count FROM tb_seckill_outbox WHERE id=$C_ORDER_ID")" "C resend count"

DB_STOCK="$(db_scalar "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")"
ORDER_COUNT="$(db_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID")"
DB_TOTAL=$(( DB_STOCK + ORDER_COUNT ))
redis_cmd SET "seckill:stock:$VOUCHER_ID" "$DB_TOTAL" >/dev/null
assert_identity "$VOUCHER_ID"
pass "A-E detected; A and C repaired; E restored before identity assertion"
