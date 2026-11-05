#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
start_evidence lint-repo

# 缺值时对应检查会被跳过，PASS 就没有意义，所以必须显式失败。
for required in MQ_CONTAINER MQ_USERNAME TRADE_DB_PASSWORD TRADE_REDIS_PASSWORD TRADE_MQ_PASSWORD \
    TRADE_JWT_ADMIN_SECRET TRADE_JWT_USER_SECRET; do
  [[ -n "${!required:-}" ]] || fail "$required is required for a meaningful lint"
done

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"; finish_evidence' EXIT
FILES=()
while IFS= read -r -d '' file; do
  FILES+=("$file")
done < <({ git -C "$ROOT" -c core.quotePath=false ls-files -z; git -C "$ROOT" -c core.quotePath=false ls-files --others --exclude-standard -z; } | sort -z -u)
[[ "${#FILES[@]}" -gt 0 ]] || fail "no files to lint"

FINDINGS=()
record_hits() {
  local label="$1" hits="$2"
  if [[ -n "$hits" ]]; then
    FINDINGS+=("$label: $hits")
  fi
}

for encoded in aG1kcA== 6buR6ams 6JmO5ZOl 54K56K+E; do
  word="$(printf '%s' "$encoded" | base64 -d)"
  hits="$(cd "$ROOT" && grep -I -n -F -- "$word" "${FILES[@]}" || true)"
  record_hits "forbidden-word" "$hits"
done

if [[ -n "$REDIS_CONTAINER" ]]; then
  if [[ "$REDIS_CONTAINER" == "redis" ]]; then
    hits="$(cd "$ROOT" && grep -I -n -E 'docker[[:space:]]+(start|exec)[[:space:]]+redis([^A-Za-z0-9_-]|$)' "${FILES[@]}" || true)"
  else
    hits="$(cd "$ROOT" && grep -I -n -F -- "$REDIS_CONTAINER" "${FILES[@]}" || true)"
  fi
  record_hits "redis-container" "$hits"
fi

if [[ -n "$MQ_CONTAINER" ]]; then
  if [[ "$MQ_CONTAINER" == "rabbitmq" ]]; then
    hits="$(cd "$ROOT" && grep -I -n -E 'docker[[:space:]]+(start|exec)[[:space:]]+rabbitmq([^A-Za-z0-9_-]|$)' "${FILES[@]}" || true)"
  else
    hits="$(cd "$ROOT" && grep -I -n -F -- "$MQ_CONTAINER" "${FILES[@]}" || true)"
  fi
  record_hits "mq-container" "$hits"
fi

if [[ -n "$MQ_USERNAME" ]]; then
  hits="$(cd "$ROOT" && grep -I -n -F -- "$MQ_USERNAME" "${FILES[@]}" | grep -v '@author' || true)"
  record_hits "mq-username" "$hits"
fi

for secret_name in TRADE_DB_PASSWORD TRADE_REDIS_PASSWORD TRADE_MQ_PASSWORD TRADE_JWT_ADMIN_SECRET TRADE_JWT_USER_SECRET; do
  secret_value="${!secret_name:-}"
  if [[ -n "$secret_value" ]]; then
    hits="$(cd "$ROOT" && grep -I -n -F -- "$secret_value" "${FILES[@]}" || true)"
    record_hits "$secret_name" "$hits"
  fi
done

if [[ "${#FINDINGS[@]}" -gt 0 ]]; then
  printf '%s\n' "${FINDINGS[@]}" >&2
  fail "repository lint found ${#FINDINGS[@]} category/categories"
fi
pass "repository lint found no forbidden words, container names, usernames, or secrets"
