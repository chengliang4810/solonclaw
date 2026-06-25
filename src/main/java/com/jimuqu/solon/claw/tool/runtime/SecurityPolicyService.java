package com.jimuqu.solon.claw.tool.runtime;

import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.BARE_HOST_FETCH_CONTEXT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.BARE_HOST_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.BLOCKED_DEVICE_PATHS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.COMPACT_CREDENTIAL_PATH_OPTION_PREFIXES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.CREDENTIAL_DIR_SEGMENTS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.CREDENTIAL_FILE_NAMES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.CREDENTIAL_PATH_OPTION_NAMES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.CREDENTIAL_PATH_SUFFIXES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.IPV4_CIDR_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.IPV6_CIDR_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.JAVA_PROXY_OPTIONS_ASSIGNMENT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.LOCAL_MANAGEMENT_PIPE_PATHS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.LOCAL_MANAGEMENT_SOCKET_PATHS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.NETWORK_CREDENTIAL_SHORT_OPTIONS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.NETWORK_UPLOAD_FILE_OPTIONS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.NETWORK_UPLOAD_FILE_SHORT_OPTIONS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_LOCAL_MANAGEMENT_ENV_ASSIGNMENT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_PROXY_ENV_ASSIGNMENT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_REGISTRY_PROXY_PROPERTY_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_REGISTRY_PROXY_VALUE_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.PROC_STDIO_FD_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.QUOTED_WINDOWS_PATH_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.RAW_BLOCK_DEVICE_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.RUNTIME_CREDENTIAL_FILE_PATHS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SENSITIVE_KEY_FILE_EXTENSIONS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SENSITIVE_KEY_FILE_MARKERS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SENSITIVE_URL_PARAMETER_NAMES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SHELL_CREDENTIAL_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SHELL_PATH_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SHELL_RELATIVE_CREDENTIAL_PATH_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.SSH_FILE_CONFIG_OPTION_NAMES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.URLISH_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WORKDIR_SAFE_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_EXACT_PATHS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_HOME_FILE_NAMES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_PREFIXES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_WINDOWS_PREFIXES;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 提供安全策略相关业务能力，封装调用方不需要感知的运行细节。 */
public class SecurityPolicyService {
    /** 记录 URL 与文件安全策略的保守阻断和降级原因。 */
    private static final Logger log = LoggerFactory.getLogger(SecurityPolicyService.class);

    /** 注入应用配置，用于安全策略。 */
    private final AppConfig appConfig;

    /** 当前线程已通过人工审批的一次性文件策略键集合。 */
    private static final ThreadLocal<Collection<String>> APPROVED_FILE_POLICY_KEYS =
            new ThreadLocal<Collection<String>>();

    /** 当前线程已通过人工审批的一次性 URL 策略键集合。 */
    private static final ThreadLocal<Collection<String>> APPROVED_URL_POLICY_KEYS =
            new ThreadLocal<Collection<String>>();

    /** 当前线程是否只预览策略审批命中而不消费审批。 */
    private static final ThreadLocal<Boolean> POLICY_APPROVAL_PREVIEW =
            new ThreadLocal<Boolean>();

    /**
     * 创建安全策略服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SecurityPolicyService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 读取App配置。
     *
     * @return 返回读取到的App配置。
     */
    AppConfig getAppConfig() {
        return appConfig;
    }

    /**
     * 检查URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL结果。
     */
    public UrlVerdict checkUrl(String url) {
        UrlVerdict verdict = checkUrlSafety(url, null);
        if (!verdict.allowed) {
            return verdict;
        }
        return checkExternalUrlApproval(normalizeExternalApprovalUrl(url));
    }

    /**
     * 检查URL Allowing私聊。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL Allowing私聊结果。
     */
    public UrlVerdict checkUrlAllowingPrivate(String url) {
        UrlVerdict verdict = checkUrlSafety(url, Boolean.TRUE);
        if (!verdict.allowed) {
            return verdict;
        }
        return checkExternalUrlApproval(normalizeExternalApprovalUrl(url));
    }

    /**
     * 检查URL 块ing私聊。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL 块ing私聊结果。
     */
    public UrlVerdict checkUrlBlockingPrivate(String url) {
        UrlVerdict verdict = checkUrlSafety(url, Boolean.FALSE);
        if (!verdict.allowed) {
            return verdict;
        }
        return checkExternalUrlApproval(normalizeExternalApprovalUrl(url));
    }

    /**
     * 检查工具返回内容中的 URL 硬安全边界；返回内容扫描不代表再次发起网络访问，因此不消费网络审批。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回硬安全边界判定。
     */
    public UrlVerdict checkReturnedUrl(String url) {
        return checkUrlSafety(url, null);
    }

    /**
     * 判断是否Always 块ed URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 如果Always 块ed URL满足条件则返回 true，否则返回 false。
     */
    public boolean isAlwaysBlockedUrl(String url) {
        return !checkAlwaysBlockedUrl(url).isAllowed();
    }

