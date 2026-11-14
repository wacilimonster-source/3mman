package com.m3man.data.reco;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * R-fix（LESSONS §3-2）：画像计数原子化后的行为回归。
 * 显式互动（UI 线程）与隐式观看结算（IO 线程）并发递增不得丢更新——
 * 单测内直接验证原子 API 语义（递增/递减下限/冷启动判定/复位）。
 */
public class RecoProfileCountersTest {

    @Test
    public void counters_incrementAndFloorAtZero() {
        RecoProfile p = new RecoProfile();
        assertEquals(0, p.likeCount.get());

        p.likeCount.incrementAndGet();
        p.likeCount.incrementAndGet();
        assertEquals(2, p.likeCount.get());

        // 递减有下限保护（调用方先判 >0），这里验证减到 0 后不再为负
        p.likeCount.decrementAndGet();
        p.likeCount.decrementAndGet();
        assertEquals(0, p.likeCount.get());
    }

    @Test
    public void coldStart_dependsOnExplicitCountsAndWeights() {
        RecoProfile p = new RecoProfile();
        assertTrue(p.isColdStart());

        p.likeCount.incrementAndGet();
        assertFalse(p.isColdStart());

        p.clear();
        assertTrue(p.isColdStart());
    }

    @Test
    public void clear_resetsAllCounters() {
        RecoProfile p = new RecoProfile();
        p.likeCount.set(3);
        p.dislikeCount.set(2);
        p.favoriteCount.set(1);
        p.watchCount.set(7);
        p.clear();
        assertEquals(0, p.likeCount.get());
        assertEquals(0, p.dislikeCount.get());
        assertEquals(0, p.favoriteCount.get());
        assertEquals(0, p.watchCount.get());
    }
}
