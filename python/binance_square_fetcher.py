# binance_square_fetcher.py
"""
币安广场帖子采集器 (终极版)

特性:
- 使用时间戳游标 (比 id 游标更稳健)
- 首次运行抓 200 条,后续增量抓取
- SQLite 存储,支持断点续传
- 优雅退出 (Ctrl+C 不会丢数据)
- 详细的运行状态

用法:
    python3 binance_square_fetcher.py              # 单次运行
    python3 binance_square_fetcher.py --loop       # 循环运行(默认每 5 分钟)
    python3 binance_square_fetcher.py --loop --interval 600   # 每 10 分钟
    python3 binance_square_fetcher.py --stats      # 只看数据库状态,不抓取
"""
import json
import os
import sys
import time
import uuid
import signal
import sqlite3
import argparse
from datetime import datetime, timedelta
import requests


# ============ 配置 ============
API_URL = 'https://www.binance.com/bapi/composite/v9/friendly/pgc/feed/feed-recommend/list'
DB_PATH = 'binance_square.db'

PAGE_SIZE = 20
FIRST_RUN_TARGET = 200       # 首次运行抓取 200 条
MAX_PAGES_PER_RUN = 30       # 每次运行最多翻页数
REQUEST_DELAY = 0.8          # 每次请求间隔(秒)
REQUEST_TIMEOUT = 30         # HTTP 超时
RETRIES = 3                  # 失败重试次数
LOOP_INTERVAL = 300          # 循环模式默认间隔(秒)

# 时间戳游标策略: 最新帖子时间戳往前推 N 秒作为安全余量
# 避免两次采集完全相等的时间戳造成边界遗漏
CURSOR_SAFETY_MARGIN = 2


# ============ 全局控制 ============
SHOULD_STOP = False  # 用于优雅退出


def handle_signal(signum, frame):
    global SHOULD_STOP
    if SHOULD_STOP:
        print('\n⚠️  二次 Ctrl+C,强制退出')
        sys.exit(1)
    print('\n\n🛑 收到停止信号,正在完成当前批次后退出...')
    print('   (再按一次 Ctrl+C 强制退出)')
    SHOULD_STOP = True


signal.signal(signal.SIGINT, handle_signal)
signal.signal(signal.SIGTERM, handle_signal)


# ============ 数据库 ============
def init_db():
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    
    cur.execute('''
        CREATE TABLE IF NOT EXISTS posts (
            id TEXT PRIMARY KEY,
            card_type TEXT,
            author_name TEXT,
            author_id TEXT,
            username TEXT,
            title TEXT,
            content TEXT,
            web_link TEXT,
            post_date INTEGER,
            view_count INTEGER,
            like_count INTEGER,
            comment_count INTEGER,
            share_count INTEGER,
            reply_count INTEGER,
            quote_count INTEGER,
            total_reaction_count INTEGER,
            trading_pairs TEXT,
            trading_pairs_v2 TEXT,
            user_input_pairs TEXT,
            coin_pair_list TEXT,
            hashtag_list TEXT,
            raw_json TEXT,
            fetched_at TEXT NOT NULL,
            batch_id TEXT NOT NULL
        )
    ''')
    
    cur.execute('''
        CREATE TABLE IF NOT EXISTS fetch_batches (
            batch_id TEXT PRIMARY KEY,
            started_at TEXT NOT NULL,
            finished_at TEXT,
            pages_fetched INTEGER DEFAULT 0,
            posts_scanned INTEGER DEFAULT 0,
            new_count INTEGER DEFAULT 0,
            duplicate_count INTEGER DEFAULT 0,
            cursor_before INTEGER,
            cursor_after INTEGER,
            hit_cursor INTEGER DEFAULT 0,
            status TEXT,
            error TEXT
        )
    ''')
    
    cur.execute('''
        CREATE TABLE IF NOT EXISTS state (
            key TEXT PRIMARY KEY,
            value TEXT,
            updated_at TEXT
        )
    ''')
    
    # 索引
    cur.execute('CREATE INDEX IF NOT EXISTS idx_posts_date ON posts(post_date)')
    cur.execute('CREATE INDEX IF NOT EXISTS idx_posts_batch ON posts(batch_id)')
    cur.execute('CREATE INDEX IF NOT EXISTS idx_posts_author ON posts(author_name)')
    
    conn.commit()
    return conn


def get_state(conn, key, default=None):
    cur = conn.cursor()
    cur.execute('SELECT value FROM state WHERE key = ?', (key,))
    row = cur.fetchone()
    return row[0] if row else default


def set_state(conn, key, value):
    cur = conn.cursor()
    cur.execute('''
        INSERT INTO state (key, value, updated_at) VALUES (?, ?, ?)
        ON CONFLICT(key) DO UPDATE SET 
            value = excluded.value, 
            updated_at = excluded.updated_at
    ''', (key, str(value), datetime.now().isoformat()))
    conn.commit()


# ============ HTTP 请求 ============
def build_session():
    session = requests.Session()
    session.headers.update({
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                      'AppleWebKit/537.36 (KHTML, like Gecko) '
                      'Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        'clienttype': 'web',
        'lang': 'en',
        'bnc-uuid': str(uuid.uuid4()),
        'bnc-time-zone': 'Asia/Shanghai',
        'versioncode': 'web',
        'csrftoken': 'd41d8cd98f00b204e9800998ecf8427e',
        'referer': 'https://www.binance.com/en/square',
        'origin': 'https://www.binance.com',
    })
    return session


