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
 * Cookie 缓存供网络请求和退出登录共用。所有读写由同一把锁保护，迭代器读取锁内快照。
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

        /**
         * M64：快照迭代器必须支持 remove()。PersistentCookieJar v1.0.1 的
         * loadForRequest() 在遍历中用 iterator.remove() 清理过期 Cookie，
         * M62 改快照后此处抛 UnsupportedOperationException 导致带过期 Cookie
         * 的域名（如 GitHub 更新检查）每次请求都失败。
         * 现记录 lastReturned 并在锁内从底层集合删除，语义与库期望一致。
         */
        private IdentifiableCookie lastReturned;

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
            lastReturned = iterator.next();
            return lastReturned.getCookie();
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException("next() has not been called");
            }
            synchronized (lock) {
                cookies.remove(lastReturned);
            }
            lastReturned = null;
        }
    }
}
