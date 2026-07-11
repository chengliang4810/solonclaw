package com.jimuqu.solon.claw.tool.runtime;

import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.BARE_HOST_FETCH_CONTEXT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.BARE_HOST_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.IPV4_CIDR_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.IPV6_CIDR_TOKEN_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.JAVA_PROXY_OPTIONS_ASSIGNMENT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_PROXY_ENV_ASSIGNMENT_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_REGISTRY_PROXY_PROPERTY_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.POWERSHELL_REGISTRY_PROXY_VALUE_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityPolicyRuleCatalog.URLISH_PATTERN;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.extractSchemelessHost;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.hasSchemelessUserInfo;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.normalizeHost;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.normalizeUrlText;
import static com.jimuqu.solon.claw.tool.runtime.SecurityUrlTextSupport.parseUri;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提取工具参数和命令文本中的 URL 候选，具体放行或阻断仍由安全策略服务判定。 */
final class SecurityUrlExtractionSupport {
    /** 复用主安全服务中的主机策略和站点策略，避免复制策略判断。 */
    private final SecurityPolicyService policyService;

    /**
     * 创建 URL 提取辅助对象。
     *
     * @param policyService 当前安全策略服务。
     */
    SecurityUrlExtractionSupport(SecurityPolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * 提取工具参数中的 URL 候选。
     *
     * @param args 工具或命令参数。
     * @return 返回提取出的 URL 候选列表。
     */
    List<String> extractUrlishValues(Object args) {
        List<String> urls = new ArrayList<String>();
        if (args == null) {
            return urls;
        }
        collectUrls(args, urls);
        return urls;
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
    static boolean looksLikeUrlKey(String key) {
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
    private static boolean looksLikeHostTargetKey(String normalized) {
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
    void extractUrlishFromText(Object raw, List<String> urls) {
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
    void extractCommandUrlishFromText(Object raw, List<String> urls) {
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
        return !policyService.checkStaticHostPolicy(value, host).isAllowed();
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
            if (SecurityAddressPolicySupport.isAlwaysBlockedIp(host)
                    || !policyService.checkStaticHostPolicy(value, host).isAllowed()) {
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
    static String stripOptionalQuote(String raw) {
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
                    && !policyService.shouldCheckBareHost(hostWithCredential)) {
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
        if (policyService.shouldCheckBareHost(host)) {
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
    static List<String> shellLikeTokens(String text, int maxTokens) {
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
            if (fetchContext || policyService.shouldCheckBareHost(host)) {
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
            if (StrUtil.isBlank(host) || !policyService.shouldCheckBareHost(host)) {
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
            int[] octets =
                    SecurityAddressPolicySupport.parseObfuscatedIpv4(normalizeHost(hostPort));
            if (octets != null
                    && SecurityAddressPolicySupport.isBlockedOrAlwaysBlockedIpv4(octets)) {
                urls.add(value);
            }
        }
    }

    /**
     * 清理URLtoken。
     *
     * @param raw 原始输入值。
     * @return 返回clean URL token结果。
     */
    static String cleanUrlToken(String raw) {
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
    private static boolean startsWithUrlWrapper(String value) {
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
    private static boolean isBracketedIpv6Literal(String value) {
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
    private static boolean containsBracketedIpv6Literal(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (!text.startsWith("[") || !text.contains("]")) {
            return false;
        }
        String host = text.substring(1, text.indexOf(']'));
        return host.indexOf(':') >= 0;
    }
}
