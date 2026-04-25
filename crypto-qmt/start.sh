#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# DB 连接 (默认沿用 ai-onchain-os),只换 schema
export DB_HOST=${DB_HOST:-43.134.118.142}
export DB_PORT=${DB_PORT:-33066}
export DB_USER=${DB_USER:-arb}
export DB_PASS=${DB_PASS:-arb123456}

# 历史 K 线导入开关 (首次启用一次,跑完置 false)
export BTC_KLINE_LOADER_ENABLED=${BTC_KLINE_LOADER_ENABLED:-false}
export BTC_KLINE_LOADER_START=${BTC_KLINE_LOADER_START:-2024-01-01T00:00:00}
export BTC_KLINE_LOADER_END=${BTC_KLINE_LOADER_END:-2026-04-25T00:00:00}

mkdir -p logs
exec java -jar target/crypto-qmt-0.1.0-SNAPSHOT.jar
