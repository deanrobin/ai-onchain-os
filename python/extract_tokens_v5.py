# extract_tokens_v5.py
"""
币安广场代币热度分析 v5
相比 v4 的调整:
- 保留单字母代币 (如 $A, $W 等币安真实代币)
- 仍然过滤 $ 后跟数字的情况 ($19, $500 等价格)
- 仍然不从 # 和 hashtagList 提取代币
设计原则: 宁可错不可漏,不在币安的交给你后续自己验证
"""
import sqlite3
import json
import re
import argparse
import requests
import os
from datetime import datetime, timedelta
from collections import defaultdict


DB_PATH = 'binance_square.db'
WHITELIST_CACHE = 'binance_symbols.json'


def load_binance_whitelist(force_refresh=False):
    """拉币安代币列表,仅用于标记,不过滤"""
    if not force_refresh and os.path.exists(WHITELIST_CACHE):
        age_hours = (datetime.now().timestamp() - os.path.getmtime(WHITELIST_CACHE)) / 3600
        if age_hours < 24:
            with open(WHITELIST_CACHE, 'r') as f:
                return set(json.load(f))
    
    print('🌐 拉取币安代币列表(仅用于标记,不过滤)...')
    try:
        r = requests.get('https://api.binance.com/api/v3/exchangeInfo', timeout=30)
        base_assets = {s['baseAsset'] for s in r.json().get('symbols', [])
                       if s.get('status') == 'TRADING' and s.get('baseAsset')}
        with open(WHITELIST_CACHE, 'w') as f:
            json.dump(sorted(base_assets), f)
        print(f'✅ 币安上架代币 {len(base_assets)} 个')
        return base_assets
    except Exception as e:
        print(f'⚠️  拉取失败: {e}')
        return set()


# ============ 正则 v5 ============
# $ 后面必须是字母或中日韩字符 (不能是数字,避免匹配价格)
# 长度 1-15 (允许单字母代币)
# 后续可以是字母/数字/CJK
TOKEN_PATTERN = re.compile(
    r'\$([A-Z\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]'             # 首字符必须字母/CJK
    r'[A-Z0-9\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]{0,14})'        # 后续 0-14 字符
)

STOP_WORDS = {
    'USD', 'USDT', 'USDC', 'EUR', 'JPY', 'CNY',  # 稳定币/法币
    'CZ',  # 人名
}


def extract_from_content(content):
    """从正文提取,返回 set"""
    if not content:
        return set()
    matches = TOKEN_PATTERN.findall(content)
    normalized = set()
    for m in matches:
        try:
            m.encode('ascii')
            n = m.upper()
        except UnicodeEncodeError:
            n = m  # 含中文等,保持原样
        if n not in STOP_WORDS:
            normalized.add(n)
    return normalized


def extract_from_fields(post):
    """从结构化字段提取(作者主动标注的代币)"""
    result = set()
    for field in ['tradingPairs', 'tradingPairsV2', 'userInputTradingPairs',
                  'coinPairList']:
        pairs = post.get(field) or []
        for pair in pairs:
            if isinstance(pair, dict):
                # 优先 code
                code = pair.get('code')
                if code and isinstance(code, str):
                    result.add(code.strip())
                    continue
                # 次选 baseAsset
                base = pair.get('baseAsset')
                if base and isinstance(base, str):
                    result.add(base.strip())
                    continue
                # 兜底 symbol,去稳定币后缀
                symbol = pair.get('symbol')
                if symbol and isinstance(symbol, str):
                    val = symbol.strip()
                    for suffix in ['USDT', 'BUSD', 'FDUSD', 'USDC']:
                        if val.endswith(suffix) and len(val) > len(suffix):
                            val = val[:-len(suffix)]
                            break
                    result.add(val)
    return {t for t in result if t not in STOP_WORDS}


def extract_all_tokens(post):
    from_content = extract_from_content(post.get('content'))
    from_fields = extract_from_fields(post)
    return {
        'from_content': from_content,
        'from_fields': from_fields,
        'all': from_content | from_fields,
    }


