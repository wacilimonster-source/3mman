package com.m3man;

import org.junit.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.Schedulers;

import com.m3man.rxjava.RetryWhenProcess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M101：RetryWhenProcess off-by-one 修复回归。
 * 语义：1 次原始请求 + 最多 3 次重试（SocketTimeoutException）= 最多 4 次尝试。
 */
public class RetryWhenProcessTest {

    /** 连续超时：断言恰好重试 3 次后 onError（共 4 次订阅）。 */
    @Test
    public void retriesExactlyThreeTimesThenErrors() {
        final AtomicInteger attempts = new AtomicInteger();
        TestObserver<Object> ts = Observable.defer(() -> {
            attempts.incrementAndGet();
            return Observable.error(new SocketTimeoutException("timeout"));
        }).retryWhen(new RetryWhenProcess(0, 3))
                .test();

        ts.awaitTerminalEvent(10, TimeUnit.SECONDS);
        assertTrue(ts.errors().get(0) instanceof SocketTimeoutException);
        assertEquals(4, attempts.get());
    }

    /** 非超时异常立即透传，不重试。 */
    @Test
    public void nonTimeoutErrorPropagatesImmediately() {
        final AtomicInteger attempts = new AtomicInteger();
        RuntimeException boom = new RuntimeException("boom");
        TestObserver<Object> ts = Observable.defer(() -> {
            attempts.incrementAndGet();
            return Observable.error(boom);
        }).retryWhen(new RetryWhenProcess(0, 3))
                .test();

        ts.awaitTerminalEvent(5, TimeUnit.SECONDS);
        assertEquals(1, attempts.get());
        assertTrue(ts.errors().contains(boom));
    }

    /** 第 2 次尝试成功：验证重试后恢复流。 */
    @Test
    public void recoversOnSecondAttempt() {
        final AtomicInteger attempts = new AtomicInteger();
        TestObserver<String> ts = Observable.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Observable.error(new SocketTimeoutException("first timeout"));
            }
            return Observable.just("ok");
        }).retryWhen(new RetryWhenProcess(0, 3))
                .subscribeOn(Schedulers.trampoline())
                .test();

        ts.awaitTerminalEvent(10, TimeUnit.SECONDS);
        ts.assertValue("ok");
        ts.assertNoErrors();
        assertEquals(2, attempts.get());
    }

    /** 复合异常（RxCache 包裹）中提取超时并重试。 */
    @Test
    public void compositeExceptionInnerTimeoutRetried() {
        final AtomicInteger attempts = new AtomicInteger();
        TestObserver<Object> ts = Observable.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Observable.error(new io.reactivex.exceptions.CompositeException(
                        new SocketTimeoutException("inner"),
                        new RuntimeException("rxcache-like")));
            }
            return Observable.just(new Object());
        }).retryWhen(new RetryWhenProcess(0, 3))
                .subscribeOn(Schedulers.trampoline())
                .test();

        ts.awaitTerminalEvent(10, TimeUnit.SECONDS);
        ts.assertNoErrors();
        assertEquals(2, attempts.get());
    }
}
