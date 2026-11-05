#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence verify-infra
[[ -f "${APP_LOG:-}" ]] || fail "APP_LOG must point to the running app's log"

db_query "SELECT table_name,index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index)
  FROM information_schema.statistics
  WHERE table_schema='$DB_NAME' AND index_name IN ('uk_user_voucher','uk_outbox_user_voucher')
  GROUP BY table_name,index_name;"
mqctl list_queues name arguments
mqctl list_exchanges name type | grep -a seckill
FAILED="$(grep -a -c PRECONDITION_FAILED "$APP_LOG" || true)"
echo "startup_precondition_failed=$FAILED"
assert_eq 0 "$FAILED" "PRECONDITION_FAILED in startup log"
assert_eq "[]" "$(mqctl list_queues name arguments | tr -d '\r' | awk '$1 == "seckillQueue" { print $2 }')" "seckillQueue arguments"
pass "unique indexes present, DLX/DLQ declared, main queue arguments unchanged"
