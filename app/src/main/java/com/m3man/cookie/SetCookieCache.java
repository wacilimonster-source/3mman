package com.m3man.cookie;
/*
 * Copyright (C) 2016 Francisco José Montiel Navarro.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.franmontiel.persistentcookiejar.cache.CookieCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import okhttp3.Cookie;

/**
 * M62：线程安全化——PersistentCookieJar 与 RulerCookie 两个 jar 实例共享本缓存，
 * 但二者是不同对象、互斥锁互不相干；OkHttp 线程遍历与退出登录 clear()/delete()
 * 并发时会对同一个裸 HashSet 造成结构损坏或 ConcurrentModificationException。
 * 现所有读写统一走内部 lock，迭代器改为锁内快照，跨实例并发安全。
 */
public class SetCookieCache implements CookieCache {

    private final Object lock = new Object();
    private Set<IdentifiableCookie> cookies;

    public SetCookieCache() {
        cookies = new HashSet<>();
    }

    @Override
    public void addAll(Collection<Cookie> newCookies) {
        synchronized (lock) {
            for (IdentifiableCookie cookie : IdentifiableCookie.decorateAll(newCookies)) {
                this.cookies.remove(cookie);
                this.cookies.add(cookie);
            }
        }
    }

    /**
     * 新增，删除一个cookie
     *
     * @param cookie 要删除的cookie
     */
    public void delete(Cookie cookie) {
        synchronized (lock) {
            this.cookies.remove(new IdentifiableCookie(cookie));
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            cookies.clear();
        }
    }

    @Override
    public Iterator<Cookie> iterator() {
        return new SetCookieCacheIterator();
    }

    private class SetCookieCacheIterator implements Iterator<Cookie> {

        private Iterator<IdentifiableCookie> iterator;

        public SetCookieCacheIterator() {
            // M62：锁内做快照，迭代期间其他线程的增删不再触发 CME
            List<IdentifiableCookie> snapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(cookies);
            }
            iterator = snapshot.iterator();
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public Cookie next() {
            return iterator.next().getCookie();
        }

        @Override
        public void remove() {
            // 快照迭代器不支持移除（原实现直接操作底层集合同样不安全）；如需删除请走 delete(Cookie)
            throw new UnsupportedOperationException("Use SetCookieCache.delete(Cookie) instead");
        }
    }
}
