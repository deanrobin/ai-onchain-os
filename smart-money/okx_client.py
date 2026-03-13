"""
OKX OnChainOS API Client
Verified endpoints (2026-03-13):
  Base www : https://www.okx.com
  Base web3: https://web3.okx.com

  token-detail            GET  www  /api/v5/wallet/token/token-detail
  token-balances          POST www  /api/v5/wallet/asset/token-balances-by-address
  tx-history              GET  web3 /api/v6/dex/post-transaction/transactions-by-address
  dex-quote               GET  www  /api/v6/dex/aggregator/quote
  signal-list             POST web3 /api/v6/dex/market/signal/list
  portfolio-overview      GET  web3 /api/v6/dex/market/portfolio/overview
  portfolio-recent-pnl    GET  web3 /api/v6/dex/market/portfolio/recent-pnl
"""
import hashlib, hmac, base64, json, time as _time
from datetime import datetime, timezone
from typing import Optional
import requests
from config import OKX_API_KEY, OKX_API_SECRET, OKX_PASSPHRASE

BASE_WWW  = "https://www.okx.com"
BASE_WEB3 = "https://web3.okx.com"


def _sign(method: str, path_with_qs: str, body_str: str = "") -> dict:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    msg = ts + method.upper() + path_with_qs + body_str
    sig = base64.b64encode(
        hmac.new(OKX_API_SECRET.encode(), msg.encode(), hashlib.sha256).digest()
    ).decode()
    return {
        "OK-ACCESS-KEY": OKX_API_KEY,
        "OK-ACCESS-SIGN": sig,
        "OK-ACCESS-TIMESTAMP": ts,
        "OK-ACCESS-PASSPHRASE": OKX_PASSPHRASE,
        "OK-ACCESS-PROJECT": OKX_API_KEY,
        "Content-Type": "application/json",
    }


def _get(base: str, path: str, params: dict) -> dict:
    from urllib.parse import urlencode
    qs = "?" + urlencode(params)
    hdrs = _sign("GET", path + qs)
    r = requests.get(base + path + qs, headers=hdrs, timeout=15)
    r.raise_for_status()
    data = r.json()
    if data.get("code") not in ("0", 0):
        raise ValueError(f"OKX API error {data.get('code')}: {data.get('msg')}")
    return data


def _post(base: str, path: str, body: dict) -> dict:
    body_str = json.dumps(body)
    hdrs = _sign("POST", path, body_str)
    r = requests.post(base + path, headers=hdrs, data=body_str, timeout=15)
    r.raise_for_status()
    data = r.json()
    if data.get("code") not in ("0", 0):
        raise ValueError(f"OKX API error {data.get('code')}: {data.get('msg')}")
    return data


# ─────────────────────────────────────────────
# 代币信息 / 价格
# ─────────────────────────────────────────────
def get_token_detail(chain_index: str, token_address: str) -> dict:
    """返回 { symbol, tokenPrice, logoUrl, isRiskToken, ... }"""
    resp = _get(BASE_WWW, "/api/v5/wallet/token/token-detail",
                {"chainIndex": chain_index, "tokenAddress": token_address})
    return resp.get("data", [{}])[0]


# ─────────────────────────────────────────────
# 钱包代币余额
# ─────────────────────────────────────────────
def get_token_balances(address: str, tokens: list[dict]) -> list[dict]:
    """
    tokens: [{"chainIndex": "1", "tokenAddress": "0x..."}]
    返回 tokenAssets: [{ symbol, balance, tokenPrice, chainIndex }]
    """
    resp = _post(BASE_WWW, "/api/v5/wallet/asset/token-balances-by-address", {
        "address": address,
        "tokenAddresses": tokens,
    })
    return resp.get("data", [{}])[0].get("tokenAssets", [])


# ─────────────────────────────────────────────
# 地址交易历史
# ─────────────────────────────────────────────
def get_transaction_history(
    address: str,
    chains: str,
    token_address: str = "",
    limit: str = "20",
    cursor: str = "",
) -> dict:
    """返回 { cursor, transactions: [...] }"""
    params = {"address": address, "chains": chains, "limit": limit}
    if token_address:
        params["tokenContractAddress"] = token_address
    if cursor:
        params["cursor"] = cursor
    resp = _get(BASE_WEB3, "/api/v6/dex/post-transaction/transactions-by-address", params)
    return resp.get("data", [{}])[0]