    /**
     * 检查Always 块ed URL。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回Always 块ed URL结果。
     */
    public UrlVerdict checkAlwaysBlockedUrl(String url) {
        String raw = normalizeUrlText(url);
        if (raw.startsWith("//")) {
            return checkAlwaysBlockedUrl("http:" + raw);
        }
        if (raw.length() == 0 || !raw.contains("://")) {
            return UrlVerdict.allow();
        }
        URI uri = parseUri(raw);
        if (uri == null) {
            return UrlVerdict.block(raw, "URL 解析失败，无法确认是否触达云元数据或链路本地地址");
        }
        String host = extractUriHost(uri);
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.allow();
        }
        UrlVerdict hostVerdict = checkAlwaysBlockedHost(raw, host);
        if (!hostVerdict.allowed) {
            return hostVerdict;
        }
        try {
            InetAddress[] addresses = resolveHost(host);
            for (InetAddress address : addresses) {
                String ip = address.getHostAddress();
                if (SecurityAddressPolicySupport.isAlwaysBlockedIp(ip) || SecurityAddressPolicySupport.isAlwaysBlockedAddress(address)) {
                    return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
                }
            }
        } catch (Exception e) {
            log.warn(
                    "URL 硬安全边界解析失败，按 fail-closed 阻断: host={}, error={}",
                    host,
                    e.toString());
            return UrlVerdict.block(raw, "DNS 解析失败，无法确认是否触达云元数据或链路本地地址：" + host);
        }
        return UrlVerdict.allow();
    }

    /**
     * 仅检查 URL 安全硬边界，不消费外部网络审批。
     *
     * @param url 待校验或访问的 URL。
     * @param allowPrivateOverride allowPrivateOverride标识或键值。
     * @return 返回URL结果。
     */
    public UrlVerdict checkUrlSafety(String url, Boolean allowPrivateOverride) {
        String raw = normalizeUrlText(url);
        if (raw.length() == 0) {
            return UrlVerdict.block(raw, "URL 缺少内容");
        }
        if (SecretRedactor.containsSecretLikeToken(raw)) {
            return UrlVerdict.block(raw, "URL 包含疑似 API key 或 token，禁止通过 URL 发送凭据");
        }

        if (raw.startsWith("//")) {
            return checkUrlSafety("http:" + raw, allowPrivateOverride);
        }
        if (!raw.contains("://")) {
            if (hasSchemelessUserInfo(raw)) {
                return UrlVerdict.block(raw, "URL 包含 userinfo 凭据，禁止通过 URL 发送用户名或密码");
            }
            String schemelessHost = extractSchemelessHost(raw);
            if (StrUtil.isBlank(schemelessHost)) {
                return UrlVerdict.allow();
            }
            if (hasSensitiveSchemelessUrlParameterName(raw)) {
                return UrlVerdict.block(raw, "URL 包含敏感凭据参数，禁止通过 URL 发送凭据");
            }
            return checkSchemelessHostAccess(raw, schemelessHost, allowPrivateOverride);
        }

        URI uri = parseUri(raw);
        if (uri == null) {
            return UrlVerdict.block(raw, "URL 解析失败");
        }

        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)
                && !"https".equals(scheme)
                && !"ws".equals(scheme)
                && !"wss".equals(scheme)) {
            return UrlVerdict.block(raw, "仅允许 http/https/ws/wss URL");
        }
        if (hasUserInfo(uri)) {
            return UrlVerdict.block(raw, "URL 包含 userinfo 凭据，禁止通过 URL 发送用户名或密码");
        }

        String host = extractUriHost(uri);
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.block(raw, "URL 缺少主机名");
        }

        UrlVerdict staticHostVerdict = checkStaticHostPolicy(raw, host);
        if (!staticHostVerdict.allowed) {
            return staticHostVerdict;
        }
        if (hasSensitiveUrlParameterName(uri)) {
            return UrlVerdict.block(raw, "URL 包含敏感凭据参数，禁止通过 URL 发送凭据");
        }
        return checkHostAccess(raw, scheme, host, allowPrivateOverride);
    }

    /**
     * 检查Schemeless Host Access。
     *
     * @param raw 原始输入值。
     * @param host 主机参数。
     * @param allowPrivateOverride allowPrivateOverride标识或键值。
     * @return 返回Schemeless Host Access结果。
     */
    private UrlVerdict checkSchemelessHostAccess(
            String raw, String host, Boolean allowPrivateOverride) {
        if (SecurityAddressPolicySupport.isAlwaysBlockedHost(host)) {
            return UrlVerdict.block(raw, "阻断云元数据/内部主机：" + host);
        }
        WebsiteRule websiteRule = checkWebsitePolicy(raw, host);
        if (websiteRule != null) {
            return UrlVerdict.block(
                    raw,
                    "Blocked by website policy: '"
                            + host
                            + "' matched rule '"
                            + websiteRule.rule
                            + "'");
        }
        if (SecurityAddressPolicySupport.isLocalOrAddressLiteral(host)) {
            return checkHostAccess(raw, "", host, allowPrivateOverride);
        }
        return UrlVerdict.allow();
    }

    /**
     * 检查Host Access。
     *
     * @param raw 原始输入值。
     * @param scheme scheme 参数。
     * @param host 主机参数。
     * @param allowPrivateOverride allowPrivateOverride标识或键值。
     * @return 返回Host Access结果。
     */
    private UrlVerdict checkHostAccess(
            String raw, String scheme, String host, Boolean allowPrivateOverride) {
        UrlVerdict staticHostVerdict = checkStaticHostPolicy(raw, host);
        if (!staticHostVerdict.allowed) {
            return staticHostVerdict;
        }

        boolean allowPrivate =
                allowPrivateOverride == null
                        ? resolveAllowPrivateUrls()
                        : allowPrivateOverride.booleanValue();
        boolean trustedPrivateHost =
                ("https".equals(scheme) || "wss".equals(scheme))
                        && SecurityAddressPolicySupport.isTrustedPrivateIpHost(host);

        int[] hostIpv4 = SecurityAddressPolicySupport.parseIpv4HostLiteral(host);

        // 先检查字面量 IP，避免 DNS 解析绕过云元数据和内网地址拦截。
        if (hostIpv4 != null) {
            String ip = SecurityAddressPolicySupport.formatIpv4(hostIpv4);
            if (SecurityAddressPolicySupport.isAlwaysBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
                return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
            }
            if (!allowPrivate
                    && SecurityAddressPolicySupport.isBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
                return UrlVerdict.block(raw, "阻断内网/私有地址：" + host + " -> " + ip);
            }
        }

        try {
            InetAddress[] addresses = resolveHost(host);
            for (InetAddress address : addresses) {
                String ip = address.getHostAddress();
                if (SecurityAddressPolicySupport.isAlwaysBlockedIp(ip) || SecurityAddressPolicySupport.isAlwaysBlockedAddress(address)) {
                    return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
                }
                if (!allowPrivate
                        && SecurityAddressPolicySupport.isPrivateOrInternal(address, ip)
                        && !(trustedPrivateHost && SecurityAddressPolicySupport.isTrustedPrivateAddress(address))) {
                    return UrlVerdict.block(raw, "阻断内网/私有地址：" + host + " -> " + ip);
                }
            }
        } catch (Exception e) {
            return UrlVerdict.block(raw, "DNS 解析失败或 URL 安全检查失败：" + host);
        }

        return UrlVerdict.allow();
    }

    /**
     * 检查静态资源Host策略。
     *
     * @param raw 原始输入值。
     * @param host 主机参数。
     * @return 返回静态资源Host策略结果。
     */
    private UrlVerdict checkStaticHostPolicy(String raw, String host) {
        UrlVerdict alwaysBlockedHost = checkAlwaysBlockedHost(raw, host);
        if (!alwaysBlockedHost.allowed) {
            return alwaysBlockedHost;
        }

        WebsiteRule websiteRule = checkWebsitePolicy(raw, host);
        if (websiteRule != null) {
            return UrlVerdict.block(
                    raw,
                    "Blocked by website policy: '"
                            + host
                            + "' matched rule '"
                            + websiteRule.rule
                            + "'");
        }
        return UrlVerdict.allow();
    }

    /**
     * 检查Always 块ed Host。
     *
     * @param raw 原始输入值。
     * @param host 主机参数。
     * @return 返回Always 块ed Host结果。
     */
    private UrlVerdict checkAlwaysBlockedHost(String raw, String host) {
        if (SecurityAddressPolicySupport.isAlwaysBlockedHost(host)) {
            return UrlVerdict.block(raw, "阻断云元数据/内部主机：" + host);
        }
        if (SecurityAddressPolicySupport.isAlwaysBlockedIp(host)) {
            return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host);
        }
        InetAddress literalAddress = parseAddressLiteral(host);
        if (literalAddress != null && SecurityAddressPolicySupport.isAlwaysBlockedAddress(literalAddress)) {
            return UrlVerdict.block(
                    raw, "阻断云元数据/链路本地地址：" + host + " -> " + literalAddress.getHostAddress());
        }
        int[] hostIpv4 = SecurityAddressPolicySupport.parseObfuscatedIpv4(host);
        if (hostIpv4 != null
                && SecurityAddressPolicySupport.isAlwaysBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
            return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + SecurityAddressPolicySupport.formatIpv4(hostIpv4));
        }
        return UrlVerdict.allow();
    }

    /**
     * 解析Address Literal。
     *
     * @param host 主机参数。
     * @return 返回解析后的Address Literal。
     */
    private InetAddress parseAddressLiteral(String host) {
        String value = StrUtil.nullToEmpty(host).trim();
        if (value.indexOf(':') < 0) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (Exception e) {
            log.debug("Address literal parsing failed; treating host as a regular name: {}", exceptionSummary(e));
            return null;
        }
    }

    /**
     * 检查工具参数。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回工具参数结果。
     */
    public UrlVerdict checkToolArgs(String toolName, java.util.Map<String, Object> args) {
        ToolArgCredentialVerdict credentialVerdict = checkStructuredCredentialToolArgs(args);
        if (!credentialVerdict.allowed) {
            return UrlVerdict.block(credentialVerdict.reference, "工具参数包含敏感凭据字段，禁止通过结构化请求参数发送凭据");
        }
        List<String> urls = extractUrls(toolName, args);
        List<String> normalizedUrls = new ArrayList<String>();
        for (String url : urls) {
            String normalizedUrl = normalizeToolUrlForCheck(toolName, url);
            if (!normalizedUrls.contains(normalizedUrl)) {
                normalizedUrls.add(normalizedUrl);
            }
            UrlVerdict verdict = checkUrlSafety(normalizedUrl, null);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        for (String normalizedUrl : normalizedUrls) {
            UrlVerdict externalVerdict = checkExternalUrlApproval(normalizedUrl);
            if (externalVerdict.allowed && isSchemelessPublicNetworkTarget(normalizedUrl)) {
                externalVerdict = checkExternalNetworkOperation(toolName);
            }
            if (!externalVerdict.allowed) {
                return externalVerdict;
            }
        }
        return UrlVerdict.allow();
    }

    /**
     * 检查没有显式 URL 参数但会发起外部网络访问的工具。
     *
     * @param toolName 工具名称。
     * @return 返回 URL 策略判定。
     */
    public UrlVerdict checkExternalNetworkOperation(String toolName) {
        UrlVerdict networkVerdict =
                UrlVerdict.approvalRequired(
                        "tool://" + StrUtil.nullToEmpty(toolName).trim(),
                        "network_external_operation",
                        "网络外部操作需要审批");
        return isUrlPolicyApproved(networkVerdict.getApprovalToken())
                ? UrlVerdict.allow()
                : networkVerdict;
    }

    /**
     * 解析Host。
     *
     * @param host 主机参数。
     * @return 返回解析后的Host。
     */
    protected InetAddress[] resolveHost(String host) throws Exception {
        return InetAddress.getAllByName(host);
    }

    /**
     * 读取Environment。
     *
     * @param name 名称参数。
     * @return 返回读取到的Environment。
     */
    protected String readEnvironment(String name) {
        return System.getenv(name);
    }

    /**
     * 检查文件工具参数。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回文件工具参数结果。
     */
    public FileVerdict checkFileToolArgs(String toolName, Map<String, Object> args) {
        List<String> paths = extractPaths(toolName, args);
        boolean writeLike = isWriteLikeTool(toolName) || hasWriteIntent(args);
        for (String path : paths) {
            FileVerdict verdict = checkPath(path, writeLike);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return FileVerdict.allow();
    }

    /**
     * 检查Structured凭据工具参数。
     *
     * @param args 工具或命令参数。
     * @return 返回Structured凭据工具参数结果。
     */
    private ToolArgCredentialVerdict checkStructuredCredentialToolArgs(Object args) {
        ToolArgCredentialVerdict verdict = new ToolArgCredentialVerdict();
        checkStructuredCredentialToolArgs(args, "", false, verdict, 0);
        return verdict;
    }

    /**
     * 检查Structured凭据工具参数。
     *
     * @param raw 原始输入值。
     * @param key 配置键或映射键。
     * @param requestContext 请求上下文上下文。
     * @param verdict 判定参数。
     * @param depth depth 参数。
     */
    @SuppressWarnings("unchecked")
    private void checkStructuredCredentialToolArgs(
            Object raw,
            String key,
            boolean requestContext,
            ToolArgCredentialVerdict verdict,
            int depth) {
        if (!verdict.allowed || raw == null || depth > 8) {
            return;
        }
        String normalizedKey = normalizeStructuredCredentialKey(key);
        boolean nextRequestContext =
                requestContext || looksLikeRequestContextKey(normalizedKey) || looksLikeUrlKey(key);
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String childKey = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                String normalizedChildKey = normalizeStructuredCredentialKey(childKey);
                if (looksLikeSensitiveStructuredCredentialKey(normalizedChildKey)
                        && hasStructuredCredentialValue(value)) {
                    verdict.block(childKey, normalizedChildKey);
                    return;
                }
                checkStructuredCredentialToolArgs(
                        value, childKey, nextRequestContext, verdict, depth + 1);
                if (!verdict.allowed) {
                    return;
                }
            }
            return;
        }
        if (raw instanceof Collection) {
            for (Object item : (Collection<Object>) raw) {
                checkStructuredCredentialToolArgs(
                        item, key, nextRequestContext, verdict, depth + 1);
                if (!verdict.allowed) {
                    return;
                }
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                checkStructuredCredentialToolArgs(
                        java.lang.reflect.Array.get(raw, i),
                        key,
                        nextRequestContext,
                        verdict,
                        depth + 1);
                if (!verdict.allowed) {
                    return;
                }
            }
            return;
        }
        if (requestContext
                && looksLikeSensitiveStructuredCredentialKey(normalizedKey)
                && hasStructuredCredentialValue(raw)) {
            verdict.block(key, normalizedKey);
        }
    }

    /**
     * 判断是否具有请求上下文键特征。
     *
     * @param normalizedKey normalized键标识或键值。
     * @return 返回looks Like请求上下文键结果。
     */
    private boolean looksLikeRequestContextKey(String normalizedKey) {
        return "headers".equals(normalizedKey)
                || "header".equals(normalizedKey)
                || "request_headers".equals(normalizedKey)
                || "http_headers".equals(normalizedKey)
                || "params".equals(normalizedKey)
                || "query".equals(normalizedKey)
                || "query_params".equals(normalizedKey)
                || "form".equals(normalizedKey)
                || "form_data".equals(normalizedKey)
                || "body".equals(normalizedKey)
                || "json".equals(normalizedKey)
                || "payload".equals(normalizedKey)
                || "data".equals(normalizedKey);
    }

    /**
     * 判断是否具有SensitiveStructured凭据键特征。
     *
     * @param normalizedKey normalized键标识或键值。
     * @return 返回looks Like Sensitive Structured凭据键结果。
     */
    private boolean looksLikeSensitiveStructuredCredentialKey(String normalizedKey) {
        return isStrongSensitiveStructuredCredentialName(normalizedKey)
                || "token".equals(normalizedKey)
                || normalizedKey.startsWith("token_")
                || "secret".equals(normalizedKey)
                || normalizedKey.startsWith("secret_")
                || "credential".equals(normalizedKey)
                || normalizedKey.startsWith("credential_")
                || "credentials".equals(normalizedKey)
                || normalizedKey.startsWith("credentials_")
                || "cookie".equals(normalizedKey)
                || normalizedKey.startsWith("cookie_")
                || "x_api_key".equals(normalizedKey)
                || normalizedKey.startsWith("x_api_key_")
                || "x_api_token".equals(normalizedKey)
                || normalizedKey.startsWith("x_api_token_")
                || "x_auth_token".equals(normalizedKey)
                || normalizedKey.startsWith("x_auth_token_")
                || "auth".equals(normalizedKey);
    }

    /**
     * 判断是否Strong Sensitive Structured凭据名称。
     *
     * @param normalizedKey normalized键标识或键值。
     * @return 如果Strong Sensitive Structured凭据名称满足条件则返回 true，否则返回 false。
     */
    private boolean isStrongSensitiveStructuredCredentialName(String normalizedKey) {
        return isStrongSensitiveUrlParameterName(normalizedKey)
                || normalizedKey.startsWith("authorization_")
                || normalizedKey.startsWith("proxy_authorization_")
                || normalizedKey.startsWith("bearer_token_")
                || normalizedKey.startsWith("api_key_")
                || normalizedKey.startsWith("apikey_")
                || normalizedKey.startsWith("access_token_")
                || normalizedKey.startsWith("refresh_token_")
                || normalizedKey.startsWith("id_token_")
                || normalizedKey.startsWith("auth_token_")
                || normalizedKey.startsWith("oauth_token_")
                || normalizedKey.startsWith("client_secret_")
                || normalizedKey.startsWith("private_key_")
                || normalizedKey.startsWith("secret_key_")
                || normalizedKey.startsWith("session_token_")
                || normalizedKey.startsWith("security_token_");
    }

    /**
     * 判断是否存在Structured凭据Value。
     *
     * @param raw 原始输入值。
     * @return 如果Structured凭据Value满足条件则返回 true，否则返回 false。
     */
    private boolean hasStructuredCredentialValue(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Map || raw instanceof Collection || raw.getClass().isArray()) {
            return true;
        }
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim();
        if (value.length() == 0) {
            return false;
        }
        return value.length() >= 6 || SecretRedactor.containsSecretLikeToken(value);
    }

    /**
     * 规范化Structured凭据键。
     *
     * @param rawKey 原始键标识或键值。
     * @return 返回Structured凭据键结果。
     */
    private String normalizeStructuredCredentialKey(String rawKey) {
        return normalizeSensitiveParameterName(rawKey);
    }

    /**
     * 执行凭据策略摘要相关逻辑。
     *
     * @return 返回凭据策略Summary结果。
     */
    public Map<String, Object> credentialPolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("directorySegmentCount", Integer.valueOf(CREDENTIAL_DIR_SEGMENTS.size()));
        summary.put("fileNameCount", Integer.valueOf(CREDENTIAL_FILE_NAMES.size()));
        summary.put("pathSuffixCount", Integer.valueOf(CREDENTIAL_PATH_SUFFIXES.size()));
        summary.put("keyFileExtensionCount", Integer.valueOf(SENSITIVE_KEY_FILE_EXTENSIONS.size()));
        summary.put("keyFileMarkerCount", Integer.valueOf(SENSITIVE_KEY_FILE_MARKERS.size()));
        summary.put(
                "configuredCredentialFileCount",
                Integer.valueOf(configuredCredentialFiles().size()));
        summary.put("directorySegmentSamples", sample(CREDENTIAL_DIR_SEGMENTS, 6));
        summary.put("fileNameSamples", sample(CREDENTIAL_FILE_NAMES, 8));
        summary.put("pathSuffixSamples", sample(CREDENTIAL_PATH_SUFFIXES, 4));
        summary.put(
                "configuredCredentialFileSamples", redactSample(configuredCredentialFiles(), 6));
        summary.put("envExampleFilesAllowed", Boolean.TRUE);
        summary.put("projectEnvFileReadBlocked", Boolean.TRUE);
        summary.put("projectEnvFileWriteBlocked", Boolean.TRUE);
        summary.put("credentialPathReadBlocked", Boolean.TRUE);
        summary.put("credentialPathWriteBlocked", Boolean.TRUE);
        summary.put("writePolicySharesCredentialClassifier", Boolean.TRUE);
        summary.put(
                "description",
                "Credential paths are blocked for file tools, patch targets, command reads, writes, archives, uploads, and compact output paths.");
        return summary;
    }

    /**
     * 执行URL策略摘要相关逻辑。
     *
     * @return 返回URL策略Summary结果。
     */
    public Map<String, Object> urlPolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        boolean allowPrivate = resolveAllowPrivateUrls();
        AppConfig.WebsiteBlocklistConfig blocklist = websiteBlocklistConfig();
        List<String> configuredDomains =
                blocklist == null || blocklist.getDomains() == null
                        ? Collections.<String>emptyList()
                        : blocklist.getDomains();
        List<String> configuredSharedFiles =
                blocklist == null || blocklist.getSharedFiles() == null
                        ? Collections.<String>emptyList()
                        : blocklist.getSharedFiles();
        SharedWebsiteRuleSummary shared = sharedWebsiteRuleSummary();
        summary.put("allowPrivateUrls", Boolean.valueOf(allowPrivate));
        summary.put(
                "alwaysBlockedHostCount",
                Integer.valueOf(SecurityAddressPolicySupport.alwaysBlockedHosts().size()));
        summary.put(
                "alwaysBlockedIpCount",
                Integer.valueOf(SecurityAddressPolicySupport.alwaysBlockedIps().size()));
        summary.put(
                "trustedPrivateIpHostCount",
                Integer.valueOf(SecurityAddressPolicySupport.trustedPrivateIpHosts().size()));
        summary.put(
                "alwaysBlockedHostSamples",
                sample(SecurityAddressPolicySupport.alwaysBlockedHosts(), 4));
        summary.put(
                "alwaysBlockedIpSamples",
                sample(SecurityAddressPolicySupport.alwaysBlockedIps(), 4));
        summary.put(
                "sensitiveQueryNameCount", Integer.valueOf(SENSITIVE_URL_PARAMETER_NAMES.size()));
        summary.put("sensitiveQueryNameSamples", sample(SENSITIVE_URL_PARAMETER_NAMES, 6));
        summary.put(
                "websiteBlocklistEnabled",
                Boolean.valueOf(blocklist != null && blocklist.isEnabled()));
        summary.put("websiteBlocklistDomainCount", Integer.valueOf(configuredDomains.size()));
        summary.put("websiteBlocklistDomainSamples", redactSample(configuredDomains, 6));
        summary.put(
                "websiteBlocklistSharedFileCount", Integer.valueOf(configuredSharedFiles.size()));
        summary.put("websiteBlocklistSharedFileSamples", redactSample(configuredSharedFiles, 6));
        summary.put("websiteBlocklistSharedRuleCount", Integer.valueOf(shared.ruleCount));
        summary.put(
                "websiteBlocklistLoadedSharedFileCount", Integer.valueOf(shared.loadedFileCount));
        summary.put(
                "websiteBlocklistSkippedSharedFileCount", Integer.valueOf(shared.skippedFileCount));
        summary.put("websiteBlocklistSharedRuleSamples", redactSample(shared.ruleSamples, 6));
        summary.put("allowedNetworkSchemes", Arrays.asList("http", "https", "ws", "wss"));
        summary.put("unsupportedNetworkSchemeBlocked", Boolean.TRUE);
        summary.put("protocolRelativeUrlChecked", Boolean.TRUE);
        summary.put("schemelessHostChecked", Boolean.TRUE);
        summary.put("percentEncodedHostChecked", Boolean.TRUE);
        summary.put("idnHostNormalized", Boolean.TRUE);
        summary.put("dnsResolutionRequired", Boolean.TRUE);
        summary.put("systemDnsCommandChecked", Boolean.TRUE);
        summary.put("powershellProxyEnvironmentChecked", Boolean.TRUE);
        summary.put("setxProxyEnvironmentChecked", Boolean.TRUE);
        summary.put("systemProxyCommandChecked", Boolean.TRUE);
        summary.put("windowsRegistryProxyCommandChecked", Boolean.TRUE);
        summary.put("proxyBypassEnvironmentChecked", Boolean.TRUE);
        summary.put("gitPersistentProxyConfigChecked", Boolean.TRUE);
        summary.put("packageManagerProxyBypassEnvironmentChecked", Boolean.TRUE);
        summary.put("packageManagerPersistentProxyConfigChecked", Boolean.TRUE);
        summary.put("userinfoBlocked", Boolean.TRUE);
        summary.put("sensitiveQueryBlocked", Boolean.TRUE);
        summary.put("schemelessSensitiveQueryBlocked", Boolean.TRUE);
        summary.put("sensitiveQueryNameAliasNormalized", Boolean.TRUE);
        summary.put("encodedSensitiveQueryBlocked", Boolean.TRUE);
        summary.put("repeatedEncodedSensitiveQueryBlocked", Boolean.TRUE);
        summary.put("semicolonSensitiveQueryBlocked", Boolean.TRUE);
        summary.put("fragmentSensitiveQueryBlocked", Boolean.TRUE);
        summary.put("sensitivePathCredentialBlocked", Boolean.TRUE);
        summary.put("cloudMetadataBlocked", Boolean.TRUE);
        summary.put(
                "description",
                "URL safety blocks cloud metadata, private addresses unless explicitly allowed, userinfo credentials, plain/encoded sensitive URL parameters, and configured website rules.");
        return summary;
    }

    /**
     * 执行私有 URL策略摘要相关逻辑。
     *
     * @return 返回私有 URL策略Summary结果。
     */
    public Map<String, Object> privateUrlPolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("allowPrivateUrls", Boolean.valueOf(resolveAllowPrivateUrls()));
        summary.put("environmentOverrideName", "SOLONCLAW_ALLOW_PRIVATE_URLS");
        summary.put("cloudMetadataAlwaysBlocked", Boolean.TRUE);
        summary.put("dnsResolutionRequired", Boolean.TRUE);
        summary.put("obfuscatedIpv4Checked", Boolean.TRUE);
        summary.put("percentEncodedHostChecked", Boolean.TRUE);
        summary.put("ipv4MappedIpv6Checked", Boolean.TRUE);
        summary.put("loopbackBlocked", Boolean.TRUE);
        summary.put("linkLocalBlocked", Boolean.TRUE);
        summary.put("siteLocalBlocked", Boolean.TRUE);
        summary.put("multicastBlocked", Boolean.TRUE);
        summary.put("reservedDocumentationRangesBlocked", Boolean.TRUE);
        summary.put(
                "trustedPrivateIpHostCount",
                Integer.valueOf(SecurityAddressPolicySupport.trustedPrivateIpHosts().size()));
        summary.put(
                "trustedPrivateIpHostSamples",
                sample(SecurityAddressPolicySupport.trustedPrivateIpHosts(), 4));
        summary.put(
                "alwaysBlockedHostSamples",
                sample(SecurityAddressPolicySupport.alwaysBlockedHosts(), 4));
        summary.put(
                "alwaysBlockedIpSamples",
                sample(SecurityAddressPolicySupport.alwaysBlockedIps(), 4));
        summary.put(
                "description",
                "Private URL safety resolves hosts and blocks loopback, link-local, private, multicast, documentation, and cloud metadata addresses unless private URL access is explicitly allowed.");
        return summary;
    }

    /**
     * 执行网站策略摘要相关逻辑。
     *
     * @return 返回website策略Summary结果。
     */
    public Map<String, Object> websitePolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        AppConfig.WebsiteBlocklistConfig blocklist = websiteBlocklistConfig();
        List<String> configuredDomains =
                blocklist == null || blocklist.getDomains() == null
                        ? Collections.<String>emptyList()
                        : blocklist.getDomains();
        List<String> configuredSharedFiles =
                blocklist == null || blocklist.getSharedFiles() == null
                        ? Collections.<String>emptyList()
                        : blocklist.getSharedFiles();
        SharedWebsiteRuleSummary shared = sharedWebsiteRuleSummary();
        summary.put("enabled", Boolean.valueOf(blocklist != null && blocklist.isEnabled()));
        summary.put("configuredDomainCount", Integer.valueOf(configuredDomains.size()));
        summary.put("configuredDomainSamples", redactSample(configuredDomains, 6));
        summary.put("sharedFileCount", Integer.valueOf(configuredSharedFiles.size()));
        summary.put("sharedFileSamples", redactSample(configuredSharedFiles, 6));
        summary.put("loadedSharedFileCount", Integer.valueOf(shared.loadedFileCount));
        summary.put("skippedSharedFileCount", Integer.valueOf(shared.skippedFileCount));
        summary.put("sharedRuleCount", Integer.valueOf(shared.ruleCount));
        summary.put("sharedRuleSamples", redactSample(shared.ruleSamples, 6));
        summary.put("hostRuleNormalization", Boolean.TRUE);
        summary.put("wildcardSubdomainSupported", Boolean.TRUE);
        summary.put("schemeAndPathIgnoredForRules", Boolean.TRUE);
        summary.put("wwwPrefixIgnored", Boolean.TRUE);
        summary.put("sharedFilePathSafetyChecked", Boolean.TRUE);
        summary.put(
                "description",
                "Website policy matches normalized host rules from configured domains and safe shared files before any network access.");
        return summary;
    }

    /**
     * 执行路径策略摘要相关逻辑。
     *
     * @return 返回路径策略Summary结果。
     */
    public Map<String, Object> pathPolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("traversalBlocked", Boolean.TRUE);
        summary.put("controlCharactersBlocked", Boolean.TRUE);
        summary.put("rawControlCharactersBlocked", Boolean.TRUE);
        summary.put("normalizedControlCharactersBlocked", Boolean.TRUE);
        summary.put("devicePathBlocked", Boolean.TRUE);
        summary.put("rawBlockDeviceWriteBlocked", Boolean.TRUE);
        summary.put("credentialPathReadBlocked", Boolean.TRUE);
        summary.put("credentialPathWriteBlocked", Boolean.TRUE);
        summary.put("projectEnvFileWriteBlocked", Boolean.TRUE);
        summary.put("skillsHubInternalReadBlocked", Boolean.TRUE);
        summary.put("skillsHubInternalWriteBlocked", Boolean.TRUE);
        summary.put("localManagementSocketReadBlocked", Boolean.TRUE);
        summary.put("localManagementSocketWriteBlocked", Boolean.TRUE);
        summary.put("localManagementSocketAccessBlocked", Boolean.TRUE);
        summary.put("localManagementSocketEnvironmentBlocked", Boolean.TRUE);
        summary.put("localManagementPipeReadBlocked", Boolean.TRUE);
        summary.put("localManagementPipeWriteBlocked", Boolean.TRUE);
        summary.put("localManagementPipeAccessBlocked", Boolean.TRUE);
        summary.put("workspaceWriteFree", Boolean.TRUE);
        summary.put("outsideWorkspaceReadFree", Boolean.TRUE);
        summary.put("outsideWorkspaceWriteApprovalRequired", Boolean.TRUE);
        summary.put("writeDeniedExactPathCount", Integer.valueOf(WRITE_DENIED_EXACT_PATHS.size()));
        summary.put("writeDeniedPrefixCount", Integer.valueOf(WRITE_DENIED_PREFIXES.size()));
        summary.put(
                "writeDeniedWindowsPrefixCount",
                Integer.valueOf(WRITE_DENIED_WINDOWS_PREFIXES.size()));
        summary.put(
                "writeDeniedHomeFileCount", Integer.valueOf(WRITE_DENIED_HOME_FILE_NAMES.size()));
        summary.put("blockedDevicePathCount", Integer.valueOf(BLOCKED_DEVICE_PATHS.size()));
        summary.put(
                "localManagementSocketPathCount",
                Integer.valueOf(LOCAL_MANAGEMENT_SOCKET_PATHS.size()));
        summary.put(
                "localManagementPipePathCount",
                Integer.valueOf(LOCAL_MANAGEMENT_PIPE_PATHS.size()));
        summary.put("writeDeniedExactPathSamples", sample(WRITE_DENIED_EXACT_PATHS, 6));
        summary.put("writeDeniedPrefixSamples", sample(WRITE_DENIED_PREFIXES, 6));
        summary.put("writeDeniedWindowsPrefixSamples", sample(WRITE_DENIED_WINDOWS_PREFIXES, 6));
        summary.put("writeDeniedHomeFileSamples", sample(WRITE_DENIED_HOME_FILE_NAMES, 6));
        summary.put("blockedDevicePathSamples", sample(BLOCKED_DEVICE_PATHS, 6));
        summary.put("localManagementSocketPathSamples", sample(LOCAL_MANAGEMENT_SOCKET_PATHS, 4));
        summary.put("localManagementPipePathSamples", sample(LOCAL_MANAGEMENT_PIPE_PATHS, 4));
        summary.put("workdirSafePattern", WORKDIR_SAFE_PATTERN.pattern());
        summary.put(
                "description",
                "Path safety blocks traversal, control characters, device files, sensitive system writes, local management endpoints, internal skill hub access, and writes outside the configured safe root.");
        return summary;
    }

    /**
     * 执行工具参数策略摘要相关逻辑。
     *
     * @return 返回工具参数策略Summary结果。
     */
    public Map<String, Object> toolArgsPolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("recursiveUrlExtraction", Boolean.TRUE);
        summary.put("returnedContentUrlExtraction", Boolean.TRUE);
        summary.put("returnedSchemelessUrlChecked", Boolean.TRUE);
        summary.put("returnedDocumentContentChecked", Boolean.TRUE);
        summary.put("returnedDocumentMetadataUrlChecked", Boolean.TRUE);
        summary.put("returnedPojoUrlChecked", Boolean.TRUE);
        summary.put(
                "returnedUrlKeySamples",
                Arrays.asList("href", "link", "browser_download_url", "source_url", "finalUrl"));
        summary.put("recursivePathExtraction", Boolean.TRUE);
        summary.put("encodedUrlParameterPolicyInherited", Boolean.TRUE);
        summary.put("rawPathControlCharacterPolicyInherited", Boolean.TRUE);
        summary.put("writeIntentDetection", Boolean.TRUE);
        summary.put("patchTargetExtraction", Boolean.TRUE);
        summary.put("downloadOutputPathOptionChecked", Boolean.TRUE);
        summary.put("downloadOutputDetachedOptionChecked", Boolean.TRUE);
        summary.put("networkUploadSourcePathChecked", Boolean.TRUE);
        summary.put("networkUploadCredentialOnlyBlocked", Boolean.TRUE);
        summary.put("proxyOptionUrlChecked", Boolean.TRUE);
        summary.put("preproxyOptionUrlChecked", Boolean.TRUE);
        summary.put("systemDnsCommandChecked", Boolean.TRUE);
        summary.put("powershellProxyEnvironmentChecked", Boolean.TRUE);
        summary.put("setxProxyEnvironmentChecked", Boolean.TRUE);
        summary.put("systemProxyCommandChecked", Boolean.TRUE);
        summary.put("windowsRegistryProxyCommandChecked", Boolean.TRUE);
        summary.put("proxyBypassEnvironmentChecked", Boolean.TRUE);
        summary.put("gitPersistentProxyConfigChecked", Boolean.TRUE);
        summary.put("packageManagerProxyBypassEnvironmentChecked", Boolean.TRUE);
        summary.put("packageManagerPersistentProxyConfigChecked", Boolean.TRUE);
        summary.put("unsupportedNetworkSchemeChecked", Boolean.TRUE);
        summary.put("urlKeySamples", toolArgsUrlKeySamples());
        summary.put("pathKeySamples", toolArgsPathKeySamples());
        summary.put("writeIntentSamples", toolArgsWriteIntentSamples());
        summary.put("patchIntentSamples", toolArgsPatchIntentSamples());
        summary.put("patchTextKeySamples", toolArgsPatchTextKeySamples());
        summary.put("writeLikeToolSamples", toolArgsWriteLikeToolSamples());
        summary.put(
                "description",
                "Tool argument and returned-content safety recursively extracts URL and path-like values, detects write intent, checks download output paths and network upload source paths, checks returned document content, metadata, and structured POJO fields, and parses patch/diff targets before tool execution.");
        return summary;
    }

    /**
     * 检查命令Paths。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令Paths结果。
     */
    public FileVerdict checkCommandPaths(String command) {
        String code = StrUtil.nullToEmpty(command);
        if (code.length() == 0) {
            return FileVerdict.allow();
        }
        String normalizedCode = normalizePathScanText(code);
        if (!normalizedCode.equals(code)) {
            FileVerdict normalizedVerdict = checkCommandPathsCandidate(normalizedCode);
            if (!normalizedVerdict.allowed) {
                return normalizedVerdict;
            }
        }
        return checkCommandPathsCandidate(code);
    }

    /**
     * 检查Configured凭据命令Paths。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回Configured凭据命令Paths结果。
     */
    public FileVerdict checkConfiguredCredentialCommandPaths(String command) {
        String code = StrUtil.nullToEmpty(command);
        if (code.length() == 0) {
            return FileVerdict.allow();
        }
        String normalizedCode = normalizePathScanText(code);
        if (!normalizedCode.equals(code)) {
            FileVerdict normalizedVerdict = checkConfiguredCredentialReferences(normalizedCode);
            if (!normalizedVerdict.allowed) {
                return normalizedVerdict;
            }
        }
        return checkConfiguredCredentialReferences(code);
    }

    /**
     * 检查命令Paths Candidate。
     *
     * @param code code 参数。
     * @return 返回命令Paths Candidate结果。
     */
    private FileVerdict checkCommandPathsCandidate(String code) {
        FileVerdict compactOutputVerdict = checkCompactOutputOptionCredentialPaths(code);
        if (!compactOutputVerdict.allowed) {
            return compactOutputVerdict;
        }
        FileVerdict credentialOptionVerdict = checkCredentialPathOptions(code);
        if (!credentialOptionVerdict.allowed) {
            return credentialOptionVerdict;
        }
        Matcher quotedWindowsMatcher = QUOTED_WINDOWS_PATH_PATTERN.matcher(code);
        while (quotedWindowsMatcher.find()) {
            FileVerdict verdict =
                    checkPath(
                            quotedWindowsMatcher.group(2),
                            isCommandWritePathContext(
                                    code,
                                    quotedWindowsMatcher.start(2),
                                    quotedWindowsMatcher.end(2)));
            if (!verdict.allowed) {
                return verdict;
            }
        }
        FileVerdict traversalVerdict = checkCommandPathTraversalTokens(code);
        if (!traversalVerdict.allowed) {
            return traversalVerdict;
        }
        Matcher relativeCredentialMatcher = SHELL_RELATIVE_CREDENTIAL_PATH_PATTERN.matcher(code);
        while (relativeCredentialMatcher.find()) {
            FileVerdict verdict = checkPath(relativeCredentialMatcher.group(1), false);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        Matcher credentialMatcher = SHELL_CREDENTIAL_TOKEN_PATTERN.matcher(code);
        while (credentialMatcher.find()) {
            FileVerdict verdict =
                    checkPath(cleanCredentialPathToken(credentialMatcher.group(1)), false);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        Matcher matcher = SHELL_PATH_PATTERN.matcher(code);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (isUrlLikePathToken(path)) {
                continue;
            }
            FileVerdict verdict =
                    checkPath(
                            path,
                            isCommandWritePathContext(code, matcher.start(1), matcher.end(1)));
            if (!verdict.allowed) {
                return verdict;
            }
        }
        FileVerdict configuredCredentialVerdict = checkConfiguredCredentialReferences(code);
        if (!configuredCredentialVerdict.allowed) {
            return configuredCredentialVerdict;
        }
        return FileVerdict.allow();
    }

    /**
     * 检查命令 token 中的编码路径遍历，覆盖相对路径不会被绝对路径正则捕获的场景。
     *
     * @param code 命令文本。
     * @return 返回命令路径遍历判定结果。
     */
    private FileVerdict checkCommandPathTraversalTokens(String code) {
        for (String token : shellLikeTokens(code, 200)) {
            String path = cleanUrlToken(token);
            if (StrUtil.isBlank(path) || isUrlLikePathToken(path)) {
                continue;
            }
            String normalized = normalizePathText(path);
            if (containsTraversal(normalized)) {
                return FileVerdict.block(path, "路径遍历被阻断");
            }
        }
        return FileVerdict.allow();
    }

    /**
     * 判断命令 token 是否是 URL 或协议相对 URL，避免网络地址被文件路径规则抢占。
     *
     * @param path 文件或 URL 候选。
     * @return 如果是 URL 样式 token 返回 true。
     */
    private boolean isUrlLikePathToken(String path) {
        String value = StrUtil.nullToEmpty(path).trim();
        if (value.startsWith("//")) {
            return true;
        }
        int scheme = value.indexOf("://");
        if (scheme <= 0) {
            return false;
        }
        String prefix = value.substring(0, scheme).toLowerCase(Locale.ROOT);
        return "http".equals(prefix)
                || "https".equals(prefix)
                || "ws".equals(prefix)
                || "wss".equals(prefix)
                || "ftp".equals(prefix)
                || "sftp".equals(prefix)
                || "scp".equals(prefix)
                || "file".equals(prefix);
    }

    /**
     * 判断命令中的路径是否处于明确写入上下文。
     *
     * @param command 待执行或解析的命令文本。
     * @param start 路径起始偏移。
     * @param end 路径结束偏移。
     * @return 如果路径作为写入目标返回 true。
     */
    private boolean isCommandWritePathContext(String command, int start, int end) {
        String code = StrUtil.nullToEmpty(command);
        if (start < 0 || start > code.length()) {
            return false;
        }
        String before = code.substring(0, start);
        String after = end >= 0 && end < code.length() ? code.substring(end) : "";
        if (hasShellRedirectionBeforePath(before)) {
            return true;
        }
        String previousToken = previousShellToken(before);
        if (isDetachedOutputOption(previousToken) || isWriteCommandToken(previousToken)) {
            return true;
        }
        String previousPreviousToken = previousShellToken(removeTrailingToken(before));
        if (isWriteCommandToken(previousPreviousToken) && isPowerShellPathOption(previousToken)) {
            return true;
        }
        return startsWithWriteCommandRemainder(after);
    }

    /**
     * 判断路径前是否出现 shell 输出重定向。
     *
     * @param before 路径之前的命令文本。
     * @return 如果路径前是输出重定向返回 true。
     */
    private boolean hasShellRedirectionBeforePath(String before) {
        String text = StrUtil.nullToEmpty(before).trim();
        return text.endsWith(">") || text.endsWith("1>") || text.endsWith("2>");
    }

    /**
     * 获取文本末尾最近的 shell token。
     *
     * @param text 待解析文本。
     * @return 返回最近 token。
     */
    private String previousShellToken(String text) {
        List<String> tokens = shellLikeTokens(StrUtil.nullToEmpty(text), 200);
        return tokens.isEmpty() ? "" : cleanUrlToken(tokens.get(tokens.size() - 1));
    }

    /**
     * 移除文本末尾最近的 shell token。
     *
     * @param text 待处理文本。
     * @return 返回移除后的文本。
     */
    private String removeTrailingToken(String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (value.length() == 0) {
            return "";
        }
        int index = value.length() - 1;
        while (index >= 0 && !Character.isWhitespace(value.charAt(index))) {
            index--;
        }
        return index < 0 ? "" : value.substring(0, index).trim();
    }

    /**
     * 判断 token 是否为常见写入命令。
     *
     * @param token shell token。
     * @return 如果是写入命令返回 true。
     */
    private boolean isWriteCommandToken(String token) {
        String value = StrUtil.nullToEmpty(token).trim().toLowerCase(Locale.ROOT);
        return "tee".equals(value)
                || "tee-object".equals(value)
                || "out-file".equals(value)
                || "set-content".equals(value)
                || "add-content".equals(value)
                || "new-item".equals(value)
                || "copy-item".equals(value)
                || "move-item".equals(value)
                || "cp".equals(value)
                || "mv".equals(value)
                || "install".equals(value);
    }

    /**
     * 判断 token 是否为 PowerShell 路径类写入选项。
     *
     * @param token shell token。
     * @return 如果是路径选项返回 true。
     */
    private boolean isPowerShellPathOption(String token) {
        String value = StrUtil.nullToEmpty(token).trim().toLowerCase(Locale.ROOT);
        return "-path".equals(value) || "-literalpath".equals(value) || "-filepath".equals(value);
    }

    /**
     * 判断路径后续是否像写命令内容参数。
     *
     * @param after 路径后的命令文本。
     * @return 如果后续文本像写入内容返回 true。
     */
    private boolean startsWithWriteCommandRemainder(String after) {
        String value = StrUtil.nullToEmpty(after).trim();
        return value.length() > 0 && !value.startsWith("|") && !value.startsWith("&&");
    }

    /**
     * 规范化路径Scan Text。
     *
     * @param raw 原始输入值。
     * @return 返回路径Scan Text结果。
     */
    private String normalizePathScanText(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        value = TerminalAnsiSanitizer.stripAnsi(value);
        value = SecretRedactor.stripDisplayControls(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return HtmlUtil.unescape(value).trim();
    }

    /**
     * 检查凭据路径Options。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回凭据路径Options结果。
     */
    private FileVerdict checkCredentialPathOptions(String command) {
        List<String> tokens = shellLikeTokens(command, 200);
        boolean networkCredentialMode = false;
        for (int i = 0; i < tokens.size(); i++) {
            String token = cleanUrlToken(tokens.get(i));
            if (isNetworkToolToken(token)) {
                networkCredentialMode = true;
                continue;
            }
            String path = credentialPathOptionValue(token);
            if (StrUtil.isBlank(path) && networkCredentialMode) {
                path = networkUploadFileOptionValue(token);
                if (StrUtil.isBlank(path)
                        && isDetachedNetworkUploadFileOption(token)
                        && i + 1 < tokens.size()) {
                    path = cleanUrlToken(tokens.get(++i));
                }
                if (StrUtil.isNotBlank(path)) {
                    path = cleanCredentialPathToken(path);
                    FileVerdict verdict = checkPath(path, false);
                    if (!verdict.allowed) {
                        return verdict;
                    }
                    continue;
                }
            }
            if (StrUtil.isBlank(path) && networkCredentialMode) {
                path = networkCredentialShortOptionValue(token);
                if (StrUtil.isNotBlank(path)) {
                    // 保留此处实现约束，避免后续维护时破坏既有行为。
                } else if (isDetachedNetworkCredentialShortOption(token) && i + 1 < tokens.size()) {
                    path = cleanUrlToken(tokens.get(++i));
                }
            }
            if (StrUtil.isBlank(path) && "-o".equals(token) && i + 1 < tokens.size()) {
                path = sshFileConfigOptionValue(cleanUrlToken(tokens.get(++i)));
            }
            if (StrUtil.isBlank(path) && isCredentialPathOption(token) && i + 1 < tokens.size()) {
                path = cleanUrlToken(tokens.get(++i));
            }
            if (StrUtil.isBlank(path)) {
                continue;
            }
            path = cleanCredentialPathToken(path);
            return FileVerdict.block(path, "凭据用途参数引用的文件被阻断");
        }
        return FileVerdict.allow();
    }

    /**
     * 执行凭据路径选项值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回凭据路径Option Value结果。
     */
    private String credentialPathOptionValue(String token) {
        for (String option : CREDENTIAL_PATH_OPTION_NAMES) {
            if (token.startsWith(option + "=")) {
                return token.substring(option.length() + 1);
            }
        }
        for (String option : COMPACT_CREDENTIAL_PATH_OPTION_PREFIXES) {
            if (startsWithCompactShortOptionValue(token, option)) {
                return token.substring(option.length());
            }
        }
        String sshFileConfigPath = sshCompactFileConfigOptionValue(token);
        if (StrUtil.isNotBlank(sshFileConfigPath)) {
            return sshFileConfigPath;
        }
        return "";
    }

    /**
     * 判断是否凭据路径Option。
     *
     * @param token token 参数。
     * @return 如果凭据路径Option满足条件则返回 true，否则返回 false。
     */
    private boolean isCredentialPathOption(String token) {
        return CREDENTIAL_PATH_OPTION_NAMES.contains(token);
    }

    /**
     * 执行SSH紧凑文件配置选项值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回ssh Compact文件配置Option Value结果。
     */
    private String sshCompactFileConfigOptionValue(String token) {
        if (!token.startsWith("-o") || token.length() <= 2) {
            return "";
        }
        return sshFileConfigOptionValue(token.substring(2));
    }

    /**
     * 执行SSH文件配置选项值相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回ssh文件配置Option Value结果。
     */
    private String sshFileConfigOptionValue(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.trim();
        for (String option : SSH_FILE_CONFIG_OPTION_NAMES) {
            String prefix = option + "=";
            if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return normalized.substring(prefix.length());
            }
        }
        return "";
    }

    /**
     * 判断是否Network工具token。
     *
     * @param token token 参数。
     * @return 如果Network工具token满足条件则返回 true，否则返回 false。
     */
    private boolean isNetworkToolToken(String token) {
        return "curl".equalsIgnoreCase(token)
                || "wget".equalsIgnoreCase(token)
                || "aria2c".equalsIgnoreCase(token)
                || "curlie".equalsIgnoreCase(token)
                || "http".equalsIgnoreCase(token)
                || "https".equalsIgnoreCase(token)
                || "httpie".equalsIgnoreCase(token)
                || "xh".equalsIgnoreCase(token);
    }

    /**
     * 执行网络Upload文件选项值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回network Upload文件Option Value结果。
     */
    private String networkUploadFileOptionValue(String token) {
        if (StrUtil.isBlank(token)) {
            return "";
        }
        for (String option : NETWORK_UPLOAD_FILE_OPTIONS) {
            if (token.startsWith(option + "=")) {
                return token.substring(option.length() + 1);
            }
        }
        for (String option : NETWORK_UPLOAD_FILE_SHORT_OPTIONS) {
            if (startsWithCompactShortOptionValue(token, option)) {
                return token.substring(option.length());
            }
        }
        if (token.startsWith("@") && token.length() > 1) {
            return token.substring(1);
        }
        int upload = token.indexOf('@');
        if (upload > 0 && upload + 1 < token.length()) {
            return token.substring(upload + 1);
        }
        return "";
    }

    /**
     * 判断是否Detached Network Upload文件Option。
     *
     * @param token token 参数。
     * @return 如果Detached Network Upload文件Option满足条件则返回 true，否则返回 false。
     */
    private boolean isDetachedNetworkUploadFileOption(String token) {
        return NETWORK_UPLOAD_FILE_OPTIONS.contains(token)
                || NETWORK_UPLOAD_FILE_SHORT_OPTIONS.contains(token);
    }

    /**
     * 执行网络凭据短选项值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回network凭据Short Option Value结果。
     */
    private String networkCredentialShortOptionValue(String token) {
        if (StrUtil.isBlank(token)) {
            return "";
        }
        for (String option : NETWORK_CREDENTIAL_SHORT_OPTIONS) {
            if (startsWithCompactShortOptionValue(token, option)) {
                return token.substring(option.length());
            }
        }
        return "";
    }

    /**
     * 判断是否以紧凑短选项值开头。
     *
     * @param token token 参数。
     * @param option 选项参数。
     * @return 返回starts With Compact Short Option Value结果。
     */
    private boolean startsWithCompactShortOptionValue(String token, String option) {
        if (!token.startsWith(option) || token.length() <= option.length() + 1) {
            return false;
        }
        char next = token.charAt(option.length());
        return next != '-' && next != ':' && next != '=';
    }

    /**
     * 清理凭据路径token。
     *
     * @param raw 原始输入值。
     * @return 返回clean凭据路径token结果。
     */
    private String cleanCredentialPathToken(String raw) {
        String value = cleanUrlToken(raw);
        int uploadFile = value.indexOf("=@");
        if (uploadFile >= 0 && uploadFile + 2 < value.length()) {
            return value.substring(uploadFile + 2);
        }
        int assignment = value.indexOf('=');
        if (assignment >= 0 && assignment + 1 < value.length()) {
            String candidate = value.substring(assignment + 1);
            if (candidate.startsWith("@") && candidate.length() > 1) {
                return candidate.substring(1);
            }
        }
        return value.startsWith("@") && value.length() > 1 ? value.substring(1) : value;
    }

    /**
     * 判断是否Detached Network凭据Short Option。
     *
     * @param token token 参数。
     * @return 如果Detached Network凭据Short Option满足条件则返回 true，否则返回 false。
     */
    private boolean isDetachedNetworkCredentialShortOption(String token) {
        return NETWORK_CREDENTIAL_SHORT_OPTIONS.contains(token);
    }

    /**
     * 检查Compact输出Option凭据Paths。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回Compact输出Option凭据Paths结果。
     */
    private FileVerdict checkCompactOutputOptionCredentialPaths(String command) {
        List<String> tokens = shellLikeTokens(command, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String path = compactOutputOptionPath(token);
            if (StrUtil.isBlank(path) && isDetachedOutputOption(token) && i + 1 < tokens.size()) {
                path = cleanUrlToken(tokens.get(++i));
            }
            if (StrUtil.isBlank(path)) {
                continue;
            }
            FileVerdict verdict = checkPath(path, true);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return FileVerdict.allow();
    }

    /**
     * 判断是否Detached输出Option。
     *
     * @param raw 原始输入值。
     * @return 如果Detached输出Option满足条件则返回 true，否则返回 false。
     */
    private boolean isDetachedOutputOption(String raw) {
        String token = cleanUrlToken(raw);
        return "-o".equals(token)
                || "-O".equals(token)
                || "-d".equals(token)
                || "--output".equals(token)
                || "--output-document".equals(token)
                || "--output-dir".equals(token)
                || "--directory-prefix".equals(token)
                || "--out".equals(token)
                || "--dir".equals(token)
                || "-P".equals(token)
                || "-outfile".equalsIgnoreCase(token)
                || "-destination".equalsIgnoreCase(token);
    }

    /**
     * 执行紧凑输出选项路径相关逻辑。
     *
     * @param raw 原始输入值。
     * @return 返回compact输出Option路径。
     */
    private String compactOutputOptionPath(String raw) {
        String token = cleanUrlToken(raw);
        if (token.length() <= 2) {
            return "";
        }
        if (token.startsWith("-o") && !token.startsWith("--")) {
            return token.substring(2);
        }
        if (token.startsWith("-O") && !token.startsWith("--")) {
            return token.substring(2);
        }
        if (token.startsWith("-d") && !token.startsWith("--")) {
            return token.substring(2);
        }
        if (token.startsWith("--output=")) {
            return token.substring("--output=".length());
        }
        if (token.startsWith("--output-document=")) {
            return token.substring("--output-document=".length());
        }
        if (token.startsWith("--output-dir=")) {
            return token.substring("--output-dir=".length());
        }
        if (token.startsWith("--directory-prefix=")) {
            return token.substring("--directory-prefix=".length());
        }
        if (token.startsWith("--out=")) {
            return token.substring("--out=".length());
        }
        if (token.startsWith("--dir=")) {
            return token.substring("--dir=".length());
        }
        if (token.startsWith("-P") && !token.startsWith("--")) {
            return token.substring(2);
        }
        if (startsWithPowerShellOptionValue(token, "-OutFile")) {
            return token.substring("-OutFile".length() + 1);
        }
        if (startsWithPowerShellOptionValue(token, "-Destination")) {
            return token.substring("-Destination".length() + 1);
        }
        return "";
    }

    /**
     * 判断是否以Power终端选项值开头。
     *
     * @param token token 参数。
     * @param option 选项参数。
     * @return 返回starts With Power Shell Option Value结果。
     */
    private boolean startsWithPowerShellOptionValue(String token, String option) {
        return token.length() > option.length() + 1
                && token.regionMatches(true, 0, option, 0, option.length())
                && (token.charAt(option.length()) == ':' || token.charAt(option.length()) == '=');
    }

    /**
     * 检查命令Urls。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令Urls结果。
     */
    public UrlVerdict checkCommandUrls(String command) {
        UrlVerdict socketVerdict = checkCommandLocalManagementSockets(command);
        if (!socketVerdict.allowed) {
            return socketVerdict;
        }
        List<String> urls = new ArrayList<String>();
        extractCommandUrlishFromText(command, urls);
        List<String> normalizedUrls = new ArrayList<String>();
        for (String url : urls) {
            String value = cleanUrlToken(url);
            if (isBareNumericCommandUrlCandidate(value)) {
                continue;
            }
            normalizedUrls.add(value);
            UrlVerdict verdict = checkUrlSafety(value, null);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        for (String value : normalizedUrls) {
            UrlVerdict externalVerdict = checkExternalUrlApproval(normalizeExternalApprovalUrl(value));
            if (externalVerdict.allowed && isSchemelessPublicNetworkTarget(value)) {
                externalVerdict = checkExternalNetworkOperation("command_url");
            }
            if (!externalVerdict.allowed) {
                return externalVerdict;
            }
        }
        return UrlVerdict.allow();
    }

    /**
     * 判断命令 URL 候选是否只是裸数字参数；例如端口 `22` 不能被当成十进制 IPv4 主机 0.0.0.22。
     *
     * @param value 已清理的候选文本。
     * @return 纯十进制数字返回 true。
     */
    private boolean isBareNumericCommandUrlCandidate(String value) {
        return Pattern.compile("^\\d+$").matcher(StrUtil.nullToEmpty(value)).matches();
    }

    /**
     * 检查命令本地Management Sockets。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令本地Management Sockets结果。
     */
    private UrlVerdict checkCommandLocalManagementSockets(String command) {
        UrlVerdict powershellEnvVerdict = checkPowerShellLocalManagementEnvironment(command);
        if (!powershellEnvVerdict.allowed) {
            return powershellEnvVerdict;
        }
        List<String> tokens = shellLikeTokens(command, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = cleanUrlToken(tokens.get(i));
            String path = null;
            if ("--unix-socket".equals(token) || "--abstract-unix-socket".equals(token)) {
                if (i + 1 < tokens.size()) {
                    path = cleanUrlToken(tokens.get(++i));
                }
            } else if (token.startsWith("--unix-socket=")) {
                path = cleanUrlToken(token.substring("--unix-socket=".length()));
            } else if (token.startsWith("--abstract-unix-socket=")) {
                path = cleanUrlToken(token.substring("--abstract-unix-socket=".length()));
            }
            if (StrUtil.isBlank(path)) {
                path = localManagementSocketEnvironmentValue(token);
            }
            if (StrUtil.isBlank(path)) {
                path = localManagementHostOptionValue(token);
                if (StrUtil.isBlank(path)
                        && isDetachedLocalManagementHostOption(token)
                        && i + 1 < tokens.size()) {
                    path = localManagementSocketEnvironmentPath(tokens.get(++i));
                }
            }
            if (StrUtil.isNotBlank(path)) {
                if (isLocalManagementSocket(path)) {
                    return UrlVerdict.block(
                            path, "阻断本地容器/运行时管理套接字访问：" + localManagementReference(path));
                }
                String endpointPipe = localManagementPipeToken(path);
                if (StrUtil.isNotBlank(endpointPipe)) {
                    return UrlVerdict.block(
                            endpointPipe,
                            "阻断本地容器/运行时管理命名管道访问：" + localManagementReference(endpointPipe));
                }
            }
            String pipe = localManagementPipeToken(token);
            if (StrUtil.isNotBlank(pipe)) {
                return UrlVerdict.block(
                        pipe, "阻断本地容器/运行时管理命名管道访问：" + localManagementReference(pipe));
            }
        }
        return UrlVerdict.allow();
    }

    /**
     * 检查Power Shell本地Management Environment。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回Power Shell本地Management Environment结果。
     */
    private UrlVerdict checkPowerShellLocalManagementEnvironment(String command) {
        Matcher matcher =
                POWERSHELL_LOCAL_MANAGEMENT_ENV_ASSIGNMENT_PATTERN.matcher(
                        StrUtil.nullToEmpty(command));
        while (matcher.find()) {
            String value = matcher.group(2);
            if (StrUtil.isBlank(value)) {
                value = matcher.group(4);
            }
            String path = localManagementSocketEnvironmentPath(value);
            if (isLocalManagementSocket(path)) {
                return UrlVerdict.block(
                        path, "阻断本地容器/运行时管理套接字访问：" + localManagementReference(path));
            }
            String pipe = localManagementPipeToken(path);
            if (StrUtil.isNotBlank(pipe)) {
                return UrlVerdict.block(
                        pipe, "阻断本地容器/运行时管理命名管道访问：" + localManagementReference(pipe));
            }
        }
        return UrlVerdict.allow();
    }

    /**
     * 执行本地Management引用相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回本地Management Reference结果。
     */
    private String localManagementReference(String value) {
        String text = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        text = SecretRedactor.redact(text, 400);
        return StrUtil.blankToDefault(text, "[REDACTED_PATH]");
    }

    /**
     * 执行本地Management套接字Environment值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回本地Management Socket Environment Value结果。
     */
    private String localManagementSocketEnvironmentValue(String token) {
        int assignment = StrUtil.nullToEmpty(token).indexOf('=');
        if (assignment <= 0 || assignment + 1 >= token.length()) {
            return "";
        }
        String name = token.substring(0, assignment).trim();
        if (!"DOCKER_HOST".equalsIgnoreCase(name)
                && !"CONTAINER_HOST".equalsIgnoreCase(name)
                && !"PODMAN_HOST".equalsIgnoreCase(name)) {
            return "";
        }
        return localManagementSocketEnvironmentPath(token.substring(assignment + 1));
    }

    /**
     * 执行本地Management套接字Environment路径相关逻辑。
     *
     * @param rawValue 原始值参数。
     * @return 返回本地Management Socket Environment路径。
     */
    private String localManagementSocketEnvironmentPath(String rawValue) {
        String value = cleanUrlToken(stripOptionalQuote(rawValue));
        if (value.regionMatches(true, 0, "unix://", 0, "unix://".length())) {
            return value.substring("unix://".length());
        }
        return value;
    }

    /**
     * 执行本地Management主机选项值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回本地Management Host Option Value结果。
     */
    private String localManagementHostOptionValue(String token) {
        String value = StrUtil.nullToEmpty(token).trim();
        if (value.regionMatches(true, 0, "--host=", 0, "--host=".length())) {
            return localManagementSocketEnvironmentPath(value.substring("--host=".length()));
        }
        if (value.regionMatches(true, 0, "-H=", 0, "-H=".length())) {
            return localManagementSocketEnvironmentPath(value.substring("-H=".length()));
        }
        if (value.length() > 2 && value.regionMatches(true, 0, "-H", 0, 2)) {
            return localManagementSocketEnvironmentPath(value.substring(2));
        }
        return "";
    }

    /**
     * 判断是否Detached本地Management Host Option。
     *
     * @param token token 参数。
     * @return 如果Detached本地Management Host Option满足条件则返回 true，否则返回 false。
     */
    private boolean isDetachedLocalManagementHostOption(String token) {
        return "-H".equalsIgnoreCase(token) || "--host".equalsIgnoreCase(token);
    }

    /**
     * 判断是否本地Management Socket。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 如果本地Management Socket满足条件则返回 true，否则返回 false。
     */
    private boolean isLocalManagementSocket(String rawPath) {
        String normalized = normalizePathText(rawPath);
        if (normalized.length() == 0) {
            return false;
        }
        for (String socketPath : LOCAL_MANAGEMENT_SOCKET_PATHS) {
            if (normalized.equals(normalizePathText(socketPath))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行本地ManagementPipetoken相关逻辑。
     *
     * @param token token 参数。
     * @return 返回本地Management Pipe token结果。
     */
    private String localManagementPipeToken(String token) {
        if (isLocalManagementPipe(token)) {
            return token;
        }
        int assignment = token.indexOf('=');
        if (assignment > 0 && assignment + 1 < token.length()) {
            String value = token.substring(assignment + 1);
            if (isLocalManagementPipe(value)) {
                return value;
            }
        }
        return "";
    }

    /**
     * 判断是否本地Management Pipe。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 如果本地Management Pipe满足条件则返回 true，否则返回 false。
     */
    private boolean isLocalManagementPipe(String rawPath) {
        String normalized = normalizePipeText(rawPath);
        if (normalized.length() == 0) {
            return false;
        }
        for (String pipePath : LOCAL_MANAGEMENT_PIPE_PATHS) {
            String localPipe = normalizePipeText(pipePath);
            if (normalized.equals(localPipe) || normalized.startsWith(localPipe + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规范化Pipe Text。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回Pipe Text结果。
     */
    private String normalizePipeText(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        value = TerminalAnsiSanitizer.stripAnsi(value);
        value = SecretRedactor.stripDisplayControls(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = HtmlUtil.unescape(value).trim();
        value = decodePathText(value);
        value = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        while (value.endsWith(",")
                || value.endsWith(";")
                || value.endsWith("\"")
                || value.endsWith("'")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.startsWith("npipe:////./")) {
            return "//./" + value.substring("npipe:////./".length());
        }
        if (value.startsWith("npipe://./")) {
            return "//./" + value.substring("npipe://./".length());
        }
        return value;
    }

    /**
     * 检查命令Always 块ed Urls。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回命令Always 块ed Urls结果。
     */
    public UrlVerdict checkCommandAlwaysBlockedUrls(String command) {
        List<String> urls = new ArrayList<String>();
        extractUrlishFromText(command, urls);
        for (String url : urls) {
            String value = cleanUrlToken(url);
            UrlVerdict verdict = checkCommandAlwaysBlockedUrlToken(value);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return UrlVerdict.allow();
    }

    /**
     * 检查命令文本里的硬阻断 URL 候选；DNS 不确定时交给后续 URL/文件策略处理，避免误判为不可审批 hardline。
     *
     * @param value 已清理的 URL 或主机候选。
     * @return 明确命中云元数据/链路本地地址时返回阻断，否则返回允许。
     */
    private UrlVerdict checkCommandAlwaysBlockedUrlToken(String value) {
        if (StrUtil.isBlank(value)) {
            return UrlVerdict.allow();
        }
        if (value.startsWith("//")) {
            return checkCommandAlwaysBlockedUrlToken("http:" + value);
        }
        if (!value.contains("://")) {
            return checkAlwaysBlockedSchemelessUrl(value);
        }
        URI uri = parseUri(value);
        if (uri == null) {
            return UrlVerdict.allow();
        }
        String host = extractUriHost(uri);
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.allow();
        }
        UrlVerdict hostVerdict = checkAlwaysBlockedHost(value, host);
        if (!hostVerdict.allowed) {
            return hostVerdict;
        }
        try {
            for (InetAddress address : resolveHost(host)) {
                String ip = address.getHostAddress();
                if (SecurityAddressPolicySupport.isAlwaysBlockedIp(ip) || SecurityAddressPolicySupport.isAlwaysBlockedAddress(address)) {
                    return UrlVerdict.block(value, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
                }
            }
        } catch (Exception e) {
            log.debug(
                    "命令硬阻断 URL 候选解析失败，交给后续 URL/文件策略处理: host={}, error={}",
                    host,
                    e.toString());
        }
        return UrlVerdict.allow();
    }

    /**
     * 检查路径。
     *
     * @param rawPath 文件或目录路径参数。
     * @param writeLike 写入Like参数。
     * @return 返回路径。
     */
    public FileVerdict checkPath(String rawPath, boolean writeLike) {
        String path = StrUtil.nullToEmpty(rawPath).trim();
        if (path.length() == 0) {
            return FileVerdict.allow();
        }
        if (containsControlCharacter(path)) {
            return FileVerdict.block(path, "路径包含非法字符");
        }
        String normalized = normalizePathText(path);
        if (containsControlCharacter(normalized)) {
            return FileVerdict.block(path, "路径包含非法字符");
        }
        if (containsTraversal(normalized)) {
            return FileVerdict.block(path, "路径遍历被阻断");
        }
        if (matchesBlockedDevicePath(normalized)) {
            return FileVerdict.block(path, "读取设备文件可能导致无限输出或阻塞，已阻断");
        }
        if (writeLike && matchesRawBlockDevicePath(normalized)) {
            return FileVerdict.block(path, "写入裸块设备可能破坏磁盘数据，已阻断");
        }
        if (matchesBlockedInternalSkillHubPath(normalized)) {
            return FileVerdict.block(
                    path,
                    writeLike
                            ? "写入 Skills Hub 内部缓存文件被阻断，请使用技能管理能力维护技能"
                            : "读取 Skills Hub 内部缓存文件被阻断，请使用 skills_list 或 skill_view 工具");
        }
        if (matchesCredentialPath(normalized)) {
            return FileVerdict.block(path, writeLike ? "写入敏感系统/凭据文件被阻断" : "读取敏感系统/凭据文件被阻断");
        }
        if (writeLike && matchesWriteDeniedPath(normalized)) {
            return FileVerdict.block(path, "写入敏感系统文件被阻断");
        }
        if (isLocalManagementSocket(path)) {
            return FileVerdict.block(path, writeLike ? "写入本地容器/运行时管理套接字被阻断" : "访问本地容器/运行时管理套接字被阻断");
        }
        if (isLocalManagementPipe(path)) {
            return FileVerdict.block(
                    path, writeLike ? "写入本地容器/运行时管理命名管道被阻断" : "访问本地容器/运行时管理命名管道被阻断");
        }
        if (writeLike && isOutsideWorkspace(path)) {
            FileVerdict verdict =
                    FileVerdict.approvalRequired(
                            path, "workspace_outside_write", "工作区外写入需要审批");
            if (!isFilePolicyApproved(verdict.getApprovalToken())) {
                return verdict;
            }
        }
        return FileVerdict.allow();
    }

    /**
     * 记录当前线程已通过审批的文件策略键。
     *
     * @param policyKey 文件策略键。
     */
    public static void approveFilePolicyForCurrentThread(String policyKey) {
        approvePolicyForCurrentThread(APPROVED_FILE_POLICY_KEYS, policyKey);
    }

    /**
     * 记录当前线程已通过审批的具体文件策略目标。
     *
     * @param policyKey 文件策略键。
     * @param target 审批绑定的具体路径。
     */
    public static void approveFilePolicyForCurrentThread(String policyKey, String target) {
        approveFilePolicyForCurrentThread(policyApprovalToken(policyKey, target));
    }

    /**
     * 记录当前线程已通过审批的 URL 策略键。
     *
     * @param policyKey URL 策略键。
     */
    public static void approveUrlPolicyForCurrentThread(String policyKey) {
        approvePolicyForCurrentThread(APPROVED_URL_POLICY_KEYS, policyKey);
    }

    /**
     * 记录当前线程已通过审批的具体 URL 策略目标。
     *
     * @param policyKey URL 策略键。
     * @param target 审批绑定的具体 URL 或工具目标。
     */
    public static void approveUrlPolicyForCurrentThread(String policyKey, String target) {
        approveUrlPolicyForCurrentThread(policyApprovalToken(policyKey, target));
    }

    /**
     * 清理当前线程的一次性策略审批，避免审批泄漏到后续工具调用。
     */
    public static void clearCurrentThreadPolicyApprovals() {
        APPROVED_FILE_POLICY_KEYS.remove();
        APPROVED_URL_POLICY_KEYS.remove();
    }

    /**
     * 在不消费线程本地策略审批的情况下执行安全检查。
     *
     * @param action 需要执行的检查逻辑。
     * @param <T> 检查结果类型。
     * @return 返回检查逻辑的原始结果。
     */
    public static <T> T previewPolicyApprovals(Supplier<T> action) {
        if (action == null) {
            return null;
        }
        Boolean previous = POLICY_APPROVAL_PREVIEW.get();
        POLICY_APPROVAL_PREVIEW.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                POLICY_APPROVAL_PREVIEW.remove();
            } else {
                POLICY_APPROVAL_PREVIEW.set(previous);
            }
        }
    }

    /**
     * 写入当前线程策略审批集合。
     *
     * @param holder 线程本地集合。
     * @param policyKey 策略键。
     */
    private static void approvePolicyForCurrentThread(
            ThreadLocal<Collection<String>> holder, String policyKey) {
        if (StrUtil.isBlank(policyKey)) {
            return;
        }
        Collection<String> approvals = holder.get();
        if (approvals == null) {
            approvals = new ArrayList<String>();
            holder.set(approvals);
        }
        approvals.add(policyKey.trim());
    }

    /**
     * 判断文件策略键是否已被当前线程一次性放行。
     *
     * @param policyKey 策略键。
     * @return 如果已放行返回 true。
     */
    private boolean isFilePolicyApproved(String policyKey) {
        return isPolicyApproved(APPROVED_FILE_POLICY_KEYS, policyKey);
    }

    /**
     * 判断 URL 策略键是否已被当前线程一次性放行。
     *
     * @param policyKey 策略键。
     * @return 如果已放行返回 true。
     */
    private boolean isUrlPolicyApproved(String policyKey) {
        return isPolicyApproved(APPROVED_URL_POLICY_KEYS, policyKey);
    }

    /**
     * 判断线程本地策略审批是否命中。
     *
     * @param holder 线程本地集合。
     * @param policyKey 策略键。
     * @return 如果命中返回 true。
     */
    private boolean isPolicyApproved(ThreadLocal<Collection<String>> holder, String policyKey) {
        if (StrUtil.isBlank(policyKey)) {
            return false;
        }
        Collection<String> approvals = holder.get();
        if (approvals == null) {
            return false;
        }
        boolean approved = approvals.contains(policyKey.trim());
        if (!approved) {
            return false;
        }
        if (Boolean.TRUE.equals(POLICY_APPROVAL_PREVIEW.get())) {
            return true;
        }
        approvals.remove(policyKey.trim());
        if (approvals.isEmpty()) {
            holder.remove();
        }
        return approved;
    }

    /**
     * 生成一次性审批 token，绑定策略类型和具体目标，避免同线程下一次敏感操作被顺带放行。
     *
     * @param policyKey 策略键。
     * @param target 审批目标。
     * @return 返回线程本地审批 token。
     */
    private static String policyApprovalToken(String policyKey, String target) {
        String key = StrUtil.nullToEmpty(policyKey).trim();
        String normalizedTarget =
                SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(target)).trim();
        return normalizedTarget.length() == 0 ? key : key + "\n" + normalizedTarget;
    }

    /**
     * 检查普通外部 URL 操作是否需要审批。
     *
     * @param url 待访问 URL。
     * @return 返回 URL 策略判定。
     */
    private UrlVerdict checkExternalUrlApproval(String url) {
        String text = StrUtil.nullToEmpty(url).trim();
        if (text.length() == 0) {
            return UrlVerdict.allow();
        }
        URI uri = parseUri(text);
        if (uri == null || StrUtil.isBlank(uri.getScheme())) {
            return UrlVerdict.allow();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)
                && !"https".equals(scheme)
                && !"ws".equals(scheme)
                && !"wss".equals(scheme)) {
            return UrlVerdict.allow();
        }
        if (isLocalUrl(uri)) {
            return UrlVerdict.allow();
        }
        UrlVerdict verdict =
                UrlVerdict.approvalRequired(text, "network_external_operation", "网络外部操作需要审批");
        return isUrlPolicyApproved(verdict.getApprovalToken()) ? UrlVerdict.allow() : verdict;
    }

    /**
     * 标准化用于外部网络审批的 URL 文本。
     *
     * @param raw 原始 URL 或主机文本。
     * @return 返回可解析的 URL 文本。
     */
    private String normalizeExternalApprovalUrl(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        return value.startsWith("//") ? "https:" + value : value;
    }

    /**
     * 判断无协议文本是否仍代表外部网络目标；DNS 失败时也不能把潜在网络访问静默放行。
     *
     * @param raw 原始 URL 或主机文本。
     * @return 如果是公网主机或公网 IP 返回 true。
     */
    private boolean isSchemelessPublicNetworkTarget(String raw) {
        String value = cleanUrlToken(raw);
        if (StrUtil.isBlank(value) || value.contains("://") || value.startsWith("//")) {
            return false;
        }
        String host = extractSchemelessHost(value);
        if (StrUtil.isBlank(host) || isLocalHostName(host)) {
            return false;
        }
        try {
            for (InetAddress address : resolveHost(host)) {
                String ip = address.getHostAddress();
                if (SecurityAddressPolicySupport.isAlwaysBlockedIp(ip)
                        || SecurityAddressPolicySupport.isAlwaysBlockedAddress(address)
                        || SecurityAddressPolicySupport.isPrivateOrInternal(address, ip)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.debug(
                    "无协议网络目标解析失败，按外部网络访问处理: host={}, error={}",
                    host,
                    e.toString());
            return true;
        }
    }

    /**
     * 判断 URL 是否指向本机或环回地址。
     *
     * @param uri 已解析 URL。
     * @return 如果是本机地址返回 true。
     */
    private boolean isLocalUrl(URI uri) {
        String host = normalizeHost(uri == null ? "" : uri.getHost());
        if (StrUtil.isBlank(host)) {
            return false;
        }
        return isLocalHostName(host);
    }

    /**
     * 判断主机名是否为本机或环回地址。
     *
     * @param host 主机名或地址。
     * @return 如果是本机返回 true。
     */
    private boolean isLocalHostName(String host) {
        String value = normalizeHost(host);
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
    }

    /**
     * 判断写入路径是否位于配置工作区之外。
     *
     * @param rawPath 文件或目录路径。
     * @return 如果路径在工作区之外返回 true。
     */
    private boolean isOutsideWorkspace(String rawPath) {
        try {
            File workspace = workspaceRoot();
            File target = resolveWorkspaceRelativePath(rawPath);
            return !isInside(target.getCanonicalFile(), workspace.getCanonicalFile());
        } catch (Exception e) {
            log.debug("Workspace boundary resolution failed; treating path as outside workspace: {}", exceptionSummary(e));
            return true;
        }
    }

    /**
     * 解析工作区根目录。
     *
     * @return 返回规范化后的工作区根目录。
     */
    private File workspaceRoot() throws java.io.IOException {
        String configured =
                appConfig == null || appConfig.getWorkspace() == null
                        ? RuntimePathConstants.DEFAULT_WORKSPACE
                        : StrUtil.blankToDefault(
                                appConfig.getWorkspace().getDir(),
                                RuntimePathConstants.DEFAULT_WORKSPACE);
        File workspace = new File(configured);
        if (!workspace.isAbsolute()) {
            workspace = new File(jarBaseDir(new File(System.getProperty("user.dir"))), configured);
        }
        return workspace.getCanonicalFile();
    }

    /**
     * 解析运行 Jar 所在目录；测试或解包运行时回退到当前进程目录。
     *
     * @param fallbackBase 无法识别 Jar 路径时使用的目录。
     * @return 返回用于解析工作区相对路径的基准目录。
     */
    private File jarBaseDir(File fallbackBase) {
        try {
            java.net.URL location =
                    SecurityPolicyService.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return fallbackBase;
            }
            File file = new File(location.toURI()).getAbsoluteFile();
            if (file.isFile()) {
                File parent = file.getParentFile();
                return parent == null ? fallbackBase : parent;
            }
        } catch (Exception e) {
            log.debug("Jar base directory resolution failed; falling back to process directory: {}", exceptionSummary(e));
            // 运行环境可能没有 code source，保持启动路径可预测。
        }
        return fallbackBase;
    }

    /**
     * 按工作区语义解析工具传入路径。
     *
     * @param rawPath 文件或目录路径。
     * @return 返回规范化目标路径。
     */
    private File resolveWorkspaceRelativePath(String rawPath) throws java.io.IOException {
        File file = new File(StrUtil.nullToEmpty(rawPath).trim());
        if (!file.isAbsolute()) {
            file = new File(workspaceRoot(), file.getPath());
        }
        return file.getCanonicalFile();
    }

    /**
     * 检查Workdir Text。
     *
     * @param rawWorkdir 文件或目录路径参数。
     * @return 返回Workdir Text结果。
     */
    public static FileVerdict checkWorkdirText(String rawWorkdir) {
        String workdir = StrUtil.nullToEmpty(rawWorkdir).trim();
        if (workdir.length() == 0 || WORKDIR_SAFE_PATTERN.matcher(workdir).matches()) {
            return FileVerdict.allow();
        }
        for (int i = 0; i < workdir.length(); i++) {
            String ch = String.valueOf(workdir.charAt(i));
            if (!WORKDIR_SAFE_PATTERN.matcher(ch).matches()) {
                return FileVerdict.block(
                        workdir,
                        "workdir contains disallowed character '" + printableCharacter(ch) + "'");
            }
        }
        return FileVerdict.block(workdir, "workdir contains disallowed characters");
    }

    /**
     * 提取Urlish Values。
     *
     * @param args 工具或命令参数。
     * @return 返回Urlish Values结果。
     */
    public List<String> extractUrlishValues(Object args) {
        List<String> urls = new ArrayList<String>();
        if (args == null) {
            return urls;
        }
        collectUrls(args, urls);
        return urls;
    }

    /**
     * 提取Urls。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回Urls结果。
     */
    private List<String> extractUrls(String toolName, java.util.Map<String, Object> args) {
        return extractUrlishValues(args);
    }

    /**
     * 规范化工具URL For Check。
     *
     * @param toolName 工具名称。
     * @param rawUrl 待校验或访问的地址参数。
     * @return 返回工具URL For Check结果。
     */
    private String normalizeToolUrlForCheck(String toolName, String rawUrl) {
        String value = cleanUrlToken(rawUrl);
        if (!isReturnedContentTool(toolName)
                || value.length() == 0
                || value.contains("://")
                || value.startsWith("//")) {
            return value;
        }
        return "http://" + value;
    }

    /**
     * 判断是否Returned Content工具。
     *
     * @param toolName 工具名称。
     * @return 如果Returned Content工具满足条件则返回 true，否则返回 false。
     */
    private boolean isReturnedContentTool(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).toLowerCase(Locale.ROOT);
        return normalized.endsWith("_result")
                || normalized.endsWith("_response")
                || normalized.endsWith("_output")
                || normalized.contains("returned");
    }

    /**
     * 收集Urls。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    @SuppressWarnings("unchecked")
    private void collectUrls(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (looksLikeUrlKey(key)) {
                    addUrlValue(value, urls);
                } else {
                    collectUrls(value, urls);
                }
            }
            return;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                collectUrls(value, urls);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                collectUrls(java.lang.reflect.Array.get(raw, i), urls);
            }
            return;
        }
        extractUrlishFromText(raw, urls);
    }

    /**
     * 追加URL值。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    @SuppressWarnings("unchecked")
    private void addUrlValue(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            collectUrls(raw, urls);
            return;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                addUrlValue(value, urls);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                addUrlValue(java.lang.reflect.Array.get(raw, i), urls);
            }
            return;
        }
        String value = cleanUrlToken(String.valueOf(raw));
        if (StrUtil.isBlank(value)) {
            return;
        }
        urls.add(value.contains("://") || value.startsWith("//") ? value : "http://" + value);
    }

    /**
     * 判断是否具有URL键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like URL键结果。
     */
    private boolean looksLikeUrlKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
        return "url".equals(normalized)
                || "uri".equals(normalized)
                || "href".equals(normalized)
                || "link".equals(normalized)
                || "endpoint".equals(normalized)
                || "base_url".equals(normalized)
                || "baseurl".equals(normalized)
                || "api_url".equals(normalized)
                || "apiurl".equals(normalized)
                || "callback_url".equals(normalized)
                || "redirect_url".equals(normalized)
                || "webhook_url".equals(normalized)
                || looksLikeHostTargetKey(normalized)
                || normalized.endsWith("_url")
                || normalized.endsWith("url")
                || normalized.endsWith("_uri")
                || normalized.endsWith("uri")
                || normalized.endsWith("_endpoint")
                || normalized.endsWith("endpoint");
    }

    /**
     * 判断是否具有主机Target键特征。
     *
     * @param normalized normalized 参数。
     * @return 返回looks Like Host Target键结果。
     */
    private boolean looksLikeHostTargetKey(String normalized) {
        return "host".equals(normalized)
                || "hostname".equals(normalized)
                || "server".equals(normalized)
                || "target".equals(normalized)
                || "upstream".equals(normalized)
                || "remote".equals(normalized)
                || "origin".equals(normalized)
                || "proxy".equals(normalized)
                || normalized.endsWith("_host")
                || normalized.endsWith("host")
                || normalized.endsWith("_hostname")
                || normalized.endsWith("hostname")
                || normalized.endsWith("_server")
                || normalized.endsWith("server")
                || normalized.endsWith("_target")
                || normalized.endsWith("target")
                || normalized.endsWith("_upstream")
                || normalized.endsWith("upstream")
                || normalized.endsWith("_remote")
                || normalized.endsWith("remote")
                || normalized.endsWith("_origin")
                || normalized.endsWith("origin")
                || normalized.endsWith("_proxy")
                || normalized.endsWith("proxy");
    }

    /**
     * 提取Urlish From Text。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractUrlishFromText(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        String text = normalizeUrlText(String.valueOf(raw));
        extractCurlConnectionOverrideHosts(text, urls);
        extractCurlDohUrls(text, urls);
        extractCurlDnsServers(text, urls);
        extractSystemDnsCommands(text, urls);
        extractLocalBindAddresses(text, urls);
        extractJavaProxyOptionsAssignments(text, urls);
        extractPowerShellProxyEnvironmentAssignments(text, urls);
        extractSetxProxyEnvironmentAssignments(text, urls);
        extractSystemProxyCommands(text, urls);
        extractWindowsRegistryProxyCommands(text, urls);
        extractGitProxyConfigAssignments(text, urls);
        extractPackageManagerProxyConfigAssignments(text, urls);
        extractProxyHosts(text, urls);
        extractProtocolRelativeUrlish(text, urls);
        extractSchemelessUserInfoUrlish(text, urls);
        extractBareSecurityRelevantHosts(text, urls);
        java.util.regex.Matcher matcher = URLISH_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        extractObfuscatedSchemelessUrlish(text, urls);
    }

    /**
     * 提取命令文本中的 URL 候选；命令里常见本地文件路径形如 logs/app.log，不能仅凭后缀像主机名就当成网络目标。
     *
     * @param raw 原始命令文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractCommandUrlishFromText(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        String text = normalizeUrlText(String.valueOf(raw));
        extractCurlConnectionOverrideHosts(text, urls);
        extractCurlDohUrls(text, urls);
        extractCurlDnsServers(text, urls);
        extractSystemDnsCommands(text, urls);
        extractLocalBindAddresses(text, urls);
        extractJavaProxyOptionsAssignments(text, urls);
        extractPowerShellProxyEnvironmentAssignments(text, urls);
        extractSetxProxyEnvironmentAssignments(text, urls);
        extractSystemProxyCommands(text, urls);
        extractWindowsRegistryProxyCommands(text, urls);
        extractGitProxyConfigAssignments(text, urls);
        extractPackageManagerProxyConfigAssignments(text, urls);
        extractProxyHosts(text, urls);
        extractProtocolRelativeUrlish(text, urls);
        extractSchemelessUserInfoUrlish(text, urls);
        boolean fetchContext = hasBareHostFetchContext(text);
        if (fetchContext) {
            extractBareSecurityRelevantHosts(text, urls);
        } else {
            extractBareCommandSecurityHosts(text, urls);
        }
        java.util.regex.Matcher matcher = URLISH_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if (value.contains("://")
                    || value.startsWith("//")
                    || fetchContext
                    || shouldKeepCommandUrlishWithoutFetchContext(value)) {
                urls.add(value);
            }
        }
        extractObfuscatedSchemelessUrlish(text, urls);
    }

    /**
     * 判断无显式抓取上下文时命令中的 URL 候选是否仍属于硬安全边界；避免把本地相对文件误识别为外部网络。
     *
     * @param raw 原始 URL 候选。
     * @return 需要继续做 URL 安全检查时返回 true。
     */
    private boolean shouldKeepCommandUrlishWithoutFetchContext(String raw) {
        String value = cleanUrlToken(raw);
        if (value.length() == 0) {
            return false;
        }
        if (value.contains("://") || value.startsWith("//") || hasSchemelessUserInfo(value)) {
            return true;
        }
        String host = extractSchemelessHost(value);
        if (StrUtil.isBlank(host)) {
            return false;
        }
        return !checkStaticHostPolicy(value, host).isAllowed();
    }

    /**
     * 提取无显式抓取上下文时仍需要硬拦截的裸主机；普通诊断命令中的内网字面量不在这里升级为 URL。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractBareCommandSecurityHosts(String text, List<String> urls) {
        extractDirectNetworkEndpointHosts(text, urls);
        Matcher matcher = BARE_HOST_TOKEN_PATTERN.matcher(StrUtil.nullToEmpty(text));
        while (matcher.find()) {
            String value = cleanUrlToken(matcher.group(1));
            if (value.length() == 0 || value.contains("://")) {
                continue;
            }
            String host = extractSchemelessHost(value);
            if (StrUtil.isBlank(host) || value.matches("\\d+")) {
                continue;
            }
            if (SecurityAddressPolicySupport.isAlwaysBlockedIp(host) || !checkStaticHostPolicy(value, host).isAllowed()) {
                urls.add(value);
            }
        }
    }

    /**
     * 判断是否Cidr Range token。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Cidr Range token满足条件则返回 true，否则返回 false。
     */
    private boolean isCidrRangeToken(String value) {
        String token = cleanUrlToken(value);
        return IPV4_CIDR_TOKEN_PATTERN.matcher(token).matches()
                || IPV6_CIDR_TOKEN_PATTERN.matcher(token).matches();
    }

    /**
     * 提取Proxy Hosts。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractProxyHosts(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = null;
            if ("--proxy".equals(token)
                    || "-x".equals(token)
                    || "--all-proxy".equals(token)
                    || "--http-proxy".equals(token)
                    || "--https-proxy".equals(token)
                    || "--ftp-proxy".equals(token)
                    || "--proxy-url".equals(token)
                    || "--proxy-server".equals(token)
                    || "--proxy1.0".equals(token)
                    || "--preproxy".equals(token)
                    || "--socks4".equals(token)
                    || "--socks4a".equals(token)
                    || "--socks5".equals(token)
                    || "--socks5-hostname".equals(token)
                    || "-Proxy".equalsIgnoreCase(token)
                    || "-ProxyUri".equalsIgnoreCase(token)
                    || "-ProxyServer".equalsIgnoreCase(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                }
            } else if (token.startsWith("--proxy=")) {
                value = token.substring("--proxy=".length());
            } else if (token.startsWith("--all-proxy=")) {
                value = token.substring("--all-proxy=".length());
            } else if (token.startsWith("--http-proxy=")) {
                value = token.substring("--http-proxy=".length());
            } else if (token.startsWith("--https-proxy=")) {
                value = token.substring("--https-proxy=".length());
            } else if (token.startsWith("--ftp-proxy=")) {
                value = token.substring("--ftp-proxy=".length());
            } else if (token.startsWith("--proxy-url=")) {
                value = token.substring("--proxy-url=".length());
            } else if (token.startsWith("--proxy-server=")) {
                value = token.substring("--proxy-server=".length());
            } else if (token.startsWith("--proxy1.0=")) {
                value = token.substring("--proxy1.0=".length());
            } else if (token.startsWith("--preproxy=")) {
                value = token.substring("--preproxy=".length());
            } else if (token.startsWith("--socks4=")) {
                value = token.substring("--socks4=".length());
            } else if (token.startsWith("--socks4a=")) {
                value = token.substring("--socks4a=".length());
            } else if (token.startsWith("--socks5=")) {
                value = token.substring("--socks5=".length());
            } else if (token.startsWith("--socks5-hostname=")) {
                value = token.substring("--socks5-hostname=".length());
            } else if (token.startsWith("-x") && token.length() > 2) {
                value = token.substring(2);
            } else if (startsWithProxyOption(token, "-Proxy:")) {
                value = token.substring("-Proxy:".length());
            } else if (startsWithProxyOption(token, "-ProxyUri:")) {
                value = token.substring("-ProxyUri:".length());
            } else if (startsWithProxyOption(token, "-ProxyServer:")) {
                value = token.substring("-ProxyServer:".length());
            } else if (isJavaProxyHostProperty(token)) {
                value = token.substring(token.indexOf('=') + 1);
            } else if (isJavaProxyOptionsAssignment(token)) {
                addJavaProxyHostsFromOptions(token.substring(token.indexOf('=') + 1), urls);
            } else if (isProxyEnvironmentAssignment(token)) {
                String name = token.substring(0, token.indexOf('='));
                addProxyEnvironmentValue(name, token.substring(token.indexOf('=') + 1), urls);
                value = null;
            }
            addProxyHost(value, urls);
        }
    }

    /**
     * 判断是否以代理选项开头。
     *
     * @param token token 参数。
     * @param prefix prefix 参数。
     * @return 返回starts With Proxy Option结果。
     */
    private boolean startsWithProxyOption(String token, String prefix) {
        return token != null
                && token.length() > prefix.length()
                && token.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * 判断是否Java Proxy Host Property。
     *
     * @param token token 参数。
     * @return 如果Java Proxy Host Property满足条件则返回 true，否则返回 false。
     */
    private boolean isJavaProxyHostProperty(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 2) {
            return false;
        }
        String name = token.substring(0, equals).toLowerCase(Locale.ROOT);
        return "-dhttp.proxyhost".equals(name)
                || "-dhttps.proxyhost".equals(name)
                || "-dftp.proxyhost".equals(name)
                || "-dsocksproxyhost".equals(name);
    }

    /**
     * 判断是否Java Proxy Options Assignment。
     *
     * @param token token 参数。
     * @return 如果Java Proxy Options Assignment满足条件则返回 true，否则返回 false。
     */
    private boolean isJavaProxyOptionsAssignment(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals).toLowerCase(Locale.ROOT);
        return "java_tool_options".equals(name)
                || "jdk_java_options".equals(name)
                || "maven_opts".equals(name)
                || "gradle_opts".equals(name);
    }

    /**
     * 追加Java代理HostsFromOptions。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addJavaProxyHostsFromOptions(String raw, List<String> urls) {
        List<String> tokens = shellLikeTokens(raw, 100);
        for (String token : tokens) {
            if (isJavaProxyHostProperty(token)) {
                addProxyHost(token.substring(token.indexOf('=') + 1), urls);
            }
        }
    }

    /**
     * 提取Java Proxy Options Assignments。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractJavaProxyOptionsAssignments(String text, List<String> urls) {
        Matcher matcher = JAVA_PROXY_OPTIONS_ASSIGNMENT_PATTERN.matcher(text);
        while (matcher.find()) {
            addJavaProxyHostsFromOptions(stripOptionalQuote(matcher.group(1)), urls);
        }
    }

    /**
     * 提取Power Shell Proxy Environment Assignments。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractPowerShellProxyEnvironmentAssignments(String text, List<String> urls) {
        Matcher matcher = POWERSHELL_PROXY_ENV_ASSIGNMENT_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = matcher.group(2);
            if (StrUtil.isBlank(value)) {
                name = matcher.group(3);
                value = matcher.group(4);
            }
            addProxyEnvironmentValue(name, stripOptionalQuote(value), urls);
        }
    }

    /**
     * 提取Setx Proxy Environment Assignments。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractSetxProxyEnvironmentAssignments(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String command = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (!"setx".equalsIgnoreCase(command)) {
                continue;
            }
            if (i + 2 >= tokens.size()) {
                continue;
            }
            String name = tokens.get(i + 1);
            String value = tokens.get(i + 2);
            if (isPersistentProxyEnvironmentName(name)) {
                addProxyEnvironmentValue(name, value, urls);
            }
        }
    }

    /**
     * 提取System Proxy Commands。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractSystemProxyCommands(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if ("netsh".equalsIgnoreCase(token)) {
                i = extractNetshWinhttpProxy(tokens, i, urls);
            } else if ("networksetup".equalsIgnoreCase(token)) {
                i = extractNetworksetupProxy(tokens, i, urls);
            }
        }
    }

    /**
     * 提取Windows注册表Proxy Commands。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractWindowsRegistryProxyCommands(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String command = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if ("Set-ItemProperty".equalsIgnoreCase(command)
                    || "New-ItemProperty".equalsIgnoreCase(command)) {
                extractWindowsRegistryProxyCommand(tokens, i + 1, urls);
            }
        }
    }

    /**
     * 提取Windows注册表Proxy命令。
     *
     * @param tokens token参数。
     * @param start start 参数。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractWindowsRegistryProxyCommand(
            List<String> tokens, int start, List<String> urls) {
        String propertyName = "";
        String propertyValue = "";
        boolean internetSettings = false;
        for (int i = start; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                break;
            }
            if (token.toLowerCase(Locale.ROOT).contains("internet settings")) {
                internetSettings = true;
            }
            String inlineName = optionInlineValue(token, "-Name");
            if (StrUtil.isBlank(inlineName)) {
                inlineName = optionInlineValue(token, "-Property");
            }
            if (StrUtil.isBlank(inlineName)) {
                inlineName = optionInlineValue(token, "-PropertyName");
            }
            if (StrUtil.isNotBlank(inlineName)) {
                propertyName = inlineName;
                continue;
            }
            String inlineValue = optionInlineValue(token, "-Value");
            if (StrUtil.isBlank(inlineValue)) {
                inlineValue = optionInlineValue(token, "-PropertyValue");
            }
            if (StrUtil.isNotBlank(inlineValue)) {
                propertyValue = inlineValue;
                continue;
            }
            if (POWERSHELL_REGISTRY_PROXY_PROPERTY_PATTERN.matcher(token).matches()
                    && i + 1 < tokens.size()) {
                propertyName = tokens.get(++i);
                continue;
            }
            if (POWERSHELL_REGISTRY_PROXY_VALUE_PATTERN.matcher(token).matches()
                    && i + 1 < tokens.size()) {
                propertyValue = tokens.get(++i);
            }
        }
        if (!internetSettings || StrUtil.isBlank(propertyName) || StrUtil.isBlank(propertyValue)) {
            return;
        }
        addWindowsRegistryProxyValue(propertyName, propertyValue, urls);
    }

    /**
     * 执行选项内联值相关逻辑。
     *
     * @param token token 参数。
     * @param option 选项参数。
     * @return 返回option Inline Value结果。
     */
    private String optionInlineValue(String token, String option) {
        if (StrUtil.isBlank(token) || token.length() <= option.length() + 1) {
            return "";
        }
        if (!token.regionMatches(true, 0, option, 0, option.length())) {
            return "";
        }
        char separator = token.charAt(option.length());
        return separator == ':' || separator == '=' ? token.substring(option.length() + 1) : "";
    }

    /**
     * 追加Windows注册表代理值。
     *
     * @param propertyName property名称参数。
     * @param propertyValue property值参数。
     * @param urls 待校验或访问的地址参数。
     */
    private void addWindowsRegistryProxyValue(
            String propertyName, String propertyValue, List<String> urls) {
        String name = StrUtil.nullToEmpty(propertyName).trim();
        String value = stripOptionalQuote(propertyValue);
        if ("ProxyServer".equalsIgnoreCase(name)) {
            addWindowsRegistryProxyServers(value, urls);
        } else if ("ProxyOverride".equalsIgnoreCase(name)) {
            addNoProxyHosts(value.replace(';', ','), urls);
        }
    }

    /**
     * 追加Windows注册表代理服务端。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addWindowsRegistryProxyServers(String raw, List<String> urls) {
        String value = stripOptionalQuote(raw);
        if (StrUtil.isBlank(value)) {
            return;
        }
        String normalized = value.replace(';', ',');
        for (String part : normalized.split(",")) {
            String token = cleanUrlToken(part);
            if (StrUtil.isBlank(token)) {
                continue;
            }
            int equals = token.indexOf('=');
            if (equals > 0 && equals + 1 < token.length()) {
                token = token.substring(equals + 1);
            }
            addSystemProxyHost(token, urls);
        }
    }

    /**
     * 提取Netsh Winhttp Proxy。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param urls 待校验或访问的地址参数。
     * @return 返回Netsh Winhttp Proxy结果。
     */
    private int extractNetshWinhttpProxy(List<String> tokens, int index, List<String> urls) {
        if (!matchesToken(tokens, index + 1, "winhttp")
                || !matchesToken(tokens, index + 2, "set")
                || !matchesToken(tokens, index + 3, "proxy")) {
            return index;
        }
        for (int i = index + 4; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                return i;
            }
            int equals = token.indexOf('=');
            if (equals > 0) {
                String key = token.substring(0, equals);
                String value = token.substring(equals + 1);
                if ("proxy-server".equalsIgnoreCase(key)) {
                    addSystemProxyHost(value, urls);
                } else if ("bypass-list".equalsIgnoreCase(key)) {
                    addNoProxyHosts(value.replace(';', ','), urls);
                }
                continue;
            }
            addSystemProxyHost(token, urls);
            if (i + 1 < tokens.size()) {
                String maybeBypass = tokens.get(i + 1);
                if (StrUtil.isNotBlank(maybeBypass) && !isShellCommandSeparator(maybeBypass)) {
                    addNoProxyHosts(maybeBypass.replace(';', ','), urls);
                }
            }
            return i;
        }
        return index;
    }

    /**
     * 提取Networksetup Proxy。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param urls 待校验或访问的地址参数。
     * @return 返回Networksetup Proxy结果。
     */
    private int extractNetworksetupProxy(List<String> tokens, int index, List<String> urls) {
        if (index + 4 >= tokens.size()) {
            return index;
        }
        String command = StrUtil.nullToEmpty(tokens.get(index + 1)).trim().toLowerCase(Locale.ROOT);
        if (!command.startsWith("-set")
                || !command.endsWith("proxy")
                || command.contains("proxyautodiscovery")
                || command.contains("autoproxyurl")
                || command.contains("proxystate")) {
            return index;
        }
        String host = tokens.get(index + 3);
        String port = tokens.get(index + 4);
        addSystemProxyHost(host + ":" + port, urls);
        return index + 4;
    }

    /**
     * 追加系统代理主机。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addSystemProxyHost(String raw, List<String> urls) {
        String value = cleanUrlToken(raw);
        if (StrUtil.isBlank(value)) {
            return;
        }
        urls.add(value.contains("://") ? value : "http://" + value);
    }

    /**
     * 判断是否匹配token。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param expected expected 参数。
     * @return 返回matches token结果。
     */
    private boolean matchesToken(List<String> tokens, int index, String expected) {
        return index >= 0
                && index < tokens.size()
                && expected.equalsIgnoreCase(StrUtil.nullToEmpty(tokens.get(index)).trim());
    }

    /**
     * 提取Package管理器Proxy配置Assignments。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractPackageManagerProxyConfigAssignments(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String command = StrUtil.nullToEmpty(tokens.get(i)).trim().toLowerCase(Locale.ROOT);
            if (!isPackageManagerConfigCommand(command)) {
                continue;
            }
            int configIndex = findNextToken(tokens, i + 1, "config");
            if (configIndex < 0) {
                continue;
            }
            int setIndex = findNextToken(tokens, configIndex + 1, "set");
            if (setIndex < 0 || setIndex + 1 >= tokens.size()) {
                continue;
            }
            String key = tokens.get(setIndex + 1);
            String value = setIndex + 2 < tokens.size() ? tokens.get(setIndex + 2) : "";
            int assignment = key.indexOf('=');
            if (assignment > 0 && assignment + 1 < key.length()) {
                value = key.substring(assignment + 1);
                key = key.substring(0, assignment);
            }
            if (isPackageManagerNoProxyConfigKey(key)) {
                addNoProxyHosts(value, urls);
            } else if (isPackageManagerProxyConfigKey(key)) {
                addProxyHost(value, urls);
            }
        }
    }

    /**
     * 提取Git Proxy配置Assignments。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractGitProxyConfigAssignments(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String command = StrUtil.nullToEmpty(tokens.get(i)).trim().toLowerCase(Locale.ROOT);
            if (!"git".equals(command)) {
                continue;
            }
            int configIndex = findToken(tokens, i + 1, "config");
            if (configIndex < 0) {
                continue;
            }
            int keyIndex = gitConfigKeyIndex(tokens, configIndex + 1);
            if (keyIndex < 0) {
                continue;
            }
            String key = tokens.get(keyIndex);
            String value = keyIndex + 1 < tokens.size() ? tokens.get(keyIndex + 1) : "";
            int assignment = key.indexOf('=');
            if (assignment > 0 && assignment + 1 < key.length()) {
                value = key.substring(assignment + 1);
                key = key.substring(0, assignment);
            }
            if (isGitNoProxyConfigKey(key)) {
                addNoProxyHosts(value, urls);
            } else if (isGitProxyConfigKey(key)) {
                addProxyHost(value, urls);
            }
        }
    }

    /**
     * 查找token。
     *
     * @param tokens token参数。
     * @param start start 参数。
     * @param expected expected 参数。
     * @return 返回token结果。
     */
    private int findToken(List<String> tokens, int start, String expected) {
        for (int i = start; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                return -1;
            }
            if (expected.equalsIgnoreCase(token)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 执行git配置键索引相关逻辑。
     *
     * @param tokens token参数。
     * @param start start 参数。
     * @return 返回git配置键Index结果。
     */
    private int gitConfigKeyIndex(List<String> tokens, int start) {
        for (int i = start; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (StrUtil.isBlank(token) || isShellCommandSeparator(token)) {
                return -1;
            }
            if (isReadOnlyGitConfigOperation(token)) {
                return -1;
            }
            if (gitConfigOptionConsumesNextValue(token) && i + 1 < tokens.size()) {
                i++;
                continue;
            }
            if (isSkippableGitConfigWriteOption(token)) {
                continue;
            }
            return token.startsWith("-") ? -1 : i;
        }
        return -1;
    }

    /**
     * 判断是否Shell命令Separator。
     *
     * @param token token 参数。
     * @return 如果Shell命令Separator满足条件则返回 true，否则返回 false。
     */
    private boolean isShellCommandSeparator(String token) {
        return ";".equals(token)
                || "&&".equals(token)
                || "||".equals(token)
                || "|".equals(token)
                || "`".equals(token);
    }

    /**
     * 判断是否Read Only Git配置Operation。
     *
     * @param token token 参数。
     * @return 如果Read Only Git配置Operation满足条件则返回 true，否则返回 false。
     */
    private boolean isReadOnlyGitConfigOperation(String token) {
        String normalized = StrUtil.nullToEmpty(token).trim().toLowerCase(Locale.ROOT);
        return "--get".equals(normalized)
                || "--get-all".equals(normalized)
                || "--get-regexp".equals(normalized)
                || "--get-color".equals(normalized)
                || "--get-colorbool".equals(normalized)
                || "--list".equals(normalized)
                || "-l".equals(normalized)
                || "--name-only".equals(normalized)
                || "--show-origin".equals(normalized)
                || "--show-scope".equals(normalized)
                || "--show-names".equals(normalized)
                || "--edit".equals(normalized)
                || "-e".equals(normalized)
                || "--unset".equals(normalized)
                || "--unset-all".equals(normalized)
                || "--remove-section".equals(normalized)
                || "--rename-section".equals(normalized);
    }

    /**
     * 执行git配置选项ConsumesNext值相关逻辑。
     *
     * @param token token 参数。
     * @return 返回git配置Option Consumes Next Value结果。
     */
    private boolean gitConfigOptionConsumesNextValue(String token) {
        String normalized = StrUtil.nullToEmpty(token).trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("=")) {
            return false;
        }
        return "--file".equals(normalized)
                || "-f".equals(normalized)
                || "--blob".equals(normalized)
                || "--type".equals(normalized)
                || "-t".equals(normalized);
    }

    /**
     * 判断是否Skippable Git配置Write Option。
     *
     * @param token token 参数。
     * @return 如果Skippable Git配置Write Option满足条件则返回 true，否则返回 false。
     */
    private boolean isSkippableGitConfigWriteOption(String token) {
        String normalized = StrUtil.nullToEmpty(token).trim().toLowerCase(Locale.ROOT);
        return "--global".equals(normalized)
                || "--system".equals(normalized)
                || "--local".equals(normalized)
                || "--worktree".equals(normalized)
                || "--add".equals(normalized)
                || "--replace-all".equals(normalized)
                || "--fixed-value".equals(normalized)
                || "--includes".equals(normalized)
                || "--no-includes".equals(normalized)
                || "--null".equals(normalized)
                || "-z".equals(normalized)
                || normalized.startsWith("--file=")
                || normalized.startsWith("--blob=")
                || normalized.startsWith("--type=");
    }

    /**
     * 判断是否Git No Proxy配置键。
     *
     * @param rawKey 原始键标识或键值。
     * @return 如果Git No Proxy配置键满足条件则返回 true，否则返回 false。
     */
    private boolean isGitNoProxyConfigKey(String rawKey) {
        String key = normalizePackageManagerConfigKey(rawKey);
        return "noproxy".equals(key);
    }

    /**
     * 判断是否Git Proxy配置键。
     *
     * @param rawKey 原始键标识或键值。
     * @return 如果Git Proxy配置键满足条件则返回 true，否则返回 false。
     */
    private boolean isGitProxyConfigKey(String rawKey) {
        String key = normalizePackageManagerConfigKey(rawKey);
        return "proxy".equals(key);
    }

    /**
     * 查找Next token。
     *
     * @param tokens token参数。
     * @param start start 参数。
     * @param expected expected 参数。
     * @return 返回Next token结果。
     */
    private int findNextToken(List<String> tokens, int start, String expected) {
        for (int i = start; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if ("--location".equals(token) || "--global".equals(token) || "-g".equals(token)) {
                continue;
            }
            return expected.equalsIgnoreCase(token) ? i : -1;
        }
        return -1;
    }

    /**
     * 判断是否Package管理器配置命令。
     *
     * @param command 待执行或解析的命令文本。
     * @return 如果Package管理器配置命令满足条件则返回 true，否则返回 false。
     */
    private boolean isPackageManagerConfigCommand(String command) {
        return "npm".equals(command)
                || "pnpm".equals(command)
                || "yarn".equals(command)
                || "yarnpkg".equals(command)
                || "pip".equals(command)
                || "pip3".equals(command);
    }

    /**
     * 判断是否Package管理器No Proxy配置键。
     *
     * @param rawKey 原始键标识或键值。
     * @return 如果Package管理器No Proxy配置键满足条件则返回 true，否则返回 false。
     */
    private boolean isPackageManagerNoProxyConfigKey(String rawKey) {
        String key = normalizePackageManagerConfigKey(rawKey);
        return "noproxy".equals(key)
                || "noproxylist".equals(key)
                || "noproxyhosts".equals(key)
                || "globalnoproxy".equals(key);
    }

    /**
     * 判断是否Package管理器Proxy配置键。
     *
     * @param rawKey 原始键标识或键值。
     * @return 如果Package管理器Proxy配置键满足条件则返回 true，否则返回 false。
     */
    private boolean isPackageManagerProxyConfigKey(String rawKey) {
        String key = normalizePackageManagerConfigKey(rawKey);
        return "proxy".equals(key)
                || "httpproxy".equals(key)
                || "httpsproxy".equals(key)
                || "allproxy".equals(key)
                || "globalproxy".equals(key);
    }

    /**
     * 规范化Package管理器配置键。
     *
     * @param rawKey 原始键标识或键值。
     * @return 返回Package管理器配置键结果。
     */
    private String normalizePackageManagerConfigKey(String rawKey) {
        String key = StrUtil.nullToEmpty(rawKey).trim().toLowerCase(Locale.ROOT);
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < key.length()) {
            key = key.substring(dot + 1);
        }
        key = key.replace("-", "").replace("_", "");
        return key;
    }

    /**
     * 剥离OptionalQuote。
     *
     * @param raw 原始输入值。
     * @return 返回strip Optional Quote结果。
     */
    private String stripOptionalQuote(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /**
     * 判断是否Proxy Environment Assignment。
     *
     * @param token token 参数。
     * @return 如果Proxy Environment Assignment满足条件则返回 true，否则返回 false。
     */
    private boolean isProxyEnvironmentAssignment(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals).toLowerCase(Locale.ROOT);
        return isPersistentProxyEnvironmentName(name);
    }

    /**
     * 判断是否Persistent Proxy Environment名称。
     *
     * @param rawName 原始名称参数。
     * @return 如果Persistent Proxy Environment名称满足条件则返回 true，否则返回 false。
     */
    private boolean isPersistentProxyEnvironmentName(String rawName) {
        String name = StrUtil.nullToEmpty(rawName).trim().toLowerCase(Locale.ROOT);
        return "http_proxy".equals(name)
                || "https_proxy".equals(name)
                || "ftp_proxy".equals(name)
                || "no_proxy".equals(name)
                || "npm_config_proxy".equals(name)
                || "npm_config_https_proxy".equals(name)
                || "npm_config_no_proxy".equals(name)
                || "npm_config_noproxy".equals(name)
                || "yarn_proxy".equals(name)
                || "yarn_https_proxy".equals(name)
                || "yarn_no_proxy".equals(name)
                || "yarn_noproxy".equals(name)
                || "pnpm_config_proxy".equals(name)
                || "pnpm_config_https_proxy".equals(name)
                || "pnpm_config_no_proxy".equals(name)
                || "pnpm_config_noproxy".equals(name)
                || "pip_proxy".equals(name)
                || "all_proxy".equals(name);
    }

    /**
     * 判断是否No Proxy Environment名称。
     *
     * @param name 名称参数。
     * @return 如果No Proxy Environment名称满足条件则返回 true，否则返回 false。
     */
    private boolean isNoProxyEnvironmentName(String name) {
        String normalized = StrUtil.nullToEmpty(name).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("$env:")) {
            normalized = normalized.substring("$env:".length());
        } else if (normalized.startsWith("env:")) {
            normalized = normalized.substring("env:".length());
        }
        return "no_proxy".equals(normalized)
                || "npm_config_no_proxy".equals(normalized)
                || "npm_config_noproxy".equals(normalized)
                || "yarn_no_proxy".equals(normalized)
                || "yarn_noproxy".equals(normalized)
                || "pnpm_config_no_proxy".equals(normalized)
                || "pnpm_config_noproxy".equals(normalized);
    }

    /**
     * 追加代理Environment值。
     *
     * @param rawName 原始名称参数。
     * @param rawValue 原始值参数。
     * @param urls 待校验或访问的地址参数。
     */
    private void addProxyEnvironmentValue(String rawName, String rawValue, List<String> urls) {
        if (isNoProxyEnvironmentName(rawName)) {
            addNoProxyHosts(rawValue, urls);
        } else {
            addProxyHost(rawValue, urls);
        }
    }

    /**
     * 追加No代理Hosts。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addNoProxyHosts(String raw, List<String> urls) {
        String value = stripOptionalQuote(raw);
        if (StrUtil.isBlank(value)) {
            return;
        }
        for (String part : value.split(",")) {
            String token = cleanUrlToken(part);
            if (StrUtil.isBlank(token) || "*".equals(token)) {
                continue;
            }
            while (token.startsWith(".")) {
                token = token.substring(1);
            }
            int slash = token.indexOf('/');
            if (slash > 0) {
                token = token.substring(0, slash);
            }
            String host =
                    token.contains("://") ? extractUrlishHost(token) : extractSchemelessHost(token);
            if (StrUtil.isNotBlank(host)) {
                urls.add(token.contains("://") ? token : "http://" + token);
            }
        }
    }

    /**
     * 追加代理主机。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addProxyHost(String raw, List<String> urls) {
        String value = cleanUrlToken(raw);
        if (StrUtil.isBlank(value)) {
            return;
        }
        int at = value.lastIndexOf('@');
        if (at >= 0 && at + 1 < value.length()) {
            String hostWithCredential =
                    value.contains("://") ? extractUrlishHost(value) : extractSchemelessHost(value);
            if (StrUtil.isNotBlank(hostWithCredential)
                    && !shouldCheckBareHost(hostWithCredential)) {
                urls.add(value);
                return;
            }
            value = value.substring(at + 1);
        }
        String host =
                value.contains("://") ? extractUrlishHost(value) : extractSchemelessHost(value);
        if (StrUtil.isBlank(host)) {
            return;
        }
        if (shouldCheckBareHost(host)) {
            urls.add(value.contains("://") ? host : value);
        }
    }

    /**
     * 提取Urlish Host。
     *
     * @param raw 原始输入值。
     * @return 返回Urlish Host结果。
     */
    private String extractUrlishHost(String raw) {
        URI uri = parseUri(cleanUrlToken(raw));
        if (uri == null) {
            return "";
        }
        return normalizeHost(uri.getHost());
    }

    /**
     * 提取Curl Connection Override Hosts。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractCurlConnectionOverrideHosts(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = null;
            String mode = null;
            if ("--connect-to".equals(token) || "--resolve".equals(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                    mode = token;
                }
            } else if (token.startsWith("--connect-to=")) {
                value = token.substring("--connect-to=".length());
                mode = "--connect-to";
            } else if (token.startsWith("--resolve=")) {
                value = token.substring("--resolve=".length());
                mode = "--resolve";
            }
            addCurlOverrideHost(mode, value, urls);
        }
    }

    /**
     * 提取Curl Doh Urls。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractCurlDohUrls(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = null;
            if ("--doh-url".equals(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                }
            } else if (token.startsWith("--doh-url=")) {
                value = token.substring("--doh-url=".length());
            }
            if (StrUtil.isNotBlank(value)) {
                urls.add(cleanUrlToken(value));
            }
        }
    }

    /**
     * 提取Curl Dns 服务端。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractCurlDnsServers(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = null;
            if ("--dns-servers".equals(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                }
            } else if ("--dns-ipv4-addr".equals(token) || "--dns-ipv6-addr".equals(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                }
            } else if (token.startsWith("--dns-servers=")) {
                value = token.substring("--dns-servers=".length());
            } else if (token.startsWith("--dns-ipv4-addr=")) {
                value = token.substring("--dns-ipv4-addr=".length());
            } else if (token.startsWith("--dns-ipv6-addr=")) {
                value = token.substring("--dns-ipv6-addr=".length());
            }
            addDnsServerHosts(value, urls);
        }
    }

    /**
     * 提取System Dns Commands。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractSystemDnsCommands(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if ("networksetup".equalsIgnoreCase(token)) {
                i = extractNetworksetupDnsServers(tokens, i, urls);
            } else if ("Set-DnsClientServerAddress".equalsIgnoreCase(token)) {
                i = extractPowerShellDnsServers(tokens, i, urls);
            } else if ("netsh".equalsIgnoreCase(token)) {
                i = extractNetshDnsServers(tokens, i, urls);
            } else if ("nmcli".equalsIgnoreCase(token)) {
                i = extractNmcliDnsServers(tokens, i, urls);
            }
        }
    }

    /**
     * 提取Networksetup Dns 服务端。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param urls 待校验或访问的地址参数。
     * @return 返回Networksetup Dns 服务端结果。
     */
    private int extractNetworksetupDnsServers(List<String> tokens, int index, List<String> urls) {
        if (!matchesToken(tokens, index + 1, "-setdnsservers") || index + 3 >= tokens.size()) {
            return index;
        }
        for (int i = index + 3; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                return i;
            }
            addDnsServerHosts(token.replace(';', ','), urls);
        }
        return tokens.size() - 1;
    }

    /**
     * 提取Power Shell Dns 服务端。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param urls 待校验或访问的地址参数。
     * @return 返回Power Shell Dns 服务端结果。
     */
    private int extractPowerShellDnsServers(List<String> tokens, int index, List<String> urls) {
        for (int i = index + 1; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                return i;
            }
            String inline = optionInlineValue(token, "-ServerAddresses");
            if (StrUtil.isNotBlank(inline)) {
                addDnsServerHosts(inline.replace(';', ','), urls);
                continue;
            }
            if ("-ServerAddresses".equalsIgnoreCase(token) && i + 1 < tokens.size()) {
                addDnsServerHosts(tokens.get(++i).replace(';', ','), urls);
            }
        }
        return tokens.size() - 1;
    }

    /**
     * 提取Netsh Dns 服务端。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param urls 待校验或访问的地址参数。
     * @return 返回Netsh Dns 服务端结果。
     */
    private int extractNetshDnsServers(List<String> tokens, int index, List<String> urls) {
        if (!matchesToken(tokens, index + 1, "interface")
                || !matchesToken(tokens, index + 2, "ip")
                || !matchesToken(tokens, index + 3, "set")
                || !matchesToken(tokens, index + 4, "dns")) {
            return index;
        }
        for (int i = index + 5; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                return i;
            }
            int equals = token.indexOf('=');
            if (equals > 0) {
                String key = token.substring(0, equals);
                String value = token.substring(equals + 1);
                if ("static".equalsIgnoreCase(key) || "address".equalsIgnoreCase(key)) {
                    addDnsServerHosts(value, urls);
                }
                continue;
            }
            if (looksLikeDnsServerToken(token)) {
                addDnsServerHosts(token, urls);
            }
        }
        return tokens.size() - 1;
    }

    /**
     * 提取Nmcli Dns 服务端。
     *
     * @param tokens token参数。
     * @param index 索引参数。
     * @param urls 待校验或访问的地址参数。
     * @return 返回Nmcli Dns 服务端结果。
     */
    private int extractNmcliDnsServers(List<String> tokens, int index, List<String> urls) {
        if (!matchesToken(tokens, index + 1, "connection")
                || !matchesToken(tokens, index + 2, "modify")) {
            return index;
        }
        for (int i = index + 3; i < tokens.size(); i++) {
            String token = StrUtil.nullToEmpty(tokens.get(i)).trim();
            if (isShellCommandSeparator(token)) {
                return i;
            }
            if (isNmcliDnsKey(token) && i + 1 < tokens.size()) {
                addDnsServerHosts(tokens.get(++i).replace(';', ','), urls);
            }
        }
        return tokens.size() - 1;
    }

    /**
     * 判断是否Nmcli Dns键。
     *
     * @param token token 参数。
     * @return 如果Nmcli Dns键满足条件则返回 true，否则返回 false。
     */
    private boolean isNmcliDnsKey(String token) {
        String normalized = StrUtil.nullToEmpty(token).trim().toLowerCase(Locale.ROOT);
        return "ipv4.dns".equals(normalized)
                || "+ipv4.dns".equals(normalized)
                || "ipv6.dns".equals(normalized)
                || "+ipv6.dns".equals(normalized);
    }

    /**
     * 判断是否具有DNS服务端token特征。
     *
     * @param token token 参数。
     * @return 返回looks Like Dns Server token结果。
     */
    private boolean looksLikeDnsServerToken(String token) {
        String value = cleanUrlToken(token);
        if (StrUtil.isBlank(value)) {
            return false;
        }
        return value.contains(".") || value.contains(":") || value.contains("metadata.");
    }

    /**
     * 提取本地Bind Addresses。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractLocalBindAddresses(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = null;
            if ("--interface".equals(token)
                    || "--local-address".equals(token)
                    || "--source-address".equals(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                }
            } else if (token.startsWith("--interface=")) {
                value = token.substring("--interface=".length());
            } else if (token.startsWith("--local-address=")) {
                value = token.substring("--local-address=".length());
            } else if (token.startsWith("--source-address=")) {
                value = token.substring("--source-address=".length());
            }
            addDnsServerHosts(value, urls);
        }
    }

    /**
     * 追加DNS服务端Hosts。
     *
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addDnsServerHosts(String raw, List<String> urls) {
        String value = cleanUrlToken(raw);
        if (StrUtil.isBlank(value)) {
            return;
        }
        for (String host : value.split(",")) {
            String normalized = cleanUrlToken(host);
            if (StrUtil.isNotBlank(normalized)) {
                urls.add(normalized);
            }
        }
    }

    /**
     * 追加CurlOverride主机。
     *
     * @param mode 模式参数。
     * @param raw 原始输入值。
     * @param urls 待校验或访问的地址参数。
     */
    private void addCurlOverrideHost(String mode, String raw, List<String> urls) {
        String value = cleanUrlToken(raw);
        if (value.length() == 0) {
            return;
        }
        if (value.startsWith("+")) {
            value = value.substring(1);
        }
        String host;
        if ("--connect-to".equals(mode)) {
            String withoutTargetPort = stripTrailingCurlPort(value);
            int firstColon = withoutTargetPort.indexOf(':');
            int secondColon = firstColon < 0 ? -1 : withoutTargetPort.indexOf(':', firstColon + 1);
            if (secondColon < 0 || secondColon + 1 >= withoutTargetPort.length()) {
                return;
            }
            host = withoutTargetPort.substring(secondColon + 1);
        } else if ("--resolve".equals(mode)) {
            int firstColon = value.indexOf(':');
            int secondColon = firstColon < 0 ? -1 : value.indexOf(':', firstColon + 1);
            if (secondColon < 0 || secondColon + 1 >= value.length()) {
                return;
            }
            host = value.substring(secondColon + 1);
        } else {
            return;
        }
        if (StrUtil.isBlank(host)) {
            return;
        }
        urls.add(cleanUrlToken(host));
    }

    /**
     * 剥离TrailingCurl端口。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Trailing Curl Port结果。
     */
    private String stripTrailingCurlPort(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        if (value.endsWith("]")) {
            return value;
        }
        int colon = value.lastIndexOf(':');
        if (colon < 0 || colon + 1 >= value.length()) {
            return value;
        }
        String suffix = value.substring(colon + 1);
        return Pattern.compile("^\\d{1,5}$").matcher(suffix).matches()
                ? value.substring(0, colon)
                : value;
    }

    /**
     * 执行终端Liketoken相关逻辑。
     *
     * @param text 待处理文本。
     * @param maxTokens maxtoken参数。
     * @return 返回Shell Like token结果。
     */
    private List<String> shellLikeTokens(String text, int maxTokens) {
        String value = StrUtil.nullToEmpty(text);
        List<String> tokens = new ArrayList<String>();
        int i = 0;
        while (i < value.length() && tokens.size() < Math.max(1, maxTokens)) {
            while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
                i++;
            }
            if (i >= value.length()) {
                break;
            }
            boolean quoted = value.charAt(i) == '"' || value.charAt(i) == '\'';
            char quote = quoted ? value.charAt(i++) : 0;
            StringBuilder token = new StringBuilder();
            while (i < value.length()) {
                char ch = value.charAt(i);
                if (quoted) {
                    if (ch == quote) {
                        i++;
                        break;
                    }
                    if (ch == '\\' && quote == '"' && i + 1 < value.length()) {
                        token.append(value.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    token.append(ch);
                    i++;
                    continue;
                }
                if (ch == '\\' && i + 1 < value.length()) {
                    token.append(value.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    break;
                }
                token.append(ch);
                i++;
            }
            if (token.length() > 0 || quoted) {
                tokens.add(token.toString());
            }
        }
        return tokens;
    }

    /**
     * 提取Protocol Relative Urlish。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractProtocolRelativeUrlish(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (String token : tokens) {
            String value = cleanUrlToken(token);
            if (value.startsWith("//") && value.length() > 2) {
                urls.add(value);
            }
        }
    }

    /**
     * 提取Schemeless用户Info Urlish。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractSchemelessUserInfoUrlish(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (String token : tokens) {
            String value = cleanUrlToken(token);
            if (value.length() == 0 || value.contains("://") || !hasSchemelessUserInfo(value)) {
                continue;
            }
            String host = extractSchemelessHost(value);
            if (StrUtil.isBlank(host)) {
                continue;
            }
            urls.add(value);
        }
    }

    /**
     * 提取Bare安全Relevant Hosts。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractBareSecurityRelevantHosts(String text, List<String> urls) {
        boolean fetchContext = hasBareHostFetchContext(text);
        extractDirectNetworkEndpointHosts(text, urls);
        Matcher matcher = BARE_HOST_TOKEN_PATTERN.matcher(StrUtil.nullToEmpty(text));
        while (matcher.find()) {
            String value = cleanUrlToken(matcher.group(1));
            if (value.length() == 0 || value.contains("://")) {
                continue;
            }
            String host = extractSchemelessHost(value);
            if (StrUtil.isBlank(host)) {
                continue;
            }
            if (!fetchContext && value.matches("\\d+")) {
                continue;
            }
            if (fetchContext || shouldCheckBareHost(host)) {
                urls.add(value);
            }
        }
    }

    /**
     * 判断是否存在Bare Host Fetch上下文。
     *
     * @param text 待处理文本。
     * @return 如果Bare Host Fetch上下文满足条件则返回 true，否则返回 false。
     */
    private boolean hasBareHostFetchContext(String text) {
        return BARE_HOST_FETCH_CONTEXT_PATTERN.matcher(StrUtil.nullToEmpty(text)).find();
    }

    /**
     * 提取Direct Network Endpoint Hosts。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractDirectNetworkEndpointHosts(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = cleanUrlToken(tokens.get(i));
            if (token.length() == 0) {
                continue;
            }
            String host = extractDirectNetworkEndpointHost(token);
            if (StrUtil.isBlank(host)
                    && "-connect".equalsIgnoreCase(token)
                    && i + 1 < tokens.size()) {
                host = extractHostFromDirectEndpoint(tokens.get(++i));
            }
            if (StrUtil.isBlank(host) || !shouldCheckBareHost(host)) {
                continue;
            }
            urls.add(host);
        }
    }

    /**
     * 提取Direct Network Endpoint Host。
     *
     * @param raw 原始输入值。
     * @return 返回Direct Network Endpoint Host结果。
     */
    private String extractDirectNetworkEndpointHost(String raw) {
        String value = cleanUrlToken(raw);
        Matcher matcher = DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN.matcher(value);
        if (!matcher.find()) {
            return "";
        }
        return extractHostFromDirectEndpoint(matcher.group(2));
    }

    /**
     * 提取Host From Direct Endpoint。
     *
     * @param raw 原始输入值。
     * @return 返回Host From Direct Endpoint结果。
     */
    private String extractHostFromDirectEndpoint(String raw) {
        String value = cleanUrlToken(raw);
        if (value.length() == 0 || value.contains("://")) {
            return "";
        }
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(0, comma);
        }
        if (value.startsWith("[") && value.contains("]")) {
            return normalizeHost(value.substring(0, value.indexOf(']') + 1));
        }
        int colon = value.indexOf(':');
        if (colon > 0 && value.indexOf(':', colon + 1) < 0) {
            value = value.substring(0, colon);
        }
        return normalizeHost(value);
    }

    /**
     * 判断是否需要Check Bare Host。
     *
     * @param host 主机参数。
     * @return 如果Check Bare Host满足条件则返回 true，否则返回 false。
     */
    private boolean shouldCheckBareHost(String host) {
        if (SecurityAddressPolicySupport.isAlwaysBlockedHost(host)) {
            return true;
        }
        int[] obfuscatedIpv4 = SecurityAddressPolicySupport.parseObfuscatedIpv4(host);
        if (obfuscatedIpv4 != null) {
            return SecurityAddressPolicySupport.isBlockedOrAlwaysBlockedIpv4(obfuscatedIpv4);
        }
        if (SecurityAddressPolicySupport.isLocalOrAddressLiteral(host)) {
            return true;
        }
        return checkWebsitePolicy(host, host) != null;
    }

    /**
     * 提取Obfuscated Schemeless Urlish。
     *
     * @param text 待处理文本。
     * @param urls 待校验或访问的地址参数。
     */
    private void extractObfuscatedSchemelessUrlish(String text, List<String> urls) {
        String[] tokens = StrUtil.nullToEmpty(text).split("\\s+");
        for (String token : tokens) {
            String value = cleanUrlToken(token);
            if (value.length() == 0 || value.contains("://") || value.indexOf('/') <= 0) {
                continue;
            }
            String hostPort = value.substring(0, value.indexOf('/'));
            int at = hostPort.lastIndexOf('@');
            if (at >= 0) {
                hostPort = hostPort.substring(at + 1);
            }
            if (hostPort.startsWith("[")) {
                continue;
            }
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && hostPort.indexOf(':') == colon) {
                hostPort = hostPort.substring(0, colon);
            }
            int[] octets = SecurityAddressPolicySupport.parseObfuscatedIpv4(normalizeHost(hostPort));
            if (octets != null && SecurityAddressPolicySupport.isBlockedOrAlwaysBlockedIpv4(octets)) {
                urls.add(value);
            }
        }
    }

    /**
     * 检查Always 块ed Schemeless URL。
     *
     * @param raw 原始输入值。
     * @return 返回Always 块ed Schemeless URL结果。
     */
    private UrlVerdict checkAlwaysBlockedSchemelessUrl(String raw) {
        String host = extractSchemelessHost(raw);
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.allow();
        }
        return checkAlwaysBlockedHost(raw, host);
    }

    /**
     * 清理URLtoken。
     *
     * @param raw 原始输入值。
     * @return 返回clean URL token结果。
     */
    private String cleanUrlToken(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        value = HtmlUtil.unescape(value).trim();
        while (startsWithUrlWrapper(value)) {
            value = value.substring(1).trim();
        }
        while (value.endsWith(",")
                || value.endsWith(".")
                || value.endsWith(";")
                || value.endsWith(":")
                || value.endsWith("\"")
                || value.endsWith("'")
                || value.endsWith("`")
                || value.endsWith(">")
                || (value.endsWith("]") && !isBracketedIpv6Literal(value))
                || value.endsWith("}")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 判断是否以URLWrapper开头。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回starts With URL Wrapper结果。
     */
    private boolean startsWithUrlWrapper(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        if (value.startsWith("[") && containsBracketedIpv6Literal(value)) {
            return false;
        }
        return value.startsWith("(")
                || value.startsWith("{")
                || value.startsWith("<")
                || value.startsWith("\"")
                || value.startsWith("'")
                || value.startsWith("`")
                || value.startsWith("[");
    }

    /**
     * 判断是否Bracketed Ipv6 Literal。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Bracketed Ipv6 Literal满足条件则返回 true，否则返回 false。
     */
    private boolean isBracketedIpv6Literal(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return false;
        }
        int closing = text.indexOf(']');
        if (closing != text.length() - 1) {
            return false;
        }
        String host = text.substring(1, closing);
        return host.indexOf(':') >= 0;
    }

    /**
     * 判断是否包含BracketedIpv6Literal。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Bracketed Ipv6 Literal结果。
     */
    private boolean containsBracketedIpv6Literal(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (!text.startsWith("[") || !text.contains("]")) {
            return false;
        }
        String host = text.substring(1, text.indexOf(']'));
        return host.indexOf(':') >= 0;
    }

    /**
     * 提取Paths。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回Paths结果。
     */
    private List<String> extractPaths(String toolName, Map<String, Object> args) {
        List<String> paths = new ArrayList<String>();
        if (args == null) {
            return paths;
        }
        collectPaths(args, paths);
        if (ToolNameConstants.PATCH.equals(toolName) || hasPatchIntent(args)) {
            collectPatchTexts(args, paths);
        }
        return paths;
    }

    /**
     * 收集Patch Texts。
     *
     * @param raw 原始输入值。
     * @param paths 文件或目录路径参数。
     */
    @SuppressWarnings("unchecked")
    private void collectPatchTexts(Object raw, List<String> paths) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (looksLikePatchTextKey(key)) {
                    extractPatchPaths(value, paths);
                } else {
                    collectPatchTexts(value, paths);
                }
            }
            return;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                collectPatchTexts(value, paths);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                collectPatchTexts(java.lang.reflect.Array.get(raw, i), paths);
            }
        }
    }

    /**
     * 判断是否具有补丁文本键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like Patch Text键结果。
     */
    private boolean looksLikePatchTextKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "patch".equals(normalized)
                || "diff".equals(normalized)
                || "content".equals(normalized)
                || "input".equals(normalized);
    }

    /**
     * 收集Paths。
     *
     * @param raw 原始输入值。
     * @param paths 文件或目录路径参数。
     */
    @SuppressWarnings("unchecked")
    private void collectPaths(Object raw, List<String> paths) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (looksLikePathKey(key)) {
                    addPathValue(paths, value);
                } else {
                    collectPaths(value, paths);
                }
            }
            return;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                collectPaths(value, paths);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                collectPaths(java.lang.reflect.Array.get(raw, i), paths);
            }
        }
    }

    /**
     * 判断是否具有路径键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like路径键结果。
     */
    private boolean looksLikePathKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
        return "path".equals(normalized)
                || "paths".equals(normalized)
                || "file".equals(normalized)
                || "files".equals(normalized)
                || "filename".equals(normalized)
                || "filenames".equals(normalized)
                || "file_name".equals(normalized)
                || "file_names".equals(normalized)
                || "file_path".equals(normalized)
                || "file_paths".equals(normalized)
                || "filepath".equals(normalized)
                || "filepaths".equals(normalized)
                || "dir".equals(normalized)
                || "dirs".equals(normalized)
                || "cwd".equals(normalized)
                || "workdir".equals(normalized)
                || "working_dir".equals(normalized)
                || "workingdirectory".equals(normalized)
                || "dirname".equals(normalized)
                || "dirnames".equals(normalized)
                || "directory".equals(normalized)
                || "directories".equals(normalized)
                || "output_file".equals(normalized)
                || "outputfile".equals(normalized)
                || "out_file".equals(normalized)
                || "outfile".equals(normalized)
                || "destination".equals(normalized)
                || "dest".equals(normalized)
                || "target_file".equals(normalized)
                || "targetfile".equals(normalized)
                || normalized.endsWith("_path")
                || normalized.endsWith("_paths")
                || normalized.endsWith("path")
                || normalized.endsWith("_file")
                || normalized.endsWith("file");
    }

    /**
     * 执行工具参数URL键Samples相关逻辑。
     *
     * @return 返回工具参数URL键Samples结果。
     */
    private static List<String> toolArgsUrlKeySamples() {
        return Arrays.asList(
                "url", "uri", "href", "endpoint", "base_url", "callback_url", "proxy", "*_url");
    }

    /**
     * 执行工具参数路径键Samples相关逻辑。
     *
     * @return 返回工具参数路径键Samples结果。
     */
    private static List<String> toolArgsPathKeySamples() {
        return Arrays.asList(
                "path",
                "paths",
                "file",
                "filename",
                "file_path",
                "dir",
                "cwd",
                "workdir",
                "directory",
                "output_file",
                "destination",
                "*_path");
    }

    /**
     * 执行工具参数写入IntentSamples相关逻辑。
     *
     * @return 返回工具参数Write Intent Samples结果。
     */
    private static List<String> toolArgsWriteIntentSamples() {
        return Arrays.asList(
                "write", "append", "delete", "remove", "move", "rename", "create", "patch");
    }

    /**
     * 执行工具参数补丁IntentSamples相关逻辑。
     *
     * @return 返回工具参数Patch Intent Samples结果。
     */
    private static List<String> toolArgsPatchIntentSamples() {
        return Collections.singletonList("patch");
    }

    /**
     * 执行工具参数补丁文本键Samples相关逻辑。
     *
     * @return 返回工具参数Patch Text键Samples结果。
     */
    private static List<String> toolArgsPatchTextKeySamples() {
        return Arrays.asList("patch", "diff", "content", "input");
    }

    /**
     * 执行工具参数写入Like工具Samples相关逻辑。
     *
     * @return 返回工具参数Write Like工具Samples结果。
     */
    private static List<String> toolArgsWriteLikeToolSamples() {
        return Arrays.asList(
                "file_write",
                "write_file",
                "file_delete",
                "delete_file",
                "remove_file",
                "file_append",
                "file_move",
                "file_rename",
                "file_mkdir",
                ToolNameConstants.PATCH);
    }

    /**
     * 提取Patch Paths。
     *
     * @param raw 原始输入值。
     * @param paths 文件或目录路径参数。
     */
    private void extractPatchPaths(Object raw, List<String> paths) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw);
        Matcher matcher =
                Pattern.compile(
                                "^\\*\\*\\*\\s+(?:Update|Add|Delete)\\s+File:\\s*(.+)$",
                                Pattern.MULTILINE)
                        .matcher(text);
        while (matcher.find()) {
            addPathValue(paths, matcher.group(1));
        }
        Matcher moveMatcher =
                Pattern.compile(
                                "^\\*\\*\\*\\s+Move\\s+File:\\s*(.+?)\\s*->\\s*(.+)$",
                                Pattern.MULTILINE)
                        .matcher(text);
        while (moveMatcher.find()) {
            addPathValue(paths, moveMatcher.group(1));
            addPathValue(paths, moveMatcher.group(2));
        }
        Matcher moveSourceMatcher =
                Pattern.compile(
                                "^\\*\\*\\*\\s+Move\\s+File:\\s*(?!.*\\s->\\s)(.+)$",
                                Pattern.MULTILINE)
                        .matcher(text);
        while (moveSourceMatcher.find()) {
            addPathValue(paths, moveSourceMatcher.group(1));
        }
        Matcher moveToMatcher =
                Pattern.compile("^\\*\\*\\*\\s+Move\\s+to:\\s*(.+)$", Pattern.MULTILINE)
                        .matcher(text);
        while (moveToMatcher.find()) {
            addPathValue(paths, moveToMatcher.group(1));
        }
        Matcher gitDiffMatcher =
                Pattern.compile("^diff\\s+--git\\s+a/(\\S+)\\s+b/(\\S+).*$", Pattern.MULTILINE)
                        .matcher(text);
        while (gitDiffMatcher.find()) {
            addPathValue(paths, gitDiffMatcher.group(1));
            addPathValue(paths, gitDiffMatcher.group(2));
        }
        Matcher gitRenameMatcher =
                Pattern.compile("^(?:rename|copy)\\s+(?:from|to)\\s+(.+)$", Pattern.MULTILINE)
                        .matcher(text);
        while (gitRenameMatcher.find()) {
            addPathValue(paths, gitRenameMatcher.group(1));
        }
        Matcher unifiedHeaderMatcher =
                Pattern.compile("^(?:---|\\+\\+\\+)\\s+(?:a/|b/)?([^\\s]+).*$", Pattern.MULTILINE)
                        .matcher(text);
        while (unifiedHeaderMatcher.find()) {
            String value = unifiedHeaderMatcher.group(1);
            if (!"/dev/null".equals(value)) {
                addPathValue(paths, value);
            }
        }
    }

    /**
     * 追加路径值。
     *
     * @param paths 文件或目录路径参数。
     * @param raw 原始输入值。
     */
    private void addPathValue(List<String> paths, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            collectPaths(raw, paths);
            return;
        }
        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                addPathValue(paths, item);
            }
            return;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                addPathValue(paths, java.lang.reflect.Array.get(raw, i));
            }
            return;
        }
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim();
        if (value.length() > 0) {
            paths.add(value);
        }
    }

    /**
     * 判断是否Write Like工具。
     *
     * @param toolName 工具名称。
     * @return 如果Write Like工具满足条件则返回 true，否则返回 false。
     */
    private boolean isWriteLikeTool(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).trim().toLowerCase(Locale.ROOT);
        return "file_write".equals(normalized)
                || "file_delete".equals(normalized)
                || "write_file".equals(normalized)
                || "delete_file".equals(normalized)
                || "file_remove".equals(normalized)
                || "remove_file".equals(normalized)
                || "unlink_file".equals(normalized)
                || "file_append".equals(normalized)
                || "append_file".equals(normalized)
                || "file_move".equals(normalized)
                || "move_file".equals(normalized)
                || "file_rename".equals(normalized)
                || "rename_file".equals(normalized)
                || "file_mkdir".equals(normalized)
                || "mkdir".equals(normalized)
                || "create_file".equals(normalized)
                || "edit_file".equals(normalized)
                || ToolNameConstants.PATCH.equals(toolName);
    }

    /**
     * 判断是否存在Write Intent。
     *
     * @param raw 原始输入值。
     * @return 如果Write Intent满足条件则返回 true，否则返回 false。
     */
    @SuppressWarnings("unchecked")
    private boolean hasWriteIntent(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ((looksLikeActionKey(key) || looksLikeToolNameKey(key))
                        && isWriteIntentValue(value)) {
                    return true;
                }
                if (hasWriteIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                if (hasWriteIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                if (hasWriteIntent(java.lang.reflect.Array.get(raw, i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否存在Patch Intent。
     *
     * @param raw 原始输入值。
     * @return 如果Patch Intent满足条件则返回 true，否则返回 false。
     */
    @SuppressWarnings("unchecked")
    private boolean hasPatchIntent(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ((looksLikeActionKey(key) || looksLikeToolNameKey(key))
                        && isPatchIntentValue(value)) {
                    return true;
                }
                if (hasPatchIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                if (hasPatchIntent(value)) {
                    return true;
                }
            }
            return false;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                if (hasPatchIntent(java.lang.reflect.Array.get(raw, i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否具有工具名称键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like工具名称键结果。
     */
    private boolean looksLikeToolNameKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "tool".equals(normalized)
                || "name".equals(normalized)
                || "tool_name".equals(normalized)
                || "toolname".equals(normalized);
    }

    /**
     * 判断是否具有Action键特征。
     *
     * @param key 配置键或映射键。
     * @return 返回looks Like Action键结果。
     */
    private boolean looksLikeActionKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "action".equals(normalized)
                || "operation".equals(normalized)
                || "op".equals(normalized)
                || "mode".equals(normalized)
                || "method".equals(normalized);
    }

    /**
     * 判断是否Patch Intent Value。
     *
     * @param raw 原始输入值。
     * @return 如果Patch Intent Value满足条件则返回 true，否则返回 false。
     */
    private boolean isPatchIntentValue(Object raw) {
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim().toLowerCase(Locale.ROOT);
        return "patch".equals(value);
    }

    /**
     * 判断是否Write Intent Value。
     *
     * @param raw 原始输入值。
     * @return 如果Write Intent Value满足条件则返回 true，否则返回 false。
     */
    private boolean isWriteIntentValue(Object raw) {
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim().toLowerCase(Locale.ROOT);
        return "write".equals(value)
                || "file_write".equals(value)
                || "write_file".equals(value)
                || "append".equals(value)
                || "file_append".equals(value)
                || "append_file".equals(value)
                || "delete".equals(value)
                || "file_delete".equals(value)
                || "delete_file".equals(value)
                || "remove".equals(value)
                || "remove_file".equals(value)
                || "move".equals(value)
                || "file_move".equals(value)
                || "move_file".equals(value)
                || "rename".equals(value)
                || "file_rename".equals(value)
                || "rename_file".equals(value)
                || "create".equals(value)
                || "create_file".equals(value)
                || "file_create".equals(value)
                || "mkdir".equals(value)
                || "file_mkdir".equals(value)
                || "edit".equals(value)
                || "edit_file".equals(value)
                || "patch".equals(value)
                || "install".equals(value)
                || "update".equals(value)
                || "save".equals(value);
    }

    /**
     * 规范化路径Text。
     *
     * @param raw 原始输入值。
     * @return 返回路径Text结果。
     */
    private String normalizePathText(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        value = TerminalAnsiSanitizer.stripAnsi(value);
        value = SecretRedactor.stripDisplayControls(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = HtmlUtil.unescape(value).trim();
        value = decodePathText(value);
        value = value.replace('\\', '/');
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * 解码路径文本。
     *
     * @param raw 原始输入值。
     * @return 返回decode路径Text结果。
     */
    private String decodePathText(String raw) {
        String value = StrUtil.nullToEmpty(raw);
        for (int i = 0; i < 4; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                log.debug("Path text decoding failed; returning original normalized path text: {}", exceptionSummary(e));
                return value;
            }
            decoded = TerminalAnsiSanitizer.stripAnsi(decoded);
            decoded = SecretRedactor.stripDisplayControls(decoded);
            decoded = Normalizer.normalize(decoded, Normalizer.Form.NFKC);
            decoded = HtmlUtil.unescape(decoded).trim();
            if (decoded.equals(value)) {
                return decoded;
            }
            value = decoded;
        }
        return value;
    }

    /**
     * 判断是否包含控制Character。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Control Character结果。
     */
    private boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行printableCharacter相关逻辑。
     *
     * @param ch ch 参数。
     * @return 返回printable Character结果。
     */
    private static String printableCharacter(String ch) {
        if ("\n".equals(ch)) {
            return "\\n";
        }
        if ("\r".equals(ch)) {
            return "\\r";
        }
        if ("\t".equals(ch)) {
            return "\\t";
        }
        if (ch.length() == 1 && Character.isISOControl(ch.charAt(0))) {
            return String.format(Locale.ROOT, "\\u%04x", Integer.valueOf(ch.charAt(0)));
        }
        return ch;
    }

    /**
     * 判断是否包含Traversal。
     *
     * @param normalized normalized 参数。
     * @return 返回contains Traversal结果。
     */
    private boolean containsTraversal(String normalized) {
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../")
                || normalized.contains("%2e%2e")
                || normalized.contains("..%2f")
                || normalized.contains("..%5c");
    }

    /**
     * 判断是否匹配阻断Device路径。
     *
     * @param normalized normalized 参数。
     * @return 返回matches 块ed Device路径。
     */
    private boolean matchesBlockedDevicePath(String normalized) {
        if (BLOCKED_DEVICE_PATHS.contains(normalized)) {
            return true;
        }
        return PROC_STDIO_FD_PATTERN.matcher(normalized).matches();
    }

    /**
     * 判断是否匹配原始阻断Device路径。
     *
     * @param normalized normalized 参数。
     * @return 返回matches原始块 Device路径。
     */
    private boolean matchesRawBlockDevicePath(String normalized) {
        return RAW_BLOCK_DEVICE_PATTERN.matcher(normalized).matches();
    }

    /**
     * 判断是否匹配阻断Internal技能中心路径。
     *
     * @param normalized normalized 参数。
     * @return 返回matches 块ed Internal技能中心路径。
     */
    private boolean matchesBlockedInternalSkillHubPath(String normalized) {
        String path = stripKnownPrefix(normalized);
        String hub = RuntimePathConstants.SKILLS_DIR_NAME + "/.hub";
        if (path.equals(hub) || path.startsWith(hub + "/")) {
            return true;
        }
        String runtimeSkillsHub =
                normalizeRuntimePath(RuntimePathConstants.SKILLS_DIR_NAME, ".hub");
        return runtimeSkillsHub.length() > 0
                && (normalized.equals(runtimeSkillsHub)
                        || normalized.startsWith(runtimeSkillsHub + "/"));
    }

    /**
     * 判断是否匹配凭据路径。
     *
     * @param normalized normalized 参数。
     * @return 返回matches凭据路径。
     */
    private boolean matchesCredentialPath(String normalized) {
        String path = stripKnownPrefix(normalized);
        for (String runtimePath : RUNTIME_CREDENTIAL_FILE_PATHS) {
            String normalizedRuntimePath = runtimePath.toLowerCase(Locale.ROOT);
            String absoluteRuntimePath = normalizeRuntimeFilePath(normalizedRuntimePath);
            if (path.equals(normalizedRuntimePath) || normalized.equals(absoluteRuntimePath)) {
                return true;
            }
        }
        for (String suffix : CREDENTIAL_PATH_SUFFIXES) {
            String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
            if (path.equals(normalizedSuffix) || path.endsWith("/" + normalizedSuffix)) {
                return true;
            }
        }
        for (String segment : CREDENTIAL_DIR_SEGMENTS) {
            String normalizedSegment = segment.toLowerCase(Locale.ROOT);
            if (path.equals(normalizedSegment) || path.contains("/" + normalizedSegment + "/")) {
                return true;
            }
            if (path.startsWith(normalizedSegment + "/")
                    || path.endsWith("/" + normalizedSegment)) {
                return true;
            }
        }
        String fileName = lastPathPart(path);
        if (CREDENTIAL_FILE_NAMES.contains(fileName)) {
            return true;
        }
        if (matchesCloudCredentialJsonFileName(fileName)) {
            return true;
        }
        if (matchesSensitiveHomeFile(path, normalized)) {
            return true;
        }
        if (fileName.startsWith(".env.") && !".env.example".equals(fileName)) {
            return true;
        }
        if (matchesSensitiveKeyFileName(fileName)) {
            return true;
        }
        return matchesConfiguredCredentialPath(normalized, path);
    }

    /**
     * 判断是否匹配Sensitive主渠道文件。
     *
     * @param strippedPath 文件或目录路径参数。
     * @param normalized normalized 参数。
     * @return 返回matches Sensitive主渠道文件结果。
     */
    private boolean matchesSensitiveHomeFile(String strippedPath, String normalized) {
        if (!WRITE_DENIED_HOME_FILE_NAMES.contains(lastPathPart(strippedPath))) {
            return false;
        }
        return startsWithHomeLikePrefix(normalized) || startsWithUserHome(normalized);
    }

    /**
     * 判断是否匹配Sensitive键文件名称。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回matches Sensitive键文件名称结果。
     */
    private boolean matchesSensitiveKeyFileName(String fileName) {
        for (String extension : SENSITIVE_KEY_FILE_EXTENSIONS) {
            if (!fileName.endsWith(extension)) {
                continue;
            }
            for (String marker : SENSITIVE_KEY_FILE_MARKERS) {
                if (fileName.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断是否匹配Cloud凭据JSON文件名称。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回matches Cloud凭据JSON文件名称结果。
     */
    private boolean matchesCloudCredentialJsonFileName(String fileName) {
        return fileName.startsWith("firebase-adminsdk") && fileName.endsWith(".json");
    }

    /**
     * 判断是否匹配已配置凭据路径。
     *
     * @param normalized normalized 参数。
     * @param strippedPath 文件或目录路径参数。
     * @return 返回matches Configured凭据路径。
     */
    private boolean matchesConfiguredCredentialPath(String normalized, String strippedPath) {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return false;
        }
        List<String> credentialFiles = appConfig.getTerminal().getCredentialFiles();
        if (credentialFiles == null || credentialFiles.isEmpty()) {
            return false;
        }
        for (String configured : credentialFiles) {
            String configuredPath = normalizeConfiguredCredentialPath(configured);
            if (StrUtil.isBlank(configuredPath)) {
                continue;
            }
            if (matchesConfiguredCredentialPath(normalized, strippedPath, configuredPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查Configured凭据References。
     *
     * @param command 待执行或解析的命令文本。
     * @return 返回Configured凭据References结果。
     */
    private FileVerdict checkConfiguredCredentialReferences(String command) {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return FileVerdict.allow();
        }
        List<String> credentialFiles = appConfig.getTerminal().getCredentialFiles();
        if (credentialFiles == null || credentialFiles.isEmpty()) {
            return FileVerdict.allow();
        }
        String normalizedCommand = normalizePathText(command);
        for (String configured : credentialFiles) {
            String configuredPath = normalizeConfiguredCredentialPath(configured);
            if (StrUtil.isBlank(configuredPath)) {
                continue;
            }
            if (containsPathToken(normalizedCommand, configuredPath)) {
                return FileVerdict.block(configured, "读取敏感系统/凭据文件被阻断");
            }
            String runtimePath = normalizeRuntimeFilePath(configuredPath);
            if (StrUtil.isNotBlank(runtimePath)
                    && containsPathToken(normalizedCommand, runtimePath)) {
                return FileVerdict.block(configured, "读取敏感系统/凭据文件被阻断");
            }
        }
        return FileVerdict.allow();
    }

    /**
     * 判断是否包含路径token。
     *
     * @param normalizedText normalized文本参数。
     * @param normalizedPath 文件或目录路径参数。
     * @return 返回contains路径token结果。
     */
    private boolean containsPathToken(String normalizedText, String normalizedPath) {
        if (StrUtil.isBlank(normalizedText) || StrUtil.isBlank(normalizedPath)) {
            return false;
        }
        String[] candidates =
                new String[] {normalizedPath, "./" + normalizedPath, "../" + normalizedPath};
        for (String candidate : candidates) {
            if (containsExactPathToken(normalizedText, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含精确路径token。
     *
     * @param normalizedText normalized文本参数。
     * @param pathToken 文件或目录路径参数。
     * @return 返回contains Exact路径token结果。
     */
    private boolean containsExactPathToken(String normalizedText, String pathToken) {
        int from = 0;
        while (from < normalizedText.length()) {
            int index = normalizedText.indexOf(pathToken, from);
            if (index < 0) {
                return false;
            }
            int end = index + pathToken.length();
            if (isPathBoundary(normalizedText, index - 1) && isPathBoundary(normalizedText, end)) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    /**
     * 判断是否路径Boundary。
     *
     * @param text 待处理文本。
     * @param index 索引参数。
     * @return 如果路径Boundary满足条件则返回 true，否则返回 false。
     */
    private boolean isPathBoundary(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char ch = text.charAt(index);
        return Character.isWhitespace(ch)
                || ch == '\''
                || ch == '"'
                || ch == '`'
                || ch == '('
                || ch == ')'
                || ch == '['
                || ch == ']'
                || ch == '{'
                || ch == '}'
                || ch == '<'
                || ch == '>'
                || ch == '|'
                || ch == '&'
                || ch == ';'
                || ch == ','
                || ch == '@'
                || ch == '='
                || ch == ':';
    }

    /**
     * 判断是否匹配已配置凭据路径。
     *
     * @param normalized normalized 参数。
     * @param strippedPath 文件或目录路径参数。
     * @param configuredPath 文件或目录路径参数。
     * @return 返回matches Configured凭据路径。
     */
    private boolean matchesConfiguredCredentialPath(
            String normalized, String strippedPath, String configuredPath) {
        if (normalized.equals(configuredPath) || strippedPath.equals(configuredPath)) {
            return true;
        }
        String runtimePath = normalizeRuntimeFilePath(configuredPath);
        if (StrUtil.isNotBlank(runtimePath)
                && (normalized.equals(runtimePath) || strippedPath.equals(runtimePath))) {
            return true;
        }
        return normalized.endsWith("/" + configuredPath)
                || strippedPath.endsWith("/" + configuredPath);
    }

    /**
     * 规范化Configured凭据路径。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回Configured凭据路径。
     */
    private String normalizeConfiguredCredentialPath(String rawPath) {
        String value = normalizePathText(expandUserHome(StrUtil.nullToEmpty(rawPath).trim()));
        if (StrUtil.isBlank(value)) {
            return "";
        }
        if (containsTraversal(value)) {
            return "";
        }
        if (isAbsolutePathText(value)) {
            return value;
        }
        return stripKnownPrefix(value);
    }

    /**
     * 执行已配置凭据Files相关逻辑。
     *
     * @return 返回configured凭据Files结果。
     */
    private List<String> configuredCredentialFiles() {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return Collections.emptyList();
        }
        List<String> values = appConfig.getTerminal().getCredentialFiles();
        return values == null ? Collections.<String>emptyList() : values;
    }

    /**
     * 执行样例相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param max max 参数。
     * @return 返回sample结果。
     */
    private static List<String> sample(List<String> values, int max) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        int limit = Math.max(0, max);
        for (String value : values) {
            if (result.size() >= limit) {
                break;
            }
            if (StrUtil.isNotBlank(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 脱敏Sample。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param max max 参数。
     * @return 返回Sample结果。
     */
    private static List<String> redactSample(List<String> values, int max) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        int limit = Math.max(0, max);
        for (String value : values) {
            if (result.size() >= limit) {
                break;
            }
            if (StrUtil.isNotBlank(value)) {
                result.add(SecretRedactor.redact(value, 400));
            }
        }
        return result;
    }

    /**
     * 规范化运行时文件路径。
     *
     * @param relativePath 文件或目录路径参数。
     * @return 返回运行时文件路径。
     */
    private String normalizeRuntimeFilePath(String relativePath) {
        if (StrUtil.isBlank(relativePath) || isAbsolutePathText(relativePath)) {
            return "";
        }
        try {
            if (appConfig == null || appConfig.getRuntime() == null) {
                return "";
            }
            Path path =
                    Paths.get(appConfig.getRuntime().getHome(), relativePath)
                            .toAbsolutePath()
                            .normalize();
            return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            log.debug("Runtime relative path normalization failed; returning empty path: {}", exceptionSummary(e));
            return "";
        }
    }

    /**
     * 判断是否匹配写入Denied路径。
     *
     * @param normalized normalized 参数。
     * @return 返回matches Write Denied路径。
     */
    private boolean matchesWriteDeniedPath(String normalized) {
        String path = stripKnownPrefix(normalized);
        boolean underUserHome = startsWithUserHome(normalized);
        if (underUserHome) {
            String homeRelative = userHomeRelativePath(normalized);
            if (WRITE_DENIED_HOME_FILE_NAMES.contains(lastPathPart(homeRelative))) {
                return true;
            }
            path = homeRelative;
        }
        for (String exact : WRITE_DENIED_EXACT_PATHS) {
            if (path.equals(exact.substring(1)) || normalized.equals(exact)) {
                return true;
            }
        }
        for (String prefix : WRITE_DENIED_PREFIXES) {
            if ((normalized.startsWith(prefix)
                            && !(underUserHome && prefix.startsWith("/private/var/")))
                    || path.startsWith(prefix.substring(1))) {
                return true;
            }
        }
        for (String prefix : WRITE_DENIED_WINDOWS_PREFIXES) {
            if (normalized.startsWith(prefix) || path.startsWith(stripKnownPrefix(prefix))) {
                return true;
            }
        }
        if (WRITE_DENIED_HOME_FILE_NAMES.contains(lastPathPart(path))) {
            return startsWithHomeLikePrefix(normalized) || underUserHome;
        }
        return false;
    }

    /**
     * 判断是否以主渠道LikePrefix开头。
     *
     * @param normalized normalized 参数。
     * @return 返回starts With主渠道Like Prefix结果。
     */
    private boolean startsWithHomeLikePrefix(String normalized) {
        return normalized.startsWith("~/")
                || normalized.startsWith("$home/")
                || normalized.startsWith("${home}/")
                || normalized.startsWith("$env:home/")
                || normalized.startsWith("$env:userprofile/")
                || normalized.startsWith("${userprofile}/")
                || normalized.startsWith("%userprofile%/")
                || normalized.startsWith("%homepath%/");
    }

    /**
     * 判断是否以用户主渠道开头。
     *
     * @param normalized normalized 参数。
     * @return 返回starts With用户主渠道结果。
     */
    private boolean startsWithUserHome(String normalized) {
        String home = StrUtil.nullToEmpty(System.getProperty("user.home")).trim();
        if (StrUtil.isBlank(home)) {
            return false;
        }
        String normalizedHome = normalizePathText(home);
        if (normalizedHome.endsWith("/") && normalizedHome.length() > 1) {
            normalizedHome = normalizedHome.substring(0, normalizedHome.length() - 1);
        }
        return normalized.startsWith(normalizedHome + "/");
    }

    /**
     * 执行用户主渠道Relative路径相关逻辑。
     *
     * @param normalized normalized 参数。
     * @return 返回用户主渠道Relative路径。
     */
    private String userHomeRelativePath(String normalized) {
        String home = StrUtil.nullToEmpty(System.getProperty("user.home")).trim();
        if (StrUtil.isBlank(home)) {
            return normalized;
        }
        String normalizedHome = normalizePathText(home);
        if (normalizedHome.endsWith("/") && normalizedHome.length() > 1) {
            normalizedHome = normalizedHome.substring(0, normalizedHome.length() - 1);
        }
        if (normalized.equals(normalizedHome)) {
            return "";
        }
        if (normalized.startsWith(normalizedHome + "/")) {
            return normalized.substring(normalizedHome.length() + 1);
        }
        return normalized;
    }

    /**
     * 解析Comparable路径。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回解析后的Comparable路径。
     */
    private File resolveComparablePath(String rawPath) {
        String value = expandUserHome(StrUtil.nullToEmpty(rawPath).trim());
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return new File(value).getCanonicalFile();
        } catch (Exception e) {
            try {
                return new File(value).getAbsoluteFile().toPath().normalize().toFile();
            } catch (Exception fallbackError) {
                log.debug(
                        "Comparable path fallback normalization failed; returning null: {}",
                        exceptionSummary(fallbackError));
                return null;
            }
        }
    }

    /**
     * 执行expand用户主渠道相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回expand用户主渠道结果。
     */
    private String expandUserHome(String path) {
        if (StrUtil.isBlank(path)) {
            return path;
        }
        String home = StrUtil.nullToEmpty(System.getProperty("user.home")).trim();
        if (StrUtil.isBlank(home)) {
            return path;
        }
        if ("~".equals(path)) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home + path.substring(1);
        }
        return path;
    }

    /**
     * 剥离KnownPrefix。
     *
     * @param normalized normalized 参数。
     * @return 返回strip Known Prefix结果。
     */
    private String stripKnownPrefix(String normalized) {
        String value = normalized;
        if (value.startsWith("~/")) {
            return value.substring(2);
        }
        String homeRelative = userHomeRelativePath(value);
        if (!value.equals(homeRelative)) {
            return homeRelative;
        }
        if (value.startsWith("$home/")) {
            return value.substring("$home/".length());
        }
        if (value.startsWith("${home}/")) {
            return value.substring("${home}/".length());
        }
        if (value.startsWith("$env:home/")) {
            return value.substring("$env:home/".length());
        }
        if (value.startsWith("$env:userprofile/")) {
            return value.substring("$env:userprofile/".length());
        }
        if (value.startsWith("${userprofile}/")) {
            return value.substring("${userprofile}/".length());
        }
        if (value.startsWith("%userprofile%/")) {
            return value.substring("%userprofile%/".length());
        }
        if (value.startsWith("%homepath%/")) {
            return value.substring("%homepath%/".length());
        }
        if (value.startsWith("$env:appdata/") || value.startsWith("%appdata%/")) {
            return value.substring(value.indexOf('/') + 1);
        }
        try {
            Path workspaceHome =
                    appConfig == null || appConfig.getRuntime() == null
                            ? null
                            : Paths.get(appConfig.getRuntime().getHome())
                                    .toAbsolutePath()
                                    .normalize();
            if (workspaceHome != null) {
                String runtime = workspaceHome.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (value.startsWith(runtime + "/")) {
                    return value.substring(runtime.length() + 1);
                }
            }
        } catch (Exception e) {
            log.debug("Runtime prefix stripping failed; keeping normalized path: {}", exceptionSummary(e));
        }
        return value;
    }

    /**
     * 规范化运行时路径。
     *
     * @param first first 参数。
     * @param second second 参数。
     * @return 返回运行时路径。
     */
    private String normalizeRuntimePath(String first, String second) {
        try {
            if (appConfig == null || appConfig.getRuntime() == null) {
                return "";
            }
            Path path =
                    Paths.get(appConfig.getRuntime().getHome(), first, second)
                            .toAbsolutePath()
                            .normalize();
            return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            log.debug("Runtime path normalization failed; returning empty path: {}", exceptionSummary(e));
            return "";
        }
    }

    /**
     * 执行last路径Part相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回last路径Part结果。
     */
    private String lastPathPart(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    /**
     * 检查Website策略。
     *
     * @param rawUrl 待校验或访问的地址参数。
     * @param host 主机参数。
     * @return 返回Website策略结果。
     */
    private WebsiteRule checkWebsitePolicy(String rawUrl, String host) {
        if (appConfig == null
                || appConfig.getSecurity() == null
                || appConfig.getSecurity().getWebsiteBlocklist() == null
                || !appConfig.getSecurity().getWebsiteBlocklist().isEnabled()) {
            return null;
        }
        List<String> domains = appConfig.getSecurity().getWebsiteBlocklist().getDomains();
        if (domains == null) {
            domains = Collections.emptyList();
        }
        for (String rawRule : domains) {
            String rule = normalizeRule(rawRule);
            if (StrUtil.isBlank(rule)) {
                continue;
            }
            if (matchHost(host, rule)) {
                return new WebsiteRule(rawUrl, rule);
            }
        }
        for (String rawRule : sharedWebsiteRules()) {
            String rule = normalizeRule(rawRule);
            if (StrUtil.isBlank(rule)) {
                continue;
            }
            if (matchHost(host, rule)) {
                return new WebsiteRule(rawUrl, rule);
            }
        }
        return null;
    }

    /**
     * 执行网站块list配置相关逻辑。
     *
     * @return 返回website 块list配置。
     */
    private AppConfig.WebsiteBlocklistConfig websiteBlocklistConfig() {
        if (appConfig == null || appConfig.getSecurity() == null) {
            return null;
        }
        return appConfig.getSecurity().getWebsiteBlocklist();
    }

    /**
     * 执行shared网站Rules相关逻辑。
     *
     * @return 返回shared Website Rules结果。
     */
    private List<String> sharedWebsiteRules() {
        return sharedWebsiteRuleSummary().rules;
    }

    /**
     * 执行shared网站Rule摘要相关逻辑。
     *
     * @return 返回shared Website Rule Summary结果。
     */
    private SharedWebsiteRuleSummary sharedWebsiteRuleSummary() {
        SharedWebsiteRuleSummary summary = new SharedWebsiteRuleSummary();
        if (appConfig == null
                || appConfig.getSecurity() == null
                || appConfig.getSecurity().getWebsiteBlocklist() == null) {
            return summary;
        }
        List<String> sharedFiles = appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles();
        if (sharedFiles == null || sharedFiles.isEmpty()) {
            return summary;
        }
        for (String rawPath : sharedFiles) {
            File file = resolveSharedFile(rawPath);
            if (file == null || !file.isFile()) {
                summary.skippedFileCount++;
                continue;
            }
            try {
                String text = cn.hutool.core.io.FileUtil.readString(file, StandardCharsets.UTF_8);
                String[] lines = text.split("\\r?\\n");
                summary.loadedFileCount++;
                for (String line : lines) {
                    String value = StrUtil.nullToEmpty(line).trim();
                    if (value.length() == 0 || value.startsWith("#")) {
                        continue;
                    }
                    summary.rules.add(value);
                    summary.ruleCount++;
                    if (summary.ruleSamples.size() < 6) {
                        summary.ruleSamples.add(value);
                    }
                }
            } catch (Exception e) {
                log.debug("Shared rule file loading failed; skipping unreadable rule file: {}", exceptionSummary(e));
                summary.skippedFileCount++;
            }
        }
        return summary;
    }

    /**
     * 解析Shared文件。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回解析后的Shared文件。
     */
    private File resolveSharedFile(String rawPath) {
        String path = expandUserHome(StrUtil.nullToEmpty(rawPath).trim());
        if (path.length() == 0) {
            return null;
        }
        if (!checkPath(path, false).isAllowed()) {
            return null;
        }
        try {
            if (isAbsolutePathText(path)) {
                File file = new File(path).getCanonicalFile();
                return checkPath(file.getAbsolutePath(), false).isAllowed() ? file : null;
            }
            if (containsTraversal(normalizePathText(path))) {
                return null;
            }
            File workspaceHome =
                    appConfig == null || appConfig.getRuntime() == null
                            ? new File(".")
                            : new File(
                                    StrUtil.blankToDefault(
                                            appConfig.getRuntime().getHome(),
                                            RuntimePathConstants.WORKSPACE_HOME));
            File home = workspaceHome.getCanonicalFile();
            File file = new File(home, path).getCanonicalFile();
            if (isInside(file, home)) {
                return checkPath(file.getAbsolutePath(), false).isAllowed() ? file : null;
            }
            return null;
        } catch (Exception e) {
            log.debug("Shared file resolution failed; rejecting shared file path: {}", exceptionSummary(e));
            return null;
        }
    }

    /**
     * 判断是否Inside。
     *
     * @param child child 参数。
     * @param parent parent 参数。
     * @return 如果Inside满足条件则返回 true，否则返回 false。
     */
    private boolean isInside(File child, File parent) {
        String childPath = child.getAbsolutePath();
        String parentPath = parent.getAbsolutePath();
        if (childPath.equals(parentPath)) {
            return true;
        }
        if (!parentPath.endsWith(File.separator)) {
            parentPath = parentPath + File.separator;
        }
        return childPath.startsWith(parentPath);
    }

    /**
     * 判断是否Absolute路径Text。
     *
     * @param path 文件或目录路径。
     * @return 如果Absolute路径Text满足条件则返回 true，否则返回 false。
     */
    private boolean isAbsolutePathText(String path) {
        String value = StrUtil.nullToEmpty(path).trim();
        return new File(value).isAbsolute()
                || value.startsWith("/")
                || value.startsWith("\\")
                || Pattern.compile("^[A-Za-z]:[/\\\\].*").matcher(value).matches();
    }

    /**
     * 执行match主机相关逻辑。
     *
     * @param host 主机参数。
     * @param rule rule 参数。
     * @return 返回match Host结果。
     */
    private boolean matchHost(String host, String rule) {
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(2);
            return host.endsWith("." + suffix);
        }
        return host.equals(rule) || host.endsWith("." + rule);
    }

    /**
     * 规范化Rule。
     *
     * @param raw 原始输入值。
     * @return 返回Rule结果。
     */
    private String normalizeRule(String raw) {
        String value = normalizeUrlText(raw).toLowerCase(Locale.ROOT);
        if (value.length() == 0 || value.startsWith("#")) {
            return "";
        }
        value = stripInlineRuleComment(value);
        if (value.contains("://")) {
            URI uri = parseUri(value);
            value = uri == null ? value : extractUriHost(uri);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        value = stripRulePort(value);
        value = normalizeHost(value);
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    /**
     * 剥离内联RuleComment。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Inline Rule Comment结果。
     */
    private String stripInlineRuleComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == '#' && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i).trim();
            }
        }
        return value;
    }

    /**
     * 剥离Rule端口。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Rule Port结果。
     */
    private String stripRulePort(String value) {
        if (StrUtil.isBlank(value) || value.startsWith("[") || value.indexOf(':') < 0) {
            return value;
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || value.indexOf(':') != colon || colon + 1 >= value.length()) {
            return value;
        }
        for (int i = colon + 1; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return value;
            }
        }
        return value.substring(0, colon);
    }

    /**
     * 提取Schemeless Host。
     *
     * @param raw 原始输入值。
     * @return 返回Schemeless Host结果。
     */
    private String extractSchemelessHost(String raw) {
        String value = normalizeUrlText(raw);
        if (value.contains("://")) {
            return "";
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int question = value.indexOf('?');
        if (question >= 0) {
            value = value.substring(0, question);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[") && value.contains("]")) {
            return normalizeHost(value.substring(0, value.indexOf(']') + 1));
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            value = value.substring(0, colon);
        }
        return normalizeHost(value.startsWith("www.") ? value.substring(4) : value);
    }

    /**
     * 解析URI。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回解析后的URI。
     */
    private URI parseUri(String url) {
        try {
            return URI.create(url);
        } catch (Exception e) {
            log.debug("URI parsing failed; treating URL text as unparsable: {}", exceptionSummary(e));
            return null;
        }
    }

    /**
     * 判断是否存在用户Info。
     *
     * @param uri 待校验或访问的地址参数。
     * @return 如果用户Info满足条件则返回 true，否则返回 false。
     */
    private boolean hasUserInfo(URI uri) {
        if (uri == null) {
            return false;
        }
        if (StrUtil.isNotBlank(uri.getRawUserInfo()) || StrUtil.isNotBlank(uri.getUserInfo())) {
            return true;
        }
        String authority = StrUtil.nullToEmpty(uri.getRawAuthority());
        if (authority.length() == 0) {
            authority = StrUtil.nullToEmpty(uri.getAuthority());
        }
        return authority.indexOf('@') >= 0;
    }

    /**
     * 判断是否存在Sensitive URL Parameter名称。
     *
     * @param uri 待校验或访问的地址参数。
     * @return 如果Sensitive URL Parameter名称满足条件则返回 true，否则返回 false。
     */
    private boolean hasSensitiveUrlParameterName(URI uri) {
        if (uri == null) {
            return false;
        }
        return containsSensitivePathCredentialName(uri.getRawPath())
                || containsSensitiveParameterName(uri.getRawPath())
                || containsSensitiveParameterName(uri.getRawQuery())
                || containsSensitiveParameterName(uri.getRawFragment());
    }

    /**
     * 判断是否存在Sensitive Schemeless URL Parameter名称。
     *
     * @param raw 原始输入值。
     * @return 如果Sensitive Schemeless URL Parameter名称满足条件则返回 true，否则返回 false。
     */
    private boolean hasSensitiveSchemelessUrlParameterName(String raw) {
        URI uri = parseUri("http://" + raw);
        if (uri == null) {
            return containsSensitiveParameterName(raw);
        }
        return hasSensitiveUrlParameterName(uri);
    }

    /**
     * 判断是否包含Sensitive路径凭据名称。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 返回contains Sensitive路径凭据名称结果。
     */
    private boolean containsSensitivePathCredentialName(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath);
        if (value.length() == 0) {
            return false;
        }
        String[] segments = value.split("[/\\\\]+");
        for (int i = 0; i < segments.length; i++) {
            String segment = decodeUrlComponent(segments[i]).trim();
            if (segment.length() == 0) {
                continue;
            }
            String name = segment;
            int equals = name.indexOf('=');
            int colon = name.indexOf(':');
            int delimiter = -1;
            if (equals >= 0 && colon >= 0) {
                delimiter = Math.min(equals, colon);
            } else if (equals >= 0) {
                delimiter = equals;
            } else if (colon >= 0) {
                delimiter = colon;
            }
            if (delimiter >= 0) {
                name = name.substring(0, delimiter);
                if (isSensitiveUrlParameterName(name)) {
                    return true;
                }
                continue;
            }
            if (isSensitiveUrlParameterName(name)
                    && hasFollowingPathCredentialValue(segments, i + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否存在Following路径凭据Value。
     *
     * @param segments segments 参数。
     * @param start start 参数。
     * @return 如果Following路径凭据Value满足条件则返回 true，否则返回 false。
     */
    private boolean hasFollowingPathCredentialValue(String[] segments, int start) {
        for (int i = start; i < segments.length; i++) {
            if (decodeUrlComponent(segments[i]).trim().length() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含Sensitive参数名称。
     *
     * @param rawParameters 原始Parameters参数。
     * @return 返回contains Sensitive Parameter名称结果。
     */
    private boolean containsSensitiveParameterName(String rawParameters) {
        String value = StrUtil.nullToEmpty(rawParameters);
        if (value.length() == 0) {
            return false;
        }
        if (containsSignedUrlParameterSet(value)) {
            return true;
        }
        if (containsSensitiveParameterNameInCandidate(value)) {
            return true;
        }
        String decoded = decodeUrlComponent(value);
        if (!decoded.equals(value)) {
            if (containsSignedUrlParameterSet(decoded)) {
                return true;
            }
            return containsSensitiveParameterNameInCandidate(decoded);
        }
        return false;
    }

    /**
     * 判断是否包含Sensitive参数名称InCandidate。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Sensitive Parameter名称In Candidate结果。
     */
    private boolean containsSensitiveParameterNameInCandidate(String value) {
        String[] parameters = value.split("[&;]");
        for (String parameter : parameters) {
            String normalizedParameter = StrUtil.nullToEmpty(parameter);
            String name = normalizedParameter;
            int question = name.indexOf('?');
            if (question >= 0) {
                name = name.substring(question + 1);
            }
            int hash = name.indexOf('#');
            if (hash >= 0) {
                name = name.substring(hash + 1);
            }
            int equals = name.indexOf('=');
            String rawParameterValue = "";
            if (equals >= 0) {
                rawParameterValue = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            if (containsNestedSensitiveParameterName(rawParameterValue)) {
                return true;
            }
            if (isStrongSensitiveUrlParameterName(name)
                    || (isSensitiveUrlParameterName(name)
                            && !isGenericSignatureParameterName(name)
                            && looksLikeSensitiveUrlParameterValue(rawParameterValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含NestedSensitive参数名称。
     *
     * @param rawValue 原始值参数。
     * @return 返回contains Nested Sensitive Parameter名称结果。
     */
    private boolean containsNestedSensitiveParameterName(String rawValue) {
        String value = decodeUrlComponent(rawValue);
        if (containsStructuredSensitiveParameterName(value)) {
            return true;
        }
        if (value.equals(StrUtil.nullToEmpty(rawValue))) {
            return false;
        }
        if (value.indexOf('?') < 0 && value.indexOf('&') < 0 && value.indexOf(';') < 0) {
            return false;
        }
        return containsSensitiveParameterName(value);
    }

    /**
     * 判断是否包含StructuredSensitive参数名称。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回contains Structured Sensitive Parameter名称结果。
     */
    private boolean containsStructuredSensitiveParameterName(String value) {
        Matcher matcher =
                Pattern.compile("(?iu)[\"']?([A-Za-z][A-Za-z0-9_.-]{2,})[\"']?\\s*[:=]")
                        .matcher(StrUtil.nullToEmpty(value));
        while (matcher.find()) {
            if (isSensitiveUrlParameterName(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含SignedURL参数Set。
     *
     * @param rawParameters 原始Parameters参数。
     * @return 返回contains Signed URL Parameter Set结果。
     */
    private boolean containsSignedUrlParameterSet(String rawParameters) {
        String[] parameters = StrUtil.nullToEmpty(rawParameters).split("[&;]");
        boolean signature = false;
        boolean accessKey = false;
        boolean credential = false;
        boolean expires = false;
        for (String parameter : parameters) {
            String name = parameterName(parameter);
            if (name.length() == 0) {
                continue;
            }
            if ("signature".equals(name)) {
                signature = true;
            }
            if ("awsaccesskeyid".equals(name)
                    || "ossaccesskeyid".equals(name)
                    || "accesskeyid".equals(name)
                    || "access_key_id".equals(name)) {
                accessKey = true;
            }
            if ("credential".equals(name)
                    || name.endsWith("_credential")
                    || "security_token".equals(name)
                    || name.endsWith("_security_token")) {
                credential = true;
            }
            if ("expires".equals(name) || "expiration".equals(name) || name.endsWith("_expires")) {
                expires = true;
            }
        }
        return signature && (accessKey || credential || expires);
    }

    /**
     * 执行参数名称相关逻辑。
     *
     * @param rawParameter 原始参数参数。
     * @return 返回parameter名称结果。
     */
    private String parameterName(String rawParameter) {
        String name = StrUtil.nullToEmpty(rawParameter);
        int question = name.indexOf('?');
        if (question >= 0) {
            name = name.substring(question + 1);
        }
        int hash = name.indexOf('#');
        if (hash >= 0) {
            name = name.substring(hash + 1);
        }
        int equals = name.indexOf('=');
        if (equals >= 0) {
            name = name.substring(0, equals);
        }
        return normalizeSensitiveParameterName(name);
    }

    /**
     * 判断是否Strong Sensitive URL Parameter名称。
     *
     * @param rawName 原始名称参数。
     * @return 如果Strong Sensitive URL Parameter名称满足条件则返回 true，否则返回 false。
     */
    private boolean isStrongSensitiveUrlParameterName(String rawName) {
        String name = normalizeSensitiveParameterName(rawName);
        return "access_token".equals(name)
                || "refresh_token".equals(name)
                || "id_token".equals(name)
                || "auth_token".equals(name)
                || "oauth_token".equals(name)
                || "authorization".equals(name)
                || "proxy_authorization".equals(name)
                || "bearer_token".equals(name)
                || "code_verifier".equals(name)
                || "client_assertion".equals(name)
                || "saml_response".equals(name)
                || "samlresponse".equals(name)
                || "access_key".equals(name)
                || "secret_key".equals(name)
                || "session_token".equals(name)
                || "client_secret".equals(name)
                || "api_key".equals(name)
                || "apikey".equals(name)
                || "password".equals(name)
                || "private_key".equals(name)
                || name.startsWith("x_amz_")
                || name.startsWith("x_goog_")
                || name.startsWith("x_oss_")
                || name.startsWith("x_cos_")
                || name.startsWith("x_obs_")
                || name.startsWith("x_ms_")
                || "security_token".equals(name);
    }

    /**
     * 判断是否Generic签名Parameter名称。
     *
     * @param rawName 原始名称参数。
     * @return 如果Generic签名Parameter名称满足条件则返回 true，否则返回 false。
     */
    private boolean isGenericSignatureParameterName(String rawName) {
        return "signature".equals(normalizeSensitiveParameterName(rawName));
    }

    /**
     * 判断是否Sensitive URL Parameter名称。
     *
     * @param rawName 原始名称参数。
     * @return 如果Sensitive URL Parameter名称满足条件则返回 true，否则返回 false。
     */
    private boolean isSensitiveUrlParameterName(String rawName) {
        String name = normalizeSensitiveParameterName(rawName);
        for (String sensitiveName : SENSITIVE_URL_PARAMETER_NAMES) {
            if (name.equals(normalizeSensitiveParameterName(sensitiveName))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否具有SensitiveURL参数值特征。
     *
     * @param rawValue 原始值参数。
     * @return 返回looks Like Sensitive URL Parameter Value结果。
     */
    private boolean looksLikeSensitiveUrlParameterValue(String rawValue) {
        String value = decodeUrlComponent(rawValue).trim();
        return value.length() >= 8 || SecretRedactor.containsSecretLikeToken(value);
    }

    /**
     * 规范化Sensitive Parameter名称。
     *
     * @param rawName 原始名称参数。
     * @return 返回Sensitive Parameter名称结果。
     */
    private String normalizeSensitiveParameterName(String rawName) {
        String name = decodeUrlComponent(rawName).trim();
        name = name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        name = name.toLowerCase(Locale.ROOT);
        name = name.replace('-', '_').replace('.', '_');
        name = name.replaceAll("\\s+", "_");
        return name;
    }

    /**
     * 解码URLComponent。
     *
     * @param raw 原始输入值。
     * @return 返回decode URL Component结果。
     */
    private String decodeUrlComponent(String raw) {
        String value = StrUtil.nullToEmpty(raw);
        for (int i = 0; i < 4; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                log.debug("URL component decoding failed; returning current normalized text: {}", exceptionSummary(e));
                return value;
            }
            decoded = normalizeUrlText(decoded);
            if (decoded.equals(value)) {
                return decoded;
            }
            value = decoded;
        }
        return value;
    }

    /**
     * 判断是否存在Schemeless用户Info。
     *
     * @param raw 原始输入值。
     * @return 如果Schemeless用户Info满足条件则返回 true，否则返回 false。
     */
    private boolean hasSchemelessUserInfo(String raw) {
        String value = normalizeUrlText(raw);
        if (value.length() == 0 || value.contains("://")) {
            return false;
        }
        int slash = value.indexOf('/');
        String authority = slash >= 0 ? value.substring(0, slash) : value;
        int question = authority.indexOf('?');
        if (question >= 0) {
            authority = authority.substring(0, question);
        }
        int hash = authority.indexOf('#');
        if (hash >= 0) {
            authority = authority.substring(0, hash);
        }
        int at = authority.lastIndexOf('@');
        if (at <= 0 || at + 1 >= authority.length()) {
            return false;
        }
        String userInfo = authority.substring(0, at);
        String host = authority.substring(at + 1);
        return userInfo.length() > 0 && extractSchemelessHost(host).length() > 0;
    }

    /**
     * 提取URI Host。
     *
     * @param uri 待校验或访问的地址参数。
     * @return 返回URI Host结果。
     */
    private String extractUriHost(URI uri) {
        if (uri == null) {
            return "";
        }
        String host = normalizeHost(uri.getHost());
        if (StrUtil.isNotBlank(host)) {
            return host;
        }
        String authority = StrUtil.nullToEmpty(uri.getRawAuthority());
        if (authority.length() == 0) {
            authority = StrUtil.nullToEmpty(uri.getAuthority());
        }
        authority = normalizeUrlText(authority);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[") && authority.contains("]")) {
            return normalizeHost(authority.substring(0, authority.indexOf(']') + 1));
        }
        int colon = authority.lastIndexOf(':');
        if (colon > 0 && authority.indexOf(':') == colon) {
            authority = authority.substring(0, colon);
        }
        return normalizeHost(authority);
    }

    /**
     * 规范化URL Text。
     *
     * @param raw 原始输入值。
     * @return 返回URL Text结果。
     */
    private String normalizeUrlText(String raw) {
        String value = StrUtil.nullToEmpty(raw).replace("\u0000", "");
        value = TerminalAnsiSanitizer.stripAnsi(value);
        value = SecretRedactor.stripDisplayControls(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        value = HtmlUtil.unescape(value);
        return value.trim();
    }

    /**
     * 规范化Host。
     *
     * @param host 主机参数。
     * @return 返回Host结果。
     */
    private String normalizeHost(String host) {
        String value = decodeHostText(host).toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        value = toAsciiHost(value);
        return value;
    }

    /**
     * 解码主机文本。
     *
     * @param host 主机参数。
     * @return 返回decode Host Text结果。
     */
    private String decodeHostText(String host) {
        String value = normalizeUrlText(host);
        for (int i = 0; i < 4; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                log.debug("Host text decoding failed; returning current normalized host: {}", exceptionSummary(e));
                return value;
            }
            decoded = normalizeUrlText(decoded);
            if (decoded.equals(value)) {
                return decoded;
            }
            value = decoded;
        }
        return value;
    }

    /**
     * 转换为Ascii Host。
     *
     * @param host 主机参数。
     * @return 返回转换后的Ascii Host。
     */
    private String toAsciiHost(String host) {
        if (StrUtil.isBlank(host) || host.indexOf(':') >= 0) {
            return host;
        }
        if (host.startsWith("*.")) {
            return "*." + toAsciiHost(host.substring(2));
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            log.debug(
                    "Host ASCII normalization failed; keeping normalized host: {}",
                    exceptionSummary(e));
            return host;
        }
    }

    /**
     * 将安全解析异常压缩成单行脱敏摘要，避免 debug 日志输出完整栈或敏感路径。
     *
     * @param error 解析或规范化过程中捕获的异常。
     * @return 返回异常类型与脱敏消息摘要。
     */
    private static String exceptionSummary(Exception error) {
        if (error == null) {
            return "";
        }
        String message =
                SecretRedactor.redact(
                        StrUtil.blankToDefault(error.getMessage(), error.getClass().getName()),
                        500);
        return error.getClass().getSimpleName() + ": " + message;
    }

    /**
     * 解析Allow私聊Urls。
     *
     * @return 返回解析后的Allow私聊Urls。
     */
    private boolean resolveAllowPrivateUrls() {
        Boolean envOverride = parseBooleanOverride(readEnvironment("SOLONCLAW_ALLOW_PRIVATE_URLS"));
        if (envOverride != null) {
            return envOverride.booleanValue();
        }
        return appConfig != null
                && appConfig.getSecurity() != null
                && appConfig.getSecurity().isAllowPrivateUrls();
    }

    /**
     * 解析Boolean Override。
     *
     * @param raw 原始输入值。
     * @return 返回解析后的Boolean Override。
     */
    private Boolean parseBooleanOverride(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0) {
            return null;
        }
        if ("1".equals(value)
                || "true".equals(value)
                || "yes".equals(value)
                || "y".equals(value)
                || "on".equals(value)) {
            return Boolean.TRUE;
        }
        if ("0".equals(value)
                || "false".equals(value)
                || "no".equals(value)
                || "n".equals(value)
                || "off".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 承载网站Rule相关状态和辅助逻辑。 */
    private static class WebsiteRule {
        /** 记录网站Rule中的URL。 */
        private final String url;

        /** 记录网站Rule中的rule。 */
        private final String rule;

        /**
         * 创建Website Rule实例，并注入运行所需依赖。
         *
         * @param url 待校验或访问的 URL。
         * @param rule rule 参数。
         */
        private WebsiteRule(String url, String rule) {
            this.url = url;
            this.rule = rule;
        }
    }

    /** 承载URL判定相关状态和辅助逻辑。 */
    public static class UrlVerdict {
        /** 是否启用allowed。 */
        private final boolean allowed;

        /** 记录URL判定中的URL。 */
        private final String url;

        /** 记录URL判定中的消息。 */
        private final String message;

        /** 是否需要人工审批而不是硬阻断。 */
        private final boolean approvalRequired;

        /** 需要审批时使用的策略键。 */
        private final String policyKey;

        /**
         * 创建URL Verdict实例，并注入运行所需依赖。
         *
         * @param allowed allowed开关值。
         * @param url 待校验或访问的 URL。
         * @param message 平台消息或错误消息。
         */
        private UrlVerdict(boolean allowed, String url, String message) {
            this(allowed, url, message, false, "");
        }

        /**
         * 创建URL Verdict实例，并注入审批策略元数据。
         *
         * @param allowed allowed开关值。
         * @param url 待校验或访问的 URL。
         * @param message 平台消息或错误消息。
         * @param approvalRequired 是否需要人工审批。
         * @param policyKey 审批策略键。
         */
        private UrlVerdict(
                boolean allowed,
                String url,
                String message,
                boolean approvalRequired,
                String policyKey) {
            this.allowed = allowed;
            this.url = url;
            this.message = message;
            this.approvalRequired = approvalRequired;
            this.policyKey = StrUtil.nullToEmpty(policyKey);
        }

        /**
         * 执行allow相关逻辑。
         *
         * @return 返回allow结果。
         */
        public static UrlVerdict allow() {
            return new UrlVerdict(true, "", "");
        }

        /**
         * 执行阻断相关逻辑。
         *
         * @param url 待校验或访问的 URL。
         * @param message 平台消息或错误消息。
         * @return 返回block结果。
         */
        public static UrlVerdict block(String url, String message) {
            return new UrlVerdict(false, url, message);
        }

        /**
         * 执行需要审批相关逻辑。
         *
         * @param url 待校验或访问的 URL。
         * @param policyKey 审批策略键。
         * @param message 平台消息或错误消息。
         * @return 返回approval required结果。
         */
        public static UrlVerdict approvalRequired(
                String url, String policyKey, String message) {
            return new UrlVerdict(false, url, message, true, policyKey);
        }

        /**
         * 判断是否Allowed。
         *
         * @return 如果Allowed满足条件则返回 true，否则返回 false。
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 读取URL。
         *
         * @return 返回读取到的URL。
         */
        public String getUrl() {
            return url;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }

        /**
         * 判断是否需要审批。
         *
         * @return 如果需要审批返回 true。
         */
        public boolean isApprovalRequired() {
            return approvalRequired;
        }

        /**
         * 读取审批策略键。
         *
         * @return 返回审批策略键。
         */
        public String getPolicyKey() {
            return policyKey;
        }

        /**
         * 读取绑定具体 URL 目标的一次性审批 token。
         *
         * @return 返回审批 token。
         */
        public String getApprovalToken() {
            return policyApprovalToken(policyKey, url);
        }
    }

    /** 承载文件判定相关状态和辅助逻辑。 */
    public static class FileVerdict {
        /** 是否启用allowed。 */
        private final boolean allowed;

        /** 记录文件判定中的路径。 */
        private final String path;

        /** 记录文件判定中的消息。 */
        private final String message;

        /** 是否需要人工审批而不是硬阻断。 */
        private final boolean approvalRequired;

        /** 需要审批时使用的策略键。 */
        private final String policyKey;

        /**
         * 创建文件Verdict实例，并注入运行所需依赖。
         *
         * @param allowed allowed开关值。
         * @param path 文件或目录路径。
         * @param message 平台消息或错误消息。
         */
        private FileVerdict(boolean allowed, String path, String message) {
            this(allowed, path, message, false, "");
        }

        /**
         * 创建文件Verdict实例，并注入审批策略元数据。
         *
         * @param allowed allowed开关值。
         * @param path 文件或目录路径。
         * @param message 平台消息或错误消息。
         * @param approvalRequired 是否需要人工审批。
         * @param policyKey 审批策略键。
         */
        private FileVerdict(
                boolean allowed,
                String path,
                String message,
                boolean approvalRequired,
                String policyKey) {
            this.allowed = allowed;
            this.path = path;
            this.message = message;
            this.approvalRequired = approvalRequired;
            this.policyKey = StrUtil.nullToEmpty(policyKey);
        }

        /**
         * 执行allow相关逻辑。
         *
         * @return 返回allow结果。
         */
        public static FileVerdict allow() {
            return new FileVerdict(true, "", "");
        }

        /**
         * 执行阻断相关逻辑。
         *
         * @param path 文件或目录路径。
         * @param message 平台消息或错误消息。
         * @return 返回block结果。
         */
        public static FileVerdict block(String path, String message) {
            return new FileVerdict(false, path, message);
        }

        /**
         * 执行需要审批相关逻辑。
         *
         * @param path 文件或目录路径。
         * @param policyKey 审批策略键。
         * @param message 平台消息或错误消息。
         * @return 返回approval required结果。
         */
        public static FileVerdict approvalRequired(
                String path, String policyKey, String message) {
            return new FileVerdict(false, path, message, true, policyKey);
        }

        /**
         * 判断是否Allowed。
         *
         * @return 如果Allowed满足条件则返回 true，否则返回 false。
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 读取路径。
         *
         * @return 返回读取到的路径。
         */
        public String getPath() {
            return path;
        }

        /**
         * 读取消息。
         *
         * @return 返回读取到的消息。
         */
        public String getMessage() {
            return message;
        }

        /**
         * 判断是否需要审批。
         *
         * @return 如果需要审批返回 true。
         */
        public boolean isApprovalRequired() {
            return approvalRequired;
        }

        /**
         * 读取审批策略键。
         *
         * @return 返回审批策略键。
         */
        public String getPolicyKey() {
            return policyKey;
        }

        /**
         * 读取绑定具体路径目标的一次性审批 token。
         *
         * @return 返回审批 token。
         */
        public String getApprovalToken() {
            return policyApprovalToken(policyKey, path);
        }
    }

    /** 承载工具Arg凭据判定相关状态和辅助逻辑。 */
    private static class ToolArgCredentialVerdict {
        /** 是否启用allowed。 */
        private boolean allowed = true;

        /** 记录工具Arg凭据判定中的引用。 */
        private String reference = "";

        /**
         * 执行阻断相关逻辑。
         *
         * @param key 配置键或映射键。
         * @param normalizedKey normalized键标识或键值。
         */
        private void block(String key, String normalizedKey) {
            this.allowed = false;
            String safeKey = canonicalStructuredCredentialKey(normalizedKey);
            if (safeKey.length() == 0) {
                safeKey = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(key)).trim();
            }
            safeKey = safeKey.replaceAll("\\s+", "_");
            safeKey = SecretRedactor.redact(safeKey, 200);
            this.reference =
                    safeKey.length() == 0 ? "tool_arg://credential" : "tool_arg://" + safeKey;
        }

        /**
         * 执行规范Structured凭据键相关逻辑。
         *
         * @param normalizedKey normalized键标识或键值。
         * @return 返回规范Structured凭据键结果。
         */
        private static String canonicalStructuredCredentialKey(String normalizedKey) {
            String key = StrUtil.nullToEmpty(normalizedKey).trim();
            if (key.startsWith("proxy_authorization")) {
                return "Proxy-Authorization";
            }
            if (key.startsWith("authorization")) {
                return "Authorization";
            }
            if (key.startsWith("x_api_key")) {
                return "x-api-key";
            }
            if (key.startsWith("x_api_token")) {
                return "x-api-token";
            }
            if (key.startsWith("x_auth_token")) {
                return "x-auth-token";
            }
            if (key.startsWith("api_key") || key.startsWith("apikey")) {
                return "apiKey";
            }
            if (key.startsWith("access_token")) {
                return "access_token";
            }
            if (key.startsWith("refresh_token")) {
                return "refresh_token";
            }
            if (key.startsWith("id_token")) {
                return "id_token";
            }
            if (key.startsWith("auth_token")) {
                return "auth_token";
            }
            if (key.startsWith("oauth_token")) {
                return "oauth_token";
            }
            if (key.startsWith("bearer_token")) {
                return "bearer_token";
            }
            if (key.startsWith("client_secret")) {
                return "client_secret";
            }
            if (key.startsWith("private_key")) {
                return "private_key";
            }
            if (key.startsWith("secret_key")) {
                return "secret_key";
            }
            if (key.startsWith("session_token")) {
                return "session_token";
            }
            if (key.startsWith("security_token")) {
                return "security_token";
            }
            if (key.startsWith("credentials")) {
                return "credentials";
            }
            if (key.startsWith("credential")) {
                return "credential";
            }
            if (key.startsWith("cookie")) {
                return "cookie";
            }
            if (key.startsWith("secret")) {
                return "secret";
            }
            if (key.startsWith("token")) {
                return "token";
            }
            if ("auth".equals(key)) {
                return "auth";
            }
            return "";
        }
    }

    /** 汇总共享网站访问规则的加载数量、样例和跳过文件数。 */
    private static class SharedWebsiteRuleSummary {
        /** 保存rules集合，维持调用顺序或去重语义。 */
        private final List<String> rules = new ArrayList<String>();

        /** 保存ruleSamples集合，维持调用顺序或去重语义。 */
        private final List<String> ruleSamples = new ArrayList<String>();

        /** 已识别的共享网站访问规则总数。 */
        private int ruleCount;

        /** 成功加载并参与合并的规则文件数量。 */
        private int loadedFileCount;

        /** 因不存在、无效或不在允许目录内而跳过的规则文件数量。 */
        private int skippedFileCount;
    }
}
