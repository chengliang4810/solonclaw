package com.jimuqu.solon.claw.tool.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** 安全策略规则目录，集中保存路径、凭据、URL 与代理检测所需的静态规则。 */
final class SecurityPolicyRuleCatalog {
    /** 凭据目录片段列表，用于识别常见密钥、云凭据、包管理凭据和工具认证目录。 */
    static final List<String> CREDENTIAL_DIR_SEGMENTS =
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

    /** 凭据文件名称列表，用于阻断直接读取或写入常见密钥、token、认证缓存和配置凭据文件。 */
    static final List<String> CREDENTIAL_FILE_NAMES =
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

    /** 敏感密钥文件扩展名列表，用于识别私钥、证书包和二进制凭据文件。 */
    static final List<String> SENSITIVE_KEY_FILE_EXTENSIONS =
            Arrays.asList(".pem", ".key", ".p12", ".pfx");

    /** 敏感密钥文件标记列表，用于识别带凭据语义的自定义文件名。 */
    static final List<String> SENSITIVE_KEY_FILE_MARKERS =
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

    /** 凭据路径后缀列表，用于拦截运行环境常见认证缓存位置。 */
    static final List<String> CREDENTIAL_PATH_SUFFIXES =
            Arrays.asList(
                    ".claude/.credentials.json",
                    ".codex/auth.json",
                    ".qwen/oauth_creds.json",
                    ".gemini/oauth_creds.json",
                    ".config/gemini/oauth_creds.json",
                    ".config/gcloud/application_default_credentials.json",
                    ".cargo/credentials.toml",
                    ".terraform.d/credentials.tfrc.json");

    /** 工作区凭据文件路径列表，用于拦截项目工作区内的认证缓存文件。 */
    static final List<String> RUNTIME_CREDENTIAL_FILE_PATHS =
            Arrays.asList("cache/bws_cache.json");

    /** 写入拒绝精确路径列表，用于阻断系统关键文件和容器管理套接字写入。 */
    static final List<String> WRITE_DENIED_EXACT_PATHS =
            Arrays.asList(
                    "/etc/hosts",
                    "/etc/resolv.conf",
                    "/etc/sudoers",
                    "/etc/passwd",
                    "/etc/shadow",
                    "/var/run/docker.sock",
                    "/run/docker.sock");

    /** 写入拒绝主目录文件列表，用于阻断 shell 启动脚本被工具链持久化修改。 */
    static final List<String> WRITE_DENIED_HOME_FILE_NAMES =
            Arrays.asList(".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile");

    /** 写入拒绝前缀列表，用于阻断系统二进制、启动项和系统配置目录写入。 */
    static final List<String> WRITE_DENIED_PREFIXES =
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

    /** Windows 写入拒绝前缀列表，用于阻断系统目录和程序目录写入。 */
    static final List<String> WRITE_DENIED_WINDOWS_PREFIXES =
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

    /** 阻断设备路径列表，用于避免工具读取或写入特殊设备造成阻塞、泄露或破坏。 */
    static final List<String> BLOCKED_DEVICE_PATHS =
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

