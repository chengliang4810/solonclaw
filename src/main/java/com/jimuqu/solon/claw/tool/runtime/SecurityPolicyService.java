package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.io.File;
import java.math.BigInteger;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提供安全策略相关业务能力，封装调用方不需要感知的运行细节。 */
public class SecurityPolicyService {
    /** 始终阻断的主机列表的统一常量值。 */
    private static final String[] ALWAYS_BLOCKED_HOSTS =
            new String[] {"metadata.google.internal", "metadata.goog"};

    /** 始终阻断的 IP 列表的统一常量值。 */
    private static final String[] ALWAYS_BLOCKED_IPS =
            new String[] {
                "169.254.169.254",
                "169.254.170.2",
                "169.254.169.253",
                "100.100.100.200",
                "fd00:ec2::254",
                "fd00:ec2:0:0:0:0:0:254"
            };

    /** 可信私有 IP 主机列表的统一常量值。 */
    private static final String[] TRUSTED_PRIVATE_IP_HOSTS =
            new String[] {"multimedia.nt.qq.com.cn"};

    /** 凭据目录片段列表的统一常量值。 */
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
                    ".gemini",
                    ".cargo",
                    ".terraform.d",
                    ".config/gh",
                    ".config/gcloud",
                    ".config/gemini",
                    ".config/pip",
                    ".m2",
                    ".gem",
                    ".nuget");

    /** 凭据文件名称列表的统一常量值。 */
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
                    ".yarnrc",
                    ".pnpmrc",
                    ".curlrc",
                    ".wgetrc",
                    ".pypirc",
                    "pip.conf",
                    "settings.xml",
                    "nuget.config",
                    "credentials",
                    "credentials.json",
                    ".credentials.json",
                    "secret.json",
                    "secrets.json",
                    "keyring.json",
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
                    "credentials.toml",
                    "credentials.tfrc.json",
                    "authorized_keys",
                    "hosts.yml",
                    "kubeconfig",
                    "id_dsa",
                    "id_ecdsa",
                    "id_ecdsa_sk",
                    "id_rsa_sk",
                    "id_rsa",
                    "id_ed25519_sk",
                    "id_ed25519",
                    "known_hosts",
                    "known_hosts.old",
                    "known_hosts2");

    /** 敏感密钥文件扩展名列表的统一常量值。 */
    private static final List<String> SENSITIVE_KEY_FILE_EXTENSIONS =
            Arrays.asList(".pem", ".key", ".p12", ".pfx");

    /** 敏感密钥文件标记列表的统一常量值。 */
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

    /** 凭据路径后缀列表的统一常量值。 */
    private static final List<String> CREDENTIAL_PATH_SUFFIXES =
            Arrays.asList(
                    ".claude/.credentials.json",
                    ".codex/auth.json",
                    ".qwen/oauth_creds.json",
                    ".gemini/oauth_creds.json",
                    ".config/gemini/oauth_creds.json",
                    ".config/gcloud/application_default_credentials.json",
                    ".cargo/credentials.toml",
                    ".terraform.d/credentials.tfrc.json");

    /** 运行时凭据文件路径列表的统一常量值。 */
    private static final List<String> RUNTIME_CREDENTIAL_FILE_PATHS =
            Arrays.asList("cache/bws_cache.json");

    /** 写入拒绝精确路径列表的统一常量值。 */
    private static final List<String> WRITE_DENIED_EXACT_PATHS =
            Arrays.asList(
                    "/etc/hosts",
                    "/etc/resolv.conf",
                    "/etc/sudoers",
                    "/etc/passwd",
                    "/etc/shadow",
                    "/var/run/docker.sock",
                    "/run/docker.sock");

    /** 写入拒绝主渠道文件名称列表的统一常量值。 */
    private static final List<String> WRITE_DENIED_HOME_FILE_NAMES =
            Arrays.asList(".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile");

    /** 写入拒绝前缀列表的统一常量值。 */
    private static final List<String> WRITE_DENIED_PREFIXES =
            Arrays.asList(
                    "/boot/",
                    "/bin/",
                    "/sbin/",
                    "/usr/bin/",
                    "/usr/sbin/",
                    "/usr/local/bin/",
                    "/usr/local/sbin/",
                    "/usr/lib/systemd/",
                    "/private/etc/",
                    "/private/var/",
                    "/etc/sudoers.d/",
                    "/etc/systemd/");

    /** 写入拒绝Windows 前缀列表的统一常量值。 */
    private static final List<String> WRITE_DENIED_WINDOWS_PREFIXES =
            Arrays.asList(
                    "c:/windows/",
                    "%windir%/",
                    "$env:windir/",
                    "${windir}/",
                    "c:/program files/",
                    "c:/program files (x86)/",
                    "$env:programfiles/",
                    "${programfiles}/",
                    "%programfiles%/",
                    "%programfiles(x86)%/");

    /** 阻断设备路径列表的统一常量值。 */
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

    /** 终端路径正则的统一常量值。 */
    private static final Pattern SHELL_PATH_PATTERN =
            Pattern.compile(
                    "(~?[/\\\\][^\\s'\"`|;&<>]+|\\$HOME[/\\\\][^\\s'\"`|;&<>]+|\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}[/\\\\][^\\s'\"`|;&<>]+|\\$env:[A-Za-z_][A-Za-z0-9_]*[/\\\\][^\\s'\"`|;&<>]+|%[A-Za-z_][A-Za-z0-9_]*(?:\\([A-Za-z0-9_ -]+\\))?%[/\\\\][^\\s'\"`|;&<>]+|[A-Za-z]:[/\\\\][^\\s'\"`|;&<>]+)",
                    Pattern.CASE_INSENSITIVE);

    /** QUOTEDWindows路径正则的统一常量值。 */
    private static final Pattern QUOTED_WINDOWS_PATH_PATTERN =
            Pattern.compile("([\"'])([A-Za-z]:[\\\\/][^\"'`|;&<>]+)\\1", Pattern.CASE_INSENSITIVE);

    /** 终端RELATIVE凭据路径正则的统一常量值。 */
    private static final Pattern SHELL_RELATIVE_CREDENTIAL_PATH_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:\\.\\.?[/\\\\])?(?:(?:[^\\s'\"`|;&<>/\\\\]+)[/\\\\])*(?:\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.docker|\\.azure|\\.claude|\\.codex|\\.qwen|\\.gemini|\\.cargo|\\.terraform\\.d|\\.m2|\\.gem|\\.nuget|\\.config[/\\\\](?:gh|gcloud|gemini|pip))[/\\\\][^\\s'\"`|;&<>]+)(?![A-Za-z0-9_./\\\\-])",
                    Pattern.CASE_INSENSITIVE);

    /** 终端凭据token 正则的统一常量值。 */
    private static final Pattern SHELL_CREDENTIAL_TOKEN_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:(?:\\.{1,2}|~|\\$[A-Za-z_][A-Za-z0-9_]*|\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|[A-Za-z0-9_.@=,+-]+)[/\\\\])*(?:(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?)|(?:\\.envrc)|(?:credentials(?:\\.(?:json|toml|tfrc\\.json))?)|(?:auth\\.json)|(?:secret\\.json)|(?:secrets\\.json)|(?:keyring\\.json)|(?:bws_cache\\.json)|(?:\\.netrc)|(?:\\.git-credentials)|(?:\\.pgpass)|(?:\\.npmrc)|(?:\\.yarnrc)|(?:\\.pnpmrc)|(?:\\.curlrc)|(?:\\.wgetrc)|(?:\\.pypirc)|(?:pip\\.conf)|(?:settings\\.xml)|(?:nuget\\.config)|(?:\\.credentials\\.json)|(?:\\.anthropic_oauth\\.json)|(?:oauth_creds\\.json)|(?:client_secrets?\\.json)|(?:application_default_credentials\\.json)|(?:service[_-]account(?:[_-]key)?\\.json)|(?:google-credentials\\.json)|(?:firebase-adminsdk[A-Za-z0-9_.-]*\\.json)|(?:token\\.json)|(?:authorized_keys)|(?:hosts\\.yml)|(?:known_hosts(?:\\.old|2)?)|(?:kubeconfig)|(?:id_(?:dsa|ecdsa(?:_sk)?|rsa(?:_sk)?|ed25519(?:_sk)?))|(?:(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx))))(?![A-Za-z0-9_./\\\\-])",
                    Pattern.CASE_INSENSITIVE);

    /** WORKDIR安全正则的统一常量值。 */
    private static final Pattern WORKDIR_SAFE_PATTERN =
            Pattern.compile("^[A-Za-z0-9/\\\\:_\\-.~ +@=,]+$");

    /** proc 标准输入输出文件描述符正则的统一常量值。 */
    private static final Pattern PROC_STDIO_FD_PATTERN =
            Pattern.compile("^/proc/(?:self|\\d+)/fd/[0-2]$");

    /** 原始阻断设备正则的统一常量值。 */
    private static final Pattern RAW_BLOCK_DEVICE_PATTERN =
            Pattern.compile(
                    "^/dev/(?:sd|hd|vd|xvd)[a-z][a-z0-9]*$|^/dev/nvme\\d+n\\d+(?:p\\d+)?$|^/dev/mmcblk\\d+(?:p\\d+)?$");

    /** 凭据路径选项名称列表的统一常量值。 */
    private static final List<String> CREDENTIAL_PATH_OPTION_NAMES =
            Arrays.asList(
                    "--key",
                    "--identity-file",
                    "--ssh-key",
                    "--ssh-key-file",
                    "--private-key",
                    "--private-key-file",
                    "--proxy-key",
                    "--client-key",
                    "--client-key-file",
                    "--cert",
                    "--cert-file",
                    "--certificate",
                    "--certificate-file",
                    "--proxy-cert",
                    "--cacert",
                    "--ca-certificate",
                    "--capath",
                    "--ca-directory",
                    "--crl-file",
                    "--netrc-file",
                    "--cookie",
                    "--cookie-jar",
                    "--load-cookies",
                    "--kubeconfig",
                    "--key-file",
                    "--service-account-key-file",
                    "--credential-file",
                    "--credentials-file",
                    "--token-file",
                    "--password-file",
                    "--userconfig",
                    "--globalconfig",
                    "--config",
                    "-F",
                    "-i");

    /** 紧凑凭据路径选项前缀列表的统一常量值。 */
    private static final List<String> COMPACT_CREDENTIAL_PATH_OPTION_PREFIXES =
            Arrays.asList("-i", "-F", "-K");

    /** 网络凭据短选项列表的统一常量值。 */
    private static final List<String> NETWORK_CREDENTIAL_SHORT_OPTIONS =
            Arrays.asList("-b", "-c", "-E", "-K");

    /** 网络上传文件选项列表的统一常量值。 */
    private static final List<String> NETWORK_UPLOAD_FILE_OPTIONS =
            Arrays.asList(
                    "--upload-file",
                    "--data-binary",
                    "--data-raw",
                    "--data",
                    "-d",
                    "--json",
                    "--post-file",
                    "--body-file");

    /** 网络上传文件短选项列表的统一常量值。 */
    private static final List<String> NETWORK_UPLOAD_FILE_SHORT_OPTIONS = Arrays.asList("-T");

    /** 本地管理套接字路径列表的统一常量值。 */
    private static final List<String> LOCAL_MANAGEMENT_SOCKET_PATHS =
            Arrays.asList(
                    "/var/run/docker.sock",
                    "/run/docker.sock",
                    "/run/containerd/containerd.sock",
                    "/run/podman/podman.sock",
                    "/var/run/cri-dockerd.sock",
                    "/var/run/crio/crio.sock");

    /** 本地管理PIPE路径列表的统一常量值。 */
    private static final List<String> LOCAL_MANAGEMENT_PIPE_PATHS =
            Arrays.asList(
                    "//./pipe/docker_engine",
                    "\\\\.\\pipe\\docker_engine",
                    "npipe:////./pipe/docker_engine",
                    "npipe://./pipe/docker_engine");

    /** SSH文件配置选项名称列表的统一常量值。 */
    private static final List<String> SSH_FILE_CONFIG_OPTION_NAMES =
            Arrays.asList(
                    "IdentityFile",
                    "CertificateFile",
                    "UserKnownHostsFile",
                    "GlobalKnownHostsFile",
                    "HostKey",
                    "HostCertificate",
                    "HostKeyAlias");

    /** URL 候选正则的统一常量值。 */
    private static final Pattern URLISH_PATTERN =
            Pattern.compile(
                    "(?iu)((?:https?|wss?|s?ftp|scp|gopher|file|dict|ldap|ldaps|tftp)://[^\\s)>'\"]+|(?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,}(?::\\d+)?/[^\\s)>'\"]*|localhost(?::\\d+)?/[^\\s)>'\"]*|(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?/[^\\s)>'\"]*|\\[[0-9a-f:.%]+\\](?::\\d+)?/[^\\s)>'\"]*)");

    /** IPv4 CIDR token 正则的统一常量值。 */
    private static final Pattern IPV4_CIDR_TOKEN_PATTERN =
            Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$");

    /** IPv6 CIDR token 正则的统一常量值。 */
    private static final Pattern IPV6_CIDR_TOKEN_PATTERN =
            Pattern.compile(
                    "^\\[[0-9a-fA-F:.%]+\\]/\\d{1,3}$|^[0-9a-fA-F:.%]*:[0-9a-fA-F:.%]*/\\d{1,3}$");

    /** 裸主机名token 正则的统一常量值。 */
    private static final Pattern BARE_HOST_TOKEN_PATTERN =
            Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}_./:-])((?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}\\p{N}-]+|localhost|(?:0x[0-9a-f]+)|(?:0[0-7]+(?:\\.0[0-7]+){3})|(?:\\d{1,10})(?:\\.\\d{1,3}){0,3}|\\[[0-9a-f:.%]+\\])(?::\\d{1,5})?(?![\\p{L}\\p{N}_./:-])");

    /** 裸主机名FETCH上下文正则的统一常量值。 */
    private static final Pattern BARE_HOST_FETCH_CONTEXT_PATTERN =
            Pattern.compile(
                    "(?iu)(?:^|[^\\p{L}\\p{N}_./:-])(?:curl|wget|aria2c|httpie|http|xh|curlie|nc|netcat|ncat|telnet|socat|websocat|grpcurl|openssl\\s+s_client|fetch|axios|httpx|requests\\.(?:get|post|put|delete|patch|head|request)|urllib\\.request\\.urlopen|urlopen|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|Start-BitsTransfer|bitsadmin|certutil|mshta|regsvr32|rundll32|WebClient|WebRequest|HttpWebRequest|RestTemplate|OkHttpClient|HttpURLConnection)\\b");

    /** 直接网络端点前缀正则的统一常量值。 */
    private static final Pattern DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN =
            Pattern.compile("(?iu)^(tcp|tcp4|tcp6|udp|udp4|udp6|ssl|tls|connect):(.+)$");

    /** JAVA代理选项列表ASSIGNMENT正则的统一常量值。 */
    private static final Pattern JAVA_PROXY_OPTIONS_ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?i)(?:^|\\s)(?:JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|MAVEN_OPTS|GRADLE_OPTS)=((?:\"[^\"]*\")|(?:'[^']*')|\\S+)");

    /** PowerShell代理环境变量ASSIGNMENT正则的统一常量值。 */
    private static final Pattern POWERSHELL_PROXY_ENV_ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?i)(?:\\$env:|Env:)(HTTP_PROXY|HTTPS_PROXY|FTP_PROXY|ALL_PROXY|NO_PROXY|NPM_CONFIG_PROXY|NPM_CONFIG_HTTPS_PROXY|NPM_CONFIG_NO_PROXY|NPM_CONFIG_NOPROXY|YARN_PROXY|YARN_HTTPS_PROXY|YARN_NO_PROXY|YARN_NOPROXY|PNPM_CONFIG_PROXY|PNPM_CONFIG_HTTPS_PROXY|PNPM_CONFIG_NO_PROXY|PNPM_CONFIG_NOPROXY|PIP_PROXY)\\s*=\\s*((?:\"[^\"]*\")|(?:'[^']*')|\\S+)|\\[Environment\\]::SetEnvironmentVariable\\s*\\(\\s*['\"](HTTP_PROXY|HTTPS_PROXY|FTP_PROXY|ALL_PROXY|NO_PROXY|NPM_CONFIG_PROXY|NPM_CONFIG_HTTPS_PROXY|NPM_CONFIG_NO_PROXY|NPM_CONFIG_NOPROXY|YARN_PROXY|YARN_HTTPS_PROXY|YARN_NO_PROXY|YARN_NOPROXY|PNPM_CONFIG_PROXY|PNPM_CONFIG_HTTPS_PROXY|PNPM_CONFIG_NO_PROXY|PNPM_CONFIG_NOPROXY|PIP_PROXY)['\"]\\s*,\\s*((?:\"[^\"]*\")|(?:'[^']*')|[^,)]+)");

    /** PowerShell注册表代理PROPERTY正则的统一常量值。 */
    private static final Pattern POWERSHELL_REGISTRY_PROXY_PROPERTY_PATTERN =
            Pattern.compile("(?i)^-(?:Name|PropertyName|Property)$|^-n$");

    /** PowerShell注册表代理值正则的统一常量值。 */
    private static final Pattern POWERSHELL_REGISTRY_PROXY_VALUE_PATTERN =
            Pattern.compile("(?i)^-(?:Value|PropertyValue)$");

    /** PowerShell本地管理环境变量ASSIGNMENT正则的统一常量值。 */
    private static final Pattern POWERSHELL_LOCAL_MANAGEMENT_ENV_ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?i)(?:\\$env:|Env:)(DOCKER_HOST|CONTAINER_HOST|PODMAN_HOST)\\s*=\\s*((?:\"[^\"]*\")|(?:'[^']*')|\\S+)|\\[Environment\\]::SetEnvironmentVariable\\s*\\(\\s*['\"](DOCKER_HOST|CONTAINER_HOST|PODMAN_HOST)['\"]\\s*,\\s*((?:\"[^\"]*\")|(?:'[^']*')|[^,)]+)");

    /** SENSITIVEURL参数名称列表的统一常量值。 */
    private static final List<String> SENSITIVE_URL_PARAMETER_NAMES =
            Arrays.asList(
                    "access_token",
                    "refresh_token",
                    "id_token",
                    "auth_token",
                    "oauth_token",
                    "authorization",
                    "proxy_authorization",
                    "bearer_token",
                    "code_verifier",
                    "client_assertion",
                    "saml_response",
                    "samlresponse",
                    "token",
                    "access_key",
                    "secret_key",
                    "session_token",
                    "client_secret",
                    "api_key",
                    "apikey",
                    "password",
                    "private_key",
                    "secret",
                    "jwt",
                    "signature",
                    "x-amz-signature",
                    "x-amz-credential",
                    "x-amz-security-token",
                    "x-goog-signature",
                    "x-goog-credential",
                    "x-oss-signature",
                    "x-oss-security-token",
                    "x-cos-signature",
                    "x-cos-security-token",
                    "x-obs-signature",
                    "x-obs-security-token",
                    "x-ms-signature",
                    "security-token");

    /** 注入应用配置，用于安全策略。 */
    private final AppConfig appConfig;

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
        return checkUrl(url, null);
    }

    /**
     * 检查URL Allowing私聊。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL Allowing私聊结果。
     */
    public UrlVerdict checkUrlAllowingPrivate(String url) {
        return checkUrl(url, Boolean.TRUE);
    }

    /**
     * 检查URL 块ing私聊。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回URL 块ing私聊结果。
     */
    public UrlVerdict checkUrlBlockingPrivate(String url) {
        return checkUrl(url, Boolean.FALSE);
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

    /**
     * 检查URL。
     *
     * @param url 待校验或访问的 URL。
     * @param allowPrivateOverride allowPrivateOverride标识或键值。
     * @return 返回URL结果。
     */
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
                        && contains(TRUSTED_PRIVATE_IP_HOSTS, host);

        int[] hostIpv4 = parseIpv4HostLiteral(host);

        // 先检查字面量 IP，避免 DNS 解析绕过云元数据和内网地址拦截。
        if (hostIpv4 != null) {
            String ip = formatIpv4(hostIpv4);
            if (isAlwaysBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
                return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + ip);
            }
            if (!allowPrivate
                    && isBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
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
                    raw, "阻断云元数据/链路本地地址：" + host + " -> " + literalAddress.getHostAddress());
        }
        int[] hostIpv4 = parseObfuscatedIpv4(host);
        if (hostIpv4 != null
                && isAlwaysBlockedIpv4(hostIpv4[0], hostIpv4[1], hostIpv4[2], hostIpv4[3])) {
            return UrlVerdict.block(raw, "阻断云元数据/链路本地地址：" + host + " -> " + formatIpv4(hostIpv4));
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
        } catch (Exception ignored) {
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
        for (String url : urls) {
            UrlVerdict verdict = checkUrl(normalizeToolUrlForCheck(toolName, url));
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return UrlVerdict.allow();
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
        summary.put("alwaysBlockedHostCount", Integer.valueOf(ALWAYS_BLOCKED_HOSTS.length));
        summary.put("alwaysBlockedIpCount", Integer.valueOf(ALWAYS_BLOCKED_IPS.length));
        summary.put("trustedPrivateIpHostCount", Integer.valueOf(TRUSTED_PRIVATE_IP_HOSTS.length));
        summary.put("alwaysBlockedHostSamples", sample(Arrays.asList(ALWAYS_BLOCKED_HOSTS), 4));
        summary.put("alwaysBlockedIpSamples", sample(Arrays.asList(ALWAYS_BLOCKED_IPS), 4));
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
        summary.put("trustedPrivateIpHostCount", Integer.valueOf(TRUSTED_PRIVATE_IP_HOSTS.length));
        summary.put(
                "trustedPrivateIpHostSamples", sample(Arrays.asList(TRUSTED_PRIVATE_IP_HOSTS), 4));
        summary.put("alwaysBlockedHostSamples", sample(Arrays.asList(ALWAYS_BLOCKED_HOSTS), 4));
        summary.put("alwaysBlockedIpSamples", sample(Arrays.asList(ALWAYS_BLOCKED_IPS), 4));
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
        String writeSafeRoot =
                appConfig == null || appConfig.getTerminal() == null
                        ? ""
                        : StrUtil.nullToEmpty(appConfig.getTerminal().getWriteSafeRoot()).trim();
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
        summary.put("writeSafeRootConfigured", Boolean.valueOf(StrUtil.isNotBlank(writeSafeRoot)));
        summary.put("writeSafeRoot", SecretRedactor.redact(writeSafeRoot, 400));
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
            FileVerdict verdict = checkPath(matcher.group(1), true);
            if (!verdict.allowed) {
                return verdict;
            }
        }
        Matcher quotedWindowsMatcher = QUOTED_WINDOWS_PATH_PATTERN.matcher(code);
        while (quotedWindowsMatcher.find()) {
            FileVerdict verdict = checkPath(quotedWindowsMatcher.group(2), true);
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
            FileVerdict verdict = checkPath(path, false);
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
        extractUrlishFromText(command, urls);
        for (String url : urls) {
            UrlVerdict verdict = checkUrl(cleanUrlToken(url));
            if (!verdict.allowed) {
                return verdict;
            }
        }
        return UrlVerdict.allow();
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
            UrlVerdict verdict =
                    value.startsWith("//")
                            ? checkAlwaysBlockedUrl("http:" + value)
                            : value.contains("://")
                                    ? checkAlwaysBlockedUrl(value)
                                    : checkAlwaysBlockedSchemelessUrl(value);
            if (!verdict.allowed) {
                return verdict;
            }
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
        if (writeLike && isOutsideSafeWriteRoot(path)) {
            return FileVerdict.block(path, "写入路径超出安全写入根被阻断");
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
        return FileVerdict.allow();
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
        boolean fetchContext = hasBareHostFetchContext(text);
        java.util.regex.Matcher matcher = URLISH_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group();
            if (!isCidrRangeToken(value) || fetchContext) {
                urls.add(value);
            }
        }
        extractBareSecurityRelevantHosts(text, urls);
        extractObfuscatedSchemelessUrlish(text, urls);
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
            int[] octets = parseObfuscatedIpv4(normalizeHost(hostPort));
            if (octets != null && isBlockedOrAlwaysBlockedIpv4(octets)) {
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
        return Arrays.asList("patch", "apply_patch", "patch_apply", "diff_apply", "apply_diff");
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
                || "apply_patch".equals(value)
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
            } catch (Exception ignored) {
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
        } catch (Exception ignored) {
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
     * 判断是否Outside Safe Write根用户。
     *
     * @param rawPath 文件或目录路径参数。
     * @return 如果Outside Safe Write根用户满足条件则返回 true，否则返回 false。
     */
    private boolean isOutsideSafeWriteRoot(String rawPath) {
        String safeRoot = "";
        if (appConfig != null && appConfig.getTerminal() != null) {
            safeRoot = StrUtil.nullToEmpty(appConfig.getTerminal().getWriteSafeRoot()).trim();
        }
        if (StrUtil.isBlank(safeRoot)) {
            safeRoot = StrUtil.nullToEmpty(System.getenv("SOLONCLAW_WRITE_SAFE_ROOT")).trim();
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
            } catch (Exception ignored) {
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
            Path runtimeHome =
                    appConfig == null || appConfig.getRuntime() == null
                            ? null
                            : Paths.get(appConfig.getRuntime().getHome())
                                    .toAbsolutePath()
                                    .normalize();
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
        } catch (Exception ignored) {
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
            } catch (Exception ignored) {
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
            File runtimeHome =
                    appConfig == null || appConfig.getRuntime() == null
                            ? new File(".")
                            : new File(
                                    StrUtil.blankToDefault(
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
        } catch (Exception ignored) {
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
            } catch (Exception ignored) {
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
            } catch (Exception ignored) {
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
        } catch (Exception ignored) {
            return host;
        }
    }

    /**
     * 判断是否本地Or Address Literal。
     *
     * @param host 主机参数。
     * @return 如果本地Or Address Literal满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否Always 块ed Ip。
     *
     * @param ip ip 参数。
     * @return 如果Always 块ed Ip满足条件则返回 true，否则返回 false。
     */
    private boolean isAlwaysBlockedIp(String ip) {
        if (contains(ALWAYS_BLOCKED_IPS, ip)) {
            return true;
        }
        return ip != null && ip.startsWith("169.254.");
    }

    /**
     * 判断是否Always 块ed Address。
     *
     * @param address address 参数。
     * @return 如果Always 块ed Address满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否Always 块ed Ipv4。
     *
     * @param a a 参数。
     * @param b b 参数。
     * @param c c 参数。
     * @param d d 参数。
     * @return 如果Always 块ed Ipv4满足条件则返回 true，否则返回 false。
     */
    private boolean isAlwaysBlockedIpv4(int a, int b, int c, int d) {
        if (a == 169 && b == 254) {
            return true;
        }
        return (a == 100 && b == 100 && c == 100 && d == 200);
    }

    /**
     * 判断是否私聊Or Internal。
     *
     * @param address address 参数。
     * @param ip ip 参数。
     * @return 如果私聊Or Internal满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否块ed Ipv4 Text。
     *
     * @param ip ip 参数。
     * @return 如果块ed Ipv4 Text满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否块ed Ipv4。
     *
     * @param a a 参数。
     * @param b b 参数。
     * @param c c 参数。
     * @param d d 参数。
     * @return 如果块ed Ipv4满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否Trusted私聊Address。
     *
     * @param address address 参数。
     * @return 如果Trusted私聊Address满足条件则返回 true，否则返回 false。
     */
    private boolean isTrustedPrivateAddress(InetAddress address) {
        byte[] rawAddress = address == null ? null : address.getAddress();
        if (rawAddress == null || rawAddress.length != 4) {
            return false;
        }
        int a = rawAddress[0] & 0xff;
        int b = rawAddress[1] & 0xff;
        return a == 198 && (b == 18 || b == 19);
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

    /**
     * 判断是否块ed Ipv6 Address。
     *
     * @param rawAddress 原始Address参数。
     * @return 如果块ed Ipv6 Address满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否块ed Or Always 块ed Ipv4。
     *
     * @param octets octets 参数。
     * @return 如果块ed Or Always 块ed Ipv4满足条件则返回 true，否则返回 false。
     */
    private boolean isBlockedOrAlwaysBlockedIpv4(int[] octets) {
        return octets != null
                && octets.length == 4
                && (isAlwaysBlockedIpv4(octets[0], octets[1], octets[2], octets[3])
                        || isBlockedIpv4(octets[0], octets[1], octets[2], octets[3]));
    }

    /**
     * 解析Ipv4 Host Literal。
     *
     * @param host 主机参数。
     * @return 返回解析后的Ipv4 Host Literal。
     */
    private int[] parseIpv4HostLiteral(String host) {
        int[] obfuscated = parseObfuscatedIpv4(host);
        if (obfuscated != null) {
            return obfuscated;
        }
        String value = StrUtil.nullToEmpty(host).toLowerCase(Locale.ROOT).trim();
        if (value.indexOf(':') >= 0) {
            return null;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!Pattern.compile("^\\d{1,3}$").matcher(parts[i]).matches()) {
                return null;
            }
            try {
                octets[i] = Integer.parseInt(parts[i], 10);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }

    /**
     * 解析Obfuscated Ipv4。
     *
     * @param host 主机参数。
     * @return 返回解析后的Obfuscated Ipv4。
     */
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

    /**
     * 解析Ipv4 Number。
     *
     * @param raw 原始输入值。
     * @param radix radix 参数。
     * @return 返回解析后的Ipv4 Number。
     */
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

    /**
     * 判断是否Number In Radix。
     *
     * @param value 待规范化或校验的原始值。
     * @param radix radix 参数。
     * @return 如果Number In Radix满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 格式化Ipv4。
     *
     * @param octets octets 参数。
     * @return 返回Ipv4结果。
     */
    private String formatIpv4(int[] octets) {
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    /**
     * 执行unsigned短相关逻辑。
     *
     * @param bytes 字节参数。
     * @param offset 分页偏移量。
     * @return 返回unsigned Short结果。
     */
    private int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    /**
     * 判断是否Zero Suffix。
     *
     * @param bytes 字节参数。
     * @param offset 分页偏移量。
     * @param length length 参数。
     * @return 如果Zero Suffix满足条件则返回 true，否则返回 false。
     */
    private boolean isZeroSuffix(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 执行contains相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param value 待规范化或校验的原始值。
     * @return 返回contains结果。
     */
    private boolean contains(String[] values, String value) {
        for (String item : values) {
            if (item.equalsIgnoreCase(StrUtil.nullToEmpty(value))) {
                return true;
            }
        }
        return false;
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

        /**
         * 创建URL Verdict实例，并注入运行所需依赖。
         *
         * @param allowed allowed开关值。
         * @param url 待校验或访问的 URL。
         * @param message 平台消息或错误消息。
         */
        private UrlVerdict(boolean allowed, String url, String message) {
            this.allowed = allowed;
            this.url = url;
            this.message = message;
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
    }

    /** 承载文件判定相关状态和辅助逻辑。 */
    public static class FileVerdict {
        /** 是否启用allowed。 */
        private final boolean allowed;

        /** 记录文件判定中的路径。 */
        private final String path;

        /** 记录文件判定中的消息。 */
        private final String message;

        /**
         * 创建文件Verdict实例，并注入运行所需依赖。
         *
         * @param allowed allowed开关值。
         * @param path 文件或目录路径。
         * @param message 平台消息或错误消息。
         */
        private FileVerdict(boolean allowed, String path, String message) {
            this.allowed = allowed;
            this.path = path;
            this.message = message;
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
