#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence test-migration

CHECK_DB=j1_migration_check
BASELINE_COMMIT=e979557
SCHEMA=src/main/resources/db/schema.sql
MIGRATION="$ROOT/src/main/resources/db/migration/J1-001-reliability.sql"
SQL_MODE_INIT="SET SESSION sql_mode='NO_ENGINE_SUBSTITUTION'"

check_mysql() {
  "$MYSQL_BIN" --default-character-set=utf8mb4 --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
    --init-command="$SQL_MODE_INIT" "$@"
}
reset_db() {
  check_mysql -e "DROP DATABASE IF EXISTS $CHECK_DB; CREATE DATABASE $CHECK_DB DEFAULT CHARSET utf8mb4;"
}
index_count() {
  check_mysql --batch --skip-column-names -e "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
    WHERE table_schema='$CHECK_DB' AND index_name IN ('uk_user_voucher','uk_outbox_user_voucher');" | tr -d '\r'
}
trap 'check_mysql -e "DROP DATABASE IF EXISTS $CHECK_DB;" || true; finish_evidence' EXIT

reset_db
check_mysql "$CHECK_DB" < "$ROOT/$SCHEMA"
EMPTY_COUNT="$(index_count)"
echo "empty_db_schema unique_indexes=$EMPTY_COUNT"
assert_eq 2 "$EMPTY_COUNT" "unique indexes on empty database"

reset_db
git -C "$ROOT" show "$BASELINE_COMMIT:$SCHEMA" | check_mysql "$CHECK_DB"
check_mysql "$CHECK_DB" -e "INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (1,1,1);"
check_mysql "$CHECK_DB" --batch --skip-column-names < "$MIGRATION"
EXISTING_COUNT="$(index_count)"
KEPT="$(check_mysql "$CHECK_DB" --batch --skip-column-names -e "SELECT COUNT(*) FROM tb_voucher_order;" | tr -d '\r')"
echo "existing_db_migration unique_indexes=$EXISTING_COUNT kept_orders=$KEPT"
assert_eq 2 "$EXISTING_COUNT" "unique indexes after migration"
assert_eq 1 "$KEPT" "existing order kept"

if check_mysql "$CHECK_DB" -e "INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES (2,1,1);" 2>/dev/null; then
  fail "duplicate (user_id, voucher_id) was accepted"
fi
pass "migration works on empty and existing databases and rejects a duplicate order"
