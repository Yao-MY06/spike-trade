#!/usr/bin/env bash
# 用法：run-mvn.sh test | package。每次调用只产出一份证据，避免同一进程内重定向两次丢日志。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
cd "$ROOT"
case "${1:-}" in
  test)
    start_evidence mvn-test
    mvn -B clean test -DargLine=-Dfile.encoding=UTF-8
    ;;
  package)
    start_evidence mvn-package
    mvn -B package -DskipTests
    ;;
  *)
    fail "usage: run-mvn.sh test|package"
    ;;
esac