def analyze(hours=None, min_score=0):
    whitelist = load_binance_whitelist()
    
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    
    if hours:
        cutoff = int((datetime.now() - timedelta(hours=hours)).timestamp())
        cur.execute('SELECT raw_json FROM posts WHERE post_date >= ?', (cutoff,))
        print(f'\n📅 分析最近 {hours} 小时的帖子')
    else:
        cur.execute('SELECT raw_json FROM posts')
        print(f'\n📅 分析数据库中全部帖子')
    
    rows = cur.fetchall()
    print(f'📝 共 {len(rows)} 条帖子\n')
    if not rows:
        return
    
    token_data = defaultdict(lambda: {
        'score': 0, 'likes_total': 0, 'comments_total': 0,
        'post_count': 0, 'in_content_count': 0, 'in_fields_count': 0,
        'posts': [],
    })
    posts_with_tokens = 0
    
    for (raw_json,) in rows:
        try:
            post = json.loads(raw_json)
        except:
            continue
        
        ex = extract_all_tokens(post)
        if not ex['all']:
            continue
        posts_with_tokens += 1
        
        likes = post.get('likeCount') or 0
        comments = post.get('commentCount') or 0
        score = likes + comments
        
        for token in ex['all']:
            d = token_data[token]
            d['score'] += score
            d['likes_total'] += likes
            d['comments_total'] += comments
            d['post_count'] += 1
            if token in ex['from_content']:
                d['in_content_count'] += 1
            if token in ex['from_fields']:
                d['in_fields_count'] += 1
            d['posts'].append({
                'id': post.get('id'),
                'author': post.get('authorName'),
                'likes': likes,
                'comments': comments,
                'score': score,
                'in_content': token in ex['from_content'],
                'in_fields': token in ex['from_fields'],
                'content_preview': (post.get('content') or '')[:120],
                'date': post.get('date'),
            })
    
    conn.close()
    
    ranked = sorted(token_data.items(), key=lambda x: -x[1]['score'])
    if min_score > 0:
        ranked = [(t, d) for t, d in ranked if d['score'] >= min_score]
    
    print('=' * 80)
    print('📊 币安广场代币热度报告 v5')
    print(f'⏰ {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}')
    print(f'🎯 评分规则: 点赞 + 评论')
    print(f'📐 提取规则: $开头 + 首字符字母/中日韩 (保留单字母代币)')
    print('=' * 80)
    
    print(f'\n📈 总览:')
    print(f'  总帖子数:          {len(rows)}')
    print(f'  含代币的帖子:      {posts_with_tokens} ({posts_with_tokens/len(rows)*100:.1f}%)')
    print(f'  发现代币种类:      {len(token_data)}')
    print(f'  其中在币安上架:    {sum(1 for t in token_data if t in whitelist)}')
    print(f'  不在币安(待验证):  {sum(1 for t in token_data if t not in whitelist)}')
    
    print(f'\n🔥 代币热度排行 (按 点赞+评论)')
    print('-' * 80)
    print(f'  {"#":<3} {"代币":<14} {"分数":<6} {"赞":<6} {"评":<5} {"帖":<4} {"正文":<4} {"标注":<4} {"上架"}')
    print('-' * 80)
    for i, (token, d) in enumerate(ranked[:40], 1):
        in_binance = '✅' if token in whitelist else '❓'
        token_display = token if len(token) <= 14 else token[:13] + '…'
        print(f'  {i:<3} {token_display:<14} {d["score"]:<6} {d["likes_total"]:<6} '
              f'{d["comments_total"]:<5} {d["post_count"]:<4} '
              f'{d["in_content_count"]:<4} {d["in_fields_count"]:<4} {in_binance}')
    
    suspicious = [(t, d) for t, d in ranked if t not in whitelist]
    if suspicious:
        print(f'\n❓ 疑似代币列表 (不在币安白名单,请自行验证):')
        print('-' * 80)
        for token, d in suspicious[:30]:
            sample = max(d['posts'], key=lambda p: p['score']) if d['posts'] else None
            print(f'  {token:<15} 分数 {d["score"]:<5} (正文 {d["in_content_count"]}, 标注 {d["in_fields_count"]})')
            if sample:
                src = '正文' if sample['in_content'] else '标注'
                print(f'    来源:{src} | 作者:{sample["author"]}')
                print(f'    样本:"{sample["content_preview"]}..."')
    
    print(f'\n📰 TOP 5 代币的帖子详情')
    print('=' * 80)
    for token, d in ranked[:5]:
        posts = sorted(d['posts'], key=lambda p: -p['score'])[:3]
        flag = '✅币安' if token in whitelist else '❓待验证'
        print(f'\n  🪙 {token}  [{flag}]  分数 {d["score"]} = 👍{d["likes_total"]} + 💬{d["comments_total"]}')
        for p in posts:
            dt = datetime.fromtimestamp(p["date"]).strftime('%m-%d %H:%M') if p["date"] else '?'
            src_marks = []
            if p['in_content']: src_marks.append('正文')
            if p['in_fields']: src_marks.append('标注')
            src = '+'.join(src_marks)
            print(f'    [{dt}] [{src}] {p["author"]} 👍{p["likes"]} 💬{p["comments"]}')
            print(f'           "{p["content_preview"]}..."')
    
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    out_file = f'analysis_v5_{timestamp}.json'
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump({
            'timestamp': datetime.now().isoformat(),
            'total_posts': len(rows),
            'posts_with_tokens': posts_with_tokens,
            'ranked': [
                {'token': t, 'in_binance': t in whitelist,
                 **{k: v for k, v in d.items() if k != 'posts'},
                 'sample_posts': d['posts'][:5]}
                for t, d in ranked
            ],
        }, f, ensure_ascii=False, indent=2, default=str)
    
    print(f'\n{"=" * 80}')
    print(f'💾 已保存: {out_file}')
    print('=' * 80)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--hours', type=int, default=None)
    parser.add_argument('--min-score', type=int, default=0)
    parser.add_argument('--refresh', action='store_true')
    args = parser.parse_args()
    
    if args.refresh:
        load_binance_whitelist(force_refresh=True)
    
    analyze(hours=args.hours, min_score=args.min_score)