    /** Shell 路径候选正则，用于从命令文本中发现绝对路径、主目录路径和环境变量路径。 */
    static final Pattern SHELL_PATH_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_.-])(~?[/\\\\][^\\s'\"`|;&<>]+|\\$HOME[/\\\\][^\\s'\"`|;&<>]+|\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}[/\\\\][^\\s'\"`|;&<>]+|\\$env:[A-Za-z_][A-Za-z0-9_]*[/\\\\][^\\s'\"`|;&<>]+|%[A-Za-z_][A-Za-z0-9_]*(?:\\([A-Za-z0-9_ -]+\\))?%[/\\\\][^\\s'\"`|;&<>]+|[A-Za-z]:[/\\\\][^\\s'\"`|;&<>]+)",
                    Pattern.CASE_INSENSITIVE);

    /** 带引号的 Windows 路径正则，用于补齐 shell token 解析不稳定的 Windows 路径场景。 */
    static final Pattern QUOTED_WINDOWS_PATH_PATTERN =
            Pattern.compile("([\"'])([A-Za-z]:[\\\\/][^\"'`|;&<>]+)\\1", Pattern.CASE_INSENSITIVE);

    /** Shell 相对凭据路径正则，用于识别不带绝对根路径的凭据目录访问。 */
    static final Pattern SHELL_RELATIVE_CREDENTIAL_PATH_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:\\.\\.?[/\\\\])?(?:(?:[^\\s'\"`|;&<>/\\\\]+)[/\\\\])*(?:\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.docker|\\.azure|\\.claude|\\.codex|\\.qwen|\\.gemini|\\.cargo|\\.terraform\\.d|\\.m2|\\.gem|\\.nuget|\\.config[/\\\\](?:gh|gcloud|gemini|pip))[/\\\\][^\\s'\"`|;&<>]+)(?![A-Za-z0-9_./\\\\-])",
                    Pattern.CASE_INSENSITIVE);

    /** Shell 凭据 token 正则，用于发现命令参数中直接出现的密钥和认证文件名。 */
    static final Pattern SHELL_CREDENTIAL_TOKEN_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_./\\\\-])((?:(?:\\.{1,2}|~|\\$[A-Za-z_][A-Za-z0-9_]*|\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|[A-Za-z0-9_.@=,+-]+)[/\\\\])*(?:(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?)|(?:\\.envrc)|(?:credentials(?:\\.(?:json|toml|tfrc\\.json))?)|(?:auth\\.json)|(?:secret\\.json)|(?:secrets\\.json)|(?:keyring\\.json)|(?:bws_cache\\.json)|(?:\\.netrc)|(?:\\.git-credentials)|(?:\\.pgpass)|(?:\\.npmrc)|(?:\\.yarnrc)|(?:\\.pnpmrc)|(?:\\.curlrc)|(?:\\.wgetrc)|(?:\\.pypirc)|(?:pip\\.conf)|(?:settings\\.xml)|(?:nuget\\.config)|(?:\\.credentials\\.json)|(?:\\.anthropic_oauth\\.json)|(?:oauth_creds\\.json)|(?:client_secrets?\\.json)|(?:application_default_credentials\\.json)|(?:service[_-]account(?:[_-]key)?\\.json)|(?:google-credentials\\.json)|(?:firebase-adminsdk[A-Za-z0-9_.-]*\\.json)|(?:token\\.json)|(?:authorized_keys)|(?:hosts\\.yml)|(?:known_hosts(?:\\.old|2)?)|(?:kubeconfig)|(?:id_(?:dsa|ecdsa(?:_sk)?|rsa(?:_sk)?|ed25519(?:_sk)?))|(?:(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx))))(?![A-Za-z0-9_./\\\\-])",
                    Pattern.CASE_INSENSITIVE);

    /** 工作目录安全正则，用于限制工作目录文本只包含常见安全路径字符。 */
    static final Pattern WORKDIR_SAFE_PATTERN =
            Pattern.compile("^[A-Za-z0-9/\\\\:_\\-.~ +@=,]+$");

    /** proc 标准输入输出文件描述符正则，用于区分可接受的标准流路径和其他 proc 文件描述符。 */
    static final Pattern PROC_STDIO_FD_PATTERN =
            Pattern.compile("^/proc/(?:self|\\d+)/fd/[0-2]$");

    /** 原始块设备正则，用于阻断直接访问磁盘块设备。 */
    static final Pattern RAW_BLOCK_DEVICE_PATTERN =
            Pattern.compile(
                    "^/dev/(?:sd|hd|vd|xvd)[a-z][a-z0-9]*$|^/dev/nvme\\d+n\\d+(?:p\\d+)?$|^/dev/mmcblk\\d+(?:p\\d+)?$");

    /** 凭据路径选项名称列表，用于识别会读取密钥、证书、cookie 或配置文件的命令参数。 */
    static final List<String> CREDENTIAL_PATH_OPTION_NAMES =
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

    /** 紧凑凭据路径选项前缀列表，用于识别短选项和值连写的密钥文件参数。 */
    static final List<String> COMPACT_CREDENTIAL_PATH_OPTION_PREFIXES =
            Arrays.asList("-i", "-F", "-K");

    /** 网络凭据短选项列表，用于识别 curl/httpie 等工具的 cookie、证书和密钥短选项。 */
    static final List<String> NETWORK_CREDENTIAL_SHORT_OPTIONS =
            Arrays.asList("-b", "-c", "-E", "-K");

    /** 网络上传文件选项列表，用于拦截命令把凭据文件作为请求体上传。 */
    static final List<String> NETWORK_UPLOAD_FILE_OPTIONS =
            Arrays.asList(
                    "--upload-file",
                    "--data-binary",
                    "--data-raw",
                    "--data",
                    "-d",
                    "--json",
                    "--post-file",
                    "--body-file");

    /** 网络上传文件短选项列表，用于识别短选项和值连写的上传文件参数。 */
    static final List<String> NETWORK_UPLOAD_FILE_SHORT_OPTIONS = Arrays.asList("-T");

    /** 本地管理套接字路径列表，用于阻断 Docker、containerd、Podman 等本地控制面访问。 */
    static final List<String> LOCAL_MANAGEMENT_SOCKET_PATHS =
            Arrays.asList(
                    "/var/run/docker.sock",
                    "/run/docker.sock",
                    "/run/containerd/containerd.sock",
                    "/run/podman/podman.sock",
                    "/var/run/cri-dockerd.sock",
                    "/var/run/crio/crio.sock");

    /** 本地管理管道路径列表，用于阻断 Windows Docker 命名管道等本地控制面访问。 */
    static final List<String> LOCAL_MANAGEMENT_PIPE_PATHS =
            Arrays.asList(
                    "//./pipe/docker_engine",
                    "\\\\.\\pipe\\docker_engine",
                    "npipe:////./pipe/docker_engine",
                    "npipe://./pipe/docker_engine");

    /** SSH 文件配置选项名称列表，用于识别 -o 中隐含的密钥、证书和 known_hosts 路径。 */
    static final List<String> SSH_FILE_CONFIG_OPTION_NAMES =
            Arrays.asList(
                    "IdentityFile",
                    "CertificateFile",
                    "UserKnownHostsFile",
                    "GlobalKnownHostsFile",
                    "HostKey",
                    "HostCertificate",
                    "HostKeyAlias");

    /** URL 候选正则，用于从结构化参数和命令文本中识别显式或近似 URL。 */
    static final Pattern URLISH_PATTERN =
            Pattern.compile(
                    "(?iu)((?:https?|wss?|s?ftp|scp|gopher|file|dict|ldap|ldaps|tftp)://[^\\s)>'\"]+|(?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,}(?::\\d+)?/[^\\s)>'\"]*|localhost(?::\\d+)?/[^\\s)>'\"]*|(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?/[^\\s)>'\"]*|\\[[0-9a-f:.%]+\\](?::\\d+)?/[^\\s)>'\"]*)");

    /** IPv4 CIDR token 正则，用于避免把网段表达式误判为可访问 URL。 */
    static final Pattern IPV4_CIDR_TOKEN_PATTERN =
            Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$");

    /** IPv6 CIDR token 正则，用于避免把 IPv6 网段表达式误判为可访问 URL。 */
    static final Pattern IPV6_CIDR_TOKEN_PATTERN =
            Pattern.compile(
                    "^\\[[0-9a-fA-F:.%]+\\]/\\d{1,3}$|^[0-9a-fA-F:.%]*:[0-9a-fA-F:.%]*/\\d{1,3}$");

    /** 裸主机名 token 正则，用于识别命令中的本机、内网、元数据和网站规则目标。 */
    static final Pattern BARE_HOST_TOKEN_PATTERN =
            Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}_./:-])((?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}\\p{N}-]+|localhost|(?:0x[0-9a-f]+)|(?:0[0-7]+(?:\\.0[0-7]+){3})|(?:\\d{1,10})(?:\\.\\d{1,3}){0,3}|\\[[0-9a-f:.%]+\\])(?::\\d{1,5})?(?![\\p{L}\\p{N}_./:-])");

    /** 裸主机名抓取上下文正则，用于判断命令是否确实准备发起网络访问。 */
    static final Pattern BARE_HOST_FETCH_CONTEXT_PATTERN =
            Pattern.compile(
                    "(?iu)(?:^|[^\\p{L}\\p{N}_./:-])(?:curl|wget|aria2c|httpie|http|xh|curlie|nc|netcat|ncat|telnet|socat|websocat|grpcurl|openssl\\s+s_client|fetch|axios|httpx|requests\\.(?:get|post|put|delete|patch|head|request)|urllib\\.request\\.urlopen|urlopen|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|Start-BitsTransfer|bitsadmin|certutil|mshta|regsvr32|rundll32|WebClient|WebRequest|HttpWebRequest|RestTemplate|OkHttpClient|HttpURLConnection)\\b");

    /** 直接网络端点前缀正则，用于识别 tcp/udp/tls/connect 等裸端点写法。 */
    static final Pattern DIRECT_NETWORK_ENDPOINT_PREFIX_PATTERN =
            Pattern.compile("(?iu)^(tcp|tcp4|tcp6|udp|udp4|udp6|ssl|tls|connect):(.+)$");

    /** Java 代理选项赋值正则，用于识别通过 JVM 选项设置的外部代理地址。 */
    static final Pattern JAVA_PROXY_OPTIONS_ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?i)(?:^|\\s)(?:JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|MAVEN_OPTS|GRADLE_OPTS)=((?:\"[^\"]*\")|(?:'[^']*')|\\S+)");

    /** PowerShell 代理环境变量赋值正则，用于识别持久或当前会话代理修改。 */
    static final Pattern POWERSHELL_PROXY_ENV_ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?i)(?:\\$env:|Env:)(HTTP_PROXY|HTTPS_PROXY|FTP_PROXY|ALL_PROXY|NO_PROXY|NPM_CONFIG_PROXY|NPM_CONFIG_HTTPS_PROXY|NPM_CONFIG_NO_PROXY|NPM_CONFIG_NOPROXY|YARN_PROXY|YARN_HTTPS_PROXY|YARN_NO_PROXY|YARN_NOPROXY|PNPM_CONFIG_PROXY|PNPM_CONFIG_HTTPS_PROXY|PNPM_CONFIG_NO_PROXY|PNPM_CONFIG_NOPROXY|PIP_PROXY)\\s*=\\s*((?:\"[^\"]*\")|(?:'[^']*')|\\S+)|\\[Environment\\]::SetEnvironmentVariable\\s*\\(\\s*['\"](HTTP_PROXY|HTTPS_PROXY|FTP_PROXY|ALL_PROXY|NO_PROXY|NPM_CONFIG_PROXY|NPM_CONFIG_HTTPS_PROXY|NPM_CONFIG_NO_PROXY|NPM_CONFIG_NOPROXY|YARN_PROXY|YARN_HTTPS_PROXY|YARN_NO_PROXY|YARN_NOPROXY|PNPM_CONFIG_PROXY|PNPM_CONFIG_HTTPS_PROXY|PNPM_CONFIG_NO_PROXY|PNPM_CONFIG_NOPROXY|PIP_PROXY)['\"]\\s*,\\s*((?:\"[^\"]*\")|(?:'[^']*')|[^,)]+)");

    /** PowerShell 注册表代理属性正则，用于定位代理配置命令中的属性名参数。 */
    static final Pattern POWERSHELL_REGISTRY_PROXY_PROPERTY_PATTERN =
            Pattern.compile("(?i)^-(?:Name|PropertyName|Property)$|^-n$");

    /** PowerShell 注册表代理值正则，用于定位代理配置命令中的属性值参数。 */
    static final Pattern POWERSHELL_REGISTRY_PROXY_VALUE_PATTERN =
            Pattern.compile("(?i)^-(?:Value|PropertyValue)$");

    /** PowerShell 本地管理环境变量赋值正则，用于识别 Docker/Podman 控制面环境变量修改。 */
    static final Pattern POWERSHELL_LOCAL_MANAGEMENT_ENV_ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?i)(?:\\$env:|Env:)(DOCKER_HOST|CONTAINER_HOST|PODMAN_HOST)\\s*=\\s*((?:\"[^\"]*\")|(?:'[^']*')|\\S+)|\\[Environment\\]::SetEnvironmentVariable\\s*\\(\\s*['\"](DOCKER_HOST|CONTAINER_HOST|PODMAN_HOST)['\"]\\s*,\\s*((?:\"[^\"]*\")|(?:'[^']*')|[^,)]+)");

    /** 敏感 URL 参数名称列表，用于阻断 token、密钥、签名和授权码通过 URL 外发。 */
    static final List<String> SENSITIVE_URL_PARAMETER_NAMES =
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

    /** 创建安全策略规则目录；该类仅提供静态规则，不允许实例化。 */
    private SecurityPolicyRuleCatalog() {}
}
