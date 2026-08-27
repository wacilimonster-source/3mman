#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_reco_tags.py — 推荐视频「标签词典」抽取脚本（开发期用）

作用
----
把全量视频标题语料做两轮处理，产出 assets/reco/tag_dictionary.json，随包发布：

  第一轮（领域词挖掘）：统计语料中高频中文 n-gram，挑出 jieba 不认识但语料里
  高内聚的词（演员名、行话、题材组合），作为领域用户词典注入 jieba。
  第二轮（正式分词）：jieba + 领域用户词典重新切分全部标题，统计文档频率 df。

运行时（RecoTagDictionary）不做中文分词模型加载，而是用「词典正向最大匹配」
把标题切成标签：命中词典的词才成为标签；完全未命中的中文片段退化为相邻
二元组（bigram），保证即使词典很小算法也能工作。

词典格式（与 RecoTagDictionary.loadFromAssets 对齐）
---------------------------------------------------
{
  "version": 1,
  "totalDocs": 20000,
  "maxTagLen": 6,
  "tags": { "制服": 3201, "户外": 890, ... }
}

用法
----
  # 全流程：挖领域词 → jieba 分词 → 统计 df → 写词典
  python gen_reco_tags.py --input reco_titles_9mman.txt --version 3

  # 控制参数
  python gen_reco_tags.py --input titles.txt --min-df 5 --max-tags 4000 \\
      --max-len 8 --min-freq 20 --version 3

  # 关闭领域词挖掘（纯 jieba）
  python gen_reco_tags.py --input titles.txt --no-mine
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

# 停用词：无主题区分度的词（分词结果里的整词才会被停用，不影响「无码/有码」这类主题词）
STOP_WORDS = {
    "超清", "高清", "中文字幕", "字幕", "中文", "国语", "粤语", "原版", "完整版", "完整",
    "全集", "合集", "系列", "最新", "更新", "推荐", "首页", "免费", "在线", "观看", "播放",
    "视频", "大片", "简介", "平台", "关注", "私信", "留言", "评论", "点赞", "下载", "破解",
    "版本", "资源", "作品", "女主", "男主", "故事", "剧情", "内容", "介绍", "提取", "密码",
    "里", "区", "吧", "啦", "呀", "呢", "啊", "哦", "嗯", "哈", "嘿",
    "的", "了", "是", "在", "有", "和", "与", "及", "之", "这", "那",
    "你", "我", "她", "他", "们", "么", "不", "没", "很", "真", "太", "最", "更", "还",
    "就", "都", "也", "又", "再", "只", "才", "被", "把", "让", "给", "对", "从", "向",
    "往", "于", "以", "为", "会", "能", "要", "想", "看", "说", "做", "来", "去",
    # 语料里出现的高频虚词 / 切分碎片 / 无题材性通用词
    "原创", "愿意", "趁着", "合作", "集合", "过往", "满身", "开干", "doi",
    "内射筒", "内射过", "脸内", "爆王", "文字", "大骚", "骚", "湿",
}


def is_stop(w):
    if w in STOP_WORDS:
        return True
    if re.fullmatch(r'\d{4}', w):   # 年份
        return True
    if re.fullmatch(r'\d+', w):     # 纯数字
        return True
    return False


try:
    import jieba  # type: ignore
    jieba.setLogLevel(60)  # silence jieba's INFO logs
    JIEBA_AVAILABLE = True
except Exception:
    JIEBA_AVAILABLE = False


# ---------------------------------------------------------------------------
# 第一轮：领域词挖掘（jieba 不认识但语料里高频、高内聚的中文组合）
# ---------------------------------------------------------------------------
def _jieba_known(word):
    """该词是否已在 jieba 主词典中"""
    return jieba.dt.FREQ.get(word, 0) > 0


# 含虚词/代词/常用单字的组合几乎不可能是领域实义词，直接排除
FUNC_CHARS = set('的了吗呢吧呀哦啊么之乎者也被把很太更还又再就都只才让给对'
                 '从向于以为会能要想看说做来去和与及这那不没真最是个有我你'
                 '他她它们们地过得着起来上下里外前后中间左右')


