"""
Database operations for smart-money module.
Tables: smart_money_wallet, smart_money_signal, my_address
"""
import logging
import os
from datetime import datetime
import mysql.connector
from analyzer import WalletMetrics

log = logging.getLogger(__name__)

DB_HOST = os.getenv("DB_HOST", "43.134.118.142")
DB_PORT = int(os.getenv("DB_PORT", "33066"))
DB_USER = os.getenv("DB_USER", "arb")
DB_PASS = os.getenv("DB_PASS", "arb123456")
DB_NAME = os.getenv("DB_NAME", "aios")


def _conn():
    return mysql.connector.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASS, database=DB_NAME, charset="utf8mb4",
    )


# ─────────────────────────────────────────────
# smart_money_wallet
# ─────────────────────────────────────────────
def upsert_wallet(m: WalletMetrics):
    sql = """
    INSERT INTO smart_money_wallet
        (address, chain_index, label, score, win_rate, realized_pnl_usd,
         buy_tx_count, sell_tx_count, avg_buy_value_usd, source, last_analyzed_at)
    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
    ON DUPLICATE KEY UPDATE
        label=VALUES(label), score=VALUES(score), win_rate=VALUES(win_rate),
        realized_pnl_usd=VALUES(realized_pnl_usd), buy_tx_count=VALUES(buy_tx_count),
        sell_tx_count=VALUES(sell_tx_count), avg_buy_value_usd=VALUES(avg_buy_value_usd),
        last_analyzed_at=VALUES(last_analyzed_at)
    """
    with _conn() as con:
        cur = con.cursor()
        cur.execute(sql, (
            m.address.lower(), m.chain_index, m.label, m.score, m.win_rate,
            m.realized_pnl_usd, m.buy_tx_count, m.sell_tx_count,
            m.avg_buy_value_usd, "signal", datetime.now(),
        ))
        con.commit()
    log.info(f"upserted wallet {m.address[:10]}... score={m.score}")


def get_all_wallets(chain_index: str = None) -> list[dict]:
    sql = "SELECT * FROM smart_money_wallet"
    params = []
    if chain_index:
        sql += " WHERE chain_index=%s"
        params.append(chain_index)
    sql += " ORDER BY score DESC LIMIT 200"
    with _conn() as con:
        cur = con.cursor(dictionary=True)
        cur.execute(sql, params)
        return cur.fetchall()


# ─────────────────────────────────────────────
# smart_money_signal
# ─────────────────────────────────────────────
def save_signal(sig: dict):
    token = sig.get("token", {})
    sql = """
    INSERT IGNORE INTO smart_money_signal
        (chain_index, token_address, token_symbol, token_name, token_logo,
         wallet_type, trigger_wallet_count, trigger_wallets, amount_usd,
         price_at_signal, market_cap_usd, sold_ratio_percent, signal_time)
    VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
    """
    try:
        ts_ms = int(sig.get("timestamp", 0))
        signal_dt = datetime.fromtimestamp(ts_ms / 1000) if ts_ms else datetime.now()
    except Exception:
        signal_dt = datetime.now()

    with _conn() as con:
        cur = con.cursor()
        cur.execute(sql, (
            sig.get("chainIndex"), token.get("tokenAddress"), token.get("symbol"),
            token.get("name"), token.get("logo"), sig.get("walletType"),
            _safe_int(sig.get("triggerWalletCount")),
            sig.get("triggerWalletAddress", ""),
            _safe_float(sig.get("amountUsd")), _safe_float(sig.get("price")),
            _safe_float(token.get("marketCapUsd")),
            _safe_float(sig.get("soldRatioPercent")), signal_dt,
        ))
        con.commit()


def get_recent_signals(chain_index: str = None, limit: int = 50) -> list[dict]:
    sql = "SELECT * FROM smart_money_signal"
    params = []
    if chain_index:
        sql += " WHERE chain_index=%s"
        params.append(chain_index)
    sql += f" ORDER BY signal_time DESC LIMIT {limit}"
    with _conn() as con:
        cur = con.cursor(dictionary=True)
        cur.execute(sql, params)
        return cur.fetchall()


# ─────────────────────────────────────────────
# my_address
# ─────────────────────────────────────────────
def get_my_addresses() -> list[dict]:
    with _conn() as con:
        cur = con.cursor(dictionary=True)
        cur.execute("SELECT * FROM my_address WHERE is_active=1 ORDER BY id")
        return cur.fetchall()


def _safe_float(v) -> float:
    try:
        return float(v) if v not in (None, "") else 0.0
    except Exception:
        return 0.0


def _safe_int(v) -> int:
    try:
        return int(v) if v not in (None, "") else 0
    except Exception:
        return 0
