"""
Configuration loader — reads from environment or .env file
Sensitive values (API keys, DB password, private key) NEVER hardcoded here
"""
import os
from dotenv import load_dotenv

load_dotenv()

# OKX OnChainOS API
OKX_API_KEY    = os.environ["OKX_API_KEY"]
OKX_API_SECRET = os.environ["OKX_API_SECRET"]
OKX_PASSPHRASE = os.environ["OKX_PASSPHRASE"]
OKX_BASE_URL   = "https://www.okx.com"

# Database
DB_HOST     = os.getenv("DB_HOST", "127.0.0.1")
DB_PORT     = int(os.getenv("DB_PORT", "33066"))
DB_NAME     = os.getenv("DB_NAME", "aios")
DB_USER     = os.getenv("DB_USER", "aios")
DB_PASSWORD = os.environ["DB_PASSWORD"]

# Supported chains: OKX chainIndex values
CHAINS = {
    "ETH":    "1",
    "BSC":    "56",
    "BASE":   "8453",
    "SOLANA": "501",
}

# Smart money scoring thresholds
SMART_MONEY_MIN_WIN_RATE    = 0.55   # 最低胜率 55%
SMART_MONEY_MIN_TRADE_COUNT = 20     # 最低交易次数
SMART_MONEY_MIN_PNL_USD     = 5000   # 最低累计盈利 $5,000
SMART_MONEY_LOOKBACK_DAYS   = 30     # 回溯天数

# Refresh cycle
DISCOVERY_INTERVAL_HOURS = 6        # 每 6 小时重新评分一次
