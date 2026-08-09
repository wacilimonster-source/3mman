#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
reco_algorithm_test.py — 推荐算法「纯逻辑」自测（开发期用）

这不是 Java 代码的替代品，而是把 RecoScorer / RecoProfile / RecoTagDictionary 里的
纯数学（idf、打分合成、反馈写回、时间衰减）忠实地用 Python 重写一篇，
对算法的「行为正确性」做断言式自测：

  1. idf 单调性：越冷门(df 越小) idf 越高；且结果落在 [0.5, 8.0]。
  2. 点赞使同类标签候选分数上升，且只影响命中的标签，不影响无关候选。
  3. 不喜欢（降权）使同类候选分数下降，但不为负无穷、不永久屏蔽。
  4. 冷启动（空画像）下，未给噪声时两个不同标签的候选分数仅由「新鲜度」决定，
     排序稳定、不会因空画像而崩溃。
  5. 时间衰减：半衰期后权重按 0.5^(days/decayDays) 缩小；不足半天不衰减。
  6. 作者召回：已知作者权重为正时贡献 authorScore，且 FROM_AUTHOR 额外 +0.8 加成。

跑法：
  python reco_algorithm_test.py
退出码 0 = 全部通过；非 0 = 有失败。
"""

import math
import sys

# ---- 常量（与 RecoParams 默认值、RecoTagDictionary、RecoProfile 对齐）----
LIKE_BOOST = 3.0
DISLIKE_PENALTY = 5.0
FAVORITE_BOOST = 4.0
TAG_COEF = 1.0
CATEGORY_COEF = 0.6
AUTHOR_COEF = 1.2
RECENCY_BIAS = 1.0
FINISH_BOOST = 1.0
SKIP_PENALTY = 0.8
EXPLORATION_RATE = 0.15

FALLBACK_DF = 50
DEFAULT_TOTAL_DOCS = 20000

WEIGHT_MAX = 30.0
WEIGHT_MIN = -30.0

FROM_CATEGORY = 0
FROM_AUTHOR = 1
FROM_EXPLORE = 2

DAY_MS = 24 * 3600 * 1000


# ---------------------------------------------------------------------------
# idf：与 RecoTagDictionary.idf 完全一致
# ---------------------------------------------------------------------------
def idf(tag, tag_df, total_docs=DEFAULT_TOTAL_DOCS):
    d = tag_df.get(tag, FALLBACK_DF)
    v = math.log((total_docs + 1.0) / (d + 1.0)) + 1.0
    if v < 0.5:
        return 0.5
    if v > 8.0:
        return 8.0
    return v


class Profile:
    def __init__(self):
        self.tagWeights = {}
        self.categoryWeights = {}
        self.authorWeights = {}
        self.lastDecayTime = 0

    def tag(self, k):
        return self.tagWeights.get(k, 0.0)

    def category(self, k):
        return self.categoryWeights.get(k, 0.0)

    def author(self, k):
        return self.authorWeights.get(k, 0.0)

    def _bump(self, table, k, delta, capacity):
        if not k or delta == 0.0 or math.isnan(delta):
            return
        nxt = table.get(k, 0.0) + delta
        nxt = max(WEIGHT_MIN, min(WEIGHT_MAX, nxt))
        table[k] = nxt

    def bumpTag(self, k, d):
        self._bump(self.tagWeights, k, d, 600)

    def bumpCategory(self, k, d):
        self._bump(self.categoryWeights, k, d, 10 ** 9)

    def bumpAuthor(self, k, d):
        self._bump(self.authorWeights, k, d, 300)

    def applyDecay(self, now, decay_days):
        if self.lastDecayTime <= 0 or decay_days <= 0:
            self.lastDecayTime = now
            return
        elapsed = now - self.lastDecayTime
        if elapsed <= 0:
            return
        days = elapsed / DAY_MS
        if days < 0.5:
            return
        factor = 0.5 ** (days / decay_days)
        for m in (self.tagWeights, self.authorWeights, self.categoryWeights):
            for k in list(m.keys()):
                m[k] *= factor
                if abs(m[k]) < 0.02:
                    del m[k]
        self.lastDecayTime = now


class Candidate:
    def __init__(self, tags, category, frm, author_key=""):
        self.tags = tags
        self.categoryValue = category
        self.from_ = frm
        self.authorKey = author_key
        self.tagScore = self.categoryScore = self.authorScore = 0.0
        self.recencyScore = self.noise = self.score = 0.0


def compute_tag_score(tags, profile, tag_df):
    if not tags:
        return 0.0
    s = 0.0
    for t in tags:
        w = profile.tag(t)
        if w == 0.0:
            continue
        s += w * idf(t, tag_df)
    return s / math.sqrt(len(tags))


def score(cand, profile, tag_df, pos, size, noise=0.0):
    cand.tagScore = compute_tag_score(cand.tags, profile, tag_df) * TAG_COEF
    cand.categoryScore = profile.category(cand.categoryValue) * CATEGORY_COEF
    cand.authorScore = 0.0 if not cand.authorKey else profile.author(cand.authorKey) * AUTHOR_COEF
    cand.recencyScore = (1.0 if size <= 1 else (1.0 - pos / (size - 1))) * RECENCY_BIAS
    cand.noise = noise
    bonus = 0.8 if cand.from_ == FROM_AUTHOR else 0.0
    cand.score = (cand.tagScore + cand.categoryScore + cand.authorScore
                  + cand.recencyScore + bonus + cand.noise)
    return cand.score


def apply_feedback(profile, tags, cat, author_key, delta, tag_df):
    if delta == 0.0:
        return
    if tags:
        per = delta / math.sqrt(len(tags))
        for t in tags:
            scaled = per * (idf(t, tag_df) / 3.0)
            profile.bumpTag(t, scaled)
    if cat:
        profile.bumpCategory(cat, delta * 0.35)
    if author_key:
        profile.bumpAuthor(author_key, delta * 0.8)


# ---------------------------------------------------------------------------
# 测试框架
# ---------------------------------------------------------------------------
PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  PASS  %s" % name)
    else:
        FAIL += 1
        print("  FAIL  %s  %s" % (name, detail))


def main():
    tag_df = {
        "制服": 3200, "素人": 2400, "萝莉": 1500, "巨乳": 2900,
        "口交": 2100, "学生妹": 1800, "无码": 1800, "稀有题材X": 12,
    }

    print("[1] idf 单调性 / 上下限")
    # 越冷门(df 越小) idf 越高
    idf_common = idf("制服", tag_df)
    idf_rare = idf("稀有题材X", tag_df)
    check("稀有题材 idf > 常见题材 idf", idf_rare > idf_common,
          "rare=%.3f common=%.3f" % (idf_rare, idf_common))
    # 完全未命中词典 → FALLBACK_DF=50 的 idf 应落在 [0.5,8]
    v = idf("完全没出现过的词", tag_df)
    check("未命中词 idf 在 [0.5,8.0]", 0.5 <= v <= 8.0, "v=%.3f" % v)

    print("[2] 点赞使同类候选分数上升，且只影响命中标签")
    p = Profile()
    # a：命中标签 + 目标分类；b：不同标签 + 不同分类，用来隔离「只影响命中的标签与分类」
    a = Candidate(["制服", "素人"], "watch", FROM_CATEGORY)
    b = Candidate(["萝莉"], "hot", FROM_CATEGORY)
    base_a = score(a, p, tag_df, 0, 10)
    base_b = score(b, p, tag_df, 1, 10)
    apply_feedback(p, ["制服", "素人"], "watch", "", LIKE_BOOST, tag_df)
    after_a = score(a, p, tag_df, 0, 10)
    after_b = score(b, p, tag_df, 1, 10)
    check("点赞后同类候选分数上升", after_a > base_a,
          "before=%.4f after=%.4f" % (base_a, after_a))
    check("点赞只影响命中标签/分类(无关候选分数不变)",
          abs(after_b - base_b) < 1e-9,
          "b before=%.4f after=%.4f" % (base_b, after_b))
    check("无关标签的权重未被污染", abs(p.tag("萝莉")) < 1e-9, "w=%.4f" % p.tag("萝莉"))
    check("点赞写入了标签权重", p.tag("制服") > 0.0, "w=%.4f" % p.tag("制服"))

    print("[3] 不喜欢降权，不永久屏蔽、不崩为负无穷")
    p2 = Profile()
    c = Candidate(["口交"], "watch", FROM_CATEGORY)
    liked = score(c, p2, tag_df, 0, 10)
    apply_feedback(p2, ["口交"], "watch", "", LIKE_BOOST, tag_df)
    liked_score = score(c, p2, tag_df, 0, 10)
    apply_feedback(p2, ["口交"], "watch", "", -DISLIKE_PENALTY, tag_df)
    disliked_score = score(c, p2, tag_df, 0, 10)
    check("不喜欢后分数低于点赞时", disliked_score < liked_score,
          "liked=%.4f disliked=%.4f" % (liked_score, disliked_score))
    # 不喜欢只是把权重压到接近 0（甚至略负），不是 -inf，也不是永久排除
    check("不喜欢不导致分数崩溃(< 1e6)", disliked_score > -1e6, "s=%.4f" % disliked_score)
    check("不喜欢后仍可正向回升(权重未被封死)",
          p2.tag("口交") > WEIGHT_MIN - 1e-9, "w=%.4f" % p2.tag("口交"))

    print("[4] 冷启动：空画像下分数稳定（仅新鲜度差异）")
    p3 = Profile()
    x = Candidate(["制服"], "watch", FROM_CATEGORY)
    y = Candidate(["无码"], "watch", FROM_CATEGORY)
    # pos/size 相同、无噪声 → 分数应一致
    sx = score(x, p3, tag_df, 0, 10, noise=0.0)
    sy = score(y, p3, tag_df, 0, 10, noise=0.0)
    check("冷启动同位置两候选分数一致", abs(sx - sy) < 1e-9,
          "sx=%.4f sy=%.4f" % (sx, sy))
    # 不同位置仅新鲜度不同：pos0 > pos9
    s0 = score(x, p3, tag_df, 0, 10, noise=0.0)
    s9 = score(x, p3, tag_df, 9, 10, noise=0.0)
    check("新鲜度：靠前候选分数 >= 靠后候选", s0 >= s9,
          "s0=%.4f s9=%.4f" % (s0, s9))

    print("[5] 时间衰减：半衰期后权重缩小；不足半天不衰减")
    # 用正的时间戳初始化（真实 App 用 System.currentTimeMillis，恒为正）
    base = 1000 * DAY_MS
    p4 = Profile()
    p4.bumpTag("学生妹", 10.0)
    # 第一次调用仅初始化 lastDecayTime（与 Java 一致：不能相对「无」衰减）
    p4.applyDecay(base, 30)
    before = p4.tag("学生妹")
    # 经过 30 天，半衰期 30 天 → 约减半
    p4.applyDecay(base + DAY_MS * 30, 30)
    after = p4.tag("学生妹")
    check("30 天后权重约为原先一半", abs(after - before * 0.5) < 1e-6,
          "before=%.4f after=%.4f" % (before, after))
    # 不足半天不衰减
    p5 = Profile()
    p5.bumpTag("制服", 8.0)
    p5.applyDecay(base, 30)
    p5.applyDecay(base + DAY_MS * 0.1, 30)
    check("不足半天不衰减", abs(p5.tag("制服") - 8.0) < 1e-9,
          "w=%.4f" % p5.tag("制服"))

    print("[6] 作者召回：作者权重 + FROM_AUTHOR 加成")
    p6 = Profile()
    p6.bumpAuthor("uploader_123", 5.0)
    au = Candidate(["素人"], "watch", FROM_AUTHOR, author_key="uploader_123")
    no_au = Candidate(["素人"], "watch", FROM_CATEGORY, author_key="uploader_123")
    sa = score(au, p6, tag_df, 0, 10)
    sn = score(no_au, p6, tag_df, 0, 10)
    # FROM_AUTHOR 额外 +0.8，且 authorScore 命中
    check("作者召回分数高于普通召回", sa > sn, "sa=%.4f sn=%.4f" % (sa, sn))
    check("作者权重贡献了 authorScore", au.authorScore > 0, "as=%.4f" % au.authorScore)

    print("\n结果：%d 通过 / %d 失败" % (PASS, FAIL))
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
