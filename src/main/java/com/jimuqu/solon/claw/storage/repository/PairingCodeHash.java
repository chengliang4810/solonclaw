package com.jimuqu.solon.claw.storage.repository;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 使用 JDK 原生 PBKDF2 对 pairing code 做不可逆盐化摘要。 */
final class PairingCodeHash {
    /** 当前摘要格式标识。 */
    private static final String PREFIX = "pbkdf2-sha256";

    /** 摘要迭代次数，pairing 请求数量很小，优先抵抗离线穷举。 */
    private static final int ITERATIONS = 120_000;

    /** 随机盐字节数。 */
    private static final int SALT_BYTES = 16;

    /** 派生摘要位数。 */
    private static final int HASH_BITS = 256;

    /** 安全随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类不允许实例化。 */
    private PairingCodeHash() {}

    /**
     * 生成可存入数据库的盐化摘要。
     *
     * @param code 用户可见的临时 pairing code。
     * @return 带算法、迭代次数和盐的摘要文本。
     */
    static String hash(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("pairing code 不能为空。");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(code, salt, ITERATIONS);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX
                + "$"
                + ITERATIONS
                + "$"
                + encoder.encodeToString(salt)
                + "$"
                + encoder.encodeToString(hash);
    }

    /**
     * 使用常量时间字节比较验证 pairing code。
     *
     * @param code 用户提交的明文 code。
     * @param stored 数据库存储的摘要。
     * @return 匹配返回 true。
     */
    static boolean matches(String code, String stored) {
        if (code == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[2]);
            byte[] expected = decoder.decode(parts[3]);
            if (iterations != ITERATIONS
                    || salt.length != SALT_BYTES
                    || expected.length != HASH_BITS / 8) {
                return false;
            }
            return MessageDigest.isEqual(expected, derive(code, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 判断数据库值是否为当前安全摘要格式。 */
    static boolean isHash(String value) {
        return value != null && value.startsWith(PREFIX + "$");
    }

    /** 使用 PBKDF2-HMAC-SHA256 派生固定长度摘要。 */
    private static byte[] derive(String code, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 pairing code 摘要。", e);
        } finally {
            spec.clearPassword();
        }
    }
}
