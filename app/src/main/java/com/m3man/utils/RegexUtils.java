package com.m3man.utils;

import com.m3man.constants.RegexConstants;

/**
 * 正则校验工具（只保留项目实际使用的 IP 校验，其余通用方法已随 2026-11-12 清理移除）。
 */
public final class RegexUtils {

    private RegexUtils() {
        throw new UnsupportedOperationException("u can't instantiate me...");
    }

    /** 验证 IPv4 地址 */
    public static boolean isIP(final CharSequence input) {
        return input != null
                && input.length() > 0
                && RegexConstants.REGEX_IP.matcher(input).matches();
    }
}
