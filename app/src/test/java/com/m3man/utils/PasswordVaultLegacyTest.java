package com.m3man.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M101：密码存储格式迁移（Base64 → Keystore AES/GCM v1:）的纯函数回归。
 * 覆盖：legacy 判定 / v1 前缀识别 / 边界输入不抛异常。
 */
public class PasswordVaultLegacyTest {

    @Test
    public void hasV1Prefix_positive() {
        assertTrue(PasswordVault.hasV1Prefix("v1:AbCdEf=="));
    }

    @Test
    public void hasV1Prefix_negativeAndNullSafe() {
        assertFalse(PasswordVault.hasV1Prefix(null));
        assertFalse(PasswordVault.hasV1Prefix(""));
        assertFalse(PasswordVault.hasV1Prefix("v1"));
        assertFalse(PasswordVault.hasV1Prefix("v2:xxx"));
        // 旧 Base64 形态（历史密码 "123456" 的编码）
        assertFalse(PasswordVault.hasV1Prefix("MTIzNDU2"));
    }

    @Test
    public void legacyPlain_printableAsciiAccepted() {
        assertTrue(PasswordVault.looksLikeLegacyPlain("123456".getBytes()));
        assertTrue(PasswordVault.looksLikeLegacyPlain("p@ss w0rd!".getBytes()));
    }

    @Test
    public void legacyPlain_rejectsNullEmptyAndBinary() {
        assertFalse(PasswordVault.looksLikeLegacyPlain(null));
        assertFalse(PasswordVault.looksLikeLegacyPlain(new byte[0]));
        // 随机二进制（含非可打印字符）不像明文密码
        byte[] binary = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 0x7F};
        assertFalse(PasswordVault.looksLikeLegacyPlain(binary));
    }
}
