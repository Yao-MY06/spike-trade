#!/usr/bin/env bash
# measure-rt.sh —— 基线接口耗时采样（阶段五 · 第四章「固定环境与基线测量」）
#
# 每个接口：先 20 次预热（结果丢弃），再 100 次采样，每次耗时写一行（毫秒，3 位小数）
# 原始数据按接口分文件保存，最后算 p50 / p95 / 最大值
#
# 用法：
#   ./measure-rt.sh                          # 默认建 run-<时间戳>/ 子目录，两次运行不互相覆盖
#   OUT_DIR=./run1 ./measure-rt.sh           # 指定目录（里面已有 raw-*.txt 则拒绝执行）
#   WARMUP=20 SAMPLES=100 BASE_URL=http://localhost:8081
#
# 百分位口径（与实施文档一致）：第 k 个百分位 = 排序后第 ceil(n × k/100) 个（1 起算，最近秩法）
#   n=100 时 p95 取第 95 条，p50 取第 50 条；n 偏小时 p95 会退化为 max
#
# 只统计 HTTP 200：非 200、超时、连不上都计入 fail，**不写入样本文件**，避免把失败记成 0ms
#
# 环境信息（代码版本 / JDK / JVM 参数 / MySQL·Redis 版本 / 数据规模）不在本脚本里，
# 单独记在 docs/evidence/baseline-metrics/environment.md（7.1 要求）。

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
WARMUP="${WARMUP:-20}"
SAMPLES="${SAMPLES:-100}"
TIMEOUT="${TIMEOUT:-10}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "${OUT_DIR:-}" ]; then
  OUT_DIR="$SCRIPT_DIR/run-$(date '+%Y%m%d-%H%M%S')"
fi
mkdir -p "$OUT_DIR"

# 防覆盖：目录里已经有原始数据就拒绝跑，避免第二次运行悄悄盖掉第一次
if compgen -G "$OUT_DIR/raw-*.txt" > /dev/null; then
  echo "[FAIL] $OUT_DIR 里已有 raw-*.txt，会覆盖上一次结果。换一个 OUT_DIR（如 ./run2）再跑。" >&2
  exit 1
fi

# 三个接口：别名|路径
#   都是免登录接口（MvcConfig 的 excludePathPatterns 里有 /shop-type/**、/voucher/**、/blog/hot），
#   覆盖三种读取路径：小表全量 / 按商户查券 / 按点赞排序分页
ENDPOINTS=(
  "shop-type-list|/shop-type/list"
  "voucher-list|/voucher/list/1"
  "blog-hot|/blog/hot?current=1"
)

sec2ms() { awk -v s="$1" 'BEGIN{ printf "%.3f\n", s * 1000 }'; }

# 第 k 百分位：pos = ceil(n*k/100)，全整数运算避免浮点误差
percentile() {
  local file="$1" k="$2" n pos
  n=$(wc -l < "$file" | tr -d ' ')
  [ "$n" -eq 0 ] && { echo "NA"; return; }
  pos=$(( (n * k + 99) / 100 ))
  [ "$pos" -lt 1 ] && pos=1
  [ "$pos" -gt "$n" ] && pos=$n
  sort -n "$file" | sed -n "${pos}p"
}

# 单次请求：输出 "状态码 秒数"；任何异常都保证有两个字段，便于下面统一判断
probe() {
  local out
  out=$(curl -s -o /dev/null -m "$TIMEOUT" -w '%{http_code} %{time_total}' "$1" 2>/dev/null) || true
  case "$out" in
    *" "*) printf '%s\n' "$out" ;;
    *)     printf '%s\n' "000 ${out:-0}" ;;
  esac
}

# --- 前置检查：应用必须活着，否则 100 次采样全是失败，数据没有意义 ---
if [ "$(probe "$BASE_URL/shop-type/list" | cut -d' ' -f1)" != "200" ]; then
  echo "[FAIL] $BASE_URL 不可达或非 200，先启动应用（--server.port=8081）" >&2
  exit 1
fi

STAMP=$(date '+%Y-%m-%d %H:%M:%S')
SUMMARY="$OUT_DIR/summary.tsv"

