"""
Smart Money Wallet Scorer
Computes a composite 0-100 score based on OKX portfolio metrics.

Score formula (weighted):
  win_rate        × 35  (胜率)
  pnl_score       × 30  (实现盈亏规模，log-normalized)
  activity_score  × 20  (交易频率 + 规模)
  consistency     × 15  (稳定性：tokens_profit / total_tokens)
"""
import math
import logging
from analyzer import WalletMetrics

log = logging.getLogger(__name__)


def score_wallet(m: WalletMetrics) -> float:
    """Returns score in range [0, 100]."""
    if m.buy_tx_count == 0:
        return 0.0

    # 1. Win rate score (0-35)
    win_score = min(m.win_rate, 1.0) * 35

    # 2. PnL score (0-30) — log scale: $1k→5, $10k→10, $100k→20, $1M→30
    pnl = max(m.realized_pnl_usd, 0)
    if pnl > 0:
        pnl_score = min(math.log10(pnl + 1) / math.log10(1_000_001) * 30, 30)
    else:
        pnl_score = max(0.0, 30 - abs(min(m.realized_pnl_usd, 0)) / 10_000)

    # 3. Activity score (0-20)
    total_tx = m.buy_tx_count + m.sell_tx_count
    # 5-100 txs in 7D is ideal; <5 penalized; >100 slight bonus cap
    activity_score = min(math.log10(total_tx + 1) / math.log10(101) * 20, 20) if total_tx > 0 else 0

    # 4. Consistency score (0-15)
    total_tokens = m.tokens_profit + m.tokens_loss
    if total_tokens > 0:
        consistency_score = (m.tokens_profit / total_tokens) * 15
    else:
        consistency_score = 0.0

    # Bonus: tokens that 5x+
    bonus = min(m.tokens_over_500pct * 1.5, 5)

    raw = win_score + pnl_score + activity_score + consistency_score + bonus
    score = round(min(max(raw, 0), 100), 2)
    log.debug(
        f"{m.address[:10]}| win={win_score:.1f} pnl={pnl_score:.1f} "
        f"act={activity_score:.1f} cons={consistency_score:.1f} bonus={bonus:.1f} → {score}"
    )
    return score


def rank_wallets(metrics_list: list) -> list:
    """Score and sort wallets descending."""
    for m in metrics_list:
        m.score = score_wallet(m)
    return sorted(metrics_list, key=lambda x: x.score, reverse=True)
