#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SECKILL_LUA="$ROOT/src/main/resources/seckill.lua"
UNRESERVE_LUA="$ROOT/src/main/resources/seckill_unreserve.lua"
SNAPSHOT_LUA="$ROOT/src/main/resources/seckill_snapshot.lua"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

[[ -f "$SECKILL_LUA" ]] || fail "missing $SECKILL_LUA"
[[ -f "$UNRESERVE_LUA" ]] || fail "missing $UNRESERVE_LUA"
[[ -f "$SNAPSHOT_LUA" ]] || fail "missing $SNAPSHOT_LUA"
REDIS_CONTAINER="${REDIS_CONTAINER:-}"
[[ -n "$REDIS_CONTAINER" ]] || fail "REDIS_CONTAINER is required"
export REDISCLI_AUTH="${REDISCLI_AUTH:-${TRADE_REDIS_PASSWORD:-}}"
[[ -n "$REDISCLI_AUTH" ]] || fail "TRADE_REDIS_PASSWORD is required"

redis_cmd() {
  docker exec -e REDISCLI_AUTH="$REDISCLI_AUTH" "$REDIS_CONTAINER" redis-cli --no-auth-warning "$@"
}
redis_eval() {
  local script="$1"; shift
  redis_cmd EVAL "$(cat "$script")" "$@"
}
assert_eq() {
  local expected="$1" actual="$2" label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label: expected [$expected], got [$actual]"
}

TMP="$(mktemp -d)"
PREFIX="j1:test:$$:$(date +%s)"
STOCK="${PREFIX}:stock"
RESV="${PREFIX}:resv"
MISSING="${PREFIX}:missing"
NOW="$(date +%s)"
cleanup() {
  redis_cmd DEL "$STOCK" "$RESV" "$MISSING" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

redis_cmd SET "$STOCK" 2 >/dev/null
assert_eq 0 "$(redis_eval "$SECKILL_LUA" 2 "$STOCK" "$RESV" 101 "$NOW")" "first reservation"
score="$(redis_cmd ZSCORE "$RESV" 101)"
[[ -n "$score" ]] || fail "reservation timestamp missing"
pass "reservation writes zset score=$score"

assert_eq 2 "$(redis_eval "$SECKILL_LUA" 2 "$STOCK" "$RESV" 101 "$NOW")" "duplicate reservation"
pass "duplicate reservation rejected"

assert_eq 0 "$(redis_eval "$SECKILL_LUA" 2 "$STOCK" "$RESV" 102 "$NOW")" "second reservation"
assert_eq 1 "$(redis_eval "$SECKILL_LUA" 2 "$STOCK" "$RESV" 103 "$NOW")" "stock exhausted"
pass "stock exhaustion rejected"

assert_eq 3 "$(redis_eval "$SECKILL_LUA" 2 "$MISSING" "$RESV" 104 "$NOW")" "missing stock key"
pass "missing stock key returns 3"

assert_eq 1 "$(redis_eval "$UNRESERVE_LUA" 2 "$STOCK" "$RESV" 101)" "first unreserve"
assert_eq 0 "$(redis_eval "$UNRESERVE_LUA" 2 "$STOCK" "$RESV" 101)" "idempotent unreserve"
assert_eq 1 "$(redis_cmd GET "$STOCK")" "stock incremented once"
pass "unreserve is idempotent"

assert_eq "1:1" "$(redis_eval "$SNAPSHOT_LUA" 2 "$STOCK" "$RESV")" "atomic snapshot"
pass "snapshot returns stock:reservations"

redis_cmd DEL "$STOCK" "$RESV" >/dev/null
redis_cmd SET "$STOCK" 20 >/dev/null
worker() {
  local id="$1"
  docker exec -e REDISCLI_AUTH="$REDISCLI_AUTH" "$REDIS_CONTAINER" redis-cli --no-auth-warning \
    EVAL "$(cat "$SECKILL_LUA")" 2 "$STOCK" "$RESV" "$id" "$NOW"
}
export -f worker
export REDISCLI_AUTH REDIS_CONTAINER STOCK RESV SECKILL_LUA NOW
results="$(seq 1 200 | xargs -P 20 -n 1 bash -c 'worker "$1"' _)"
success="$(printf '%s\n' "$results" | awk '$1 == 0 { n++ } END { print n + 0 }')"
assert_eq 20 "$success" "concurrent successes"
assert_eq "0:20" "$(redis_eval "$SNAPSHOT_LUA" 2 "$STOCK" "$RESV")" "concurrent snapshot"
pass "200 concurrent attempts with stock 20 released exactly 20"

echo "ALL LUA TESTS PASS"