def fetch_page(session, page_index):
    """抓取单页,带重试"""
    payload = {
        "pageIndex": page_index,
        "pageSize": PAGE_SIZE,
        "scene": "web-homepage",
        "contentIds": []
    }
    
    for attempt in range(RETRIES):
        try:
            r = session.post(API_URL, json=payload, timeout=REQUEST_TIMEOUT)
            
            if r.status_code != 200:
                print(f'    ⚠️  HTTP {r.status_code} (第 {attempt+1}/{RETRIES} 次)')
                time.sleep(2 ** attempt)
                continue
            
            data = r.json()
            if data.get('code') != '000000':
                print(f'    ⚠️  业务错误: {data.get("message")}')
                return None
            
            return data.get('data', {}).get('vos', []) or []
        
        except requests.Timeout:
            print(f'    ⚠️  超时 (第 {attempt+1}/{RETRIES} 次)')
            time.sleep(2 ** attempt)
        except requests.ConnectionError as e:
            print(f'    ⚠️  连接错误 (第 {attempt+1}/{RETRIES} 次): {e}')
            time.sleep(2 ** attempt)
        except Exception as e:
            print(f'    ⚠️  异常 (第 {attempt+1}/{RETRIES} 次): {e}')
            time.sleep(2 ** attempt)
    
    return None


# ============ 数据处理 ============
def save_posts(conn, posts, batch_id):
    """批量保存帖子,返回 (new_count, duplicate_count)"""
    cur = conn.cursor()
    fetched_at = datetime.now().isoformat()
    new_count = 0
    dup_count = 0
    
    for post in posts:
        pid = post.get('id')
        if not pid:
            continue
        
        # 检查是否已存在
        cur.execute('SELECT 1 FROM posts WHERE id = ?', (pid,))
        if cur.fetchone():
            dup_count += 1
            continue
        
        try:
            cur.execute('''
                INSERT INTO posts (
                    id, card_type, author_name, author_id, username,
                    title, content, web_link, post_date,
                    view_count, like_count, comment_count, share_count,
                    reply_count, quote_count, total_reaction_count,
                    trading_pairs, trading_pairs_v2, user_input_pairs,
                    coin_pair_list, hashtag_list, raw_json,
                    fetched_at, batch_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 
                          ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (
                pid,
                post.get('cardType'),
                post.get('authorName'),
                post.get('squareAuthorId'),
                post.get('username'),
                post.get('title'),
                post.get('content'),
                post.get('webLink'),
                post.get('date'),
                post.get('viewCount'),
                post.get('likeCount'),
                post.get('commentCount'),
                post.get('shareCount'),
                post.get('replyCount'),
                post.get('quoteCount'),
                post.get('totalReactionCount'),
                json.dumps(post.get('tradingPairs') or [], ensure_ascii=False),
                json.dumps(post.get('tradingPairsV2') or [], ensure_ascii=False),
                json.dumps(post.get('userInputTradingPairs') or [], ensure_ascii=False),
                json.dumps(post.get('coinPairList') or [], ensure_ascii=False),
                json.dumps(post.get('hashtagList') or [], ensure_ascii=False),
                json.dumps(post, ensure_ascii=False, default=str),
                fetched_at,
                batch_id,
            ))
            new_count += 1
        except sqlite3.IntegrityError:
            dup_count += 1
    
    conn.commit()
    return new_count, dup_count


# ============ 核心采集逻辑 ============
def run_once(conn):
    """执行一次采集,返回 (new_count, success)"""
    batch_id = datetime.now().strftime('%Y%m%d_%H%M%S')
    started_at = datetime.now().isoformat()
    
    # 读取游标 (时间戳)
    cursor_str = get_state(conn, 'last_post_timestamp')
    cursor_ts = int(cursor_str) if cursor_str else None
    is_first_run = cursor_ts is None
    
    print(f'\n{"=" * 70}')
    print(f'📥 批次 {batch_id}')
    print(f'{"=" * 70}')
    if is_first_run:
        print(f'🆕 首次运行,目标 {FIRST_RUN_TARGET} 条')
    else:
        cursor_dt = datetime.fromtimestamp(cursor_ts).strftime('%Y-%m-%d %H:%M:%S')
        print(f'🔄 增量采集,游标: {cursor_ts} ({cursor_dt})')
    
    # 记录批次开始
    cur = conn.cursor()
    cur.execute('''
        INSERT INTO fetch_batches 
        (batch_id, started_at, status, cursor_before)
        VALUES (?, ?, 'running', ?)
    ''', (batch_id, started_at, cursor_ts))
    conn.commit()
    
    session = build_session()
    all_new_posts = []
    max_timestamp_seen = 0
    hit_cursor = False
    pages_fetched = 0
    posts_scanned = 0
    
    try:
        for page in range(1, MAX_PAGES_PER_RUN + 1):
            if SHOULD_STOP:
                print('  🛑 收到停止信号,中断采集')
                break
            
            prin
