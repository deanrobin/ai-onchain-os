"""
Smart Money Wallet Analyzer
Pulls portfolio overview + recent PnL from OKX, calculates composite score.
"""
import time
import logging
from dataclasses import dataclass, field
from typing import Optional
import okx_client as okx

log = logging.getLogger(__name__)


@dataclass
class WalletMetrics:
    address: str
    chain_index: str
    label: str = ""
    # From OKX portfolio/overview (7D)
    win_rate: float = 0.0
    realized_pnl_usd: float = 0.0
    buy_tx_count: int = 0
    sell_tx_count: int = 0
    avg_buy_value_usd: float = 0.0
    # Top token performance
    top3_pnl_usd: float = 0.0
    tokens_over_500pct: int = 0
    tokens_profit: int = 0
    tokens_loss: int = 0
    # From recent-pnl
    active_positions: int = 0
    total_position_value_usd: float = 0.0
    # Computed
    score: float = 0.0
    raw_overview: dict = field(default_factory=dict)


def analyze_wallet(address: str, chain_index: str,
                   label: str = "", time_frame: str = "3") -> Optional[WalletMetrics]:
    """
    Fetch OKX portfolio data for a wallet and return WalletMetrics.
    time_frame: 3 = 7D (default for smart money scoring)
    """
    m = WalletMetrics(address=address, chain_index=chain_index, label=label)
    try:
        # 1. Portfolio overview
        ov = okx.get_portfolio_overview(chain_index, address, time_frame)
        m.raw_overview = ov
        m.win_rate = _safe_float(ov.get("winRate"))
        m.realized_pnl_usd = _safe_float(ov.get("realizedPnlUsd"))
        m.buy_tx_count = _safe_int(ov.get("buyTxCount"))
        m.sell_tx_count = _safe_int(ov.get("sellTxCount"))
        m.avg_buy_value_usd = _safe_float(ov.get("avgBuyValueUsd"))
        m.top3_pnl_usd = _safe_float(ov.get("top3PnlTokenSumUsd"))
        tc = ov.get("tokenCountByPnlPercent", {})
        m.tokens_over_500pct = _safe_int(tc.get("over500Percent"))
        m.tokens_profit = _safe_int(tc.get("over500Percent")) + _safe_int(tc.get("zeroTo500Percent"))
        m.tokens_loss = _safe_int(tc.get("zeroToMinus50Percent")) + _safe_int(tc.get("overMinus50Percent"))
    except Exception as e:
        log.warning(f"portfolio overview failed for {address[:10]}: {e}")

    time.sleep(0.8)  # rate limit guard

    try:
        # 2. Recent PnL snapshot
        pnl_data = okx.get_portfolio_recent_pnl(chain_index, address, "20")
        pnl_list = pnl_data.get("pnlList", [])
        m.active_positions = sum(
            1 for p in pnl_list if p.get("unrealizedPnlUsd") not in ("SELL_ALL", "", None)
        )
        m.total_position_value_usd = sum(
            _safe_float(p.get("tokenBalanceUsd")) for p in pnl_list
        )
    except Exception as e:
        log.warning(f"recent pnl failed for {address[:10]}: {e}")

    return m


def _safe_float(v) -> float:
    try:
        return float(v) if v not in (None, "", "SELL_ALL") else 0.0
    except (ValueError, TypeError):
        return 0.0


def _safe_int(v) -> int:
    try:
        return int(v) if v not in (None, "") else 0
    except (ValueError, TypeError):
        return 0