{
  echo "# 采样元信息"
  echo "时间            $STAMP"
  echo "BASE_URL        $BASE_URL"
  echo "输出目录        $OUT_DIR"
  echo "预热次数        $WARMUP（结果丢弃）"
  echo "采样次数        $SAMPLES（仅统计 HTTP 200，其余计入 fail，不写入样本）"
  echo "超时            ${TIMEOUT}s"
  echo "百分位口径      第 k 个 = 排序后第 ceil(n*k/100) 个（最近秩法；n=100 时 p95=第95条）"
  echo "接口"
  for spec in "${ENDPOINTS[@]}"; do echo "  ${spec%%|*}  ${spec#*|}"; done
} > "$OUT_DIR/meta.txt"

printf "endpoint\tn\tfail\tp50_ms\tp95_ms\tmax_ms\n" > "$SUMMARY"

echo "=== 基线耗时采样 $STAMP （$BASE_URL，预热 $WARMUP / 采样 $SAMPLES）==="

for spec in "${ENDPOINTS[@]}"; do
  name="${spec%%|*}"
  path="${spec#*|}"
  raw="$OUT_DIR/raw-$name.txt"
  : > "$raw"

  # 1) 预热：结果全部丢弃，只为让缓存/连接池/类加载进入稳态；失败也计数，写进元信息
  warmfail=0
  for _ in $(seq 1 "$WARMUP"); do
    read -r c _t <<< "$(probe "$BASE_URL$path")"
    [ "$c" = "200" ] || warmfail=$((warmfail + 1))
  done

  # 2) 采样：只有 HTTP 200 才写一行毫秒数；其余记 fail，绝不写 0 或超时值冒充样本
  fail=0
  for _ in $(seq 1 "$SAMPLES"); do
    read -r c t <<< "$(probe "$BASE_URL$path")"
    if [ "$c" != "200" ] || [ -z "${t:-}" ]; then
      fail=$((fail + 1))
      continue
    fi
    sec2ms "$t" >> "$raw"
  done

  n=$(wc -l < "$raw" | tr -d ' ')
  p50=$(percentile "$raw" 50)
  p95=$(percentile "$raw" 95)
  maxv=$(sort -n "$raw" | tail -1)
  [ -z "$maxv" ] && maxv="NA"

  printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$name" "$n" "$fail" "$p50" "$p95" "$maxv" >> "$SUMMARY"
  printf "  %-18s n=%-4s fail=%-3s p50=%-9s p95=%-9s max=%-9s 预热失败=%s\n" \
    "$name" "$n" "$fail" "$p50" "$p95" "$maxv" "$warmfail"
done

# 3) 一份能直接贴进证据的 markdown 摘要
{
  echo "# 基线接口耗时摘要"
  echo
  echo "采集时间：$STAMP　|　目标：$BASE_URL　|　预热 $WARMUP 次后连续采样 $SAMPLES 次"
  echo
  echo "| 接口 | 样本数 n | 失败数 | p50 (ms) | p95 (ms) | max (ms) |"
  echo "| --- | --- | --- | --- | --- | --- |"
  tail -n +2 "$SUMMARY" | while IFS=$'\t' read -r ep n fail p50 p95 mx; do
    printf "| \`%s\` | %s | %s | %s | %s | %s |\n" "$ep" "$n" "$fail" "$p50" "$p95" "$mx"
  done
  echo
  echo "口径："
  echo
  echo "- 百分位用**最近秩法**：第 k 个 = 排序后第 ceil(n×k/100) 个（n=100 时 p95=第 95 条）。"
  echo "- **只统计 HTTP 200**；非 200 / 超时 / 连不上计入 fail，不写入样本文件，避免失败被当成 0ms 的成功。"
  echo "- 单机单进程**串行**请求，未并发；**不构成吞吐量或压测结论**，只作后续改造的对照基线。"
  echo
  echo "原始数据（每行一次成功请求的毫秒数）："
  for spec in "${ENDPOINTS[@]}"; do echo "- \`raw-${spec%%|*}.txt\`"; done
} > "$OUT_DIR/summary.md"

echo "=== 完成：$OUT_DIR ==="
ls -1 "$OUT_DIR"
