"""
Smart Money Discovery & Analysis Pipeline

Usage:
  python main.py signals   — poll new signals + extract wallet addresses
  python main.py analyze   — analyze all tracked wallets (portfolio overview)
  python main.py all       — signals → analyze → rank
"""
import sys
import time
import logging
import okx_client as okx
import analyzer
import scorer
import db

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
log = logging.getLogger("main")

CHAINS = ["1", "56", "8453"]  # ETH, BSC, Base
SIGNAL_MIN_USD = "5000"
SIGNAL_MIN_WALLETS = "1"


def run_signals():
    """Fetch latest smart money signals, save to DB, extract wallet addresses."""
    log.info("=== Step 1: Fetching Smart Money Signals ===")
    new_wallets: set[tuple] = set()

    for chain in CHAINS:
        log.info(f"  chain={chain}")
        try:
            sigs = okx.get_smart_money_signals(
                chain, wallet_type="1",
                min_amount_usd=SIGNAL_MIN_USD,
                min_address_count=SIGNAL_MIN_WALLETS,
                limit=20,
            )
            log.info(f"  → {len(sigs)} signals")
            for sig in sigs:
                db.save_signal(sig)
                # Extract wallet addresses from signal
                addrs = sig.get("triggerWalletAddress", "")
                for addr in addrs.split(","):
                    addr = addr.strip().lower()
                    if addr and len(addr) >= 40:
                        new_wallets.add((addr, chain))
            time.sleep(1)
        except Exception as e:
            log.error(f"  signals failed chain={chain}: {e}")

    log.info(f"Discovered {len(new_wallets)} unique wallets from signals")
    return new_wallets


def run_analyze(wallet_addresses: set = None):
    """Fetch portfolio overview for wallets, compute score, save to DB."""
    log.info("=== Step 2: Analyzing Wallets ===")

    # Combine: newly discovered + already in DB
    if wallet_addresses:
        targets = list(wallet_addresses)
    else:
        existing = db.get_all_wallets()
        targets = [(w["address"], w["chain_index"]) for w in existing]

    if not targets:
        log.warning("No wallets to analyze.")
        return []

    metrics_list = []
    for i, (addr, chain) in enumerate(targets):
        log.info(f"  [{i+1}/{len(targets)}] {addr[:12]}... chain={chain}")
        try:
            m = analyzer.analyze_wallet(addr, chain)
            if m and m.buy_tx_count > 0:
                metrics_list.append(m)
        except Exception as e:
            log.error(f"  analyze failed: {e}")
        time.sleep(1)  # rate limit

    # Score and rank
    ranked = scorer.rank_wallets(metrics_list)

    # Save to DB
    for m in ranked:
        try:
            db.upsert_wallet(m)
        except Exception as e:
            log.error(f"  DB upsert failed {m.address[:10]}: {e}")

    log.info(f"Analyzed {len(ranked)} wallets")
    if ranked:
        log.info("Top 5:")
        for m in ranked[:5]:
            log.info(
                f"  #{ranked.index(m)+1} {m.address[:12]}... "
                f"score={m.score} win={m.win_rate:.1%} pnl=${m.realized_pnl_usd:.0f}"
            )
    return ranked


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"

    if cmd == "signals":
        run_signals()
    elif cmd == "analyze":
        run_analyze()
    elif cmd == "all":
        wallets = run_signals()
        run_analyze(wallets)
    else:
        print(f"Unknown command: {cmd}")
        print("Usage: python main.py [signals|analyze|all]")
        sys.exit(1)


if __name__ == "__main__":
    main()
