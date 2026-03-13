"""
OKX OnChainOS API Client
Verified endpoints (2026-03-13):
  - token-detail:             GET  https://www.okx.com/api/v5/wallet/token/token-detail
  - token-balances-by-address:POST https://www.okx.com/api/v5/wallet/asset/token-balances-by-address
  - transactions-by-address:  GET  https://web3.okx.com/api/v6/dex/post-transaction/transactions-by-address
  - dex quote:                GET  https://www.okx.com/api/v6/dex/aggregator/quote
"""
import hashlib
import hmac
import base64
import json
import time as _time
from datetime import datetime, timezone
from typing import Optional
import requests

from config import OKX_API_KEY, OKX_API_SECRET, OKX_PASSPHRASE

BASE_WWW   = "https://www.okx.com"
BASE_WEB3  = "https://web3.okx.com"


def _sign(method: str, path: str, body_str: str = "") -> dict:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    msg = ts + method.upper() + path + body_str
    sig = base64.b64encode(
        hmac.new(OKX_API_SECRET.encode(), msg.encode(), hashlib.sha256).digest()
    ).decode()
    return {
        "OK-ACCESS-KEY": OKX_API_KEY,
        "OK-ACCESS-SIGN": sig,
        "OK-ACCESS-TIMESTAMP": ts,
        "OK-ACCESS-PASSPHRASE": OKX_PASSPHRASE,
        "OK-ACCESS-PROJECT": OKX_API_KEY,   # UUID key = project id
        "Content-Type": "application/json",
    }


def _get(base: str, path: str, params: dict) -> dict:
    from urllib.parse import urlencode
    qs = "?" + urlencode(params)
    headers = _sign("GET", path + qs)
    r = requests.get(base + path + qs, headers=headers, timeout=10)
    r.raise_for_status()
    return r.json()


def _post(base: str, path: str, body: dict) -> dict:
    body_str = json.dumps(body)
    headers = _sign("POST", path, body_str)
    r = requests.post(base + path, headers=headers, data=body_str, timeout=10)
    r.raise_for_status()
    return r.json()


# ─────────────────────────────────────────────
# 1. 代币信息 / 价格
# ─────────────────────────────────────────────
def get_token_detail(chain_index: str, token_address: str) -> dict:
    """
    返回 { symbol, tokenPrice, logoUrl, isRiskToken, ... }
    chain_index: "1"=ETH, "56"=BSC, "8453"=Base, "501"=Solana
    token_address: "0xeee...eee" 表示主链币
    """
    resp = _get(BASE_WWW, "/api/v5/wallet/token/token-detail", {
        "chainIndex": chain_index,
        "tokenAddress": token_address,
    })
    return resp.get("data", [{}])[0]


# ─────────────────────────────────────────────
# 2. 钱包代币余额
# ─────────────────────────────────────────────
def get_token_balances(address: str, tokens: list[dict]) -> list[dict]:
    """
    tokens: [{"chainIndex": "1", "tokenAddress": "0x..."}, ...]
    返回 tokenAssets 列表: { symbol, balance, tokenPrice, chainIndex, ... }
    """
    resp = _post(BASE_WWW, "/api/v5/wallet/asset/token-balances-by-address", {
        "address": address,
        "tokenAddresses": tokens,
    })
    return resp.get("data", [{}])[0].get("tokenAssets", [])


# ─────────────────────────────────────────────
# 3. 地址交易历史
# ─────────────────────────────────────────────
def get_transaction_history(
    address: str,
    chains: str,               # "1" or "1,56,8453"
    token_address: str = "",   # "" = 主链币, 合约地址 = 该代币, 不传 = 全部
    limit: str = "20",
    cursor: str = "",
) -> dict:
    """
    返回 { cursor, transactions: [...] }
    transaction fields: chainIndex, txHash, itype, txTime, from, to,
                        tokenContractAddress, amount, symbol, txStatus, hitBlacklist
    """
    params = {"address": address, "chains": chains, "limit": limit}
    if token_address:
        params["tokenContractAddress"] = token_address
    if cursor:
        params["cursor"] = cursor
    resp = _get(BASE_WEB3, "/api/v6/dex/post-transaction/transactions-by-address", params)
    return resp.get("data", [{}])[0]


# ─────────────────────────────────────────────
# 4. DEX 报价
# ─────────────────────────────────────────────
def get_dex_quote(
    chain_index: str,
    from_token: str,
    to_token: str,
    amount: str,           # 以最小单位表示（wei / lamport）
    slippage: str = "0.005",
) -> dict:
    """
    返回 { toTokenAmount, dexRouterList, estimateGasFee, ... }
    """
    resp = _get(BASE_WWW, "/api/v6/dex/aggregator/quote", {
        "chainIndex": chain_index,
        "fromTokenAddress": from_token,
        "toTokenAddress": to_token,
        "amount": amount,
        "slippage": slippage,
    })
    return resp.get("data", [{}])[0]


# ─────────────────────────────────────────────
# 快速测试
# ─────────────────────────────────────────────
if __name__ == "__main__":
    ETH  = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7"
    TEST = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"

    print("=== token detail ===")
    d = get_token_detail("1", ETH)
    print(f"  ETH price=${d.get('tokenPrice')}")

    _time.sleep(1)
    print("=== token balances ===")
    assets = get_token_balances(TEST, [
        {"chainIndex": "1", "tokenAddress": ETH},
        {"chainIndex": "1", "tokenAddress": USDT},
    ])
    for a in assets:
        print(f"  {a['symbol']}: {a['balance']} @ ${a['tokenPrice']}")

    _time.sleep(1)
    print("=== tx history ===")
    data = get_transaction_history(TEST, "1", limit="3")
    for tx in data.get("transactions", []):
        print(f"  {tx['symbol']} {tx['amount']} [{tx['txStatus']}] {tx['txHash'][:20]}...")

    _time.sleep(1)
    print("=== dex quote ===")
    q = get_dex_quote("1", ETH, USDT, "1000000000000000000")
    route = q.get("dexRouterList", [{}])[0].get("dexProtocol", {}).get("dexName", "?")
    print(f"  1 ETH → {int(q.get('toTokenAmount','0'))//10**6} USDT  路由={route}")
