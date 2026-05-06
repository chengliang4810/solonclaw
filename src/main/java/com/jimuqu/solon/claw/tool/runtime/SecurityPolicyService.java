package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hermes-style URL and website access guardrails. */
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
                    ".config/gh");
    private static final List<String> CREDENTIAL_FILE_NAMES =
            Arrays.asList(
                    ".env",
                    ".env.local",
                    ".env.production",
                    ".env.development",
                    ".netrc",
                    ".pgpass",
                    ".npmrc",
                    ".pypirc",
                    "authorized_keys",
                    "hosts.yml",
                    "id_rsa",
                    "id_ed25519",
                    "known_hosts");
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
                    "(~?[/\\\\][^\\s'\"`|;&<>]+|\\$HOME[/\\\\][^\\s'\"`|;&<>]+|\\$env:[A-Za-z_][A-Za-z0-9_]*[/\\\\][^\\s'\"`|;&<>]+|%[A-Za-z_][A-Za-z0-9_]*%[/\\\\][^\\s'\"`|;&<>]+|[A-Za-z]:[/\\\\][^\\s'\"`|;&<>]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_CREDENTIAL_TOKEN_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:\\.env(?:\\.[A-Za-z0-9_.-]+)?)|(?:credentials)|(?:\\.netrc)|(?:\\.pgpass)|(?:\\.npmrc)|(?:\\.pypirc)|(?:authorized_keys)|(?:hosts\\.yml)|(?:id_rsa)|(?:id_ed25519))(?![A-Za-z0-9_.-])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern WORKDIR_SAFE_PATTERN =
            Pattern.compile("^[A-Za-z0-9/\\\\:_\\-.~ +@=,]+$");
    private static final Pattern PROC_STDIO_FD_PATTERN =
            Pattern.compile("^/proc/(?:self|\\d+)/fd/[0-2]$");
    private static final Pattern URLISH_PATTERN =
            Pattern.compile(
                    "(?i)(https?://[^\\s)>'\"]+|(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d+)?/[^\\s)>'\"]*)");

    private final AppConfig appConfig;

    public SecurityPolicyService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public UrlVerdict checkUrl(String url) {
        String raw = StrUtil.nullToEmpty(url).trim();
        if (raw.length() == 0) {
            return UrlVerdict.block(raw, "URL 缺少内容");
        }
        if (SecretRedactor.containsSecretLikeToken(raw)) {
            return UrlVerdict.block(raw, "URL 包含疑似 API key 或 token，禁止通过 URL 发送凭据");
        }

        URI uri = parseUri(raw);
        if (uri == null) {
            return UrlVerdict.block(raw, "URL 解析失败");
        }

        String scheme = StrUtil.nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (scheme.length() == 0) {
            String schemelessHost = extractSchemelessHost(raw);
            if (StrUtil.isBlank(schemelessHost)) {
                return UrlVerdict.allow();
            }
            WebsiteRule websiteRule = checkWebsitePolicy(raw, schemelessHost);
            if (websiteRule != null) {
                return UrlVerdict.block(
                        raw,
                        "Blocked by website policy: '"
                                + schemelessHost
                                + "' matched rule '"
                                + websiteRule.rule
                                + "'");
            }
            return UrlVerdict.allow();
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return UrlVerdict.block(raw, "仅允许 http/https URL");
        }

        String host = normalizeHost(uri.getHost());
        if (StrUtil.isBlank(host)) {
            return UrlVerdict.block(raw, "URL 缺少主机名");
        }

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

        boolean allowPrivate =
                appConfig != null
                        && appConfig.getSecurity() != null
                        && appConfig.getSecurity().isAllowPrivateUrls();
        boolean trustedPrivate = "https".equals(scheme) && contains(TRUSTED_PRIVATE_IP_HOSTS, host);

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                String ip = address.getHostAddress();
                if (isAlwaysBlockedIp(ip)
                        || address.isLinkLocalAddress()
                        || isAlwaysBlockedAddress(address)) {
                    return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
                }
                if (!allowPrivate && !trustedPrivate && isPrivateOrInternal(address, ip)) {
                    return UrlVerdict.block(raw, "阻断内网/私有地址：" + host + " -> " + ip);
                }
            }
        } catch (Exception e) {
            return UrlVerdict.block(raw, "DNS 解析失败或 URL 安全检查失败：" + host);
        }

        return UrlVerdict.allow();
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

    public FileVerdict checkFileToolArgs(String toolName, Map<String, Object> args) {
        List<String> paths = extractPaths(toolName, args);
        for (String path : paths) {
            FileVerdict verdict = checkPath(path, isWriteLikeTool(toolName));
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return FileVerdict.allow();
    }

    public FileVerdict checkCommandPaths(String command) {
        String code = StrUtil.nullToEmpty(command);
        if (code.length() == 0) {
            return FileVerdict.allow();
        }
        Matcher matcher = SHELL_PATH_PATTERN.matcher(code);
        while (matcher.find()) {
            FileVerdict verdict = checkPath(matcher.group(1), true);
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
        return FileVerdict.allow();
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

    public FileVerdict checkPath(String rawPath, boolean writeLike) {
        String path = StrUtil.nullToEmpty(rawPath).trim();
        if (path.length() == 0) {
            return FileVerdict.allow();
        }
        String normalized = normalizePathText(path);
        if (normalized.indexOf('\0') >= 0) {
            return FileVerdict.block(path, "路径包含非法字符");
        }
        if (containsTraversal(normalized)) {
            return FileVerdict.block(path, "路径遍历被阻断");
        }
        if (matchesBlockedDevicePath(normalized)) {
            return FileVerdict.block(path, "读取设备文件可能导致无限输出或阻塞，已阻断");
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
        if (writeLike && isOutsideSafeWriteRoot(normalized)) {
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

    private List<String> extractUrls(String toolName, java.util.Map<String, Object> args) {
        List<String> urls = new ArrayList<String>();
        if (args == null) {
            return urls;
        }
        collectUrls(args, urls);
        return urls;
    }

    @SuppressWarnings("unchecked")
    private void collectUrls(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            for (Object value : ((Map<?, ?>) raw).values()) {
                collectUrls(value, urls);
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

    private void extractUrlishFromText(Object raw, List<String> urls) {
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw);
        java.util.regex.Matcher matcher = URLISH_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
    }

    private String cleanUrlToken(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        while (value.endsWith(",")
                || value.endsWith(".")
                || value.endsWith(";")
                || value.endsWith(":")
                || value.endsWith("]")
                || value.endsWith("}")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private List<String> extractPaths(String toolName, Map<String, Object> args) {
        List<String> paths = new ArrayList<String>();
        if (args == null) {
            return paths;
        }
        collectPaths(args, paths);
        if (ToolNameConstants.PATCH.equals(toolName)) {
            extractPatchPaths(args.get("patch"), paths);
        }
        return paths;
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
        Matcher moveToMatcher =
                Pattern.compile("^\\*\\*\\*\\s+Move\\s+to:\\s*(.+)$", Pattern.MULTILINE)
                        .matcher(text);
        while (moveToMatcher.find()) {
            addPathValue(paths, moveToMatcher.group(1));
        }
    }

    private void addPathValue(List<String> paths, Object raw) {
        if (raw == null) {
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
        return "file_write".equals(toolName)
                || "file_delete".equals(toolName)
                || ToolNameConstants.PATCH.equals(toolName);
    }

    private String normalizePathText(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
        value = value.replace('\\', '/');
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        return value.toLowerCase(Locale.ROOT);
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
        return fileName.startsWith(".env.") && !".env.example".equals(fileName);
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
                || normalized.startsWith("$env:userprofile/")
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

    private boolean isOutsideSafeWriteRoot(String normalized) {
        String safeRoot = "";
        if (appConfig != null && appConfig.getTerminal() != null) {
            safeRoot = StrUtil.nullToEmpty(appConfig.getTerminal().getWriteSafeRoot()).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            safeRoot = StrUtil.nullToEmpty(System.getenv("JIMUQU_WRITE_SAFE_ROOT")).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            safeRoot = StrUtil.nullToEmpty(System.getenv("HERMES_WRITE_SAFE_ROOT")).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            return false;
        }
        String root = normalizePathText(safeRoot);
        if (root.endsWith("/") && root.length() > 1) {
            root = root.substring(0, root.length() - 1);
        }
        if (StrUtil.isBlank(root)) {
            return false;
        }
        return !(normalized.equals(root) || normalized.startsWith(root + "/"));
    }

    private String stripKnownPrefix(String normalized) {
        String value = normalized;
        if (value.startsWith("~/")) {
            return value.substring(2);
        }
        if (value.startsWith("$home/")) {
            return value.substring("$home/".length());
        }
        if (value.startsWith("$env:userprofile/")) {
            return value.substring("$env:userprofile/".length());
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
        for (String rawRule : appConfig.getSecurity().getWebsiteBlocklist().getDomains()) {
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
        String path = StrUtil.nullToEmpty(rawPath).trim();
        if (path.length() == 0) {
            return null;
        }
        File file = new File(path);
        if (file.isAbsolute()) {
            return file;
        }
        File runtimeHome =
                appConfig == null || appConfig.getRuntime() == null
                        ? new File(".")
                        : new File(StrUtil.blankToDefault(
                                appConfig.getRuntime().getHome(),
                                com.jimuqu.solon.claw.support.constants.RuntimePathConstants.RUNTIME_HOME));
        return new File(runtimeHome, path);
    }

    private boolean matchHost(String host, String rule) {
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(2);
            return host.endsWith("." + suffix);
        }
        return host.equals(rule) || host.endsWith("." + rule);
    }

    private String normalizeRule(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim().toLowerCase(Locale.ROOT);
        if (value.length() == 0 || value.startsWith("#")) {
            return "";
        }
        if (value.contains("://")) {
            URI uri = parseUri(value);
            value = uri == null ? value : StrUtil.nullToEmpty(uri.getHost());
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        value = normalizeHost(value);
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    private String extractSchemelessHost(String raw) {
        String value = StrUtil.nullToEmpty(raw).trim();
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

    private String normalizeHost(String host) {
        String value = StrUtil.nullToEmpty(host).trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
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
                && rawAddress[10] == (byte) 0xff
                && rawAddress[11] == (byte) 0xff) {
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
