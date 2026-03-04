package com.jimuqu.solonclaw.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT Token 服务测试
 *
 * @author SolonClaw
 */
@DisplayName("JWT Token 服务测试")
class JwtTokenServiceTest {

    private JwtTokenService jwtTokenService;
    private static final String TEST_SECRET = "test-secret-key-for-jwt-token-generation-min-32-chars";

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(TEST_SECRET, 60000);
    }

    @Nested
    @DisplayName("Token 生成测试")
    class TokenGenerationTests {

        @Test
        @DisplayName("应能生成有效的 Token")
        void shouldGenerateValidToken() {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.USER);

            String token = jwtTokenService.generateToken(user);

            assertNotNull(token, "Token 不应为空");
            assertFalse(token.isEmpty(), "Token 不应为空字符串");
            assertTrue(token.split("\\.").length == 3, "Token 应为三段式 JWT 格式");
        }

        @Test
        @DisplayName("生成的 Token 应能被验证")
        void generatedTokenShouldBeVerifiable() {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.USER);
            String token = jwtTokenService.generateToken(user);

            assertDoesNotThrow(() -> jwtTokenService.verifyToken(token),
                    "生成的 Token 应能被成功验证");
        }

        @Test
        @DisplayName("使用错误密钥生成的 Token 应无法验证")
        void tokenWithWrongSecretShouldFailVerification() {
            User user = new User("testuser", "password", "test@example.com");
            JwtTokenService otherService = new JwtTokenService("different-secret-key-min-32-chars-long");
            String token = otherService.generateToken(user);

            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(token),
                    "使用错误密钥生成的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("生成的 Token 应包含正确的 Issuer")
        void generatedTokenShouldContainCorrectIssuer() {
            User user = new User("testuser", "password", "test@example.com");
            String token = jwtTokenService.generateToken(user);

            var decoded = jwtTokenService.verifyToken(token);
            assertEquals("solonclaw", decoded.getIssuer(), "Issuer 应为 solonclaw");
        }

        @Test
        @DisplayName("生成的 Token 应包含 JWT ID")
        void generatedTokenShouldContainJwtId() {
            User user = new User("testuser", "password", "test@example.com");
            String token = jwtTokenService.generateToken(user);

            var decoded = jwtTokenService.verifyToken(token);
            assertNotNull(decoded.getId(), "Token 应包含 JWT ID");
        }

        @Test
        @DisplayName("用户信息为空时应抛出异常")
        void shouldThrowExceptionWhenUserIsNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> jwtTokenService.generateToken(null),
                    "用户为空时应抛出异常");
        }
    }

    @Nested
    @DisplayName("Token 验证测试")
    class TokenVerificationTests {

        @Test
        @DisplayName("验证成功应返回 DecodedJWT")
        void successfulVerificationShouldReturnDecodedJWT() {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.USER);
            String token = jwtTokenService.generateToken(user);

            var decoded = jwtTokenService.verifyToken(token);

            assertNotNull(decoded, "DecodedJWT 不应为空");
            assertEquals(user.getId(), decoded.getClaim("userId").asString(),
                    "Token 中的 userId 应匹配");
            assertEquals("testuser", decoded.getClaim("username").asString(),
                    "Token 中的 username 应匹配");
            assertEquals("USER", decoded.getClaim("role").asString(),
                    "Token 中的 role 应匹配");
        }

        @Test
        @DisplayName("空 Token 验证应失败")
        void emptyTokenShouldFailVerification() {
            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(""),
                    "空 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("null Token 验证应失败")
        void nullTokenShouldFailVerification() {
            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(null),
                    "null Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("无效 Token 验证应失败")
        void invalidTokenShouldFailVerification() {
            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken("invalid.token.here"),
                    "无效 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("篡改载荷的 Token 应验证失败")
        void tamperedPayloadShouldFailVerification() throws Exception {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.USER);
            String token = jwtTokenService.generateToken(user);

            // 篡改 Token 的载荷部分
            String[] parts = token.split("\\.");
            String tamperedPayload = parts[1] + "tampered";
            String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(tamperedToken),
                    "篡改载荷的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("篡改签名的 Token 应验证失败")
        void tamperedSignatureShouldFailVerification() throws Exception {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.USER);
            String token = jwtTokenService.generateToken(user);

            // 篡改签名部分
            String[] parts = token.split("\\.");
            String tamperedSignature = parts[2].substring(0, parts[2].length() - 4) + "xxxx";
            String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(tamperedToken),
                    "篡改签名的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("缺少 Issuer 的 Token 应验证失败")
        void tokenWithoutIssuerShouldFailVerification() throws Exception {
            // 生成没有 Issuer 的 Token
            Algorithm algorithm = Algorithm.HMAC256(TEST_SECRET);
            String tokenWithoutIssuer = JWT.create()
                    .withSubject("test-user")
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(System.currentTimeMillis() + 60000))
                    .sign(algorithm);

            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(tokenWithoutIssuer),
                    "缺少 Issuer 的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("错误 Issuer 的 Token 应验证失败")
        void tokenWithWrongIssuerShouldFailVerification() throws Exception {
            // 生成错误 Issuer 的 Token
            Algorithm algorithm = Algorithm.HMAC256(TEST_SECRET);
            String tokenWithWrongIssuer = JWT.create()
                    .withIssuer("wrong-issuer")
                    .withSubject("test-user")
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(System.currentTimeMillis() + 60000))
                    .sign(algorithm);

            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(tokenWithWrongIssuer),
                    "错误 Issuer 的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Token 过期测试")
    class TokenExpirationTests {

        @Test
        @DisplayName("刚生成的 Token 不应过期")
        void freshTokenShouldNotBeExpired() {
            User user = new User("testuser", "password", "test@example.com");
            String token = jwtTokenService.generateToken(user);

            assertFalse(jwtTokenService.isTokenExpired(token), "刚生成的 Token 不应过期");
        }

        @Test
        @DisplayName("无效 Token 应被视为过期")
        void invalidTokenShouldBeConsideredExpired() {
            assertTrue(jwtTokenService.isTokenExpired("invalid.token"),
                    "无效 Token 应被视为过期");
        }

        @Test
        @DisplayName("空 Token 应被视为过期")
        void emptyTokenShouldBeConsideredExpired() {
            assertTrue(jwtTokenService.isTokenExpired(""), "空 Token 应被视为过期");
            assertTrue(jwtTokenService.isTokenExpired(null), "null Token 应被视为过期");
        }

        @Test
        @DisplayName("过期的 Token 验证应返回 TOKEN_EXPIRED 错误码")
        void expiredTokenShouldReturnExpiredErrorCode() throws Exception {
            // 创建一个立即过期的 Token
            JwtTokenService shortLivedService = new JwtTokenService(TEST_SECRET, 100);
            User user = new User("testuser", "password", "test@example.com");
            String token = shortLivedService.generateToken(user);

            // 等待过期
            Thread.sleep(200);

            AuthException ex = assertThrows(AuthException.class,
                    () -> shortLivedService.verifyToken(token),
                    "过期的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.TOKEN_EXPIRED, ex.getErrorCode(),
                    "过期 Token 应返回 TOKEN_EXPIRED 错误码");
        }
    }

    @Nested
    @DisplayName("Token 刷新测试")
    class TokenRefreshTests {

        @Test
        @DisplayName("应能刷新有效的 Token")
        void shouldRefreshValidToken() throws InterruptedException {
            User user = new User("testuser", "password", "test@example.com");
            String oldToken = jwtTokenService.generateToken(user);

            // 等待 1 秒确保时间戳不同（JWT 时间精度为秒）
            Thread.sleep(1100);

            String newToken = jwtTokenService.refreshToken(oldToken, user);

            assertNotNull(newToken, "新 Token 不应为空");
            assertNotEquals(oldToken, newToken, "新 Token 应与旧 Token 不同");
            assertDoesNotThrow(() -> jwtTokenService.verifyToken(newToken),
                    "新 Token 应能被验证");
        }

        @Test
        @DisplayName("应能刷新过期的 Token")
        void shouldRefreshExpiredToken() throws InterruptedException {
            User user = new User("testuser", "password", "test@example.com");
            JwtTokenService shortLivedService = new JwtTokenService(TEST_SECRET, 1000, 5000);
            String oldToken = shortLivedService.generateToken(user);

            // 等待 Token 过期
            Thread.sleep(1100);

            // 验证旧 Token 已过期
            assertTrue(shortLivedService.isTokenExpired(oldToken), "旧 Token 应已过期");

            // 刷新 token，生成新的
            String newToken = shortLivedService.refreshToken(oldToken, user);

            assertNotNull(newToken, "新 Token 不应为空");
            assertFalse(shortLivedService.isTokenExpired(newToken), "新 Token 不应过期");
        }

        @Test
        @DisplayName("空 Token 刷新应失败")
        void shouldFailRefreshWithEmptyToken() {
            User user = new User("testuser", "password", "test@example.com");

            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.refreshToken("", user),
                    "空 Token 刷新应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Token 黑名单测试")
    class TokenBlacklistTests {

        @Test
        @DisplayName("应能注销 Token")
        void shouldRevokeToken() {
            User user = new User("testuser", "password", "test@example.com");
            String token = jwtTokenService.generateToken(user);

            // 获取 Token ID
            String tokenId = jwtTokenService.getTokenId(token);
            assertNotNull(tokenId, "Token ID 不应为空");

            // 验证 Token 有效
            assertDoesNotThrow(() -> jwtTokenService.verifyToken(token));

            // 注销 Token
            jwtTokenService.revokeToken(tokenId);

            // 验证 Token 已失效
            assertTrue(jwtTokenService.isTokenRevoked(tokenId), "Token 应在黑名单中");
            AuthException ex = assertThrows(AuthException.class,
                    () -> jwtTokenService.verifyToken(token),
                    "已注销的 Token 验证应失败");
            assertEquals(AuthException.ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        }

        @Test
        @DisplayName("应能检查 Token 是否在黑名单")
        void shouldCheckIfTokenIsRevoked() {
            String tokenId = "test-token-id";

            assertFalse(jwtTokenService.isTokenRevoked(tokenId), "Token 不应在黑名单中");

            jwtTokenService.revokeToken(tokenId);

            assertTrue(jwtTokenService.isTokenRevoked(tokenId), "Token 应在黑名单中");
        }

        @Test
        @DisplayName("应能从黑名单中移除 Token")
        void shouldRemoveFromBlacklist() {
            String tokenId = "test-token-id";
            jwtTokenService.revokeToken(tokenId);

            assertTrue(jwtTokenService.isTokenRevoked(tokenId), "Token 应在黑名单中");

            jwtTokenService.removeFromBlacklist(tokenId);

            assertFalse(jwtTokenService.isTokenRevoked(tokenId), "Token 不应在黑名单中");
        }

        @Test
        @DisplayName("应能获取黑名单大小")
        void shouldGetBlacklistSize() {
            assertEquals(0, jwtTokenService.getBlacklistSize(), "初始黑名单大小应为 0");

            jwtTokenService.revokeToken("token-1");
            assertEquals(1, jwtTokenService.getBlacklistSize(), "黑名单大小应为 1");

            jwtTokenService.revokeToken("token-2");
            assertEquals(2, jwtTokenService.getBlacklistSize(), "黑名单大小应为 2");
        }
    }

    @Nested
    @DisplayName("密钥安全测试")
    class SecretSecurityTests {

        @Test
        @DisplayName("空密钥应抛出异常")
        void shouldThrowExceptionForNullSecret() {
            assertThrows(IllegalArgumentException.class,
                    () -> new JwtTokenService(null),
                    "空密钥应抛出异常");
        }

        @Test
        @DisplayName("空字符串密钥应抛出异常")
        void shouldThrowExceptionForEmptySecret() {
            assertThrows(IllegalArgumentException.class,
                    () -> new JwtTokenService(""),
                    "空字符串密钥应抛出异常");
        }

        @Test
        @DisplayName("短密钥应发出警告但仍能工作")
        void shouldWarnForShortSecret() {
            // 短密钥应该仍然可以工作，只是会有警告日志
            assertDoesNotThrow(() -> new JwtTokenService("short-key"),
                    "短密钥应能工作（但会有警告）");
        }
    }

    @Nested
    @DisplayName("Token 信息提取测试")
    class TokenInfoExtractionTests {

        @Test
        @DisplayName("应能从 Token 中提取用户 ID")
        void shouldExtractUserIdFromToken() {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.USER);
            String token = jwtTokenService.generateToken(user);

            String userId = jwtTokenService.getUserIdFromToken(token);

            assertEquals(user.getId(), userId, "提取的 userId 应匹配");
        }

        @Test
        @DisplayName("应能从 Token 中提取角色")
        void shouldExtractRoleFromToken() {
            User user = new User("testuser", "password", "test@example.com");
            user.setRole(UserRole.ADMIN);
            String token = jwtTokenService.generateToken(user);

            String role = jwtTokenService.getRoleFromToken(token);

            assertEquals("ADMIN", role, "提取的 role 应匹配");
        }

        @Test
        @DisplayName("应能从 Token 中提取 Token ID")
        void shouldExtractTokenIdFromToken() {
            User user = new User("testuser", "password", "test@example.com");
            String token = jwtTokenService.generateToken(user);

            String tokenId = jwtTokenService.getTokenId(token);

            assertNotNull(tokenId, "Token ID 不应为空");
            assertFalse(tokenId.isEmpty(), "Token ID 不应为空字符串");
        }

        @Test
        @DisplayName("从无效 Token 提取信息应失败")
        void extractingFromInvalidTokenShouldFail() {
            assertThrows(AuthException.class,
                    () -> jwtTokenService.getUserIdFromToken("invalid"),
                    "从无效 Token 提取信息应失败");
        }
    }

    @Nested
    @DisplayName("刷新窗口期测试")
    class RefreshWindowTests {

        @Test
        @DisplayName("超过刷新窗口期的 Token 应无法刷新")
        void shouldNotRefreshTokenBeyondRefreshWindow() throws Exception {
            JwtTokenService service = new JwtTokenService(TEST_SECRET, 1000, 2000);
            User user = new User("testuser", "password", "test@example.com");

            // 手动创建一个超过刷新窗口期的 Token
            Algorithm algorithm = Algorithm.HMAC256(TEST_SECRET);
            String oldToken = JWT.create()
                    .withIssuer("solonclaw")
                    .withSubject(user.getId())
                    .withIssuedAt(new Date(System.currentTimeMillis() - 3000)) // 3秒前
                    .withExpiresAt(new Date(System.currentTimeMillis() - 1000)) // 已过期
                    .sign(algorithm);

            AuthException ex = assertThrows(AuthException.class,
                    () -> service.refreshToken(oldToken, user),
                    "超过刷新窗口期的 Token 应无法刷新");
            assertEquals(AuthException.ErrorCode.TOKEN_EXPIRED, ex.getErrorCode());
        }
    }
}
