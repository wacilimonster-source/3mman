#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_reco_tags.py — 推荐视频「标签词典」抽取脚本（开发期用）

作用
----
把全量视频标题语料做分词 + 统计文档频率（df），产出 assets/reco/tag_dictionary.json，
随包发布。运行时（RecoTagDictionary）不做中文分词模型加载，而是用「词典正向最大匹配」
把标题切成标签：命中词典的词才成为标签；完全未命中的中文片段退化为相邻二元组（bigram），
保证即使词典很小算法也能工作。

词典格式（与 RecoTagDictionary.loadFromAssets 对齐）
---------------------------------------------------
{
  "version": 1,
  "totalDocs": 20000,
  "maxTagLen": 6,
  "tags": { "制服": 3201, "户外": 890, ... }
}

分词策略
--------
1. 优先 jieba（pip install jieba）。能切出真实中文词，词典质量最高。
2. 无 jieba 时退化为 n-gram 候选（2~maxTagLen 的中文片段），再按词频过滤。
   这一步不需要任何第三方依赖，保证脚本在任何环境都能跑。

用法
----
  # 仅用内置种子词（无语料时也能产出可用词典）
  python gen_reco_tags.py

  # 用标题语料生成（.txt 一行一条 / .json 字符串数组或 {"titles":[...]} / .csv 指定列）
  python gen_reco_tags.py --input titles.txt --output ../../app/src/main/assets/reco/tag_dictionary.json

  # 控制参数
  python gen_reco_tags.py --input titles.txt --min-df 3 --max-tags 2000 --version 1