def mine_domain_terms(titles, max_len, min_freq, max_terms, cohesion=15.0,
                      userdict_path=None):
    """统计 CJK n-gram 频次，挑出「高频 + 高内聚 + jieba 不认识」的领域词。

    内聚度 = f(w) * N / max_split(f_left * f_right)
      - N 为语料 CJK 总字数；内聚度远大于 1 说明两部分总是结伴出现，
        是真词而不是碰巧相邻（如「人妻」远高于「人」×「妻」的独立频率积）。
      - 拆分含单字部分（unigram 也计数），因此 2 字词同样受内聚度约束，
        避免「的太」「的小」这类高频垃圾搭配混入。
    返回 {word: freq}，并可选写出 jieba 用户词典文件。
    """
    from collections import Counter

    gram = Counter()
    total_chars = 0
    for t in titles:
        for run in CJK_RE.findall(t):
            total_chars += len(run)
            n = len(run)
            for i in range(n):
                gram[run[i]] += 1                      # unigram：供 2 字词内聚度拆分
                cap = min(max_len, n - i)
                for length in range(2, cap + 1):
                    gram[run[i:i + length]] += 1

    if total_chars == 0:
        return {}

    mined = {}
    for w, c in gram.items():
        if c < min_freq or _jieba_known(w):
            continue
        if any(ch in FUNC_CHARS for ch in w):
            continue
        best = 0
        for i in range(1, len(w)):
            prod = gram.get(w[:i], 0) * gram.get(w[i:], 0)
            if prod > best:
                best = prod
        if best == 0:
            continue
        score = c * float(total_chars) / best
        if score < cohesion:
            continue
        mined[w] = c

    items = sorted(mined.items(), key=lambda kv: (-kv[1], -len(kv[0])))
    if max_terms and len(items) > max_terms:
        items = items[:max_terms]
    result = dict(items)

    if userdict_path:
        try:
            with open(userdict_path, 'w', encoding='utf-8') as f:
                for w, c in items:
                    f.write('%s %d\n' % (w, c))
            print('[gen_reco_tags] 领域用户词典已写出：%s' %
                  os.path.abspath(userdict_path))
        except Exception as e:
            print('[gen_reco_tags] 用户词典写盘失败（不影响主流程）：%s' % e)
    return result


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
    merged = {}
    for w, c in df.items():
        if c < min_df:
            continue
        if is_stop(w):
            continue
        merged[w] = c

    # 种子词只做兜底：语料统计过的用真实 df；语料没出现的，df 按语料规模重标定
    # （cap 到 10% 文档数，避免 totalDocs 很小而种子 df 过大导致 idf 塌缩到下限）
    floor = max(2, int(total * 0.10)) if total > 0 else 0
    for w, c in seed.items():
        if is_stop(w):
            continue
        if w in merged:
            continue  # 语料已统计，用真实 df
        merged[w] = min(c, floor) if floor > 0 else c

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
    parser.add_argument('--min-df', type=int, default=5,
                        help='语料词进入词典的最小文档频率（种子词不受此限）')
    parser.add_argument('--max-tags', type=int, default=4000,
                        help='词典容量上限（按 df 降序截断），0 表示不限制')
    parser.add_argument('--max-len', type=int, default=8,
                        help='单标签最大长度（字符数）')
    parser.add_argument('--version', type=int, default=4, help='词典版本号')
    parser.add_argument('--no-seed', action='store_true', help='不合并内置种子词')
    parser.add_argument('--no-mine', action='store_true',
                        help='关闭领域词挖掘（纯 jieba 分词）')
    parser.add_argument('--min-freq', type=int, default=20,
                        help='领域词准入的最小语料频次')
    parser.add_argument('--max-terms', type=int, default=800,
                        help='领域用户词典容量上限')
    parser.add_argument('--cohesion', type=float, default=15.0,
                        help='领域词内聚度阈值（越大越严格）')
    parser.add_argument('--force-jieba', action='store_true',
                        help='强制使用 jieba（未安装则报错，而不是退化为 n-gram）')
    args = parser.parse_args(argv)

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    use_jieba = JIEBA_AVAILABLE
    if args.force_jieba:
        if not JIEBA_AVAILABLE:
            sys.exit('已指定 --force-jieba 但 jieba 未安装：pip install jieba')
        use_jieba = True

    seed = {} if args.no_seed else dict(SEED_TAGS)

    titles = []
    domain_terms = {}
    if args.input:
        titles = read_corpus(args.input)
        print('[gen_reco_tags] 读取语料 %d 条（分词：%s）'
              % (len(titles), 'jieba' if use_jieba else 'n-gram 退化'))

        # ---- 第一轮：领域词挖掘 → 注入 jieba 用户词典 ----
        if use_jieba and not args.no_mine:
            # 写到 build-tools 目录（开发期产物，不进 APK 的 assets）
            userdict_path = os.path.join(
                os.path.dirname(os.path.abspath(__file__)),
                'reco_userdict.txt')
            domain_terms = mine_domain_terms(titles, args.max_len,
                                             args.min_freq, args.max_terms,
                                             cohesion=args.cohesion,
                                             userdict_path=userdict_path)
            for w, c in domain_terms.items():
                jieba.add_word(w, freq=c)
            print('[gen_reco_tags] 已注入领域词 %d 个'
                  % len(domain_terms))
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
