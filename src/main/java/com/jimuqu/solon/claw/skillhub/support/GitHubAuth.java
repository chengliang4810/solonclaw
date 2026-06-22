package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.noear.snack4.ONode;

/** GitHub API 鉴权辅助。 */
public class GitHubAuth {
    /** GitHub 鉴权辅助的低敏日志记录器，禁止记录 token、JWT、响应体或授权 URL。 */
    private static final Logger LOG = Logger.getLogger(GitHubAuth.class.getName());

    /** 记录Git中心认证中的HTTPClient。 */
    private final SkillHubHttpClient httpClient;

    /** 记录Git中心认证中的cachedtoken。 */
    private String cachedToken;

    /** 记录Git中心认证中的cachedMethod。 */
    private String cachedMethod;

    /** 记录Git中心认证中的应用tokenExpiry时间。 */
    private long appTokenExpiryAt;

    /**
     * 创建Git中心认证实例，并注入运行所需依赖。
     *
     * @param httpClient HTTPClient参数。
     */
    public GitHubAuth(SkillHubHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 读取Headers。
     *
     * @return 返回读取到的Headers。
     */
    public Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/vnd.github.v3+json");
        String token = resolveToken();
        if (StrUtil.isNotBlank(token)) {
            headers.put("Authorization", "token " + token);
        }
        return headers;
    }

    /**
     * 判断是否Authenticated。
     *
     * @return 如果Authenticated满足条件则返回 true，否则返回 false。
     */
    public boolean isAuthenticated() {
        return StrUtil.isNotBlank(resolveToken());
    }

    /**
     * 执行认证Method相关逻辑。
     *
     * @return 返回认证Method结果。
     */
    public String authMethod() {
        resolveToken();
        return StrUtil.blankToDefault(cachedMethod, "anonymous");
    }

    /**
     * 解析token。
     *
     * @return 返回解析后的token。
     */
    private String resolveToken() {
        long now = System.currentTimeMillis();
        if (StrUtil.isNotBlank(cachedToken)
                && (!"github-app".equals(cachedMethod) || now < appTokenExpiryAt)) {
            return cachedToken;
        }

        String configToken =
                StrUtil.blankToDefault(
                        RuntimeConfigResolver.getValue("solonclaw.integrations.github.token"),
                        RuntimeConfigResolver.getValue("solonclaw.integrations.github.cliToken"));
        if (StrUtil.isNotBlank(configToken)) {
            cachedToken = configToken.trim();
            cachedMethod = "pat";
            return cachedToken;
        }

        String ghToken = tryGhCli();
        if (StrUtil.isNotBlank(ghToken)) {
            cachedToken = ghToken;
            cachedMethod = "gh-cli";
            return cachedToken;
        }

        String appToken = tryGitHubApp();
        if (StrUtil.isNotBlank(appToken)) {
            cachedToken = appToken;
            cachedMethod = "github-app";
            appTokenExpiryAt = now + 55L * 60L * 1000L;
            return cachedToken;
        }

        cachedMethod = "anonymous";
        return null;
    }

    /**
     * 执行tryGhCLI相关逻辑。
     *
     * @return 返回try Gh Cli结果。
     */
    private String tryGhCli() {
        try {
            Process process =
                    new ProcessBuilder("gh", "auth", "token").redirectErrorStream(true).start();
            byte[] output = IoUtil.readBytes(process.getInputStream());
            process.waitFor();
            if (process.exitValue() == 0) {
                String token = new String(output, StandardCharsets.UTF_8).trim();
                return StrUtil.blankToDefault(token, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.fine(
                    "GitHub CLI token 读取被中断，已回退到后续鉴权方式：errorType="
                            + e.getClass().getSimpleName());
        } catch (IOException | RuntimeException e) {
            LOG.fine(
                    "GitHub CLI token 读取失败，已回退到后续鉴权方式：errorType="
                            + e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * 执行tryGit中心应用相关逻辑。
     *
     * @return 返回try Git中心App结果。
     */
    private String tryGitHubApp() {
        String appId = RuntimeConfigResolver.getValue("solonclaw.integrations.github.appId");
        String keyPath =
                RuntimeConfigResolver.getValue("solonclaw.integrations.github.privateKeyPath");
        String installationId =
                RuntimeConfigResolver.getValue("solonclaw.integrations.github.installationId");
        if (StrUtil.hasBlank(appId, keyPath, installationId)) {
            return null;
        }

        try {
            File keyFile = FileUtil.file(keyPath);
            if (!keyFile.exists()) {
                return null;
            }
            String privateKeyPem = FileUtil.readUtf8String(keyFile);
            String jwt = buildJwt(privateKeyPem, appId);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + jwt);
            headers.put("Accept", "application/vnd.github.v3+json");
            String response =
                    httpClient.postJson(
                            "https://api.github.com/app/installations/"
                                    + installationId
                                    + "/access_tokens",
                            headers,
                            "{}");
            return ONode.ofJson(response).get("token").getString();
        } catch (Exception e) {
            LOG.fine(
                    "GitHub App token 申请失败，已回退为匿名访问：errorType="
                            + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 构建Jwt。
     *
     * @param privateKeyPem private键Pem参数。
     * @param appId 应用标识。
     * @return 返回创建好的Jwt。
     */
    private String buildJwt(String privateKeyPem, String appId) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson =
                "{\"iat\":"
                        + (now - 60L)
                        + ",\"exp\":"
                        + (now + 600L)
                        + ",\"iss\":\""
                        + appId
                        + "\"}";
        String encodedHeader = Base64.encodeUrlSafe(headerJson);
        String encodedPayload = Base64.encodeUrlSafe(payloadJson);
        String message = encodedHeader + "." + encodedPayload;

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(loadPrivateKey(privateKeyPem));
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.encodeUrlSafe(signature.sign());
        return message + "." + encodedSignature;
    }

    /**
     * 加载私聊键。
     *
     * @param pem pem 参数。
     * @return 返回私聊键结果。
     */
    private PrivateKey loadPrivateKey(String pem) throws Exception {
        String normalized =
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s+", "");
        byte[] decoded = java.util.Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}
