package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.math.BigInteger;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Jimuqu URL and website access guardrails. */
public class SecurityPolicyService {
    private static final String[] ALWAYS_BLOCKED_HOSTS =
            new String[] {"metadata.google.internal", "metadata.goog"};
    private static final String[] ALWAYS_BLOCKED_IPS =
            new String[] {
                "169.254.169.254",
                "169.254.170.2",
                "169.254.169.253",
                "100.100.100.200",
                "fd00:ec2::254",
                "fd00:ec2:0:0:0:0:0:254"
            };
    private static final String[] TRUSTED_PRIVATE_IP_HOSTS =
            new String[] {"multimedia.nt.qq.com.cn"};
    private static final List<String> CREDENTIAL_DIR_SEGMENTS =
            Arrays.asList(
                    ".ssh",
                    ".aws",
                    ".gnupg",
                    ".kube",
                    ".docker",
                    ".azure",
                    ".claude",
                    ".codex",
                    ".qwen",
                    ".config/gh",
                    ".config/gcloud");
    private static final List<String> CREDENTIAL_FILE_NAMES =
            Arrays.asList(
                    ".env",
                    ".envrc",
                    ".env.local",
                    ".env.production",
                    ".env.development",
                    ".netrc",
                    ".git-credentials",
                    ".pgpass",
                    ".npmrc",
                    ".pypirc",
                    "credentials",
                    "credentials.json",
                    ".credentials.json",
                    "auth.json",
                    ".anthropic_oauth.json",
                    "oauth_creds.json",
                    "client_secret.json",
                    "client_secrets.json",
                    "application_default_credentials.json",
                    "service_account.json",
                    "service-account.json",
                    "service_account_key.json",
                    "service-account-key.json",
                    "google-credentials.json",
                    "token.json",
                    "authorized_keys",
                    "hosts.yml",
                    "kubeconfig",
                    "id_dsa",
                    "id_ecdsa",
                    "id_ecdsa_sk",
                    "id_rsa",
                    "id_ed25519_sk",
                    "id_ed25519",
                    "known_hosts");
    private static final List<String> SENSITIVE_KEY_FILE_EXTENSIONS =
            Arrays.asList(".pem", ".key", ".p12", ".pfx");
    private static final List<String> SENSITIVE_KEY_FILE_MARKERS =
            Arrays.asList(
                    "private",
                    "secret",
                    "credential",
                    "credentials",
                    "token",
                    "oauth",
                    "service-account",
                    "service_account",
                    "api-key",
                    "apikey",
                    "id_");
    private static final List<String> CREDENTIAL_PATH_SUFFIXES =
            Arrays.asList(
                    ".claude/.credentials.json",
                    ".codex/auth.json",
                    ".qwen/oauth_creds.json",
                    ".config/gcloud/application_default_credentials.json");
    private static final List<String> WRITE_DENIED_EXACT_PATHS =
            Arrays.asList(
                    "/etc/sudoers",
                    "/etc/passwd",
                    "/etc/shadow",
                    "/var/run/docker.sock",
                    "/run/docker.sock");
    private static final List<String> WRITE_DENIED_HOME_FILE_NAMES =
            Arrays.asList(
                    ".bashrc",
                    ".zshrc",
                    ".profile",
                    ".bash_profile",
                    ".zprofile");
    private static final List<String> WRITE_DENIED_PREFIXES =
            Arrays.asList(
                    "/boot/",
                    "/usr/lib/systemd/",
                    "/private/etc/",
                    "/private/var/",
                    "/etc/sudoers.d/",
                    "/etc/systemd/");
    private static final List<String> BLOCKED_DEVICE_PATHS =
            Arrays.asList(
                    "/dev/zero",
                    "/dev/random",
                    "/dev/urandom",
                    "/dev/full",
                    "/dev/stdin",
                    "/dev/tty",
                    "/dev/console",
                    "/dev/stdout",
                    "/dev/stderr",
                    "/dev/fd/0",
                    "/dev/fd/1",
                    "/dev/fd/2");
    private static final Pattern SHELL_PATH_PATTERN =
            Pattern.compile(
                    "(~?[/\\\\][^\\s'\"`|;&<>]+|\\$HOME[/\\\\][^\\s'\"`|;&<>]+|\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}[/\\\\][^\\s'\"`|;&<>]+|\\$env:[A-Za-z_][A-Za-z0-9_]*[/\\\\][^\\s'\"`|;&<>]+|%[A-Za-z_][A-Za-z0-9_]*%[/\\\\][^\\s'\"`|;&<>]+|[A-Za-z]:[/\\\\][^\\s'\"`|;&<>]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_RELATIVE_CREDENTIAL_PATH_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:\\.\\.?[/\\\\])?(?:(?:[^\\s'\"`|;&<>/\\\\]+)[/\\\\])*(?:\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.docker|\\.azure|\\.claude|\\.codex|\\.qwen|\\.config[/\\\\](?:gh|gcloud))[/\\\\][^\\s'\"`|;&<>]+)(?![A-Za-z0-9_./\\\\-])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_CREDENTIAL_TOKEN_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:(?:\\.{1,2}|~|\\$[A-Za-z_][A-Za-z0-9_]*|\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%)[/\\\\])*(?:(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?)|(?:\\.envrc)|(?:credentials(?:\\.json)?)|(?:auth\\.json)|(?:\\.netrc)|(?:\\.git-credentials)|(?:\\.pgpass)|(?:\\.npmrc)|(?:\\.pypirc)|(?:\\.credentials\\.json)|(?:\\.anthropic_oauth\\.json)|(?:oauth_creds\\.json)|(?:client_secrets?\\.json)|(?:application_default_credentials\\.json)|(?:service[_-]account(?:[_-]key)?\\.json)|(?:google-credentials\\.json)|(?:firebase-adminsdk[A-Za-z0-9_.-]*\\.json)|(?:token\\.json)|(?:authorized_keys)|(?:hosts\\.yml)|(?:kubeconfig)|(?:id_(?:dsa|ecdsa(?:_sk)?|rsa|ed25519(?:_sk)?))|(?:(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx))))(?![A-Za-z0-9_./\\\\-])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKDIR_SAFE_PATTERN =
            Pattern.compile("^[A-Za-z0-9/\\\\:_\\-.~ +@=,]+$");
    private static final Pattern PROC_STDIO_FD_PATTERN =
            Pattern.compile("^/proc/(?:self|\\d+)/fd/[0-2]$");
    private static final Pattern RAW_BLOCK_DEVICE_PATTERN =
            Pattern.compile("^/dev/(?:sd|hd|vd|xvd)[a-z][a-z0-9]*$|^/dev/nvme\\d+n\\d+(?:p\\d+)?$|^/dev/mmcblk\\d+(?:p\\d+)?$");
    private static final Pattern URLISH_PATTERN =
            Pattern.compile(
                    "(?iu)((?:https?|wss?)://[^\\s)>'\"]+|(?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,}(?::\\d+)?/[^\\s)>'\"]*|localhost(?::\\d+)?/[^\\s)>'\"]*|(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?/[^\\s)>'\"]*|\\[[0-9a-f:.%]+\\](?::\\d+)?/[^\\s)>'\"]*)");
    private static final Pattern BARE_HOST_TOKEN_PATTERN =
            Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}_./:-])((?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}\\p{N}-]+|localhost|(?:0x[0-9a-f]+)|(?:0[0-7]+(?:\\.0[0-7]+){3})|(?:\\d{1,10})(?:\\.\\d{1,3}){0,3}|\\[[0-9a-f:.%]+\\])(?::\\d{1,5})?(?![\\p{L}\\p{N}_./:-])");
    private static final Pattern BARE_HOST_FETCH_CONTEXT_PATTERN =
            Pattern.compile(
                    "(?iu)(?:^|[^\\p{L}\\p{N}_./:-])(?:curl|wget|aria2c|httpie|http|xh|nc|netcat|ncat|telnet|socat|openssl\\s+s_client|fetch|axios|httpx|requests\\.(?:get|post|put|delete|patch|head|request)|urllib\\.request\\.urlopen|urlopen|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|Start-BitsTransfer|bitsadmin|certutil|mshta|regsvr32|rundll32|WebClient|WebRequest|HttpWebRequest|RestTemplate|OkHttpClient|HttpURLConnection)\\b");
    private static final Pattern DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN =
            Pattern.compile("(?iu)^(tcp|tcp4|tcp6|udp|udp4|udp6|ssl|tls|connect):(.+)$");
    private static final List<String> SENSITIVE_URL_PARAMETER_NAMES =
            Arrays.asList(
                    "access_token",
                    "refresh_token",
                    "id_token",
                    "auth_token",
                    "client_secret",
                    "api_key",
                    "apikey",
                    "password",
                    "private_key",
                    "secret",
                    "jwt",
                    "signature",
                    "x-amz-signature");

    private final AppConfig appConfig;

    public SecurityPolicyService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public UrlVerdict checkUrl(String url) {
        return checkUrl(url, null);
    }

    public UrlVerdict checkUrlAllowingPrivate(String url) {
        return checkUrl(url, Boolean.TRUE);
    }

    public boolean isAlwaysBlockedUrl(String url) {
        return !checkAlwaysBlockedUrl(url).isAllowed();
    }

    public UrlVerdict checkAlwaysBlockedUrl(String url) {
        String raw = normalizeUrlText(url);
        if (raw.length() == 0 || !raw.contains("://")) {
            return UrlVerdict.allow();
        }
        URI uri = parseUri(raw);
        if (uri == null) {
            return UrlVerdict.allow();
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
                if (isAlwaysBlockedIp(ip) || isAlwaysBlockedAddress(address)) {
                    return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
                }
            }
        } catch (Exception ignored) {
            return UrlVerdict.allow();
        }
        return UrlVerdict.allow();
    }

    private UrlVerdict checkUrl(String url, Boolean allowPrivateOverride) {
        String raw = normalizeUrlText(url);
        if (raw.length() == 0) {
            return UrlVerdict.block(raw, "URL 缺少内容");
        }
        if (SecretRedactor.containsSecretLikeToken(raw)) {
            return UrlVerdict.block(raw, "URL 包含疑似 API key 或 token，禁止通过 URL 发送凭据");
        }

        if (raw.startsWith("//")) {
            return checkUrl("http:" + raw, allowPrivateOverride);
        }
        if (!raw.contains("://")) {
            if (hasSchemelessUserInfo(raw)) {
                return UrlVerdict.block(raw, "URL 包含 userinfo 凭据，禁止通过 URL 发送用户名或密码");
            }
            String schemelessHost = extractSchemelessHost(raw);
            if (StrUtil.isBlank(schemelessHost)) {
                return UrlVerdict.allow();
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
        if (hasSensitiveUrlParameterName(uri)) {
            return UrlVerdict.block(raw, "URL 包含敏感凭据参数，禁止通过 URL 发送凭据");
        }

        String host = extractUriHost(uri);
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.block(raw, "URL 缺少主机名");
        }

        return checkHostAccess(raw, scheme, host, allowPrivateOverride);
    }

    private UrlVerdict checkSchemelessHostAccess(
            String raw, String host, Boolean allowPrivateOverride) {
        for (String blocked : ALWAYS_BLOCKED_HOSTS) {
            if (blocked.equals(host)) {
                return UrlVerdict.block(raw, "阻断云元数据/内部主机：" + host);
            }
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
        if (isLocalOrAddressLiteral(host)) {
            return checkHostAccess(raw, "", host, allowPrivateOverride);
        }
        return UrlVerdict.allow();
    }

    private UrlVerdict checkHostAccess(
            String raw, String scheme, String host, Boolean allowPrivateOverride) {
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

        boolean allowPrivate =
                allowPrivateOverride == null
                        ? resolveAllowPrivateUrls()
                        : allowPrivateOverride.booleanValue();
        boolean trustedPrivateHost =
                ("https".equals(scheme) || "wss".equals(scheme))
                        && contains(TRUSTED_PRIVATE_IP_HOSTS, host);
        int[] hostIpv4 = parseObfuscatedIpv4(host);
        if (hostIpv4 != null) {
            String ip = formatIpv4(hostIpv4);
            if (isAlwaysBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
                return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
            }
            if (!allowPrivate && isBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
                return UrlVerdict.block(raw, "阻断内网/私有地址：" + host + " -> " + ip);
            }
        }

        try {
            InetAddress[] addresses = resolveHost(host);
            for (InetAddress address : addresses) {
                String ip = address.getHostAddress();
                if (isAlwaysBlockedIp(ip) || isAlwaysBlockedAddress(address)) {
                    return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
                }
                if (!allowPrivate
                        && isPrivateOrInternal(address, ip)
                        && !(trustedPrivateHost && isTrustedPrivateAddress(address))) {
                    return UrlVerdict.block(raw, "阻断内网/私有地址：" + host + " -> " + ip);
                }
            }
        } catch (Exception e) {
            return UrlVerdict.block(raw, "DNS 解析失败或 URL 安全检查失败：" + host);
        }

        return UrlVerdict.allow();
    }

    private UrlVerdict checkAlwaysBlockedHost(String raw, String host) {
        for (String blocked : ALWAYS_BLOCKED_HOSTS) {
            if (blocked.equals(host)) {
                return UrlVerdict.block(raw, "阻断云元数据/内部主机：" + host);
            }
        }
        if (isAlwaysBlockedIp(host)) {
            return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host);
        }
        InetAddress literalAddress = parseAddressLiteral(host);
        if (literalAddress != null && isAlwaysBlockedAddress(literalAddress)) {
            return UrlVerdict.block(
                    raw,
                    "阻断云元数据/链路本地地址："
                            + host
                            + " -> "
                            + literalAddress.getHostAddress());
        }
        int[] hostIpv4 = parseObfuscatedIpv4(host);
        if (hostIpv4 != null
                && isAlwaysBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
            return UrlVerdict.block(
                    raw, "阻断云元数据/链路本地地址：" + host + " -> " + formatIpv4(hostIpv4));
        }
        return UrlVerdict.allow();
    }

    private InetAddress parseAddressLiteral(String host) {
        String value = StrUtil.nullToEmpty(host).trim();
        if (value.indexOf(':') < 0) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    public UrlVerdict checkToolArgs(String toolName, java.util.Map<String, Object> args) {
        List<String> urls = extractUrls(toolName, args);
        for (String url : urls) {
            UrlVerdict verdict = checkUrl(url);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return UrlVerdict.allow();
    }

    protected InetAddress[] resolveHost(String host) throws Exception {
        return InetAddress.getAllByName(host);
    }

    protected String readEnvironment(String name) {
        return System.getenv(name);
    }

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

    public Map<String, Object> credentialPolicySummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
        summary.put("directorySegmentCount", Integer.valueOf(CREDENTIAL_DIR_SEGMENTS.size()));
        summary.put("fileNameCount", Integer.valueOf(CREDENTIAL_FILE_NAMES.size()));
        summary.put("pathSuffixCount", Integer.valueOf(CREDENTIAL_PATH_SUFFIXES.size()));
        summary.put("keyFileExtensionCount", Integer.valueOf(SENSITIVE_KEY_FILE_EXTENSIONS.size()));
        summary.put("keyFileMarkerCount", Integer.valueOf(SENSITIVE_KEY_FILE_MARKERS.size()));
        summary.put("configuredCredentialFileCount", Integer.valueOf(configuredCredentialFiles().size()));
        summary.put("directorySegmentSamples", sample(CREDENTIAL_DIR_SEGMENTS, 6));
        summary.put("fileNameSamples", sample(CREDENTIAL_FILE_NAMES, 8));
        summary.put("pathSuffixSamples", sample(CREDENTIAL_PATH_SUFFIXES, 4));
        summary.put("configuredCredentialFileSamples", redactSample(configuredCredentialFiles(), 6));
        summary.put("envExampleFilesAllowed", Boolean.TRUE);
        summary.put("description", "Credential paths are blocked for file tools, patch targets, command reads, archives, uploads, and compact output paths.");
        return summary;
    }

    public FileVerdict checkCommandPaths(String command) {
        String code = StrUtil.nullToEmpty(command);
        if (code.length() == 0) {
            return FileVerdict.allow();
        }
        FileVerdict compactOutputVerdict = checkCompactOutputOptionCredentialPaths(code);
        if (!compactOutputVerdict.allowed) {
            return compactOutputVerdict;
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
            FileVerdict verdict = checkPath(credentialMatcher.group(1), false);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        Matcher matcher = SHELL_PATH_PATTERN.matcher(code);
        while (matcher.find()) {
            FileVerdict verdict = checkPath(matcher.group(1), true);
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

    private FileVerdict checkCompactOutputOptionCredentialPaths(String command) {
        List<String> tokens = shellLikeTokens(command, 200);
        for (String token : tokens) {
            String path = compactOutputOptionPath(token);
            if (StrUtil.isBlank(path)) {
                continue;
            }
            FileVerdict verdict = checkPath(path, false);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return FileVerdict.allow();
    }

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
        return "";
    }

    public UrlVerdict checkCommandUrls(String command) {
        List<String> urls = new ArrayList<String>();
        extractUrlishFromText(command, urls);
        for (String url : urls) {
            UrlVerdict verdict = checkUrl(cleanUrlToken(url));
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return UrlVerdict.allow();
    }

    public UrlVerdict checkCommandAlwaysBlockedUrls(String command) {
        List<String> urls = new ArrayList<String>();
        extractUrlishFromText(command, urls);
        for (String url : urls) {
            String value = cleanUrlToken(url);
            UrlVerdict verdict =
                    value.contains("://")
                            ? checkAlwaysBlockedUrl(value)
                            : checkAlwaysBlockedSchemelessUrl(value);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return UrlVerdict.allow();
    }

    public FileVerdict checkPath(String rawPath, boolean writeLike) {
        String path = StrUtil.nullToEmpty(rawPath).trim();
        if (path.length() == 0) {
            return FileVerdict.allow();
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
        if (!writeLike && matchesBlockedInternalReadPath(normalized)) {
            return FileVerdict.block(path, "读取 Skills Hub 内部缓存文件被阻断，请使用 skills_list 或 skill_view 工具");
        }
        if (matchesCredentialPath(normalized)) {
            return FileVerdict.block(
                    path,
                    writeLike
                            ? "写入敏感系统/凭据文件被阻断"
                            : "读取敏感系统/凭据文件被阻断");
        }
        if (writeLike && matchesWriteDeniedPath(normalized)) {
            return FileVerdict.block(path, "写入敏感系统文件被阻断");
        }
        if (writeLike && isOutsideSafeWriteRoot(path)) {
            return FileVerdict.block(path, "写入路径超出安全写入根被阻断");
        }
        return FileVerdict.allow();
    }

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

    public List<String> extractUrlishValues(Object args) {
        List<String> urls = new ArrayList<String>();
        if (args == null) {
            return urls;
        }
        collectUrls(args, urls);
        return urls;
    }

    private List<String> extractUrls(String toolName, java.util.Map<String, Object> args) {
        return extractUrlishValues(args);
    }

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
        urls.add(value.contains("://") ? value : "http://" + value);
    }

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

    private void extractUrlishFromText(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        String text = normalizeUrlText(String.valueOf(raw));
        extractCurlConnectionOverrideHosts(text, urls);
        extractProxyHosts(text, urls);
        extractProtocolRelativeUrlish(text, urls);
        extractSchemelessUserInfoUrlish(text, urls);
        java.util.regex.Matcher matcher = URLISH_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        extractBareSecurityRelevantHosts(text, urls);
        extractObfuscatedSchemelessUrlish(text, urls);
    }

    private void extractProxyHosts(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String value = null;
            if ("--proxy".equals(token)
                    || "-x".equals(token)
                    || "--socks5".equals(token)
                    || "--socks5-hostname".equals(token)) {
                if (i + 1 < tokens.size()) {
                    value = tokens.get(++i);
                }
            } else if (token.startsWith("--proxy=")) {
                value = token.substring("--proxy=".length());
            } else if (token.startsWith("--socks5=")) {
                value = token.substring("--socks5=".length());
            } else if (token.startsWith("--socks5-hostname=")) {
                value = token.substring("--socks5-hostname=".length());
            } else if (token.startsWith("-x") && token.length() > 2) {
                value = token.substring(2);
            } else if (isProxyEnvironmentAssignment(token)) {
                value = token.substring(token.indexOf('=') + 1);
            }
            addProxyHost(value, urls);
        }
    }

    private boolean isProxyEnvironmentAssignment(String token) {
        if (StrUtil.isBlank(token)) {
            return false;
        }
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals).toLowerCase(Locale.ROOT);
        return "http_proxy".equals(name)
                || "https_proxy".equals(name)
                || "all_proxy".equals(name);
    }

    private void addProxyHost(String raw, List<String> urls) {
        String value = cleanUrlToken(raw);
        if (StrUtil.isBlank(value)) {
            return;
        }
        int at = value.lastIndexOf('@');
        if (at >= 0 && at + 1 < value.length()) {
            value = value.substring(at + 1);
        }
        String host = value.contains("://") ? extractUrlishHost(value) : extractSchemelessHost(value);
        if (StrUtil.isBlank(host)) {
            return;
        }
        if (shouldCheckBareHost(host)) {
            urls.add(value.contains("://") ? host : value);
        }
    }

    private String extractUrlishHost(String raw) {
        URI uri = parseUri(cleanUrlToken(raw));
        if (uri == null) {
            return "";
        }
        return normalizeHost(uri.getHost());
    }

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

    private void extractProtocolRelativeUrlish(String text, List<String> urls) {
        List<String> tokens = shellLikeTokens(text, 200);
        for (String token : tokens) {
            String value = cleanUrlToken(token);
            if (value.startsWith("//") && value.length() > 2) {
                urls.add(value);
            }
        }
    }

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

    private void extractBareSecurityRelevantHosts(String text, List<String> urls) {
        if (!hasBareHostFetchContext(text)) {
            return;
        }
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
            if (shouldCheckBareHost(host)) {
                urls.add(value);
            }
        }
    }

    private boolean hasBareHostFetchContext(String text) {
        return BARE_HOST_FETCH_CONTEXT_PATTERN.matcher(StrUtil.nullToEmpty(text)).find();
    }

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

    private String extractDirectNetworkEndpointHost(String raw) {
        String value = cleanUrlToken(raw);
        Matcher matcher = DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN.matcher(value);
        if (!matcher.find()) {
            return "";
        }
        return extractHostFromDirectEndpoint(matcher.group(2));
    }

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

    private boolean shouldCheckBareHost(String host) {
        for (String blocked : ALWAYS_BLOCKED_HOSTS) {
            if (blocked.equals(host)) {
                return true;
            }
        }
        int[] obfuscatedIpv4 = parseObfuscatedIpv4(host);
        if (obfuscatedIpv4 != null) {
            return isBlockedOrAlwaysBlockedIpv4(obfuscatedIpv4);
        }
        if (isLocalOrAddressLiteral(host)) {
            return true;
        }
        return checkWebsitePolicy(host, host) != null;
    }

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
            int[] octets = parseObfuscatedIpv4(normalizeHost(hostPort));
            if (octets != null && isBlockedOrAlwaysBlockedIpv4(octets)) {
                urls.add(value);
            }
        }
    }

    private UrlVerdict checkAlwaysBlockedSchemelessUrl(String raw) {
        String host = extractSchemelessHost(raw);
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.allow();
        }
        return checkAlwaysBlockedHost(raw, host);
    }

    private String cleanUrlToken(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        while (value.startsWith("(") || value.startsWith("{")) {
            value = value.substring(1).trim();
        }
        while (value.endsWith(",")
                || value.endsWith(".")
                || value.endsWith(";")
                || value.endsWith(":")
                || (value.endsWith("]") && !isBracketedIpv6Literal(value))
                || value.endsWith("}")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

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

    private boolean looksLikePatchTextKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "patch".equals(normalized)
                || "diff".equals(normalized)
                || "content".equals(normalized)
                || "input".equals(normalized);
    }

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
                || "dirname".equals(normalized)
                || "dirnames".equals(normalized)
                || "directory".equals(normalized)
                || "directories".equals(normalized)
                || normalized.endsWith("_path")
                || normalized.endsWith("_paths")
                || normalized.endsWith("path");
    }

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

    private boolean isWriteLikeTool(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).trim().toLowerCase(Locale.ROOT);
        return "file_write".equals(normalized)
                || "file_delete".equals(normalized)
                || "write_file".equals(normalized)
                || "delete_file".equals(normalized)
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

    private boolean looksLikeToolNameKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "tool".equals(normalized)
                || "name".equals(normalized)
                || "tool_name".equals(normalized)
                || "toolname".equals(normalized);
    }

    private boolean looksLikeActionKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).trim().toLowerCase(Locale.ROOT);
        return "action".equals(normalized)
                || "operation".equals(normalized)
                || "op".equals(normalized)
                || "mode".equals(normalized)
                || "method".equals(normalized);
    }

    private boolean isPatchIntentValue(Object raw) {
        String value = StrUtil.nullToEmpty(String.valueOf(raw)).trim().toLowerCase(Locale.ROOT);
        return "patch".equals(value)
                || "apply_patch".equals(value)
                || "apply-patch".equals(value)
                || "patch_apply".equals(value)
                || "patch-apply".equals(value)
                || "diff_apply".equals(value)
                || "diff-apply".equals(value)
                || "apply_diff".equals(value)
                || "apply-diff".equals(value);
    }

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
                || "apply_patch".equals(value)
                || "install".equals(value)
                || "update".equals(value)
                || "save".equals(value);
    }

    private String normalizePathText(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        value = value.replace('\\', '/');
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        return value.toLowerCase(Locale.ROOT);
    }

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

    private boolean containsTraversal(String normalized) {
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../")
                || normalized.contains("%2e%2e")
                || normalized.contains("..%2f")
                || normalized.contains("..%5c");
    }

    private boolean matchesBlockedDevicePath(String normalized) {
        if (BLOCKED_DEVICE_PATHS.contains(normalized)) {
            return true;
        }
        return PROC_STDIO_FD_PATTERN.matcher(normalized).matches();
    }

    private boolean matchesRawBlockDevicePath(String normalized) {
        return RAW_BLOCK_DEVICE_PATTERN.matcher(normalized).matches();
    }

    private boolean matchesBlockedInternalReadPath(String normalized) {
        String path = stripKnownPrefix(normalized);
        String hub = RuntimePathConstants.SKILLS_DIR_NAME + "/.hub";
        if (path.equals(hub) || path.startsWith(hub + "/")) {
            return true;
        }
        String runtimeSkillsHub = normalizeRuntimePath(RuntimePathConstants.SKILLS_DIR_NAME, ".hub");
        return runtimeSkillsHub.length() > 0
                && (normalized.equals(runtimeSkillsHub)
                        || normalized.startsWith(runtimeSkillsHub + "/"));
    }

    private boolean matchesCredentialPath(String normalized) {
        String path = stripKnownPrefix(normalized);
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
            if (path.startsWith(normalizedSegment + "/") || path.endsWith("/" + normalizedSegment)) {
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

    private boolean matchesSensitiveHomeFile(String strippedPath, String normalized) {
        if (!WRITE_DENIED_HOME_FILE_NAMES.contains(lastPathPart(strippedPath))) {
            return false;
        }
        return startsWithHomeLikePrefix(normalized) || startsWithUserHome(normalized);
    }

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

    private boolean matchesCloudCredentialJsonFileName(String fileName) {
        return fileName.startsWith("firebase-adminsdk") && fileName.endsWith(".json");
    }

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
            if (StrUtil.isNotBlank(runtimePath) && containsPathToken(normalizedCommand, runtimePath)) {
                return FileVerdict.block(configured, "读取敏感系统/凭据文件被阻断");
            }
        }
        return FileVerdict.allow();
    }

    private boolean containsPathToken(String normalizedText, String normalizedPath) {
        if (StrUtil.isBlank(normalizedText) || StrUtil.isBlank(normalizedPath)) {
            return false;
        }
        String[] candidates =
                new String[] {
                    normalizedPath, "./" + normalizedPath, "../" + normalizedPath
                };
        for (String candidate : candidates) {
            if (containsExactPathToken(normalizedText, candidate)) {
                return true;
            }
        }
        return false;
    }

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
                || ch == ',';
    }

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
        return normalized.endsWith("/" + configuredPath) || strippedPath.endsWith("/" + configuredPath);
    }

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

    private List<String> configuredCredentialFiles() {
        if (appConfig == null || appConfig.getTerminal() == null) {
            return Collections.emptyList();
        }
        List<String> values = appConfig.getTerminal().getCredentialFiles();
        return values == null ? Collections.<String>emptyList() : values;
    }

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

    private String normalizeRuntimeFilePath(String relativePath) {
        if (StrUtil.isBlank(relativePath) || isAbsolutePathText(relativePath)) {
            return "";
        }
        try {
            if (appConfig == null || appConfig.getRuntime() == null) {
                return "";
            }
            Path path = Paths.get(appConfig.getRuntime().getHome(), relativePath)
                    .toAbsolutePath()
                    .normalize();
            return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean matchesWriteDeniedPath(String normalized) {
        String path = stripKnownPrefix(normalized);
        for (String exact : WRITE_DENIED_EXACT_PATHS) {
            if (path.equals(exact.substring(1)) || normalized.equals(exact)) {
                return true;
            }
        }
        for (String prefix : WRITE_DENIED_PREFIXES) {
            if (normalized.startsWith(prefix)
                    || path.startsWith(prefix.substring(1))) {
                return true;
            }
        }
        if (WRITE_DENIED_HOME_FILE_NAMES.contains(lastPathPart(path))) {
            return startsWithHomeLikePrefix(normalized) || startsWithUserHome(normalized);
        }
        return false;
    }

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

    private boolean isOutsideSafeWriteRoot(String rawPath) {
        String safeRoot = "";
        if (appConfig != null && appConfig.getTerminal() != null) {
            safeRoot = StrUtil.nullToEmpty(appConfig.getTerminal().getWriteSafeRoot()).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            safeRoot = StrUtil.nullToEmpty(System.getenv("JIMUQU_WRITE_SAFE_ROOT")).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            safeRoot = StrUtil.nullToEmpty(System.getenv("JIMUQU_WRITE_SAFE_ROOT")).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            return false;
        }
        File root = resolveComparablePath(safeRoot);
        File target = resolveComparablePath(rawPath);
        if (root == null || target == null) {
            return false;
        }
        return !isInside(target, root);
    }

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
            } catch (Exception ignored) {
                return null;
            }
        }
    }

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

    private String stripKnownPrefix(String normalized) {
        String value = normalized;
        if (value.startsWith("~/")) {
            return value.substring(2);
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
            Path runtimeHome =
                    appConfig == null || appConfig.getRuntime() == null
                            ? null
                            : Paths.get(appConfig.getRuntime().getHome()).toAbsolutePath().normalize();
            if (runtimeHome != null) {
                String runtime = runtimeHome.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (value.startsWith(runtime + "/")) {
                    return value.substring(runtime.length() + 1);
                }
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private String normalizeRuntimePath(String first, String second) {
        try {
            if (appConfig == null || appConfig.getRuntime() == null) {
                return "";
            }
            Path path = Paths.get(appConfig.getRuntime().getHome(), first, second)
                    .toAbsolutePath()
                    .normalize();
            return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String lastPathPart(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

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

    private List<String> sharedWebsiteRules() {
        List<String> rules = new ArrayList<String>();
        List<String> sharedFiles = appConfig.getSecurity().getWebsiteBlocklist().getSharedFiles();
        if (sharedFiles == null || sharedFiles.isEmpty()) {
            return rules;
        }
        for (String rawPath : sharedFiles) {
            File file = resolveSharedFile(rawPath);
            if (file == null || !file.isFile()) {
                continue;
            }
            try {
                String text = cn.hutool.core.io.FileUtil.readString(file, StandardCharsets.UTF_8);
                String[] lines = text.split("\\r?\\n");
                for (String line : lines) {
                    String value = StrUtil.nullToEmpty(line).trim();
                    if (value.length() == 0 || value.startsWith("#")) {
                        continue;
                    }
                    rules.add(value);
                }
            } catch (Exception ignored) {
            }
        }
        return rules;
    }

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
            File runtimeHome =
                    appConfig == null || appConfig.getRuntime() == null
                            ? new File(".")
                            : new File(StrUtil.blankToDefault(
                                    appConfig.getRuntime().getHome(),
                                    RuntimePathConstants.RUNTIME_HOME));
            File home = runtimeHome.getCanonicalFile();
            File file = new File(home, path).getCanonicalFile();
            if (isInside(file, home)) {
                return checkPath(file.getAbsolutePath(), false).isAllowed() ? file : null;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

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

    private boolean isAbsolutePathText(String path) {
        String value = StrUtil.nullToEmpty(path).trim();
        return new File(value).isAbsolute()
                || value.startsWith("/")
                || value.startsWith("\\")
                || Pattern.compile("^[A-Za-z]:[/\\\\].*").matcher(value).matches();
    }

    private boolean matchHost(String host, String rule) {
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(2);
            return host.endsWith("." + suffix);
        }
        return host.equals(rule) || host.endsWith("." + rule);
    }

    private String normalizeRule(String raw) {
        String value = normalizeUrlText(raw).toLowerCase(Locale.ROOT);
        if (value.length() == 0 || value.startsWith("#")) {
            return "";
        }
        if (value.contains("://")) {
            URI uri = parseUri(value);
            value = uri == null ? value : extractUriHost(uri);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        value = normalizeHost(value);
        return value.startsWith("www.") ? value.substring(4) : value;
    }

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
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            value = value.substring(0, colon);
        }
        return normalizeHost(value.startsWith("www.") ? value.substring(4) : value);
    }

    private URI parseUri(String url) {
        try {
            return URI.create(url);
        } catch (Exception ignored) {
            return null;
        }
    }

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

    private boolean hasSensitiveUrlParameterName(URI uri) {
        if (uri == null) {
            return false;
        }
        return containsSensitiveParameterName(uri.getRawQuery())
                || containsSensitiveParameterName(uri.getRawFragment());
    }

    private boolean containsSensitiveParameterName(String rawParameters) {
        String value = StrUtil.nullToEmpty(rawParameters);
        if (value.length() == 0) {
            return false;
        }
        String[] parameters = value.split("[&;]");
        for (String parameter : parameters) {
            String name = parameter;
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
            if (isSensitiveUrlParameterName(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitiveUrlParameterName(String rawName) {
        String name = decodeUrlComponent(rawName).trim().toLowerCase(Locale.ROOT);
        return SENSITIVE_URL_PARAMETER_NAMES.contains(name);
    }

    private String decodeUrlComponent(String raw) {
        String value = StrUtil.nullToEmpty(raw);
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

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

    private String normalizeUrlText(String raw) {
        String value = StrUtil.nullToEmpty(raw).replace("\u0000", "");
        value = TerminalAnsiSanitizer.stripAnsi(value);
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return value.trim();
    }

    private String normalizeHost(String host) {
        String value = normalizeUrlText(host).toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        value = toAsciiHost(value);
        return value;
    }

    private String toAsciiHost(String host) {
        if (StrUtil.isBlank(host) || host.indexOf(':') >= 0) {
            return host;
        }
        if (host.startsWith("*.")) {
            return "*." + toAsciiHost(host.substring(2));
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return host;
        }
    }

    private boolean isLocalOrAddressLiteral(String host) {
        String value = StrUtil.nullToEmpty(host).toLowerCase(Locale.ROOT);
        if ("localhost".equals(value)) {
            return true;
        }
        if (value.indexOf(':') >= 0) {
            return true;
        }
        return parseObfuscatedIpv4(value) != null
                || Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matcher(value).matches();
    }

    private boolean isAlwaysBlockedIp(String ip) {
        if (contains(ALWAYS_BLOCKED_IPS, ip)) {
            return true;
        }
        return ip != null && ip.startsWith("169.254.");
    }

    private boolean isAlwaysBlockedAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] rawAddress = address.getAddress();
        if (rawAddress == null) {
            return false;
        }
        if (rawAddress.length == 4) {
            return isAlwaysBlockedIpv4(
                    rawAddress[0] & 0xff,
                    rawAddress[1] & 0xff,
                    rawAddress[2] & 0xff,
                    rawAddress[3] & 0xff);
        }
        if (rawAddress.length == 16
                && isZeroSuffix(rawAddress, 0, 10)
                && rawAddress[10] == (byte) 0xff
                && rawAddress[11] == (byte) 0xff) {
            return isAlwaysBlockedIpv4(
                    rawAddress[12] & 0xff,
                    rawAddress[13] & 0xff,
                    rawAddress[14] & 0xff,
                    rawAddress[15] & 0xff);
        }
        if (rawAddress.length == 16 && isZeroSuffix(rawAddress, 0, 12)) {
            return isAlwaysBlockedIpv4(
                    rawAddress[12] & 0xff,
                    rawAddress[13] & 0xff,
                    rawAddress[14] & 0xff,
                    rawAddress[15] & 0xff);
        }
        return false;
    }

    private boolean isAlwaysBlockedIpv4(int a, int b, int c, int d) {
        if (a == 169 && b == 254) {
            return true;
        }
        return (a == 100 && b == 100 && c == 100 && d == 200);
    }

    private boolean isPrivateOrInternal(InetAddress address, String ip) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        if (ip == null) {
            return false;
        }
        byte[] rawAddress = address.getAddress();
        if (rawAddress.length == 16
                && isZeroSuffix(rawAddress, 0, 10)
                && rawAddress[10] == (byte) 0xff
                && rawAddress[11] == (byte) 0xff) {
            int a = rawAddress[12] & 0xff;
            int b = rawAddress[13] & 0xff;
            int c = rawAddress[14] & 0xff;
            int d = rawAddress[15] & 0xff;
            if (isBlockedIpv4(a, b, c, d)) {
                return true;
            }
        }
        if (rawAddress.length == 16 && isZeroSuffix(rawAddress, 0, 12)) {
            int a = rawAddress[12] & 0xff;
            int b = rawAddress[13] & 0xff;
            int c = rawAddress[14] & 0xff;
            int d = rawAddress[15] & 0xff;
            if (isBlockedIpv4(a, b, c, d)) {
                return true;
            }
        }
        if (isBlockedIpv4Text(ip)) {
            return true;
        }
        return isBlockedIpv6Address(rawAddress);
    }

    private boolean isBlockedIpv4Text(String ip) {
        String value = StrUtil.nullToEmpty(ip);
        int percent = value.indexOf('%');
        if (percent >= 0) {
            value = value.substring(0, percent);
        }
        int[] obfuscated = parseObfuscatedIpv4(value);
        if (obfuscated != null) {
            return isBlockedIpv4(obfuscated[0], obfuscated[1], obfuscated[2], obfuscated[3]);
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            try {
                if (parts[i].length() == 0) {
                    return false;
                }
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return isBlockedIpv4(octets[0], octets[1], octets[2], octets[3]);
    }

    private boolean isBlockedIpv4(int a, int b, int c, int d) {
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return true;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return true;
        }
        return (a == 192 && b == 0 && (c == 0 || c == 2))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113);
    }

    private boolean isTrustedPrivateAddress(InetAddress address) {
        byte[] rawAddress = address == null ? null : address.getAddress();
        if (rawAddress == null || rawAddress.length != 4) {
            return false;
        }
        int a = rawAddress[0] & 0xff;
        int b = rawAddress[1] & 0xff;
        return a == 198 && (b == 18 || b == 19);
    }

    private boolean resolveAllowPrivateUrls() {
        Boolean envOverride = parseBooleanOverride(readEnvironment("JIMUQU_ALLOW_PRIVATE_URLS"));
        if (envOverride != null) {
            return envOverride.booleanValue();
        }
        return appConfig != null
                && appConfig.getSecurity() != null
                && appConfig.getSecurity().isAllowPrivateUrls();
    }

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

    private boolean isBlockedIpv6Address(byte[] rawAddress) {
        if (rawAddress == null || rawAddress.length != 16) {
            return false;
        }
        int first = unsignedShort(rawAddress, 0);
        int second = unsignedShort(rawAddress, 2);
        if (first == 0 || first == 1 || first == 0x2002 || first >= 0xff00) {
            return true;
        }
        if (first >= 0xfc00 && first <= 0xfdff) {
            return true;
        }
        if (first == 0x2001 && second < 0x0200) {
            return true;
        }
        if (first == 0x2001 && second == 0x0db8) {
            return true;
        }
        return first == 0x0064 && second == 0xff9b && isZeroSuffix(rawAddress, 4, 8);
    }

    private boolean isBlockedOrAlwaysBlockedIpv4(int[] octets) {
        return octets != null
                && octets.length == 4
                && (isAlwaysBlockedIpv4(octets[0], octets[1], octets[2], octets[3])
                        || isBlockedIpv4(octets[0], octets[1], octets[2], octets[3]));
    }

    private int[] parseObfuscatedIpv4(String host) {
        String value = StrUtil.nullToEmpty(host).toLowerCase(Locale.ROOT).trim();
        if (value.length() == 0 || value.indexOf(':') >= 0) {
            return null;
        }
        if (value.startsWith("0x") && value.indexOf('.') < 0) {
            return parseIpv4Number(value.substring(2), 16);
        }
        if (Pattern.compile("^\\d+$").matcher(value).matches()) {
            return parseIpv4Number(value, 10);
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        boolean nonDecimal = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int radix = 10;
            if (part.startsWith("0x") && part.length() > 2) {
                radix = 16;
                part = part.substring(2);
                nonDecimal = true;
            } else if (part.length() > 1 && part.charAt(0) == '0') {
                radix = 8;
                nonDecimal = true;
            }
            if (!isNumberInRadix(part, radix)) {
                return null;
            }
            try {
                octets[i] = Integer.parseInt(part, radix);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return null;
            }
        }
        return nonDecimal ? octets : null;
    }

    private int[] parseIpv4Number(String raw, int radix) {
        if (!isNumberInRadix(raw, radix)) {
            return null;
        }
        try {
            BigInteger value = new BigInteger(raw, radix);
            if (value.signum() < 0 || value.bitLength() > 32) {
                return null;
            }
            long packed = value.longValue();
            return new int[] {
                (int) ((packed >> 24) & 0xff),
                (int) ((packed >> 16) & 0xff),
                (int) ((packed >> 8) & 0xff),
                (int) (packed & 0xff)
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isNumberInRadix(String value, int radix) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), radix) < 0) {
                return false;
            }
        }
        return true;
    }

    private String formatIpv4(int[] octets) {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private boolean isZeroSuffix(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(String[] values, String value) {
        for (String item : values) {
            if (item.equalsIgnoreCase(StrUtil.nullToEmpty(value))) {
                return true;
            }
        }
        return false;
    }

    private static class WebsiteRule {
        private final String url;
        private final String rule;

        private WebsiteRule(String url, String rule) {
            this.url = url;
            this.rule = rule;
        }
    }

    public static class UrlVerdict {
        private final boolean allowed;
        private final String url;
        private final String message;

        private UrlVerdict(boolean allowed, String url, String message) {
            this.allowed = allowed;
            this.url = url;
            this.message = message;
        }

        public static UrlVerdict allow() {
            return new UrlVerdict(true, "", "");
        }

        public static UrlVerdict block(String url, String message) {
            return new UrlVerdict(false, url, message);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getUrl() {
            return url;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class FileVerdict {
        private final boolean allowed;
        private final String path;
        private final String message;

        private FileVerdict(boolean allowed, String path, String message) {
            this.allowed = allowed;
            this.path = path;
            this.message = message;
        }

        public static FileVerdict allow() {
            return new FileVerdict(true, "", "");
        }

        public static FileVerdict block(String path, String message) {
            return new FileVerdict(false, path, message);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getPath() {
            return path;
        }

        public String getMessage() {
            return message;
        }
    }
}
