package com.m3man.utils;

import android.net.Uri;
import android.text.TextUtils;

import com.danikula.videocache.file.FileNameGenerator;

/**
 * @author flymegoc
 * @date 2017/11/23
 * @describe
 */

public class VideoCacheFileNameGenerator implements FileNameGenerator {
    // Urls contain mutable parts (parameter 'sessionToken') and stable video's id (parameter 'videoId').
    // e. g. http://example.com?videoId=abcqaz&sessionToken=xyz987
    //http://185.38.13.159//mp43/243907.mp4?st=Jsr4cwsuIoZ5aDVLckLamA&e=1511443397
    //"http://185.38.13.130//mp43/238248.mp4?st=Uwgj0IbndG0N7J5qQx1CuA&e=1511443750"
    @Override
    public String generate(String url) {
        if (TextUtils.isEmpty(url)) {
            return "video.temp";
        }
        int startIndex = url.lastIndexOf("/");
        int endIndex = url.indexOf(".mp4");
        try {
            return url.substring(startIndex, endIndex) + ".temp";
        } catch (Exception e) {
            // L6：地址不含 .mp4 或不合法时，退化为基于 URL 的合法文件名
            // （去掉协议 / 路径 / 参数中的非法字符），避免把整串 URL 当文件名导致缓存文件创建失败
            String name = url.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (name.length() > 120) {
                name = name.substring(0, 120);
            }
            return name + ".temp";
        }
    }
}
