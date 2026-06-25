package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;

import com.jimuqu.solon.claw.config.AppConfig;

import org.noear.solon.core.handle.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 提供消息网关Injection认证相关业务能力，封装调用方不需要感知的运行细节。 */
public class GatewayInjectionAuthService {
    /** 外部注入请求携带的 HMAC 签名请求头。 */
    private static final String HEADER_SIGNATURE = "X-solonclaw-Signature";

    /** 外部注入请求携带的秒级时间戳请求头，用于防重放。 */
    private static final String HEADER_TIMESTAMP = "X-solonclaw-Timestamp";

    /** 外部注入请求携带的随机串请求头，用于同一窗口内去重。 */
    private static final String HEADER_NONCE = "X-solonclaw-Nonce";

    /** 网关注入签名采用的 HMAC 算法。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 随机串缓存上限，避免长期运行后内存无限增长。 */
    private static final int MAX_NONCES = 2048;

    /** 应用配置，提供签名密钥、请求体上限和重放窗口。 */
    private final AppConfig appConfig;

    /** 已使用随机串和首次出现时间，按插入顺序淘汰过期项。 */
    private final Map<String, Long> seenNonces =
            Collections.synchronizedMap(new LinkedHashMap<String, Long>());

    /**
     * 创建外部网关注入请求认证服务。
     *
     * @param appConfig 应用运行配置。
     */
    public GatewayInjectionAuthService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 校验网关注入请求的签名、时间窗口、随机串和请求体大小。
     *
     * @param context 当前请求或运行上下文。
     * @param body 请求体或消息正文内容。
     */
    public void verify(Context context, String body) {
        if (!"POST".equalsIgnoreCase(context.method())) {
            context.status(405);
            throw new IllegalStateException("Gateway injection requires POST");
        }
        String secret = appConfig.getGateway().getInjectionSecret();
        if (StrUtil.isBlank(secret)) {
            context.status(403);
            throw new IllegalStateException("Gateway injection secret is not configured");
        }
        int maxBodyBytes = Math.max(1024, appConfig.getGateway().getInjectionMaxBodyBytes());
        int bodyBytes = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
        if (bodyBytes <= 0 || bodyBytes > maxBodyBytes) {
            context.status(413);
            throw new IllegalStateException("Gateway injection body size is invalid");
        }

        String timestampText = context.header(HEADER_TIMESTAMP);
        String nonce = context.header(HEADER_NONCE);
        String signature = stripPrefix(context.header(HEADER_SIGNATURE));
        if (StrUtil.hasBlank(timestampText, nonce, signature)) {
            context.status(401);
            throw new IllegalStateException("Gateway injection signature headers are required");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampText.trim());
        } catch (Exception e) {
            context.status(401);
            throw new IllegalStateException("Gateway injection timestamp is invalid");
        }
        long now = System.currentTimeMillis() / 1000L;
        long window = Math.max(30, appConfig.getGateway().getInjectionReplayWindowSeconds());
        if (Math.abs(now - timestamp) > window) {
            context.status(401);
            throw new IllegalStateException("Gateway injection timestamp is outside replay window");
        }
        String payload =
                timestampText.trim() + "." + nonce.trim() + "." + StrUtil.nullToEmpty(body);
        String expected = hmacSha256Hex(secret, payload);
        if (!constantTimeEquals(expected, signature)) {
            context.status(401);
            throw new IllegalStateException("Gateway injection signature is invalid");
        }
        if (!markNonce(nonce, now, window)) {
            context.status(409);
            throw new IllegalStateException("Gateway injection nonce has already been used");
        }
    }

    /**
     * 记录随机串并淘汰过期缓存，阻止同一重放窗口内重复提交。
     *
     * @param nonce 用于防重放的随机串。
     * @param now 当前时间戳。
     * @param window 重放检测时间窗口。
     * @return 随机串首次出现时返回 true，已使用或为空时返回 false。
     */
    private boolean markNonce(String nonce, long now, long window) {
        String key = StrUtil.nullToEmpty(nonce).trim();
        if (StrUtil.isEmpty(key)) {
            return false;
        }
        synchronized (seenNonces) {
            Iterator<Map.Entry<String, Long>> iterator = seenNonces.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (now - entry.getValue() > window || seenNonces.size() > MAX_NONCES) {
                    iterator.remove();
                }
            }
            if (seenNonces.containsKey(key)) {
                return false;
            }
            seenNonces.put(key, now);
            return true;
        }
    }

    /**
     * 移除签名前缀，得到纯十六进制签名。
     *
     * @param value 请求头中的签名文本。
     * @return 去掉 sha256= 前缀后的十六进制签名。
     */
    private String stripPrefix(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.regionMatches(true, 0, "sha256=", 0, "sha256=".length())) {
            return text.substring("sha256=".length()).trim();
        }
        return text;
    }

    /**
     * 使用 HMAC-SHA256 计算载荷签名。
     *
     * @param secret 签名使用的共享密钥。
     * @param payload 待签名或解析的载荷内容。
     * @return 小写十六进制签名。
     */
    private String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign gateway injection payload", e);
        }
    }

    /**
     * 用常量时间比较方式校验签名，降低时序侧信道风险。
     *
     * @param expectedHex 服务端根据请求体计算出的签名。
     * @param actualHex 客户端随请求提交的签名。
     * @return 两个签名按字节一致时返回 true。
     */
    private boolean constantTimeEquals(String expectedHex, String actualHex) {
        byte[] expected =
                StrUtil.nullToEmpty(expectedHex).toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] actual =
                StrUtil.nullToEmpty(actualHex).toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 转换为Hex。
     *
     * @param bytes HMAC 原始字节。
     * @return 小写十六进制字符串。
     */
    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            String hex = Integer.toHexString(value & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
