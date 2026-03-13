"""
OKX OnChainOS Open API 客户端
文档：https://web3.okx.com/zh-hans/onchainos/dev-docs
"""
import hashlib
import hmac
import base64
import time
import requests
import logging
from datetime import datetime, timezone

import config

log = logging.getLogger(__name__)


def _sign(timestamp: str, method: str, path: str, body: str = "") -> str:
    """OKX API 签名：HMAC-SHA256(timestamp + method + path + body)"""
    msg = timestamp + method.upper() + path + (body or "")
    mac = hmac.new(config.OKX_API_SECRET.encode(), msg.encode(), hashlib.sha256)
    return base64.b64encode(mac.digest()).decode()


def _headers(method: str, path: str, body: str = "") -> dict:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    return {
        "OK-ACCESS-KEY":        config.OKX_API_KEY,
        "OK-ACCESS-SIGN":       _sign(ts, method, path, body),
        "OK-ACCESS-TIMESTAMP":  ts,
        "OK-ACCESS-PASSPHRASE": config.OKX_PASSPHRASE,
        "Content-Type":         "application/json",
    }


def get(path: str, params: dict = None) -> dict:
    url = config.OKX_BASE_URL + path
    try:
        resp = requests.get(url, headers=_headers("GET", path), params=params, timeout=10)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        log.warning("⚠️ OKX API GET 失败 %s: %s", path, e)
        return {}


def post(path: str, body: dict) -> dict:
    import json
    body_str = json.dumps(body)
    url = config.OKX_BASE_URL + path
    try:
        resp = requests.post(url, headers=_headers("POST", path, body_str), data=body_str, timeout=10)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        log.warning("⚠️ OKX API POST 失败 %s: %s", path, e)
        return {}


# ──────────────────────────────────────────
# 聪明钱相关接口
# ──────────────────────────────────────────

def get_wallet_transactions(address: str, chain_index: str, limit: int = 20) -> list:
    """获取钱包最近交易记录"""
    data = get("/api/v5/waas/transaction/get-transaction-list", params={
        "address":    address,
        "chainIndex": chain_index,
        "limit":      limit,
    })
    return data.get("data", {}).get("transactionList", [])


def get_token_price(chain_index: str, token_address: str) -> float:
    """获取代币当前价格（USD）"""
    data = get("/api/v5/waas/market/current-price", params={
        "chainIndex":    chain_index,
        "tokenAddress":  token_address,
    })
    prices = data.get("data", [])
    if prices:
        try:
            return float(prices[0].get("price", 0))
        except (ValueError, TypeError):
            pass
    return 0.0


def get_dex_swap_quote(chain_index: str, from_token: str, to_token: str,
                       amount: str, wallet: str, slippage: str = "0.005") -> dict:
    """获取 DEX 兑换报价（OKX DEX Aggregator）"""
    return get("/api/v5/dex/aggregator/quote", params={
        "chainId":          chain_index,
        "fromTokenAddress": from_token,
        "toTokenAddress":   to_token,
        "amount":           amount,
        "slippage":         slippage,
        "userWalletAddress": wallet,
    })
