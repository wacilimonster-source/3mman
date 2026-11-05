package com.m3man.perf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * PerfRecorder 聚合契约：p50/p95 计算、失败计数、计数器、最慢区段。
 * 纯 JVM 断言（Perf 的聚合实现不依赖 Android 桩）。
 */
public class PerfAggregationTest {

    @Test
    public void stageDuration_aggregatesPercentiles() throws Exception {
        String stage = "test.p50.stage." + System.nanoTime();
        PerfRecorder r = Perf.recorder();
        for (int i = 1; i <= 100; i++) {
            long begin = r.begin(stage, Perf.nextRequestId());
            r.end(stage, 1L, begin - (long) i * 1_000_000L, Perf.OUTCOME_OK);
        }
        Map<String, Perf.StageSummary> snap = Perf.snapshot();
        Perf.StageSummary s = snap.get(stage);
        assertEquals(100, s.sampleCount);
        assertEquals(0, s.failureCount);
        // 样本 1..100ms：p50 ≈ 50ms，p95 ≈ 95ms
        assertEquals(50.0, s.p50Ms, 0.5);
        assertEquals(95.0, s.p95Ms, 0.5);
        assertEquals(100.0, s.maxMs, 0.5);
    }

    @Test
    public void failureOutcome_counted() {
        String stage = "test.fail.stage." + System.nanoTime();
        PerfRecorder r = Perf.recorder();
        long begin = r.begin(stage, 7L);
        r.end(stage, 7L, begin - 2_000_000L, Perf.OUTCOME_FAIL);
        Perf.StageSummary s = Perf.snapshot().get(stage);
        assertEquals(1, s.sampleCount);
        assertEquals(1, s.failureCount);
    }

    @Test
    public void counters_trackLastAndTotal() {
        String name = "test.counter." + System.nanoTime();
        PerfRecorder r = Perf.recorder();
        r.counter(name, 5);
        r.counter(name, 3);
        Perf.CounterSummary c = Perf.countersSnapshot().get(name);
        assertEquals(3, c.last);
        assertEquals(8, c.total);
    }

    @Test
    public void slowestRecords_descendingOrder() {
        String stage = "test.slow.stage." + System.nanoTime();
        PerfRecorder r = Perf.recorder();
        long b1 = r.begin(stage, 1L);
        r.end(stage, 1L, b1 - 1_000_000L, Perf.OUTCOME_OK);   // 1ms
        long b2 = r.begin(stage, 2L);
        r.end(stage, 2L, b2 - 9_000_000L, Perf.OUTCOME_OK);   // 9ms
        long b3 = r.begin(stage, 3L);
        r.end(stage, 3L, b3 - 4_000_000L, Perf.OUTCOME_OK);   // 4ms
        java.util.List<Perf.SlowRecord> records = Perf.slowestRecords();
        assertTrue(records.size() >= 3);
        assertTrue(records.get(0).durNs >= records.get(records.size() - 1).durNs);
    }

    @Test
    public void span_convenience_endRecordsOnce() {
        String stage = "test.span.stage." + System.nanoTime();
        Perf.Span span = Perf.span(stage);
        span.end(Perf.OUTCOME_OK);
        assertEquals(1, Perf.snapshot().get(stage).sampleCount);
    }
}
