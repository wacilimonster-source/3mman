package com.m3man.constants;

/**
 * 正则常量（只保留项目实际使用的 IP 校验，其余通用常量已随 2026-11-12 清理移除）。
 */
public final class RegexConstants {

    /** 正则：IPv4 地址 */
    public static final java.util.regex.Pattern REGEX_IP = java.util.regex.Pattern.compile(
            "((2[0-4]\\d|25[0-5]|[01]?\\d\\d?)\\.){3}(2[0-4]\\d|25[0-5]|[01]?\\d\\d?)");

    private RegexConstants() {
    }
}
