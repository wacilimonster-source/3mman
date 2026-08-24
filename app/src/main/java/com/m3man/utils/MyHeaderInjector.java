package com.m3man.utils;

import com.danikula.videocache.headers.HeaderInjector;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class MyHeaderInjector implements HeaderInjector {

    // M73：该 map 由 UI 线程写入、videocache 代理线程读取（addHeaders），
    // 非 volatile 的引用替换在代理线程可能看到半初始化/旧引用。改为 volatile 引用 +
    // 不可变快照式更新，读侧始终拿到完整一致的 map。
    private volatile HashMap<String,String> hashMap;

    @Inject
    public MyHeaderInjector() {
        this.hashMap = new HashMap<>();
    }

    @Override
    public Map<String, String> addHeaders(String url) {
        return hashMap;
    }

    public HashMap<String, String> getHashMap() {
        return hashMap;
    }

    public void setHashMap(HashMap<String, String> hashMap) {
        this.hashMap = hashMap;
    }
}
