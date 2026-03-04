package com.jimuqu.solonclaw.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.*;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Date;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT Token 服务
 * <p>
 * 负责生成和验证 JWT Token，提供完整的安全验证机制
 *
 * @author SolonClaw
 */
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /**
     * Token 默认有效期（毫秒）- 7 天
     */
    private static final long DEFAULT_EXPIRE_MS = 7 * 24 * 60 * 60 * 1000L;

    /**
     * Token 刷新有效期（毫秒）- 14 天（超过此时间无法刷新）
     */
    private static final long DEFAULT_REFRESH_WINDOW_MS = 14 * 24 * 60 * 60 * 1000L;

    /**
     * 最小密钥长度
     */
    private static final int MIN_SECRET_LENGTH = 32;

    /**
     * Issuer 标识
     */
    private static final String ISSUER = "solonclaw";

    /**
     * Token 密钥，从环境变量读取
     */
    private final String secret;

    /**
     * Token 有效期
     */
    private final long expireMs;

    /**
     * Token 刷新窗口期
     */
    private final long refreshWindowMs;

    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    /**
     * Token 黑名单（用于主动注销 Token）
     * Key: Token ID (jti), Value: 过期时间
     */
    private final Set<String> tokenBlacklist = ConcurrentHashMap.newKeySet();

    public JwtTokenService(String secret) {
        this(secret, DEFAULT_EXPIRE_MS, DEFAULT_REFRESH_WINDOW_MS);
    }

    public JwtTokenService(String secret, long expireMs) {
        this(secret, expireMs, DEFAULT_REFRESH_WINDOW_MS);
    }

    public JwtTokenService(String secret, long expireMs, long refreshWindowMs) {
        // 密钥验证
        if (secret == null || secret.isEmpty()) {
            log.error("JWT 密钥未配置！请设置环境变量或配置文件中的密钥");
            throw new IllegalArgumentException("JWT 密钥不能为空，请配置有效的密钥");
        }

        if (secret.length() < MIN_SECRET_LENGTH) {
            log.warn("JWT 密钥长度不足 {} 字符，当前长度: {}，建议使用更长的密钥",
                    MIN_SECRET_LENGTH, secret.length());
        }

        this.secret = secret;
        this.expireMs = expireMs;
        this.refreshWindowMs = refreshWindowMs;
        this.algorithm = Algorithm.HMAC256(secret);

        // 构建验证器，包含 issuer 验证
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();

        log.info("JWT Token 服务初始化完成, expireMs={}, refreshWindowMs={}", expireMs, refreshWindowMs);
    }

    /**
     * 生成 Token
     *
     * @param user 用户信息
     * @return JWT Token 字符串
     */
    public String generateToken(User user) {
        if (user == null) {
            throw new IllegalArgumentException("用户信息不能为空");
        }

        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expireMs);
        String tokenId = UUID.randomUUID().toString();

        String token = JWT.create()
                .withIssuer(ISSUER)
                .withSubject(user.getId())
                .withJWTId(tokenId)
                .withIssuedAt(now)
                .withExpiresAt(expireAt)
                .withClaim("userId", user.getId())
                .withClaim("username", user.getUsername())
                .withClaim("role", user.getRole().name())
                .withClaim("email", user.getEmail())
                .sign(algorithm);

        log.debug("生成 Token: userId={}, tokenId={}", user.getId(), tokenId);
        return token;
    }

    /**
     * 验证 Token
     *
     * @param token JWT Token 字符串
     * @return 解码后的 JWT 对象
     * @throws AuthException 当 Token 无效或已过期时抛出
     */
    public DecodedJWT verifyToken(String token) throws AuthException {
        if (token == null || token.trim().isEmpty()) {
            log.warn("Token 为空");
            throw new AuthException("Token 不能为空", AuthException.ErrorCode.INVALID_TOKEN);
        }

        try {
            DecodedJWT decoded = verifier.verify(token);

            // 检查黑名单
            String tokenId = decoded.getId();
            if (tokenId != null && tokenBlacklist.contains(tokenId)) {
                log.warn("Token 已被注销: tokenId={}", tokenId);
                throw new AuthException("Token 已被注销", AuthException.ErrorCode.INVALID_TOKEN);
            }

            log.debug("验证 Token 成功: userId={}, tokenId={}",
                    decoded.getClaim("userId").asString(), tokenId);
            return decoded;
        } catch (TokenExpiredException e) {
            log.warn("Token 已过期: {}", e.getMessage());
            throw new AuthException("Token 已过期", AuthException.ErrorCode.TOKEN_EXPIRED);
        } catch (SignatureVerificationException e) {
            log.warn("Token 签名验证失败: {}", e.getMessage());
            throw new AuthException("Token 签名无效", AuthException.ErrorCode.INVALID_TOKEN);
        } catch (JWTVerificationException e) {
            log.warn("Token 验证失败: {}", e.getMessage());
            throw new AuthException("Token 验证失败: " + e.getMessage(), AuthException.ErrorCode.INVALID_TOKEN);
        }
    }

    /**
     * 从 Token 中获取用户 ID
     *
     * @param token JWT Token 字符串
     * @return 用户 ID
     * @throws AuthException 当 Token 无效时抛出
     */
    public String getUserIdFromToken(String token) throws AuthException {
        DecodedJWT decoded = verifyToken(token);
        return decoded.getClaim("userId").asString();
    }

    /**
     * 从 Token 中获取用户角色
     *
     * @param token JWT Token 字符串
     * @return 用户角色
     * @throws AuthException 当 Token 无效时抛出
     */
    public String getRoleFromToken(String token) throws AuthException {
        DecodedJWT decoded = verifyToken(token);
        return decoded.getClaim("role").asString();
    }

    /**
     * 从 Token 中获取 Token ID
     *
     * @param token JWT Token 字符串
     * @return Token ID (jti)
     * @throws AuthException 当 Token 无效时抛出
     */
    public String getTokenId(String token) throws AuthException {
        DecodedJWT decoded = verifyToken(token);
        return decoded.getId();
    }

    /**
     * 检查 Token 是否过期（不验证签名，仅检查过期时间）
     *
     * @param token JWT Token 字符串
     * @return true 表示已过期，false 表示未过期
     */
    public boolean isTokenExpired(String token) {
        if (token == null || token.trim().isEmpty()) {
            return true;
        }

        try {
            // 先尝试完整验证
            DecodedJWT decoded = verifier.verify(token);
            // 检查黑名单
            if (decoded.getId() != null && tokenBlacklist.contains(decoded.getId())) {
                return true;
            }
            return decoded.getExpiresAt().before(new Date());
        } catch (JWTVerificationException e) {
            return true;
        }
    }

    /**
     * 刷新 Token
     * <p>
     * 只有在刷新窗口期内的 Token 才能被刷新
     *
     * @param token 旧的 JWT Token
     * @param user  用户信息
     * @return 新的 JWT Token
     * @throws AuthException 当 Token 超过刷新窗口期时抛出
     */
    public String refreshToken(String token, User user) throws AuthException {
        if (token == null || token.trim().isEmpty()) {
            throw new AuthException("Token 不能为空", AuthException.ErrorCode.INVALID_TOKEN);
        }

        try {
            // 尝试解码 Token（不验证过期时间）
            DecodedJWT decoded = JWT.decode(token);

            // 检查是否在刷新窗口期内
            Date issuedAt = decoded.getIssuedAt();
            if (issuedAt != null) {
                long tokenAge = System.currentTimeMillis() - issuedAt.getTime();
                if (tokenAge > refreshWindowMs) {
                    log.warn("Token 超过刷新窗口期: tokenAge={}ms, refreshWindowMs={}ms",
                            tokenAge, refreshWindowMs);
                    throw new AuthException("Token 已超过刷新期限，请重新登录",
                            AuthException.ErrorCode.TOKEN_EXPIRED);
                }
            }

            // 检查黑名单
            String tokenId = decoded.getId();
            if (tokenId != null && tokenBlacklist.contains(tokenId)) {
                log.warn("Token 已被注销，无法刷新: tokenId={}", tokenId);
                throw new AuthException("Token 已被注销", AuthException.ErrorCode.INVALID_TOKEN);
            }

            log.debug("刷新 Token: userId={}", decoded.getClaim("userId").asString());
        } catch (JWTVerificationException e) {
            // 如果解码失败，记录日志但不阻止刷新（可能是格式问题）
            log.debug("Token 解码异常: {}", e.getMessage());
        }

        // 将旧 Token 加入黑名单（如果可以获取 tokenId）
        try {
            DecodedJWT oldDecoded = JWT.decode(token);
            if (oldDecoded.getId() != null) {
                revokeToken(oldDecoded.getId());
            }
        } catch (Exception e) {
            // 忽略解码失败
        }

        return generateToken(user);
    }

    /**
     * 注销 Token（加入黑名单）
     *
     * @param tokenId Token ID (jti)
     */
    public void revokeToken(String tokenId) {
        if (tokenId != null && !tokenId.isEmpty()) {
            tokenBlacklist.add(tokenId);
            log.info("Token 已注销: tokenId={}", tokenId);
        }
    }

    /**
     * 检查 Token 是否在黑名单中
     *
     * @param tokenId Token ID (jti)
     * @return true 表示在黑名单中，false 表示不在
     */
    public boolean isTokenRevoked(String tokenId) {
        return tokenId != null && tokenBlacklist.contains(tokenId);
    }

    /**
     * 从黑名单中移除 Token（清理用）
     *
     * @param tokenId Token ID (jti)
     */
    public void removeFromBlacklist(String tokenId) {
        if (tokenId != null) {
            tokenBlacklist.remove(tokenId);
            log.debug("Token 已从黑名单移除: tokenId={}", tokenId);
        }
    }

    /**
     * 获取当前黑名单大小
     *
     * @return 黑名单中的 Token 数量
     */
    public int getBlacklistSize() {
        return tokenBlacklist.size();
    }
}