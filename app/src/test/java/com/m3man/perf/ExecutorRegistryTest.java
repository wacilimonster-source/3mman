package com.m3man.perf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExecutorRegistry 池规格契约（方案 §4）：
 * diff 最多 2 线程、dbWrite/stateWriter/parseForeground/warm 单线程、parseBackground 2 线程。
 */
public class ExecutorRegistryTest {

    @Test
    public void diffPool_atMostTwoConcurrent() throws Exception {
        ExecutorService diff = ExecutorRegistry.get(ExecutorRegistry.Pool.DIFF);
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        Runnable task = () -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            concurrent.decrementAndGet();
        };
        // 第 3 个任务必须排队：同时刻在跑的不会超过 2
        for (int i = 0; i < 3; i++) {
            diff.execute(task);
        }
        assertTrue(running.await(5, TimeUnit.SECONDS));
        Thread.sleep(50); // 给可能超额并发的时间窗
        assertEquals(2, maxConcurrent.get());
        release.countDown();
    }

    @Test
    public void dbWrite_singleThread_serialExecution() throws Exception {
        ExecutorService db = ExecutorRegistry.get(ExecutorRegistry.Pool.DB_WRITE);
        final String[] names = new String[2];
        CountDownLatch done = new CountDownLatch(2);
        db.execute(() -> {
            names[0] = Thread.currentThread().getName();
            done.countDown();
        });
        db.execute(() -> {
            names[1] = Thread.currentThread().getName();
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(names[0], names[1]);
        // 线程名前缀 = 池名小写、下划线转连字符（perf-db-write-N）
        assertTrue(names[0].startsWith("db-write"));
    }

    @Test
    public void parseBackground_twoThreads_warmSingle() throws Exception {
        ExecutorService bg = ExecutorRegistry.get(ExecutorRegistry.Pool.PARSE_BACKGROUND);
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            bg.execute(() -> {
                running.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            });
        }
        assertTrue(running.await(5, TimeUnit.SECONDS));
        release.countDown();

        ExecutorService warm = ExecutorRegistry.get(ExecutorRegistry.Pool.WARM);
        CountDownLatch warmLatch = new CountDownLatch(2);
        final String[] warmNames = new String[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            warm.execute(() -> {
                warmNames[idx] = Thread.currentThread().getName();
                warmLatch.countDown();
            });
        }
        assertTrue(warmLatch.await(5, TimeUnit.SECONDS));
        assertEquals(warmNames[0], warmNames[1]);
    }
}