# ─────────────────────────────────────────────
# DEX 报价
# ─────────────────────────────────────────────
def get_dex_quote(chain_index: str, from_token: str, to_token: str,
                  amount: str, slippage: str = "0.005") -> dict:
    resp = _get(BASE_WWW, "/api/v6/dex/aggregator/quote", {
        "chainIndex": chain_index, "fromTokenAddress": from_token,
        "toTokenAddress": to_token, "amount": amount, "slippage": slippage,
    })
    return resp.get("data", [{}])[0]


# ─────────────────────────────────────────────
# 信号 API — Smart Money 最新买入信号
# ─────────────────────────────────────────────
def get_smart_money_signals(
    chain_index: str,
    wallet_type: str = "1",          # 1=Smart Money, 2=KOL, 3=Whales
    min_amount_usd: str = "10000",
    min_address_count: str = "2",
    limit: int = 20,
) -> list[dict]:
    """
    返回信号列表，每条含:
      timestamp, token{address,symbol,marketCapUsd},
      price, walletType, triggerWalletCount, triggerWalletAddress,
      amountUsd, soldRatioPercent
    """
    body: dict = {
        "chainIndex": chain_index,
        "walletType": wallet_type,
        "minAmountUsd": min_amount_usd,
        "minAddressCount": min_address_count,
    }
    resp = _post(BASE_WEB3, "/api/v6/dex/market/signal/list", body)
    signals = resp.get("data", [])
    return signals[:limit]


# ─────────────────────────────────────────────
# 持仓分析 — 地址画像概览
# ─────────────────────────────────────────────
def get_portfolio_overview(chain_index: str, wallet_address: str,
                           time_frame: str = "3") -> dict:
    """
    time_frame: 1=1D 2=3D 3=7D 4=1M 5=3M
    返回: realizedPnlUsd, winRate, buyTxCount, sellTxCount,
          top3PnlTokenList, tokenCountByPnlPercent, ...
    """
    resp = _get(BASE_WEB3, "/api/v6/dex/market/portfolio/overview", {
        "chainIndex": chain_index,
        "walletAddress": wallet_address,
        "timeFrame": time_frame,
    })
    return resp.get("data", {})


# ─────────────────────────────────────────────
# 持仓分析 — 近期收益列表（逐代币 PnL）
# ─────────────────────────────────────────────
def get_portfolio_recent_pnl(
    chain_index: str,
    wallet_address: str,
    limit: str = "50",
    cursor: str = "",
) -> dict:
    """
    返回: { cursor, pnlList: [{ tokenSymbol, unrealizedPnlUsd, realizedPnlUsd,
             totalPnlUsd, tokenBalanceUsd, buyAvgPrice, sellAvgPrice, ... }] }
    """
    params = {
        "chainIndex": chain_index,
        "walletAddress": wallet_address,
        "limit": limit,
    }
    if cursor:
        params["cursor"] = cursor
    resp = _get(BASE_WEB3, "/api/v6/dex/market/portfolio/recent-pnl", params)
    return resp.get("data", {})


# ─────────────────────────────────────────────
# 快速测试
# ─────────────────────────────────────────────
if __name__ == "__main__":
    ETH  = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7"
    TEST = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"

    print("=== 1. token detail ===")
    d = get_token_detail("1", ETH)
    print(f"  ETH: price=${d.get('tokenPrice')}")

    _time.sleep(1)
    print("=== 2. portfolio overview (Vitalik 7D) ===")
    ov = get_portfolio_overview("1", TEST, "3")
    print(f"  winRate={ov.get('winRate')} realizedPnl=${ov.get('realizedPnlUsd')}")
    print(f"  buy={ov.get('buyTxCount')} sell={ov.get('sellTxCount')}")

    _time.sleep(1)
    print("=== 3. recent PnL ===")
    pnl = get_portfolio_recent_pnl("1", TEST, "5")
    for p in pnl.get("pnlList", []):
        print(f"  {p.get('tokenSymbol')}: total={p.get('totalPnlUsd')} unrealized={p.get('unrealizedPnlUsd')}")

    _time.sleep(1)
    print("=== 4. smart money signals (ETH) ===")
    sigs = get_smart_money_signals("1", wallet_type="1", min_amount_usd="5000", min_address_count="1")
    for s in sigs[:3]:
        tk = s.get("token", {})
        print(f"  {tk.get('symbol')} ${s.get('amountUsd')} wallets={s.get('triggerWalletCount')} @${s.get('price')}")
