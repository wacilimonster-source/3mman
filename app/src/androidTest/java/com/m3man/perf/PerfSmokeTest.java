package com.m3man.perf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T00 设备冒烟测试：PerfRecorder 聚合与 ExecutorRegistry 在真机/模拟器上真实可用。
 * 对应方案 §7——JVM returnDefaultValues 桩不能证明真实可用，此处用设备测试兜底。
 */
@RunWith(AndroidJUnit4.class)
public class PerfSmokeTest {

    @Test
    public void perfRecorder_aggregatesOnDevice() {
        String stage = "smoke." + System.nanoTime();
        for (int i = 0; i < 20; i++) {
            long begin = Perf.recorder().begin(stage, Perf.nextRequestId());
            Perf.recorder().end(stage, 1L, begin - 1_000_000L, Perf.OUTCOME_OK);
        }
        Map<String, Perf.StageSummary> snap = Perf.snapshot();
        Perf.StageSummary s = snap.get(stage);
        assertNotNull(s);
        assertEquals(20, s.sampleCount);
        assertTrue(s.p50Ms >= 0.9 && s.p50Ms <= 1.5);
    }

    @Test
    public void executorRegistry_crossThreadDispatchWorks() throws Exception {
        final AtomicReference<String> threadName = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        ExecutorRegistry.get(ExecutorRegistry.Pool.DB_WRITE).execute(() -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // 线程名前缀 = 池名小写、下划线转连字符（perf-db-write-N）
        assertTrue(threadName.get().startsWith("db-write"));
    }
}