"""

import argparse
import csv
import json
import os
import re
import sys
from collections import Counter

# ----------------------------------------------------------------------------
# 种子词：即使没有任何语料，也能让推荐算法从第一天起就有「主题」可学。
# 这些是成人视频聚合类目里的常见题材/玩法标签；df 大致按常见度编排
# （数值越大代表语料里越常见，idf 越低；越冷门 df 越小，权重越高）。
# ----------------------------------------------------------------------------
SEED_TAGS = {
    # 题材 / 人设
    "制服": 3200, "自拍": 2600, "素人": 2400, "萝莉": 1500, "巨乳": 2900,
    "熟女": 2200, "美少女": 1300, "学生妹": 1800, "教师": 1400, "护士": 1500,
    "秘书": 900, "丝袜": 2700, "高跟": 1100, "翘臀": 1600, "美腿": 1400,
    "人妻": 1700, "邻家": 700, "母女": 600, "姐妹": 800, "情侣": 1200,
    "空姐": 800, "女仆": 700, "动漫": 1000, "cosplay": 950, "直播": 1300,
    "寡妇": 300, "嫂子": 600, "姐姐": 700, "妹妹": 650, "阿姨": 400,
    # 玩法 / 类型
    "口交": 2100, "肛交": 1500, "群交": 900, "潮吹": 1200, "出轨": 1000,
    "偷拍": 1600, "调教": 1100, "捆绑": 900, "角色扮演": 1000, "按摩": 1300,
    "足交": 700, "自慰": 1400, "高潮": 1500, "中出": 1200, "内射": 1700,
    "无码": 1800, "有码": 900, "野战": 800, "车震": 700, "公共": 600,
    "办公室": 900, "教室": 700, "更衣室": 500, "浴室": 800, "卧室": 600,
    "后入": 1000, "骑乘": 800, "口爆": 700,
}

CJK_RE = re.compile(r'[\u3400-\u9fff\uf900-\ufaff]+')
ASCII_RE = re.compile(r'[A-Za-z0-9]{2,}')

try:
    import jieba  # type: ignore
    jieba.setLogLevel(60)  # silence jieba's INFO logs
    JIEBA_AVAILABLE = True
except Exception:
    JIEBA_AVAILABLE = False


# ---------------------------------------------------------------------------
# 语料读取
# ---------------------------------------------------------------------------
def read_corpus(path):
    """返回标题字符串列表。支持 .txt / .json / .csv / 目录。"""
    titles = []
    if os.path.isdir(path):
        for name in sorted(os.listdir(path)):
            if name.lower().endswith(('.txt', '.json')):
                titles.extend(read_corpus(os.path.join(path, name)))
        return titles

    lower = path.lower()
    if lower.endswith('.json'):
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        if isinstance(data, list):
            return [str(x) for x in data if x]
        if isinstance(data, dict):
            for key in ('titles', 'data', 'items', 'videos'):
                if key in data and isinstance(data[key], list):
                    return [str(x) for x in data[key] if x]
        raise ValueError('无法从 JSON 解析标题列表：%s' % path)

    if lower.endswith('.csv'):
        column = os.environ.get('RECO_CSV_COLUMN', 'title')
        with open(path, 'r', encoding='utf-8', newline='') as f:
            reader = csv.DictReader(f)
            if reader.fieldnames and column not in reader.fieldnames:
                column = reader.fieldnames[0]
            for row in reader:
                v = row.get(column)
                if v:
                    titles.append(str(v))
        return titles

    # 默认按纯文本处理：一行一条
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                titles.append(line)
    return titles


# ---------------------------------------------------------------------------
# 分词
# ---------------------------------------------------------------------------
def _ascii_words(text):
    return ASCII_RE.findall(text)


def tokenize_jieba(title):
    out = []
    for piece in jieba.cut(title):
        piece = piece.strip()
        if not piece:
            continue
        # 纯标点 / 空白 / 单字直接丢弃
        if len(piece) == 1:
            continue
        if CJK_RE.fullmatch(piece) or ASCII_RE.fullmatch(piece):
            out.append(piece)
    return out


def tokenize_ngram(title, max_len):
    """无 jieba 时的退化方案：CJK 片段全部 2~max_len 子串 + ASCII 词。"""
    out = []
    for run in CJK_RE.findall(title):
        n = len(run)
        for i in range(n):
            for length in range(2, min(max_len, n - i) + 1):
                out.append(run[i:i + length])
    out.extend(_ascii_words(title))
    return out


def tokens_for(title, max_len, use_jieba):
    if not title:
        return []
    if use_jieba:
        return tokenize_jieba(title)
    return tokenize_ngram(title, max_len)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def build_df(titles, max_len, min_df, max_tags, use_jieba):
    df = Counter()
    total = 0
    for t in titles:
        toks = set(tokens_for(t, max_len, use_jieba))  # 文档内去重后统计 df
        if toks:
            total += 1
        for w in toks:
            df[w] += 1
    return df, total


def merge_and_emit(seed, df, total, min_df, max_tags, version, out_path):
    merged = dict(seed)
    for w, c in df.items():
        if c < min_df:
            continue
        merged[w] = max(merged.get(w, 0), c)

    items = sorted(merged.items(), key=lambda kv: kv[1], reverse=True)
    if max_tags and len(items) > max_tags:
        items = items[:max_tags]

    tags = {w: int(c) for w, c in items}
    max_tag_len = max((len(w) for w in tags.keys()), default=2)
    total_docs = max(total, len(tags), 1)

    payload = {
        "version": version,
        "totalDocs": total_docs,
        "maxTagLen": max(max_tag_len, 2),
        "tags": tags,
    }

    out_dir = os.path.dirname(out_path)
    if out_dir and not os.path.exists(out_dir):
        os.makedirs(out_dir, exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    return payload


def main(argv=None):
    parser = argparse.ArgumentParser(description='生成推荐标签词典 tag_dictionary.json')
    parser.add_argument('--input', '-i', default=None,
                        help='标题语料：.txt(一行一条) / .json(数组或含 titles 字段) / .csv / 目录')
    parser.add_argument('--output', '-o',
                        default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                            '..', 'app', 'src', 'main', 'assets', 'reco',
                                            'tag_dictionary.json'),
                        help='输出路径，默认 build-tools/../app/src/main/assets/reco/tag_dictionary.json')
    parser.add_argument('--min-df', type=int, default=2,
                        help='语料词进入词典的最小文档频率（种子词不受此限）')
    parser.add_argument('--max-tags', type=int, default=2000,
                        help='词典容量上限（按 df 降序截断），0 表示不限制')
    parser.add_argument('--max-len', type=int, default=6,
                        help='单标签最大长度（字符数）')
    parser.add_argument('--version', type=int, default=1, help='词典版本号')
    parser.add_argument('--no-seed', action='store_true', help='不合并内置种子词')
    parser.add_argument('--force-jieba', action='store_true',
                        help='强制使用 jieba（未安装则报错，而不是退化为 n-gram）')
    args = parser.parse_args(argv)

    use_jieba = JIEBA_AVAILABLE
    if args.force_jieba:
        if not JIEBA_AVAILABLE:
            sys.exit('已指定 --force-jieba 但 jieba 未安装：pip install jieba')
        use_jieba = True

    seed = {} if args.no_seed else dict(SEED_TAGS)

    titles = []
    if args.input:
        titles = read_corpus(args.input)
        print('[gen_reco_tags] 读取语料 %d 条（分词：%s）'
              % (len(titles), 'jieba' if use_jieba else 'n-gram 退化'))
    else:
        print('[gen_reco_tags] 未提供语料，仅输出内置种子词（%d 个）' % len(seed))

    df, total = build_df(titles, args.max_len, args.min_df,
                         args.max_tags, use_jieba) if titles else ({}, 0)

    payload = merge_and_emit(seed, df, total, args.min_df, args.max_tags,
                             args.version, args.output)

    print('[gen_reco_tags] 已写出 %s：version=%d totalDocs=%d maxTagLen=%d tags=%d'
          % (os.path.abspath(args.output), payload['version'],
             payload['totalDocs'], payload['maxTagLen'], len(payload['tags'])))


if __name__ == '__main__':
    main()
