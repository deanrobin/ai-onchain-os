"""
聪明钱发现主入口
运行方式：python3 main.py
"""
import logging
import schedule
import time

import config
import okx_client
from scorer import score_wallet, is_smart_money

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger(__name__)


def discover_smart_money():
    """定时任务：遍历各链，发现聪明钱地址，写入 DB"""
    log.info("🔍 [SmartMoney] 开始聪明钱发现...")
    for chain_name, chain_index in config.CHAINS.items():
        log.info("  ⛓️  处理链: %s (chainIndex=%s)", chain_name, chain_index)
        # TODO:
        # 1. 从 OKX OnChainOS 拉取候选地址（大额交易发起者 / token top buyers 等）
        # 2. 对每个地址拉取历史交易
        # 3. score_wallet() 评分
        # 4. is_smart_money() 筛选
        # 5. 写入 DB smart_money_wallet 表
    log.info("✅ [SmartMoney] 本轮发现完成")


if __name__ == "__main__":
    log.info("🚀 ai-onchain-os smart-money 启动")
    discover_smart_money()

    schedule.every(config.DISCOVERY_INTERVAL_HOURS).hours.do(discover_smart_money)
    while True:
        schedule.run_pending()
        time.sleep(60)
