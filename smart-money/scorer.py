"""
聪明钱评分模块
输入：钱包交易历史
输出：SmartMoneyScore（胜率/PnL/综合评分）
"""
import logging
from dataclasses import dataclass, field
from typing import List

log = logging.getLogger(__name__)


@dataclass
class WalletScore:
    address: str
    chain: str
    trade_count: int = 0
    win_count: int = 0
    total_pnl_usd: float = 0.0
    avg_hold_hours: float = 0.0
    score: float = 0.0          # 0~100 综合评分

    @property
    def win_rate(self) -> float:
        return self.win_count / self.trade_count if self.trade_count > 0 else 0.0


def score_wallet(address: str, chain: str, transactions: list) -> WalletScore:
    """
    根据交易历史计算聪明钱评分
    TODO: 解析 OKX 交易记录，计算 PnL
    """
    ws = WalletScore(address=address, chain=chain)
    # TODO: 实现交易解析逻辑
    # - 识别 DEX swap 交易
    # - 配对买入/卖出计算 PnL
    # - 统计胜率
    log.debug("📊 [Scorer] @%s (%s) 评分计算中（待实现）", address[:8], chain)
    return ws


def is_smart_money(ws: WalletScore) -> bool:
    """判断是否满足聪明钱标准"""
    import config
    return (
        ws.trade_count >= config.SMART_MONEY_MIN_TRADE_COUNT
        and ws.win_rate >= config.SMART_MONEY_MIN_WIN_RATE
        and ws.total_pnl_usd >= config.SMART_MONEY_MIN_PNL_USD
    )
