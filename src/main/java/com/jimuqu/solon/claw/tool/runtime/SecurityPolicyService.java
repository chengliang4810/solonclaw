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
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlExtractionSupport.cleanUrlToken;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlExtractionSupport.looksLikeUrlKey;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlExtractionSupport.shellLikeTokens;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlExtractionSupport.stripOptionalQuote;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.containsSensitiveParameterName;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.extractSchemelessHost;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.extractUriHost;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.hasSchemelessUserInfo;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.hasSensitiveSchemelessUrlParameterName;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.hasSensitiveUrlParameterName;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.hasUserInfo;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.isStrongSensitiveUrlParameterName;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.normalizeHost;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.normalizeRule;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.normalizeSensitiveParameterName;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.normalizeUrlText;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.parseUri;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WORKDIR_SAFE_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_EXACT_PATHS;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_HOME_FILE_NAMES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_PREFIXES;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.WRITE_DENIED_WINDOWS_PREFIXES;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import com.jimuqu.solon.claw.support.FilePathSupport;
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
        SecurityWebsiteRule websiteRule = checkWebsitePolicy(raw, host);
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
    UrlVerdict checkStaticHostPolicy(String raw, String host) {
        UrlVerdict alwaysBlockedHost = checkAlwaysBlockedHost(raw, host);
        if (!alwaysBlockedHost.allowed) {
            return alwaysBlockedHost;
        }

        SecurityWebsiteRule websiteRule = checkWebsitePolicy(raw, host);
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
            log.debug(
                    "Address literal parsing failed; treating host as a regular name: {}",
                    ErrorTextSupport.summaryWithType(e));
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
        SharedSecurityWebsiteRuleSummary shared = sharedSecurityWebsiteRuleSummary();
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
        SharedSecurityWebsiteRuleSummary shared = sharedSecurityWebsiteRuleSummary();
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
            return !FilePathSupport.isUnderPath(
                    target.getCanonicalFile(), workspace.getCanonicalFile());
        } catch (Exception e) {
            log.debug(
                    "Workspace boundary resolution failed; treating path as outside workspace: {}",
                    ErrorTextSupport.summaryWithType(e));
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
            log.debug(
                    "Jar base directory resolution failed; falling back to process directory: {}",
                    ErrorTextSupport.summaryWithType(e));
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
        return urlExtractionSupport().extractUrlishValues(args);
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
     * 创建 URL 候选提取辅助对象。
     *
     * @return 返回绑定当前策略服务的 URL 提取辅助对象。
     */
    private SecurityUrlExtractionSupport urlExtractionSupport() {
        return new SecurityUrlExtractionSupport(this);
    }

    /**
     * 从普通文本中提取 URL 候选。
     *
     * @param raw 原始文本。
     * @param urls URL 候选收集结果。
     */
    private void extractUrlishFromText(Object raw, List<String> urls) {
        urlExtractionSupport().extractUrlishFromText(raw, urls);
    }

    /**
     * 从命令文本中提取 URL 候选。
     *
     * @param raw 原始命令文本。
     * @param urls URL 候选收集结果。
     */
    private void extractCommandUrlishFromText(Object raw, List<String> urls) {
        urlExtractionSupport().extractCommandUrlishFromText(raw, urls);
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
     * 判断裸主机文本是否需要进入 URL 安全检查。
     *
     * @param host 主机名或地址字面量。
     * @return 需要按 URL 安全策略处理时返回 true。
     */
    boolean shouldCheckBareHost(String host) {
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
     * 提取工具参数中的文件路径候选。
     *
     * @param toolName 工具名称。
     * @param args 工具或命令参数。
     * @return 返回路径候选列表。
     */
    private List<String> extractPaths(String toolName, Map<String, Object> args) {
        return SecurityPathExtractionSupport.create().extractPaths(toolName, args);
    }


    /**
     * 判断工具名称是否代表文件写入类操作。
     *
     * @param toolName 工具名称。
     * @return 写入类工具返回 true。
     */
    private boolean isWriteLikeTool(String toolName) {
        return SecurityPathExtractionSupport.isWriteLikeTool(toolName);
    }

    /**
     * 判断工具参数中是否表达写入意图。
     *
     * @param raw 工具或命令参数。
     * @return 存在写入意图时返回 true。
     */
    private boolean hasWriteIntent(Object raw) {
        return SecurityPathExtractionSupport.hasWriteIntent(raw);
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
                log.debug(
                        "Path text decoding failed; returning original normalized path text: {}",
                        ErrorTextSupport.summaryWithType(e));
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
            log.debug(
                    "Runtime relative path normalization failed; returning empty path: {}",
                    ErrorTextSupport.summaryWithType(e));
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
                        ErrorTextSupport.summaryWithType(fallbackError));
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
            log.debug(
                    "Runtime prefix stripping failed; keeping normalized path: {}",
                    ErrorTextSupport.summaryWithType(e));
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
            log.debug(
                    "Runtime path normalization failed; returning empty path: {}",
                    ErrorTextSupport.summaryWithType(e));
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
    SecurityWebsiteRule checkWebsitePolicy(String rawUrl, String host) {
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
                return new SecurityWebsiteRule(rawUrl, rule);
            }
        }
        for (String rawRule : sharedSecurityWebsiteRules()) {
            String rule = normalizeRule(rawRule);
            if (StrUtil.isBlank(rule)) {
                continue;
            }
            if (matchHost(host, rule)) {
                return new SecurityWebsiteRule(rawUrl, rule);
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
    private List<String> sharedSecurityWebsiteRules() {
        return sharedSecurityWebsiteRuleSummary().rules;
    }

    /**
     * 执行shared网站Rule摘要相关逻辑。
     *
     * @return 返回shared Website Rule Summary结果。
     */
    private SharedSecurityWebsiteRuleSummary sharedSecurityWebsiteRuleSummary() {
        SharedSecurityWebsiteRuleSummary summary = new SharedSecurityWebsiteRuleSummary();
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
                log.debug(
                        "Shared rule file loading failed; skipping unreadable rule file: {}",
                        ErrorTextSupport.summaryWithType(e));
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
            if (FilePathSupport.isUnderPath(file, home)) {
                return checkPath(file.getAbsolutePath(), false).isAllowed() ? file : null;
            }
            return null;
        } catch (Exception e) {
            log.debug(
                    "Shared file resolution failed; rejecting shared file path: {}",
                    ErrorTextSupport.summaryWithType(e));
            return null;
        }
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


}
