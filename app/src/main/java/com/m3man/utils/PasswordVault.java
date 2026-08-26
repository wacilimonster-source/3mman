package com.m3man.utils;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * M94：基于 Android Keystore 的密码加密工具（AES/GCM/NoPadding，密钥不出安全硬件）。
 * <p>
 * 密文格式："v1:" + Base64(IV(12B) || GCM密文+Tag(128b))，前缀 {@link #PREFIX_V1} 用于
 * 区分新旧两种存储形态，老数据首次读取后由调用方惰性升级为新格式。
 * <p>
 * 降级策略约定：本类任何加密/解密失败都以异常抛出（含 API&lt;23 无 Keystore AES/GCM、
 * Keystore 初始化失败等），由调用方（AppPreferencesHelper）捕获后回落旧 Base64 形态，
 * 保证登录功能不中断；本类自身不做静默降级。
 * <p>
 * ===================== 单测入口（M94 可测试性说明） =====================
 * 核心判定逻辑刻意不依赖任何 Android 类，可在纯 JVM 单元测试中直接组合验证：
 *   1) {@link #looksLikeLegacyPlain(byte[])}：对已解码字节判定是否"旧版可打印 ASCII 明文"；
 *   2) {@link #hasV1Prefix(String)}：字符串是否带新格式 "v1:" 前缀。
 * 运行时便捷方法 {@link #looksLikeLegacyBase64(String)} 因需 android.util.Base64 解码，
 * 仅用于设备端，其语义等价于 "!hasV1Prefix(s) && looksLikeLegacyPlain(Base64.decode(s))"。
 * ======================================================================
 *
 * @author 3mman
 */
public final class PasswordVault {

    /** Keystore 密钥别名（评审指定） */
    public static final String KEY_ALIAS = "3mman_pwd_key";

    /** 新格式密文前缀 */
    public static final String PREFIX_V1 = "v1:";

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    /** GCM 认证标签长度（bit） */
    private static final int GCM_TAG_BITS = 128;
    /** IV 长度（Keystore GCM 默认 12 字节） */
    private static final int IV_LENGTH = 12;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 缓存已生成的密钥，避免每次读写都查 Keystore */
    private static volatile SecretKey sCachedKey;

    private PasswordVault() {
    }

    /**
     * 加密：返回 Base64(IV || 密文)，不含 "v1:" 前缀（前缀由调用方拼接）。
     *
     * @throws GeneralSecurityException API&lt;23、Keystore 不可用、加解密失败等一切异常
     */
    public static String encrypt(String plain) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plain.getBytes(UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            // NO_WRAP：避免换行符混入 SP 存储值，保证前缀/解码处理简单可靠
            return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("encrypt failed", e);
        }
    }

    /**
     * 解密：入参为不含 "v1:" 前缀的 Base64(IV || 密文)。
     *
     * @throws GeneralSecurityException 格式非法、Keystore 不可用、认证失败（含密钥被清）等
     */
    public static String decrypt(String base64Payload) throws GeneralSecurityException {
        if (TextUtils.isEmpty(base64Payload)) {
            throw new GeneralSecurityException("empty cipher payload");
        }
        byte[] all;
        try {
            all = Base64.decode(base64Payload, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("cipher payload is not base64", e);
        }
        if (all.length <= IV_LENGTH) {
            throw new GeneralSecurityException("cipher payload too short");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, UTF_8);
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("decrypt failed", e);
        }
    }

    /**
     * 取已有密钥或生成新密钥。KeyGenParameterSpec 仅 API 23+ 支持，
     * 低版本直接抛异常走调用方降级路径（方法内自带 SDK_INT 守卫）。
     */
    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        SecretKey cached = sCachedKey;
        if (cached != null) {
            return cached;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // M94：minSdk 19，Keystore 不支持 AES/GCM 时由调用方回落旧 Base64 形态
            throw new GeneralSecurityException(
                    "AndroidKeyStore AES/GCM requires API 23+, current=" + Build.VERSION.SDK_INT);
        }
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (Exception e) {
            // M94：load 声明的 IOException/CertificateException 等统一转安全异常
            throw new GeneralSecurityException("keystore load failed", e);
        }
        java.security.Provider provider = keyStore.getProvider();
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, provider != null ? provider.getName() : ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            key = generator.generateKey();
        }
        sCachedKey = key;
        return key;
    }

    /**
     * M94 纯函数（无 Android 依赖，JVM 可测）：判断已 Base64 解码的字节是否为
     * "旧版可打印 ASCII 明文"。空/null 一律 false。
     */
    public static boolean looksLikeLegacyPlain(byte[] decoded) {
        if (decoded == null || decoded.length == 0) {
            return false;
        }
        for (byte b : decoded) {
            int v = b & 0xFF;
            // 仅接受可打印 ASCII（0x20~0x7E）；GCM 密文几乎不可能全落此区间
            if (v < 0x20 || v > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * M94 纯函数（无 Android 依赖，JVM 可测）：是否携带新格式 "v1:" 前缀。
     */
    public static boolean hasV1Prefix(String s) {
        return s != null && s.startsWith(PREFIX_V1);
    }

    /**
     * 运行时便捷判定（设备端用，内部依赖 android.util.Base64）：
     * 非空、可被 Base64.decode、解码后为可打印 ASCII、且不以 "v1:" 开头 → true。
     * 单测请组合 {@link #hasV1Prefix(String)} 与 {@link #looksLikeLegacyPlain(byte[])}：
     * 语义等价于 "!s.isEmpty() && !hasV1Prefix(s) && looksLikeLegacyPlain(Base64.decode(s))"。
     */
    public static boolean looksLikeLegacyBase64(String s) {
        if (TextUtils.isEmpty(s) || hasV1Prefix(s)) {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(s, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return looksLikeLegacyPlain(decoded);
    }
}
