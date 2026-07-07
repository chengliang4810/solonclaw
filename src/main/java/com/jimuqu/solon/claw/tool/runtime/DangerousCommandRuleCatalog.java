package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 集中维护危险命令审批的静态规则、终端守护规则和规则采样，避免审批流程类承载大段正则定义。 */
final class DangerousCommandRuleCatalog {
    /** 创建危险命令规则目录实例，禁止外部实例化。 */
    private DangerousCommandRuleCatalog() {}

    /** 路径SEPARATOR的统一常量值。 */
    private static final String PATH_SEPARATOR = "[\\\\/]";

    /** 主渠道路径PREFIX的统一常量值。 */
    private static final String HOME_PATH_PREFIX =
            "(?:~|\\$home|\\$\\{home\\}|\\$env:home|\\$env:userprofile|%userprofile%|%homepath%)";

    /** Agent主渠道路径PREFIX的统一常量值。 */
    private static final String AGENT_HOME_PATH_PREFIX =
            "(?:\\$solonclaw_home|\\$\\{solonclaw_home\\}|\\$env:solonclaw_home|%solonclaw_home%|"
                    + "\\$solonclaw_home|\\$\\{solonclaw_home\\}|\\$env:solonclaw_home|%solonclaw_home%)";

    /** 终端角色配置写入TARGET的统一常量值。 */
    private static final String SHELL_PROFILE_WRITE_TARGET =
            HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.(?:bashrc|zshrc|profile|bash_profile|zprofile)\\b";

    /** SENSITIVE写入TARGET的统一常量值。 */
    private static final String SENSITIVE_WRITE_TARGET =
            "(?:/etc/|/dev/sd|"
                    + HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.ssh(?:"
                    + PATH_SEPARATOR
                    + "|$)|"
                    + HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.(?:bashrc|zshrc|profile|bash_profile|zprofile)\\b|"
                    + HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.(?:netrc|pgpass|npmrc|yarnrc|pnpmrc|pypirc|curlrc|wgetrc)\\b|"
                    + HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.(?:m2|gem|nuget|cargo|terraform\\.d|gemini|config"
                    + PATH_SEPARATOR
                    + "(?:pip|gemini))"
                    + PATH_SEPARATOR
                    + "(?:settings\\.xml|credentials|credentials\\.toml|credentials\\.tfrc\\.json|oauth_creds\\.json|nuget\\.config|pip\\.conf)\\b|"
                    + HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.(?:solonclaw)"
                    + PATH_SEPARATOR
                    + "\\.env\\b|"
                    + AGENT_HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.env\\b)";

    /** PROJECTSENSITIVE写入TARGET的统一常量值。 */
    private static final String PROJECT_SENSITIVE_WRITE_TARGET =
            "(?:(?<![A-Za-z0-9_.-])(?:[/\\\\]|\\.{1,2}[/\\\\])?(?:[^\\s/\\\\\"'`]+[/\\\\])*(?:\\.env(?:\\.[^/\\\\\\s\"'`]+)*|\\.envrc|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|config\\.ya?ml|credentials(?:\\.(?:json|toml|tfrc\\.json))?|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[^/\\\\\\s\"'`]*\\.json|auth\\.json|oauth_creds\\.json|token\\.json|pip\\.conf|settings\\.xml|nuget\\.config))";

    /** PowerShellSENSITIVE写入TARGET的统一常量值。 */
    private static final String POWERSHELL_SENSITIVE_WRITE_TARGET =
            "(?:" + PROJECT_SENSITIVE_WRITE_TARGET + "|" + SENSITIVE_WRITE_TARGET + ")";

    /** 凭据PERMISSIONTARGET的统一常量值。 */
    private static final String CREDENTIAL_PERMISSION_TARGET =
            "(?:(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:(?:[^\\s/\\\\\"'`]+)[/\\\\])*(?:\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.docker|\\.azure|\\.gemini|\\.cargo|\\.terraform\\.d|\\.m2|\\.gem|\\.nuget|\\.config[/\\\\](?:gh|gcloud|gemini|pip))[/\\\\][^\\s\"'`]+|(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?|\\.netrc|\\.git-credentials|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|auth\\.json|oauth_creds\\.json|token\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|id_(?:rsa|ed25519|ecdsa|dsa)(?:_sk)?))";

    /** REMOTE凭据文件TARGET的统一常量值。 */
    private static final String REMOTE_CREDENTIAL_FILE_TARGET =
            "(?:[\"']?(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:(?:[^\\s/\\\\\"'`:=]+)[/\\\\])*(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?|\\.envrc|\\.netrc|\\.git-credentials|\\.pgpass|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|auth\\.json|\\.credentials\\.json|\\.anthropic_oauth\\.json|oauth_creds\\.json|client_secrets?\\.json|token\\.json|application_default_credentials\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[A-Za-z0-9_.-]*\\.json|authorized_keys|kubeconfig|id_(?:rsa|ed25519|ecdsa|dsa)(?:_sk)?|(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx))[\"']?(?:\\s|$|:))";

    /** EXPLICIT凭据文件TARGET的统一常量值。 */
    private static final String EXPLICIT_CREDENTIAL_FILE_TARGET =
            "(?:[\"']?(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:(?:[^\\s/\\\\\"'`:=]+)[/\\\\])*(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?|\\.envrc|\\.netrc|\\.git-credentials|\\.pgpass|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|auth\\.json|\\.credentials\\.json|\\.anthropic_oauth\\.json|oauth_creds\\.json|client_secrets?\\.json|token\\.json|application_default_credentials\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[A-Za-z0-9_.-]*\\.json|authorized_keys|kubeconfig|id_(?:rsa|ed25519|ecdsa|dsa)(?:_sk)?|(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx))[\"']?(?:\\s|$|:))";

    /** 网络凭据文件TARGET的统一常量值。 */
    private static final String NETWORK_CREDENTIAL_FILE_TARGET =
            "(?:\\.env|\\.envrc|\\.netrc|\\.git-credentials|\\.pgpass|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|credential|secret|token(?:\\.json)?|auth\\.json|\\.credentials\\.json|\\.anthropic_oauth\\.json|oauth|oauth_creds\\.json|client_secrets?(?:\\.json)?|application_default_credentials\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[A-Za-z0-9_.-]*\\.json|api-?key|(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx)|id_(?:rsa|ed25519|ecdsa|dsa))";

    /** PowerShell凭据文件BYTEREAD的统一常量值。 */
    private static final String POWERSHELL_CREDENTIAL_FILE_BYTE_READ =
            "\\[(?:IO|System\\.IO)\\.File\\]::ReadAllBytes\\s*\\(\\s*[\"']?\\S*"
                    + NETWORK_CREDENTIAL_FILE_TARGET
                    + "\\S*[\"']?\\s*\\)";

    /** PowerShell凭据文件文本READ的统一常量值。 */
    private static final String POWERSHELL_CREDENTIAL_FILE_TEXT_READ =
            "\\[(?:IO|System\\.IO)\\.File\\]::ReadAll(?:Text|Lines)\\s*\\(\\s*[\"']?\\S*"
                    + NETWORK_CREDENTIAL_FILE_TARGET
                    + "\\S*[\"']?\\s*\\)";

    /** PowerShell凭据文件ENCODE的统一常量值。 */
    private static final String POWERSHELL_CREDENTIAL_FILE_ENCODE =
            "\\[Convert\\]::ToBase64String\\s*\\(\\s*"
                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                    + "\\s*\\)";

    /** DEBUGARTIFACT输出TARGET的统一常量值。 */
    private static final String DEBUG_ARTIFACT_OUTPUT_TARGET =
            "[\"']?(?:[^\\s\"'`|;&]*[/\\\\])?(?:debug|trace|junit|test-results|test_result|coverage|diagnostic|diagnostics|artifact|artifacts)[A-Za-z0-9_.-]*\\.(?:log|txt|xml|json|ndjson|out)[\"']?";

    /** SENSITIVE环境变量名称的统一常量值。 */
    private static final String SENSITIVE_ENV_NAME =
            "(?:[A-Za-z_][A-Za-z0-9_]*(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH)[A-Za-z0-9_]*)";

    /** SENSITIVEHTTPHEADER名称的统一常量值。 */
    private static final String SENSITIVE_HTTP_HEADER_NAME =
            "(?:authorization|proxy[_.-]?authorization|proxyAuthorization|cookie|(?:x[_.-]?)?api[_.-]?(?:key|token)|x?(?:ApiKey|ApiToken)|apikey|(?:x[_.-]?)?access[_.-]?(?:key|token)|x?(?:AccessKey|AccessToken)|x[_.-]?auth[_.-]?token|x?AuthToken|(?:x[_.-]?)?bearer[_.-]?token|x?BearerToken|(?:x[_.-]?)?secret[_.-]?key|x?SecretKey)";

    /** SENSITIVE请求FIELD名称的统一常量值。 */
    private static final String SENSITIVE_REQUEST_FIELD_NAME =
            "(?:access[_.\\s-]?(?:key|token)|access(?:Key|Token)|refresh[_.\\s-]?token|refreshToken|id[_.\\s-]?token|idToken|auth[_.\\s-]?token|authToken|bearer[_.\\s-]?token|bearerToken|session[_.\\s-]?token|sessionToken|api[_.\\s-]?(?:key|token)|api(?:Key|Token)|token|secret|secret[_.\\s-]?key|secretKey|client[_.\\s-]?secret|clientSecret|private[_.\\s-]?key|privateKey|password|passwd|credential|authorization)";

    /** 命令TAIL的统一常量值。 */
    private static final String COMMAND_TAIL = "(?:\\s*(?:(?:&&|\\|\\||;).*)?$|\\s*$)";

    /** BROAD列表ENADDRESS的统一常量值。 */
    private static final String BROAD_LISTEN_ADDRESS = "(?:0\\.0\\.0\\.0|\\[?::\\]?|\\*)";

    /** HARDLINE命令POSITION的统一常量值。 */
    private static final String HARDLINE_COMMAND_POSITION =
            "(?:^|[;&|\\n`]|\\$\\()\\s*(?:(?:sudo|doas|pkexec)\\s+(?:-[^\\s]+\\s+)*|runas\\s+(?:/(?:user|profile|env|netonly|savecred):\\S+\\s+)*)?(?:env\\s+(?:(?:-[^\\s]+|--[^\\s]+|\\w+=\\S*)\\s+)*)?(?:(?:exec|nohup|setsid|time)\\s+)*\\s*";

    /** 终端命令START的统一常量值。 */
    private static final String SHELL_COMMAND_START =
            "(?:^|[;&|\\n`]|\\$\\()\\s*(?:(?:sudo|doas|pkexec)\\s+(?:-[^\\s]+\\s+)*)?";

    /** 元数据 URL 硬阻断规则键，由 URL 安全策略动态检测。 */
    static final String HARDLINE_METADATA_URL_RULE_KEY = "hardline_metadata_url";

    /** 硬阻断策略覆盖的工具列表。 */
    private static final List<String> HARDLINE_COVERED_TOOLS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            ToolNameConstants.EXECUTE_SHELL,
                            ToolNameConstants.EXECUTE_CODE,
                            ToolNameConstants.EXECUTE_PYTHON,
                            ToolNameConstants.EXECUTE_JS));

    /** 硬阻断策略覆盖的危险类别。 */
    private static final List<String> HARDLINE_BLOCKED_CATEGORIES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "root_or_system_recursive_delete",
                            "filesystem_format_or_raw_device_write",
                            "system_shutdown_or_reboot",
                            "kill_all_or_fork_bomb",
                            "windows_disk_or_profile_destruction",
                            "metadata_url_access"));

    /** KUBECTL选项PREFIX的统一常量值。 */
    private static final String KUBECTL_OPTION_PREFIX =
            "(?:\\s+(?:--?[A-Za-z0-9-]+)(?:=\\S+|\\s+\\S+)?)*";

    /** 终端级别BACKGROUND的统一常量值。 */
    static final Pattern SHELL_LEVEL_BACKGROUND = pattern("\\b(?:nohup|disown|setsid)\\b");

    /** 内联BACKGROUNDAMP的统一常量值。 */
    static final Pattern INLINE_BACKGROUND_AMP = pattern("\\s&\\s");

    /** TRAILINGBACKGROUNDAMP的统一常量值。 */
    static final Pattern TRAILING_BACKGROUND_AMP = pattern("\\s&\\s*(?:#.*)?$");

    /** PYTHON终端EXECCALL的统一常量值。 */
    static final Pattern PYTHON_SHELL_EXEC_CALL =
            pattern(
                    "\\b(?:os\\.system|subprocess\\.(?:run|Popen|call|check_call|check_output))\\s*\\(");

    /** 终端护栏摘要键，和前台命令实际检测分支保持一致。 */
    private static final List<String> TERMINAL_GUARDRAIL_KEYS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "shell_level_background",
                            "detached_terminal_session",
                            "powershell_background_job",
                            "inline_background_ampersand",
                            "long_lived_foreground"));

    /** 命令参数KEYS的统一常量值。 */
    static final Set<String> COMMAND_ARGUMENT_KEYS =
            Collections.unmodifiableSet(
                    new LinkedHashSet<String>(
                            Arrays.asList(
                                    "code",
                                    "command",
                                    "commands",
                                    "cmd",
                                    "script",
                                    "shell",
                                    "shell_command")));

    /** LONGLIVED前台进程正则S的统一常量值。 */
    static final List<Pattern> LONG_LIVED_FOREGROUND_PATTERNS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            pattern(
                                    "\\b(?:npm|pnpm|yarn|bun)\\s+(?:run\\s+)?(?:dev|start|serve|watch)\\b"),
                            pattern("\\bdocker\\s+compose\\s+up\\b"),
                            pattern("\\bnext\\s+dev\\b"),
                            pattern("\\bvite(?:\\s|$)"),
                            pattern("\\bnodemon\\b"),
                            pattern("\\buvicorn\\b"),
                            pattern("\\bgunicorn\\b"),
                            pattern("\\bpython(?:3)?\\s+-m\\s+http\\.server\\b")));

    /** RULES的统一常量值。 */
    static final List<DangerRule> RULES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new DangerRule(
                                    "delete_root",
                                    "delete in root path",
                                    pattern("\\brm\\s+(-[^\\s]*\\s+)*\\/"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "recursive_delete",
                                    "recursive delete",
                                    pattern(SHELL_COMMAND_START + "rm\\s+-(?!-)[^\\s]*r"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "recursive_delete_long_flag",
                                    "recursive delete (long flag)",
                                    pattern(SHELL_COMMAND_START + "rm\\s+--recursive\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "find_delete",
                                    "find -delete",
                                    pattern("\\bfind\\b.*-delete\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "find_exec_rm",
                                    "find -exec rm",
                                    pattern("\\bfind\\b.*-exec\\s+(/\\S*/)?rm\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "xargs_rm",
                                    "xargs with rm",
                                    pattern("\\bxargs\\s+.*\\brm\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_permissive_chmod",
                                    "credential file permission widened",
                                    pattern(
                                            "\\bchmod\\s+[^\\n]*(?:777|666|o\\+[rwx]*[rw]|a\\+[rwx]*[rw])\\b[^\\n]*[\"']?"
                                                    + "(?:"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + ")"
                                                    + "[\"']?"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_owner_or_acl_change",
                                    "credential file owner or ACL changed",
                                    pattern(
                                            "\\b(?:chown|chgrp|takeown|icacls)\\b[^\\n]*[\"']?"
                                                    + "(?:"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + ")"
                                                    + "[\"']?"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "world_writable",
                                    "world/other-writable permissions",
                                    pattern(
                                            "\\bchmod\\s+(?!--recursive\\b)(-[^\\s]*\\s+)*(777|666|o\\+[rwx]*w|a\\+[rwx]*w)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "world_writable_long_flag",
                                    "recursive world/other-writable (long flag)",
                                    pattern(
                                            "\\bchmod\\s+--recursive\\b.*(777|666|o\\+[rwx]*w|a\\+[rwx]*w)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "chmod_setuid_setgid",
                                    "setuid/setgid permission change",
                                    pattern(
                                            "\\bchmod\\s+(-[^\\s]*\\s+)*(?:[ug]\\+s|[2467][0-7]{3}(?!\\s+~?[/\\\\.]?\\.?(?:ssh|aws|gnupg|kube|docker|azure)\\b))\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "setcap_privilege",
                                    "Linux capability grant",
                                    pattern("\\bsetcap\\b[^\\n]*\\bcap_[a-z0-9_,+-]+\\+ep\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_acl_permission_widen",
                                    "Linux ACL permission widened",
                                    pattern(
                                            "\\bsetfacl\\b(?=[^\\n]*(?:-m|--modify)\\b)[^\\n]*(?::(?:rwx|rw-|r-x|[rwx-]{3})\\b|:[^\\s,]+:[rwx-]*w[rwx-]*\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_immutable_flag_removed",
                                    "Linux immutable flag removed",
                                    pattern("\\bchattr\\b[^\\n]*-i\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "dynamic_library_preload_injection",
                                    "dynamic library preload injection",
                                    pattern(
                                            "\\b(?:LD_PRELOAD|DYLD_INSERT_LIBRARIES)\\s*=|(?:>|tee\\b|Set-Content\\b|Out-File\\b)[^\\n]*/etc/ld\\.so\\.preload\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "shell_profile_persistence_injection",
                                    "shell profile persistence injection",
                                    pattern(
                                            "(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b)[^\\n]*[\"']?"
                                                    + SHELL_PROFILE_WRITE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "chown_root",
                                    "recursive chown to root",
                                    pattern("\\bchown\\s+(-[^\\s]*)?R\\s+root"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "chown_root_long_flag",
                                    "recursive chown to root (long flag)",
                                    pattern("\\bchown\\s+--recursive\\b.*root"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "mkfs",
                                    "format filesystem",
                                    pattern("\\bmkfs\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "dd_disk",
                                    "disk copy",
                                    pattern("\\bdd\\s+.*if="),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hosts_file_tampering",
                                    "hosts file tampering",
                                    pattern(
                                            "(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b)[^\\n]*(?:/etc/hosts\\b|/private/etc/hosts\\b|[A-Za-z]:\\\\Windows\\\\System32\\\\drivers\\\\etc\\\\hosts\\b|\\$env:windir\\\\System32\\\\drivers\\\\etc\\\\hosts\\b|%windir%\\\\System32\\\\drivers\\\\etc\\\\hosts\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "dns_resolver_tampering",
                                    "DNS resolver configuration changed",
                                    pattern(
                                            "(?:(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b)[^\\n]*/etc/resolv\\.conf\\b|\\bnmcli\\s+connection\\s+modify\\b[^\\n]*\\bipv[46]\\.dns\\b|\\bnetworksetup\\s+-setdnsservers\\b|\\bSet-DnsClientServerAddress\\b|\\bnetsh\\s+interface\\s+ip\\s+set\\s+dns\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "network_route_or_portproxy_change",
                                    "network route or port proxy changed",
                                    pattern(
                                            "\\b(?:ip\\s+route\\s+(?:add|replace|del|delete)|route\\s+(?:add|delete|del)|netsh\\s+interface\\s+portproxy\\s+(?:add|delete|del|reset)|(?:New|Set|Remove)-NetRoute\\b|(?:New|Set|Remove)-NetNat\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "persistent_proxy_configuration_change",
                                    "persistent proxy configuration changed",
                                    pattern(
                                            "(?:\\bgit\\s+config\\s+(?:(?:--global|--system|--local|--worktree|--add|--replace-all|--fixed-value)\\s+)*(?:http|https)\\.(?:proxy|noProxy|noproxy)(?:=\\S+|\\s+\\S+)|\\b(?:npm|pnpm|yarn|yarnpkg)\\s+config\\s+set\\s+(?:proxy|https-proxy|httpsProxy|no-proxy|noProxy|noproxy)(?:=\\S+|\\s+\\S+)|\\bpip3?\\s+config\\s+set\\s+global\\.(?:proxy|no-proxy|no_proxy|noproxy)(?:=\\S+|\\s+\\S+)|\\bnetsh\\s+winhttp\\s+set\\s+proxy\\b|\\bnetworksetup\\s+-set(?:web|secureweb|socksfirewall)proxy\\b|\\bsetx\\s+(?:https?_proxy|all_proxy|no_proxy|HTTPS?_PROXY|ALL_PROXY|NO_PROXY)\\s+\\S+|\\bSet-ItemProperty\\b[^\\n]*\\\\Internet Settings[^\\n]*(?:ProxyEnable|ProxyServer|ProxyOverride))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sudoers_policy_change",
                                    "sudoers or privilege policy changed",
                                    pattern(
                                            "(?:(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b)[^\\n]*(?:/etc/sudoers\\b|/etc/sudoers\\.d/|/etc/doas\\.conf\\b)|\\bvisudo\\b|\\b(?:install|cp|mv)\\b[^\\n]*(?:/etc/sudoers\\b|/etc/sudoers\\.d/|/etc/doas\\.conf\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "service_persistence_registration",
                                    "service persistence registration",
                                    pattern(
                                            "(?:(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b|\\b(?:install|cp|mv)\\b)[^\\n]*(?:/etc/systemd/system/|/usr/lib/systemd/system/|/Library/Launch(?:Agents|Daemons)/|~/Library/LaunchAgents/)[^\\s\"'`]*\\.(?:service|timer|plist)\\b|\\bsystemctl\\s+(?:-[^\\s]+\\s+)*(?:enable|reenable|preset|preset-all|link)\\b|\\blaunchctl\\s+(?:bootstrap|load)\\b|\\bupdate-rc\\.d\\s+\\S+\\s+(?:defaults|enable)\\b|\\bchkconfig\\s+\\S+\\s+on\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "git_hook_persistence_change",
                                    "Git hook persistence changed",
                                    pattern(
                                            "(?:(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b|\\b(?:install|cp|mv|chmod)\\b)[^\\n]*(?:(?:^|[/\\\\])\\.git|\\.git)[/\\\\]hooks[/\\\\][^\\s\"'`]+|\\bgit\\s+config\\s+(?:--global\\s+)?core\\.hooksPath\\s+\\S+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_kernel_policy_change",
                                    "Linux kernel or kernel module policy changed",
                                    pattern(
                                            "\\b(?:modprobe|insmod|rmmod)\\b|\\bsysctl\\s+(?:-w\\s+|--write\\s+)[A-Za-z0-9_.]+\\s*=|(?:>>?|\\btee\\b(?:\\s+-a)?)\\s*[^\\n]*(?:/etc/sysctl\\.conf\\b|/etc/sysctl\\.d/[^\\s\"'`]+\\.conf\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "filesystem_mount_policy_change",
                                    "filesystem mount policy changed",
                                    pattern(
                                            "\\bmount\\b(?=[^\\n]*(?:\\s-o\\s+[^\\n]*(?:remount|rw)|--options\\s+[^\\n]*(?:remount|rw)|\\s(?:/dev/[A-Za-z0-9_.-]+|/sys|/proc|/boot|/)\\b))|\\bumount\\b(?=[^\\n]*(?:\\s/(?:boot|etc|var|usr|sys|proc)\\b|\\s/dev/[A-Za-z0-9_.-]+\\b))|(?:>>?|\\btee\\b(?:\\s+-a)?)\\s*[^\\n]*/etc/fstab\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "overwrite_etc",
                                    "overwrite system config",
                                    pattern("(>|tee\\b).*?/etc/"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "write_block_device",
                                    "write to block device",
                                    pattern(">\\s*[\"']?/dev/sd"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_script_shell_substitution",
                                    "execute remote content through shell command substitution",
                                    pattern(
                                            "\\b(?:bash|sh|zsh|ksh)\\s+-[^\\s]*c\\s+['\"]?\\$\\([^\\n)]*\\b(?:curl|wget)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "shell_command_flag",
                                    "shell command via -c/-lc flag",
                                    pattern("\\b(bash|sh|zsh|ksh)\\s+-[^\\s]*c(\\s+|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "script_eval_flag",
                                    "script execution via -e/-c flag",
                                    pattern(
                                            "\\b(python[23]?|perl|ruby|node)\\s+-[ec](?:\\s+|(?=['\"]))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_script_process_substitution",
                                    "execute remote script via process substitution",
                                    pattern(
                                            "\\b(bash|sh|zsh|ksh)\\s+<\\s*<?\\s*\\(\\s*(curl|wget)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "encoded_payload_execute",
                                    "decode encoded payload, then execute it",
                                    pattern(
                                            "\\b(?:(?:base64\\s+(?:-[^\\s]*d[^\\s]*|--decode)\\b|openssl\\s+enc\\s+-[A-Za-z0-9-]*d[A-Za-z0-9-]*\\b|certutil(?:\\.exe)?\\s+-decode\\b)[^\\n]*(?:>|-out\\s+|\\s+)[^\\s;&|]+|FromBase64String\\s*\\([^\\n]*\\)[^\\n]*(?:Set-Content|Out-File))"
                                                    + "[^\\n]*(?:&&|;|\\|\\|)[^\\n]*(?:(?:bash|sh|zsh|ksh|fish|pwsh|powershell(?:\\.exe)?|python[23]?|perl|ruby|node)\\s+[^\\s;&|]+|(?:chmod\\s+\\+x\\s+[^\\s;&|]+\\s*(?:&&|;|\\|\\|)\\s*)?(?:\\./|/|[A-Za-z]:[\\\\/])[^\\s;&|]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "ssh_config_trust_weaken",
                                    "SSH config trust weakened",
                                    pattern(
                                            "(?=.*(?:StrictHostKeyChecking\\s+(?:no|off|false|accept-new)|UserKnownHostsFile\\s+(?:/dev/null|NUL|nul)|ProxyCommand\\s+\\S))(?=.*(?:>>?|\\btee\\b(?:\\s+-a)?|\\b(?:Set-Content|Add-Content|Out-File)\\b)[^\\n]*(?:~|\\$HOME|\\$env:HOME|%USERPROFILE%|\\.{1,2})[/\\\\]\\.ssh[/\\\\]config\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_tee",
                                    "overwrite system file via tee",
                                    pattern(
                                            "\\b(?:tee|Tee-Object)\\b[^\\n]*(?:-(?:FilePath|Path|LiteralPath)\\b\\s*(?::|=|\\s+)\\s*)?[\"']?"
                                                    + SENSITIVE_WRITE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_redirection",
                                    "overwrite system file via redirection",
                                    pattern(
                                            "(?:&>>?|(?:\\d|\\*)?>>?)\\s*[\"']?"
                                                    + SENSITIVE_WRITE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "project_sensitive_tee",
                                    "overwrite project env/config via tee",
                                    pattern(
                                            "\\b(?:tee|Tee-Object)\\b[^\\n]*(?:-(?:FilePath|Path|LiteralPath)\\b\\s*(?::|=|\\s+)\\s*)?[\"']?"
                                                    + PROJECT_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "project_sensitive_redirection",
                                    "overwrite project env/config via redirection",
                                    pattern(
                                            "(?:&>>?|(?:\\d|\\*)?>>?)\\s*[\"']?"
                                                    + PROJECT_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "environment_dump",
                                    "dump environment variables to terminal output",
                                    pattern(
                                            "(?:^|[;&|\\n`])\\s*(?:(?:cmd(?:\\.exe)?\\s+/c\\s+)?set\\s*(?:$|[|>&;])|(?:env|printenv)\\s*(?:$|[|>&;])|(?:Get-ChildItem|gci|dir|ls)\\s+Env:|Get-Item\\s+Env:\\*)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_clipboard_export",
                                    "copy sensitive environment values to clipboard",
                                    pattern(
                                            "(?:\\b(?:echo|printf|printenv)\\b[^\\n|;&]*?(?:\\$\\{?|%|!)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\}|%|!)?[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy)\\b|\\b(?:Write-Host|Write-Output)\\b[^\\n|;&]*(?:\\$env:|\\$\\{env:)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\})?[^\\n|;&]*\\|\\s*(?:clip(?:\\.exe)?|Set-Clipboard|scb)\\b|\\bprintenv\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy)\\b|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?)(?:\\$env:|%)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "%?|\\b(?:Set-Clipboard|scb)\\b[^\\n]*\\$\\{env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\}|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?\\(?\\s*(?:Get-Item|Get-Content|gi|gc)\\s+Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\s*\\))?|(?:\\$env:|\\$\\{env:|\\[Environment\\]::GetEnvironmentVariable\\(\\s*['\"]?|\\b(?:Get-Item|Get-Content|gi|gc)\\s+Env:)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:['\"]?\\)|\\})?[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy|Set-Clipboard|scb)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_file_clipboard_export",
                                    "copy credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy|Set-Clipboard|scb)\\b|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:-(?:Path|LiteralPath)\\b\\s*(?::|=|\\s+)\\s*)"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?\\(?\\s*(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "|\\(\\s*(?:cat|type|Get-Content|gc)\\b[^\\n|;&)]*"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "[^\\n|;&)]*\\)\\s*\\|\\s*(?:clip(?:\\.exe)?|Set-Clipboard|scb)\\b|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:clip(?:\\.exe)?|Set-Clipboard|scb)\\b|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|"
                                                    + "(?:^|[;&|\\n`])\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:clip(?:\\.exe)?|Set-Clipboard|scb)\\b|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_encoded_clipboard_export",
                                    "copy encoded credential file content to clipboard",
                                    pattern(
                                            "(?:(?:\\bbase64\\b(?!(?:[^\\n|;&]*\\s(?:-[^\\s]*d[^\\s]*|--decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:base64|enc\\b(?=[^\\n]*-base64\\b)(?![^\\n]*\\s-(?:d|decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-encode\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:\\[Convert\\]::ToBase64String|ConvertTo-SecureString\\b)|"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy|Set-Clipboard|scb)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_encoded_network_send",
                                    "send encoded credential file content through network command",
                                    pattern(
                                            "(?:(?:\\bbase64\\b(?!(?:[^\\n|;&]*\\s(?:-[^\\s]*d[^\\s]*|--decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:base64|enc\\b(?=[^\\n]*-base64\\b)(?![^\\n]*\\s-(?:d|decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-encode\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:\\[Convert\\]::ToBase64String|ConvertTo-SecureString\\b)|"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")[^\\n|;&]*\\|\\s*(?:(?:curl|wget)\\b[^\\n]*(?:--data(?:-[a-z-]+)?|-d|--post-data|--body-file|--method\\s+POST|-X\\s+POST)\\b|(?:httpie|https?|xh|curlie)\\b[^\\n]*(?:POST|PUT|PATCH|@-)|(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm)\\b[^\\n]*(?:-(?:Body|Method)\\b|Post|Put|Patch))|(?:New-Object\\s+Net\\.WebClient|\\[Net\\.WebClient\\]::new\\s*\\(\\s*\\)|\\[System\\.Net\\.WebClient\\]::new\\s*\\(\\s*\\))[^\\n]*\\.Upload(?:Data|String)\\s*\\([^\\n]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_encoded_debug_artifact_write",
                                    "write encoded credential file content into debug artifact",
                                    pattern(
                                            "(?:(?:\\bbase64\\b(?!(?:[^\\n|;&]*\\s(?:-[^\\s]*d[^\\s]*|--decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:base64|enc\\b(?=[^\\n]*-base64\\b)(?![^\\n]*\\s-(?:d|decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-encode\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:\\[Convert\\]::ToBase64String|ConvertTo-SecureString\\b)|"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")[^\\n|;&]*(?:>+|\\|\\s*(?:tee|Out-File|Set-Content|Add-Content|Tee-Object)\\b[^\\n|;&]*(?:-(?:FilePath|Path|LiteralPath)\\b\\s*(?::|=|\\s+)\\s*)?)\\s*"
                                                    + DEBUG_ARTIFACT_OUTPUT_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-encode\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\s+"
                                                    + DEBUG_ARTIFACT_OUTPUT_TARGET
                                                    + "|\\bopenssl\\s+(?:base64|enc\\b(?=[^\\n]*-base64\\b)(?![^\\n]*\\s-(?:d|decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*(?:-out\\s+|>+\\s*)"
                                                    + DEBUG_ARTIFACT_OUTPUT_TARGET
                                                    + "|\\[(?:IO|System\\.IO)\\.File\\]::WriteAll(?:Text|Bytes)\\s*\\(\\s*"
                                                    + DEBUG_ARTIFACT_OUTPUT_TARGET
                                                    + "[^\\n,]*,\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + "|\\b(?:Set-Content|Add-Content|Out-File|Tee-Object)\\b[^\\n|;&]*"
                                                    + DEBUG_ARTIFACT_OUTPUT_TARGET
                                                    + "[^\\n|;&]*(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?\\(?\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_encoded_notification_output",
                                    "show encoded credential file content in notification",
                                    pattern(
                                            "(?:(?:\\bbase64\\b(?!(?:[^\\n|;&]*\\s(?:-[^\\s]*d[^\\s]*|--decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:base64|enc\\b(?=[^\\n]*-base64\\b)(?![^\\n]*\\s-(?:d|decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-encode\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:\\[Convert\\]::ToBase64String|ConvertTo-SecureString\\b)|"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")[^\\n|;&]*\\|\\s*(?:notify-send|terminal-notifier|osascript\\b[^\\n|;&]*(?:display\\s+notification|display\\s+alert)|New-BurntToastNotification|New-BTNotification)\\b|\\b(?:notify-send|terminal-notifier|osascript\\b[^\\n|;&]*(?:display\\s+notification|display\\s+alert)|New-BurntToastNotification|New-BTNotification)\\b[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_encoded_output",
                                    "encode credential file content",
                                    pattern(
                                            "(?:\\bbase64\\b(?!(?:[^\\n]*\\s(?:-[^\\s]*d[^\\s]*|--decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:base64|enc\\b(?=[^\\n]*-base64\\b)(?![^\\n]*\\s-(?:d|decode)\\b))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-encode\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:\\[Convert\\]::ToBase64String|ConvertTo-SecureString\\b)|"
                                                    + POWERSHELL_CREDENTIAL_FILE_ENCODE
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_hash_output",
                                    "hash credential file content",
                                    pattern(
                                            "(?:\\b(?:sha(?:1|224|256|384|512)?sum|md5sum|b2sum|cksum|shasum)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:dgst|sha(?:1|224|256|384|512)|md5)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:rsa|pkey|pkcs8|pkcs12)\\b(?=[^\\n]*(?:\\s-(?:in|inkey)\\s+|\\s-(?:in|inkey)=)\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")(?=[^\\n]*(?:\\s-text\\b|\\s-noout\\b|\\s-info\\b))"
                                                    + "|\\bssh-keygen\\s+-l[fE]*\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-hashfile\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bGet-FileHash\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:Get-Item|Get-ChildItem|gi|gci|ls|dir)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*Get-FileHash\\b"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*Get-FileHash\\b"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_binary_dump",
                                    "dump credential file bytes",
                                    pattern(
                                            "(?:\\b(?:strings|xxd|hexdump|od)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bFormat-Hex\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*Format-Hex\\b"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*Format-Hex\\b"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_visual_encode",
                                    "encode credential file into image",
                                    pattern(
                                            "(?:\\bqrencode\\b[^\\n|;&]*(?:\\s-r\\s+|\\s--read-from=|\\s--read-from\\s+|<\\s*)\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:qrencode|magick|convert)\\b|\\b(?:magick|convert)\\b[^\\n|;&]*label:@\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:qrencode|magick|convert)\\b"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_environment_load",
                                    "load credential file into command environment",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:source|\\.)\\s+"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|(?:^|[;&|\\n`])\\s*(?:dotenv|dotenvx|env-cmd)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|(?:^|[;&|\\n`])\\s*direnv\\s+(?:allow|exec|export)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_environment_inline_assignment",
                                    "set sensitive environment variable inline with a command",
                                    pattern(
                                            "(?:^|[;&|\\n`])\\s*(?:(?:env\\s+)?"
                                                    + SENSITIVE_ENV_NAME
                                                    + "=\\S+\\s+(?!(?:psql|mysql|redis-cli)\\b)\\S+|\\$env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s*=\\s*\\S+|(?:Set-Item|New-Item|si|ni)\\s+Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s+\\S+|(?:export|declare\\s+-x|typeset\\s+-x)\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "=\\S+|(?:cmd(?:\\.exe)?\\s+/c\\s+)?set\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "=\\S+|(?:Set-Item|New-Item|Set-Content|si|ni|sc)\\s+(?:-[A-Za-z]+\\s+)*Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s+\\S+|(?:Set-Item|New-Item|Set-Content|si|ni|sc)\\b(?=[^\\n|;&]*(?:-(?:Path|Name)\\s+Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "|Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "))(?=[^\\n|;&]*-Value\\s+\\S+)[^\\n|;&]*"
                                                    + "|(?:Remove-Item|Clear-Item|ri|del|erase|clear)\\s+(?:-[A-Za-z]+\\s+)*Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "|setx\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s+\\S+|\\[(?:System\\.)?Environment\\]::SetEnvironmentVariable\\(\\s*['\"]?"
                                                    + SENSITIVE_ENV_NAME
                                                    + "['\"]?\\s*,\\s*[^,)]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_environment_http_header_send",
                                    "send sensitive environment variable through HTTP header",
                                    pattern(
                                            "(?:"
                                                    + SHELL_COMMAND_START
                                                    + "(?:curl|wget)\\b[^\\n]*(?:(?:-H\\s*|--header\\s*(?:=\\s*)?|--proxy-header\\s*(?:=\\s*)?)[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:\\s*[^\\n'\"|;&]*(?:\\$\\{?|\\$env:|%|!)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\}|%|!)?|(?:--header=|--proxy-header=)[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:\\s*[^\\n'\"|;&]*(?:\\$\\{?|\\$env:|%|!)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\}|%|!)?)|"
                                                    + SHELL_COMMAND_START
                                                    + "(?:httpie|https?|xh|curlie)\\b[^\\n]*\\s[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:(?!=)\\s*['\"]?[^\\n'\"|;&]*(?:\\$\\{?|\\$env:|%|!)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\}|%|!)?|"
                                                    + SHELL_COMMAND_START
                                                    + "(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm)\\b[^\\n]*(?:-Headers?\\b\\s*(?::|=|\\s+)\\s*@\\{[^\\n}]*[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*[\"']?\\s*=\\s*['\"]?[^\\n'\";}]*(?:\\$env:|\\$\\{env:|\\$\\{?|%|!)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:\\}|%|!)?))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_environment_read",
                                    "print sensitive environment variable",
                                    pattern(
                                            "(?:\\bprintenv\\s+|\\becho\\s+\\$\\{?|\\becho\\s+%|\\becho\\s+!|\\b(?:Write-Host|Write-Output|Write-Warning|Write-Error|Write-Information|Write-Verbose)\\b[^\\n|;&]*(?:\\$env:|\\$\\{env:)|\\bprintf\\b[^\\n|;&]*(?:\\$\\{?|!)|\\b(?:Get-Item|Get-Content|Get-ChildItem|gi|gc|gci|dir|ls)\\s+(?:-[A-Za-z]+\\s+)*Env:|\\$\\{env:|\\$env:|%|\\[Environment\\]::GetEnvironmentVariable\\(\\s*['\"]?)(?:"
                                                    + SENSITIVE_ENV_NAME
                                                    + ")(?:%|\\}|!)?"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_credential_material_dump",
                                    "Linux credential or process memory material dumped",
                                    pattern(
                                            "\\b(?:gcore\\b[^\\n]*\\b\\d+\\b|coredumpctl\\s+(?:dump|debug)\\b|dd\\b[^\\n]*\\bif=[\"']?/proc/(?:self|\\d+)/mem\\b|cat\\s+[^\\n]*/proc/(?:self|\\d+)/mem\\b|unshadow\\s+/etc/passwd\\s+/etc/shadow\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cli_access_token_read",
                                    "print CLI access token",
                                    pattern(
                                            "\\b(?:gcloud\\s+auth\\s+(?:application-default\\s+)?print-(?:access|identity)-token|az\\s+(?:account\\s+get-access-token|acr\\s+login\\b(?=[^\\n]*--expose-token\\b))|gh\\s+auth\\s+token|aws\\s+(?:ecr\\s+get-login-password|codeartifact\\s+get-authorization-token|sts\\s+(?:get-session-token|get-federation-token|assume-role(?:-with-(?:web-identity|saml))?)|sso\\s+get-role-credentials|configure\\s+export-credentials)|kubectl"
                                                    + KUBECTL_OPTION_PREFIX
                                                    + "\\s+create\\s+token\\b|vault\\s+token\\s+lookup\\b|doctl\\s+auth\\s+list\\b|flyctl\\s+auth\\s+token\\b|heroku\\s+auth:token\\b|aliyun\\s+configure\\s+(?:get|export)\\b|(?:tccli|qcloud)\\s+configure\\s+list\\b|huaweicloud\\s+configure\\s+show\\b|ossutil\\s+config\\s+(?:get|show)\\b(?=[^\\n]*(?:accessKeySecret|stsToken|secret|token)\\b)|coscli\\s+config\\s+show\\b(?=[^\\n]*(?:--secret|secret|token)\\b)|obsutil\\s+config\\s+(?:get|show)\\b(?=[^\\n]*(?:secret_key|security_token|sk|token)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kubernetes_credential_file_read",
                                    "read Kubernetes credential configuration",
                                    pattern(
                                            "\\bkubectl"
                                                    + KUBECTL_OPTION_PREFIX
                                                    + "\\s+config\\s+view\\b(?=[^\\n]*--raw\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cloud_cli_credential_file_read",
                                    "read cloud CLI credential configuration",
                                    pattern(
                                            "\\b(?:aws\\s+configure\\s+get\\s+(?:(?:profile\\.[A-Za-z0-9_.-]+\\.)?(?:aws_secret_access_key|aws_session_token|credential_process|sso_start_url|sso_role_name|sso_account_id))\\b|gcloud\\s+config\\s+get(?:-value)?\\s+(?:auth/credential_file_override|account)\\b|az\\s+account\\s+show\\b(?=[^\\n]*--query\\s+[^\\n]*(?:accessToken|refreshToken|password|secret|credential|tenantId)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "secret_store_read",
                                    "read secret manager value",
                                    pattern(
                                            "\\b(?:aws\\s+(?:secretsmanager\\s+get-secret-value|ssm\\s+get-parameters?\\b(?=[^\\n]*--with-decryption\\b))|gcloud\\s+secrets\\s+versions\\s+access|az\\s+keyvault\\s+secret\\s+show|aliyun\\s+kms\\s+GetSecretValue\\b|(?:tccli|qcloud)\\s+ssm\\s+(?:GetSecretValue|DescribeSecret)\\b|huaweicloud\\s+csms\\s+ShowSecretValue\\b|kubectl\\s+(?:-[^\\s]+\\s+)*(?:get|describe)\\s+secret\\b|(?:docker|podman|nerdctl)\\s+secret\\s+(?:inspect|ls|list)\\b|(?:docker\\s+compose|docker-compose|podman\\s+compose)\\s+config\\b(?=[^\\n]*(?:--environment\\b|--hash\\s+\\S*(?:secret|credential|token)|\\b(?:secret|credential|token|password)\\b))|vault\\s+(?:kv\\s+get|read)\\b|op\\s+(?:read\\s+op://|item\\s+get\\b(?=[^\\n]*(?:--fields?\\s+\\S*(?:password|passwd|secret|token|credential)|--fields?=\\S*(?:password|passwd|secret|token|credential)|--format\\s+json\\b|--format=json\\b|--otp\\b|--reveal\\b))|account\\s+export\\b|document\\s+get\\b(?=[^\\n]*(?:Emergency Kit|Secret Key)))|bw\\s+(?:get\\s+(?:password|item|notes|attachment|totp)\\b|export\\b)|(?:pass|gopass)\\s+(?:show\\s+)?(?!(?:git|ls|list|search|find|grep|init|insert|edit|rm|remove|delete|mv|cp|generate)\\b)[^\\s-][^\\n]*|secret-tool\\s+lookup\\b|gh\\s+secret\\s+(?:list|view)\\b|vercel\\s+env\\s+(?:ls|pull)\\b|netlify\\s+env\\s+(?:list|get)\\b|doppler\\s+secrets\\s+(?:get|download)\\b|fly(?:ctl)?\\s+secrets\\s+list\\b|wrangler\\s+secret\\s+list\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "encrypted_secret_file_decrypt",
                                    "decrypt encrypted secret file",
                                    pattern(
                                            "\\b(?:sops\\s+(?:-d|--decrypt)\\b|ansible-vault\\s+(?:view|decrypt)\\b|gpg(?:2)?\\s+(?:--decrypt|-d)\\b|age\\s+(?:--decrypt|-d)\\b|aws\\s+kms\\s+decrypt\\b|gcloud\\s+kms\\s+decrypt\\b|az\\s+keyvault\\s+key\\s+decrypt\\b|vault\\s+write\\s+transit/decrypt/\\S+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "secret_store_write",
                                    "write secret manager value",
                                    pattern(
                                            "\\b(?:aws\\s+secretsmanager\\s+(?:put-secret-value|create-secret|update-secret)|gcloud\\s+secrets\\s+versions\\s+add|az\\s+keyvault\\s+secret\\s+set|(?:docker|podman|nerdctl)\\s+secret\\s+create\\b|kubectl"
                                                    + KUBECTL_OPTION_PREFIX
                                                    + "\\s+(?:create\\s+secret|(?:patch|replace)\\s+secret|apply\\b[^\\n]*(?:\\s-f\\s+\\S*(?:secret|credential|token)\\S*|--filename(?:=|\\s+)\\S*(?:secret|credential|token)\\S*))\\b|aliyun\\s+kms\\s+(?:CreateSecret|PutSecretValue|UpdateSecret)\\b|(?:tccli|qcloud)\\s+ssm\\s+(?:CreateSecret|PutSecretValue|UpdateSecret)\\b|huaweicloud\\s+csms\\s+(?:CreateSecret|PutSecretValue|UpdateSecret)\\b|vault\\s+kv\\s+(?:put|patch)\\b|op\\s+(?:item|document)\\s+(?:create|edit)\\b|bw\\s+(?:create|edit)\\s+(?:item|attachment)\\b|(?:pass|gopass)\\s+(?:insert|edit|generate)\\b|secret-tool\\s+store\\b|gh\\s+secret\\s+set\\b|vercel\\s+env\\s+(?:add|import)\\b|netlify\\s+env\\s+(?:set|import|clone)\\b|doppler\\s+secrets\\s+(?:set|upload)\\b|fly(?:ctl)?\\s+secrets\\s+set\\b|wrangler\\s+secret\\s+put\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "secret_store_destroy",
                                    "delete or destroy secret manager value",
                                    pattern(
                                            "\\b(?:aws\\s+secretsmanager\\s+delete-secret|gcloud\\s+secrets\\s+(?:delete|versions\\s+destroy)\\b|az\\s+keyvault\\s+secret\\s+(?:delete|purge)\\b|aliyun\\s+kms\\s+DeleteSecret\\b|(?:tccli|qcloud)\\s+ssm\\s+DeleteSecret\\b|huaweicloud\\s+csms\\s+DeleteSecret\\b|(?:docker|podman|nerdctl)\\s+secret\\s+(?:rm|delete)\\b|kubectl"
                                                    + KUBECTL_OPTION_PREFIX
                                                    + "\\s+delete\\s+secret\\b|vault\\s+kv\\s+(?:delete|destroy|metadata\\s+delete)\\b|op\\s+(?:item|document)\\s+delete\\b|bw\\s+delete\\s+(?:item|attachment)\\b|(?:pass|gopass)\\s+(?:rm|remove|delete)\\b|secret-tool\\s+clear\\b|gh\\s+secret\\s+(?:delete|remove)\\b|vercel\\s+env\\s+(?:rm|remove)\\b|netlify\\s+env\\s+(?:unset|delete)\\b|doppler\\s+secrets\\s+(?:delete|unset)\\b|fly(?:ctl)?\\s+secrets\\s+unset\\b|wrangler\\s+secret\\s+delete\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cloud_cli_credential_config_change",
                                    "cloud CLI credential configuration changed",
                                    pattern(
                                            "\\b(?:aws\\s+configure\\s+set\\s+(?:(?:profile\\.[A-Za-z0-9_.-]+\\.)?(?:aws_access_key_id|aws_secret_access_key|aws_session_token|sso_start_url|credential_process))\\b|gcloud\\s+auth\\s+login\\b(?=[^\\n]*--cred-file\\b)|gcloud\\s+config\\s+set\\s+(?:auth/credential_file_override|account)\\b|az\\s+ad\\s+app\\s+credential\\s+reset\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "domestic_cloud_cli_credential_config_change",
                                    "domestic cloud CLI credential configuration changed",
                                    pattern(
                                            "\\b(?:aliyun\\s+configure\\s+set\\b(?=[^\\n]*(?:--access-key-id|--access-key-secret|--sts-token)\\b)|(?:tccli|qcloud)\\s+configure\\s+set\\b(?=[^\\n]*(?:secretId|secretKey|token)\\b)|huaweicloud\\s+configure\\s+set\\b(?=[^\\n]*(?:access_key|secret_key|security_token)\\b)|ossutil\\s+config\\b(?=[^\\n]*(?:accessKeyID|accessKeySecret|stsToken|--access-key-id|--access-key-secret|--sts-token)\\b)|coscli\\s+config\\s+(?:add|set)\\b(?=[^\\n]*(?:secret_id|secret_key|token|SecretId|SecretKey)\\b)|obsutil\\s+config\\b(?=[^\\n]*(?:access_key|secret_key|security_token|ak|sk)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "macos_keychain_password_read",
                                    "macOS keychain password read",
                                    pattern(
                                            "\\bsecurity\\s+(?:find-(?:generic|internet)-password\\b(?=[^\\n]*(?:\\s-w\\b|\\s-g\\b|--password\\b))|dump-keychain\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "macos_keychain_password_change",
                                    "macOS keychain password changed",
                                    pattern(
                                            "\\bsecurity\\s+(?:(?:add|delete)-(?:generic|internet)-password\\b|unlock-keychain\\b(?=[^\\n]*(?:\\s-p\\b|\\s-password\\b|--password\\b))|set-keychain-settings\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "ssh_add_private_key",
                                    "load SSH private key into agent",
                                    pattern(
                                            "(?:\\bssh-add\\b(?:(?=[^\\n]*(?:~|\\$HOME|\\$env:HOME|%USERPROFILE%|\\.{1,2})[/\\\\]\\.ssh[/\\\\][^\\s\"'`]*(?:id_(?:rsa|ed25519|ecdsa|dsa)(?:_sk)?|\\.pem)\\b)(?![^\\n]*\\s-[lLdD]\\b)|(?=[^\\n]*\\s-\\s*(?:<<<|<|$))(?=[^\\n]*(?:SSH_PRIVATE_KEY|PRIVATE_KEY|BEGIN\\s+(?:OPENSSH|RSA|EC|DSA)\\s+PRIVATE\\s+KEY|id_(?:rsa|ed25519|ecdsa|dsa)|\\.pem)))|(?:SSH_PRIVATE_KEY|PRIVATE_KEY|BEGIN\\s+(?:OPENSSH|RSA|EC|DSA)\\s+PRIVATE\\s+KEY|id_(?:rsa|ed25519|ecdsa|dsa)|\\.pem)[^\\n]*\\|\\s*ssh-add\\s+-(?:\\s|$))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "private_key_material_export",
                                    "export or unprotect private key material",
                                    pattern(
                                            "\\b(?:gpg(?:2)?\\s+--export-secret-keys\\b|openssl\\s+(?:rsa|pkey|pkcs12)\\b(?=[^\\n]*(?:\\s-out\\s+\\S+|\\s-export\\b))(?=[^\\n]*(?:\\s-nodes\\b|\\s-nocrypt\\b|\\s-(?:passout|password)\\s+pass:|\\s-in\\s+\\S*(?:id_(?:rsa|ed25519|ecdsa|dsa)|private|key|\\.pem)\\S*))|ssh-keygen\\s+-p\\b(?=[^\\n]*(?:\\s-N\\s+['\"]{0,2}|\\s-P\\s+\\S+)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "package_manager_secret_read",
                                    "read package manager credential",
                                    pattern(
                                            "\\b(?:(?:npm|pnpm|yarn)\\s+config\\s+get\\s+\\S*(?:_authToken|_auth|password|token)|pip\\s+config\\s+get\\s+\\S*(?:password|token|credential|secret)|poetry\\s+config\\s+(?:--list\\s+\\S*(?:password|token|credential|secret)|\\S*(?:password|token|credential|secret)\\s*(?:$|[;&|]))|twine\\s+upload\\b(?=[^\\n]*(?:\\s-p\\s+\\S+|--password(?:=|\\s+)\\S+))|gem\\s+credentials\\b|nuget\\s+sources\\s+list\\b(?=[^\\n]*--format\\s+detailed))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "package_manager_secret_write",
                                    "write package manager credential",
                                    pattern(
                                            "\\b(?:(?:npm|pnpm|yarn)\\s+config\\s+(?:set|add)\\s+\\S*(?:_authToken|_auth|password|token)\\s+\\S+|pip\\s+config\\s+set\\s+\\S*(?:password|token|credential|secret)\\s+\\S+|poetry\\s+config\\s+(?:http-basic\\.|pypi-token\\.)\\S+\\s+\\S+|(?:uv|pdm|hatch)\\s+publish\\b(?=[^\\n]*(?:--token(?:=|\\s+)\\S+|--password(?:=|\\s+)\\S+|--username(?:=|\\s+)\\S+))|cargo\\s+login\\b|gem\\s+push\\b(?=[^\\n]*(?:\\s-k\\s+\\S+|--key\\s+\\S+))|nuget\\s+sources\\s+(?:add|update)\\b(?=[^\\n]*(?:-Password\\s+\\S+|-StorePasswordInClearText\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "package_manager_source_change",
                                    "package manager source configuration changed",
                                    pattern(
                                            "\\b(?:(?:npm|pnpm|yarn)\\s+config\\s+set\\s+(?:registry|npmRegistryServer)\\s+(?!https://registry\\.npmjs\\.org/?(?:\\s|$))\\S+|pip\\s+config\\s+set\\s+(?:global\\.)?(?:index-url|extra-index-url|trusted-host)\\s+\\S+|poetry\\s+source\\s+(?:add|remove)\\b|cargo\\s+login\\b|cargo\\s+owner\\s+--add\\b|gem\\s+sources\\s+(?:--add|--remove)\\b|nuget\\s+sources\\s+(?:add|update|remove)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "package_manager_script_policy_change",
                                    "package manager install script policy changed",
                                    pattern(
                                            "\\b(?:(?:npm|pnpm|yarn)\\s+config\\s+set\\s+(?:ignore-scripts\\s+false|unsafe-perm\\s+true|enableScripts\\s+true|audit\\s+false|verify-store-integrity\\s+false|enableImmutableInstalls\\s+false|enableStrictSsl\\s+false)\\b|pnpm\\s+approve-builds\\b|bun\\s+pm\\s+trust\\b|yarn\\s+config\\s+set\\s+enableScripts\\s+true\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "package_manager_remote_execute",
                                    "package manager remote package execution",
                                    pattern(
                                            "\\b(?:npx|uvx|bunx)\\b|\\bnpm\\s+(?:exec|create)\\b|\\bpnpm\\s+(?:dlx|exec|create)\\b|\\byarn\\s+(?:dlx|create)\\b|\\bbun\\s+create\\b|\\bpipx\\s+run\\b|\\bdeno\\s+run\\b(?=[^\\n]*(?:https?://|jsr:|npm:))|\\b(?:npm|pnpm|yarn|bun)\\s+(?:install|add)\\b(?=[^\\n]*(?:git\\+https?://|https?://|github:|gitlab:|bitbucket:))|\\bpip(?:3)?\\s+install\\b(?=[^\\n]*(?:git\\+https?://|https?://\\S*\\.(?:whl|tar\\.gz|zip)\\b))|\\bcargo\\s+install\\b(?=[^\\n]*(?:--git\\s+https?://|--git=https?://))|\\bgo\\s+install\\b(?=[^\\n]*@[A-Za-z0-9_.-]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_archive",
                                    "archive credential files",
                                    pattern(
                                            "\\b(?:tar|bsdtar|gtar)\\s+(?:-[A-Za-z]*c[A-Za-z]*|c[A-Za-z]*f?|--create)\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bzip\\b(?=[^\\n]*\\.(?:zip|7z|tar|tgz|gz|xz)\\b)(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\b(?:7z|7za)\\s+a\\b(?=[^\\n]*\\.(?:zip|7z|tar|tgz|gz|xz)\\b)(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bCompress-Archive\\b(?=[^\\n]*(?:-Path\\b|-LiteralPath\\b|\\.zip\\b))(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bjar\\s+(?:-[A-Za-z]*c[A-Za-z]*|c[A-Za-z]*f?|--create)\\b(?=[^\\n]*\\.(?:jar|zip)\\b)(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_archive_member_output",
                                    "read credential file member from archive",
                                    pattern(
                                            "(?:\\b(?:tar|bsdtar|gtar)\\s+(?:-[A-Za-z]*[tx][A-Za-z]*|[tTxX][A-Za-z]*f?|--list|--extract)\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bunzip\\b(?=[^\\n]*(?:\\s-p\\b|\\s-l\\b|\\s-c\\b))(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bzipinfo\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\b(?:7z|7za)\\s+(?:l|e|x)\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bjar\\s+(?:-[A-Za-z]*[tx][A-Za-z]*|[tTxX][A-Za-z]*f?|--(?:list|extract))\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_copy_to_shared_location",
                                    "copy credential file to shared or public location",
                                    pattern(
                                            "\\b(?:cp|mv|install)\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")(?=[^\\n]*(?:\\s(?:/tmp|/var/tmp|/private/tmp|/dev/shm|public|share|shared|uploads?|downloads?)(?:[/\\\\\\s]|$)|[/\\\\](?:public|share|shared|uploads?|downloads?)(?:[/\\\\\\s]|$)))"
                                                    + "|\\b(?:Copy-Item|copy|xcopy|robocopy)\\b(?=[^\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")(?=[^\\n]*(?:[/\\\\](?:public|share|shared|uploads?|downloads?)(?:[/\\\\\\s]|$)|\\s(?:public|share|shared|uploads?|downloads?)(?:[/\\\\\\s]|$)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_write_to_shared_location",
                                    "write credential file content to shared or public location",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")[^\\n|;&]*(?:>>?|\\|\\s*(?:tee\\b(?:\\s+-a\\b)?|Tee-Object\\b|Out-File\\b|Set-Content\\b|Add-Content\\b)[^\\n|;&]*(?:-(?:FilePath|Path|LiteralPath)\\b\\s*(?::|=|\\s+)\\s*)?)[^\\n|;&]*(?:\\s(?:/tmp|/var/tmp|/private/tmp|/dev/shm|public|share|shared|uploads?|downloads?)(?:[/\\\\\\s]|$)|[/\\\\](?:public|share|shared|uploads?|downloads?)(?:[/\\\\\\s]|$))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_http_header_send",
                                    "send credential through HTTP header",
                                    pattern(
                                            "\\b(?:curl|wget)\\b[^\\n]*(?:(?:-H\\s*|--header\\s*(?:=\\s*)?)[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:|(?:--header=)[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:|(?:--proxy-header\\s*(?:=\\s*)?)[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:|(?:--proxy-header=)[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:)|\\b(?:httpie|https?|xh|curlie)\\b[^\\n]*\\s[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*:(?!=)|\\b(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm)\\b[^\\n]*(?:-Headers?\\b\\s*(?::|=|\\s+)\\s*@\\{[^\\n}]*[\"']?\\s*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*[\"']?\\s*=)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "network_credential_send",
                                    "send credential through network command option",
                                    pattern(
                                            "\\b(?:curl|wget)\\b[^\\n]*(?:\\s(?:(?:https?|wss?)://|//)?[^\\s/@]+(?::|%3a)[^\\s/@]+@[^\\s/]+|\\s-u(?:\\s+\\S|\\S+)|\\s--(?:user|password|http-user|http-password|ftp-user|ftp-password|proxy-user|proxy-password|oauth2-bearer)(?:=|\\s+)\\S|\\s--ask-password\\b|\\s--cookie(?:=|\\s+)\\S|\\s-b\\s+\\S+=\\S*|\\s(?:--data(?:-[a-z-]+)?|-d|--post-data|--form(?:-string)?|-F|--url-query)(?:=|\\s+)['\"]?[^\\s'\"|;&]*"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "\\s*=\\s*(?![\"']?[@<]\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + ")\\S+|\\s--json(?:=|\\s+)['\"]?[^\\s'\"|;&]*[\"']?"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "[\"']?\\s*:\\s*[\"']?\\S+)|\\baria2c\\b[^\\n]*\\s--(?:http-user|http-passwd|ftp-user|ftp-passwd|proxy-user|proxy-passwd)(?:=|\\s+)\\S+|\\b(?:httpie|https?|xh|curlie)\\b[^\\n]*\\s"
                                                    + "(?:--auth(?:=|\\s+)\\S+|-a(?:=|\\s+)?\\S+)|\\b(?:httpie|https?|xh|curlie)\\b[^\\n]*\\s"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "\\s*(?:=|:=)\\s*\\S+|\\b(?:curl|wget)\\b[^\\n]*\\s(?:--data(?:-[a-z-]+)?|-d|--post-data|--json)(?:=|\\s+)['\"]?[^\\s'\"|;&]*[\"']?"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "[\"']?\\s*:\\s*[\"']?\\S+|\\b(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm)\\b[^\\n]*(?:\\s-(?:Credential|ProxyCredential|Token|Certificate|CertificateThumbprint)\\b\\s*(?::|=|\\s+)\\S|\\s-(?:UseDefaultCredentials|ProxyUseDefaultCredentials)\\b(?!\\s*:\\s*\\$?false\\b)|\\s-(?:Body|Form)\\b\\s*(?::|=|\\s+)\\s*(?:@\\{[^\\n}]*[\"']?\\s*"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "\\s*[\"']?\\s*=|[\"']?[^\\s'\"|;&]*[\"']?"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "(?:\\s*=|[\"']?\\s*:\\s*[\"']?)\\S+))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "network_credential_file_send",
                                    "send credential from local netrc or cookie file",
                                    pattern(
                                            "\\b(?:curl|wget)\\b[^\\n]*(?:\\s--netrc(?:-optional|-file)?(?:=|\\s+)?\\S*|\\s--(?:config|load-cookies|cookie-jar)(?:=|\\s+)\\S|\\s(?-i:-K)\\s*\\S+|\\s--(?:cert|key|proxy-cert|proxy-key|certificate|private-key|ca-certificate|cacert|capath)(?:=|\\s+)\\S+|\\s(?-i:-E)\\s+\\S+|\\s(?-i:-[bcEK])\\S+|\\s(?-i:-c)\\s+\\S|\\s(?-i:-b)\\s+(?:\\S*[/\\\\])?\\S*(?:cookie|cookies|jar)\\S*|\\s(?:--upload-file|--body-file|--post-file)(?:=|\\s+)\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*|\\s-T\\s*\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*|\\s(?:--data(?:-[a-z-]+)?|-d|--json)(?:=|\\s+)@\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*|\\s(?:--form(?:-string)?|-F)(?:=|\\s+)[\"']?[^\\s'\"|;&]*=[@<]\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']?|\\s(?:--form(?:-string)?|-F)(?:=|\\s+)\\S*[@<]\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']?)|\\baria2c\\b[^\\n]*\\s--(?:load-cookies|certificate|private-key|ca-certificate)(?:=|\\s+)\\S+|\\b(?:httpie|https?|xh|curlie)\\b[^\\n]*\\s(?:[^\\s'\"|;&]+@|@)\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*|\\b(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm)\\b[^\\n]*\\s-InFile\\b\\s*(?::|=|\\s+)\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "powershell_network_credential_file_send",
                                    "PowerShell sends credential file through HTTP",
                                    pattern(
                                            "\\b(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm)\\b[^\\n]*-(?:Body|Form)\\b\\s*(?::|=|\\s+)\\s*\\(?\\s*(?:@\\{[^\\n}]*\\b(?:Get-Content|gc|Get-Item|gi)\\b[^\\n}]*[\"']?\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']?|\\b(?:Get-Content|gc|Get-Item|gi)\\b[^\\n|;&]*[\"']?\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']?|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")|\\bStart-BitsTransfer\\b(?=[^\\n|;&]*-(?:TransferType|Type)\\b\\s*(?::|=|\\s+)\\s*Upload\\b)(?=[^\\n|;&]*-(?:Destination|Dest)\\b\\s*(?::|=|\\s+)\\s*[\"']?(?:https?|wss?)://)[^\\n|;&]*-(?:Source|Src)\\b\\s*(?::|=|\\s+)\\s*[\"']?\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']?"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "powershell_webclient_credential_file_send",
                                    "PowerShell WebClient sends credential file",
                                    pattern(
                                            "(?:New-Object\\s+Net\\.WebClient|\\[Net\\.WebClient\\]::new\\s*\\(\\s*\\)|\\[System\\.Net\\.WebClient\\]::new\\s*\\(\\s*\\))[^\\n]*\\.Upload(?:File|Data|String)\\s*\\([^\\n]*[\"']\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']|(?:New-Object\\s+Net\\.WebClient|\\[Net\\.WebClient\\]::new\\s*\\(\\s*\\)|\\[System\\.Net\\.WebClient\\]::new\\s*\\(\\s*\\))[^\\n]*\\.Upload(?:Data|String)\\s*\\([^\\n]*\\b(?:cat|type|Get-Content|gc)\\b[^\\n)]*[\"']?\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']?|(?:New-Object\\s+Net\\.WebClient|\\[Net\\.WebClient\\]::new\\s*\\(\\s*\\)|\\[System\\.Net\\.WebClient\\]::new\\s*\\(\\s*\\))[^\\n]*\\.Upload(?:Data|String)\\s*\\([^\\n]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "\\S*[\"']?"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_compare_output",
                                    "compare credential file content",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:diff|cmp|comm)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|(?:^|[;&|\\n`])\\s*git\\s+(?:diff|show)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|(?:^|[;&|\\n`])\\s*(?:fc(?:\\.exe)?|comp(?:\\.exe)?)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bCompare-Object\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bCompare-Object\\b[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*Compare-Object\\b"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*Compare-Object\\b"
                                                    + "|\\bCompare-Object\\b[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*Compare-Object\\b"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_filtered_output",
                                    "filter credential file content to terminal",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:nl|cut|sort|uniq|findstr(?:\\.exe)?)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:Select-String|sls)\\b[^\\n|;&]*"
                                                    + EXPLICIT_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:Select-String|sls|Where-Object|where)\\b|\\?)"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:Select-String|sls|Where-Object|where)\\b|\\?)"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:Select-String|sls|Where-Object|where)\\b|\\?)"
                                                    + "|\\b(?:Select-String|sls)\\b[^\\n|;&]*-(?:InputObject|Input)\\b\\s*(?::|=|\\s+)\\s*\\(?\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|\\b(?:Select-String|sls)\\b[^\\n|;&]*-(?:InputObject|Input)\\b\\s*(?::|=|\\s+)\\s*\\(?\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_structured_output",
                                    "parse credential file content to terminal",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:jq|yq)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:cat|type|Get-Content|gc|Import-Clixml)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:ConvertFrom-Json|ConvertFrom-Csv|ConvertFrom-StringData|Format-Table|Format-List|Format-Wide)\\b|\\b(?:ConvertFrom-Json|ConvertFrom-Csv|ConvertFrom-StringData|Format-Table|Format-List|Format-Wide)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:ConvertFrom-Json|ConvertFrom-Csv|ConvertFrom-StringData|Format-Table|Format-List|Format-Wide)\\b"
                                                    + "|\\b(?:ConvertFrom-Json|ConvertFrom-Csv|ConvertFrom-StringData|Format-Table|Format-List|Format-Wide)\\b[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:Format-Table|Format-List|Format-Wide)\\b"
                                                    + "|\\b(?:Format-Table|Format-List|Format-Wide)\\b[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_transcript_output",
                                    "transcript credential file content",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:tee\\b(?:\\s+-a\\b)?|Tee-Object\\b|Out-File\\b|Set-Content\\b|Add-Content\\b|Out-String\\b|Out-Default\\b)|(?:^|[;&|\\n`])\\s*script\\b[^\\n|;&]*\\s-c\\s+[\"'][^\"'\\n]*(?:cat|type|Get-Content|gc)\\b[^\"'\\n]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|\\bStart-Transcript\\b[\\s\\S]{0,1200}\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:tee\\b(?:\\s+-a\\b)?|Tee-Object\\b|Out-File\\b|Set-Content\\b|Add-Content\\b)(?![^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine))|Out-String\\b|Out-Default\\b)"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:tee\\b(?:\\s+-a\\b)?|Tee-Object\\b|Out-File\\b|Set-Content\\b|Add-Content\\b)(?![^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine))|Out-String\\b|Out-Default\\b)"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_history_write",
                                    "write credential file content into shell history",
                                    pattern(
                                            "(?:\\bhistory\\s+-s\\b[^\\n|;&]*(?:\\$\\([^\\n)]*\\b(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|`[^`\\n]*(?:cat|type|Get-Content|gc)\\b[^`\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^`\\n]*`|"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bAdd-History\\b[^\\n|;&]*(?:\\(\\s*(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")|\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*(?:>>?|\\|\\s*(?:tee\\b(?:\\s+-a\\b)?|Add-Content\\b))[^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine)"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*(?:>>?|\\|\\s*(?:tee\\b(?:\\s+-a\\b)?|Add-Content\\b))[^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine)"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*(?:>>?|\\|\\s*(?:tee\\b(?:\\s+-a\\b)?|Add-Content\\b))[^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine)"
                                                    + "|\\b(?:Add-Content|Set-Content|Out-File)\\b[^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine)[^\\n|;&]*\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:Add-Content|Set-Content|Out-File)\\b[^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine)[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|\\b(?:Add-Content|Set-Content|Out-File)\\b[^\\n|;&]*(?:\\.bash_history|\\.zsh_history|ConsoleHost_history\\.txt|PSReadLine)[^\\n|;&]*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_notification_output",
                                    "show credential file content in notification",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:notify-send|terminal-notifier|osascript\\b[^\\n|;&]*(?:display\\s+notification|display\\s+alert)|New-BurntToastNotification|New-BTNotification)\\b|\\b(?:notify-send|terminal-notifier|osascript\\b[^\\n|;&]*(?:display\\s+notification|display\\s+alert)|New-BurntToastNotification|New-BTNotification)\\b[^\\n|;&]*(?:\\$\\([^\\n)]*\\b(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|`[^`\\n]*(?:cat|type|Get-Content|gc)\\b[^`\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^`\\n]*`|\\(\\s*(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + ")|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:notify-send|terminal-notifier|osascript\\b[^\\n|;&]*(?:display\\s+notification|display\\s+alert)|New-BurntToastNotification|New-BTNotification)\\b|"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:notify-send|terminal-notifier|osascript\\b[^\\n|;&]*(?:display\\s+notification|display\\s+alert)|New-BurntToastNotification|New-BTNotification)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_pager_output",
                                    "view credential file through pager",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:bat|batcat|most|pg)\\b(?![^\\n|;&]*(?:\\||>|>>))[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_pipeline_preview",
                                    "preview credential file content through pipeline",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:head|tail|less|more|bat|batcat|most|pg|Out-Host|Select-Object|select|ForEach-Object|foreach)\\b|%)"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:(?:head|tail|less|more|bat|batcat|most|pg|Out-Host|Select-Object|select|ForEach-Object|foreach)\\b|%)"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_substitution_output",
                                    "print credential file content through command substitution",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:echo|printf|Write-Output|Write-Host)\\b[^\\n|;&]*(?:\\$\\([^\\n)]*\\b(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|`[^`\\n]*(?:cat|type|Get-Content|gc)\\b[^`\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^`\\n]*`|\\(\\s*(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|\\(\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "\\s*\\)|\\(\\s*"
                                                    + POWERSHELL_CREDENTIAL_FILE_BYTE_READ
                                                    + "\\s*\\)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_terminal_output",
                                    "print credential file content to terminal",
                                    pattern(
                                            "(?:\\b(?:cat|type|head|tail|less|more|sed|awk|grep|Get-Content|gc)\\b(?![^\\n|;&]*(?:\\||>|>>))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:select-string|sls)\\b(?![^\\n|;&]*(?:\\||>|>>))[^\\n|;&]*"
                                                    + EXPLICIT_CREDENTIAL_FILE_TARGET
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "(?![^\\n|;&]*(?:\\||>|>>))"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_editor_open",
                                    "open credential file in editor",
                                    pattern(
                                            "\\b(?:vi|vim|nvim|nano|emacs|code|notepad(?:\\.exe)?|notepad\\+\\+)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_system_open",
                                    "open credential file with system viewer",
                                    pattern(
                                            "(?:\\b(?:open|xdg-open|gio\\s+open|start)\\b|\\b(?:Invoke-Item|ii)\\b)[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_metadata_output",
                                    "print credential file metadata",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:ls|stat|file|du|wc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|(?:^|[;&|\\n`])\\s*(?:Get-Item|gi|Get-ChildItem|gci|dir|ls|Get-Acl)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:Measure-Object|measure)\\b"
                                                    + "|"
                                                    + POWERSHELL_CREDENTIAL_FILE_TEXT_READ
                                                    + "[^\\n|;&]*\\|\\s*(?:Measure-Object|measure)\\b"
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_credential_file_transfer",
                                    "transfer credential file with remote copy tool",
                                    pattern(
                                            "\\b(?:scp|sftp|rsync|rclone|s3cmd|gsutil|azcopy|ossutil|coscli|obsutil)\\b(?=[^\\n]*(?:\\s|=|:)"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")|\\baws\\s+s3\\s+(?:cp|sync)\\b(?=[^\\n]*(?:s3://|\\ss3:))(?=[^\\n]*(?:\\s|=|:)"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")|\\bgcloud\\s+storage\\s+(?:cp|rsync)\\b(?=[^\\n]*(?:gs://|\\sgs:))(?=[^\\n]*(?:\\s|=|:)"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "ssh_host_key_check_disabled",
                                    "SSH host key verification disabled",
                                    pattern(
                                            "\\b(?:ssh|scp|sftp|rsync)\\b[^\\n]*(?:-o\\s*StrictHostKeyChecking\\s*=\\s*(?:no|off|false|accept-new)|-o\\s*UserKnownHostsFile\\s*=\\s*(?:/dev/null|NUL|nul))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_path_option",
                                    "credential file passed through command option",
                                    pattern(
                                            "(?:"
                                                    + SHELL_COMMAND_START
                                                    + "(?:ssh|scp|sftp)\\b[^\\n]*(?:\\s(?-i:-[iF])\\s*\\S+|\\s-o\\s*(?:IdentityFile|CertificateFile|UserKnownHostsFile|GlobalKnownHostsFile|HostKey|HostCertificate|HostKeyAlias)=\\S+)|\\brsync\\b[^\\n]*(?:\\s-e\\s*[\"']?ssh\\b|\\s--rsh(?:=|\\s+)[\"']?ssh\\b)[^\\n]*(?:\\s(?-i:-[iF])\\s*\\S+|\\s-o\\s*(?:IdentityFile|CertificateFile|UserKnownHostsFile|GlobalKnownHostsFile|HostKey|HostCertificate|HostKeyAlias)=\\S+)|\\bgit\\b[^\\n]*\\s-c\\s+core\\.sshCommand\\s*=\\s*[\"']?ssh\\b[^\\n]*(?:\\s(?-i:-[iF])\\s*\\S+|\\s-o\\s*(?:IdentityFile|CertificateFile|UserKnownHostsFile|GlobalKnownHostsFile|HostKey|HostCertificate|HostKeyAlias)=\\S+)|\\b(?:curl|wget)\\b[^\\n]*\\s(?:(?-i:-[bcEK])\\s*\\S+|--(?:netrc-file|cookie|cookie-jar|load-cookies|config)(?:=|\\s+)\\S+)|\\b(?:kubectl|helm)\\b[^\\n]*\\s--kubeconfig(?:=|\\s+)\\S+|\\bgcloud\\b[^\\n]*\\s--(?:key-file|credential-file|credentials-file)(?:=|\\s+)\\S+|\\baz\\b[^\\n]*\\s--(?:cert|key|password-file)(?:=|\\s+)\\S+|\\bopenssl\\b[^\\n]*\\s-(?:key|cert|CAfile|CApath)\\s+\\S+|\\b(?:ansible|ansible-playbook)\\b[^\\n]*\\s--(?:private-key|key-file)(?:=|\\s+)\\S+|\\b(?:npm|pnpm|yarn)\\b[^\\n]*\\s--(?:userconfig|globalconfig)(?:=|\\s+)\\S+|\\b(?:rclone|s3cmd|coscli)\\b[^\\n]*\\s--config(?:=|\\s+)\\S+|\\bossutil\\b[^\\n]*\\s--config-file(?:=|\\s+)\\S+|\\bobsutil\\b[^\\n]*\\s-config(?:=|\\s+)\\S+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_config_option",
                                    "credential file passed as generic configuration option",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?!curl\\b|wget\\b|ssh\\b|scp\\b|sftp\\b|rsync\\b|git\\b|kubectl\\b|helm\\b|gcloud\\b|az\\b|openssl\\b|ansible\\b|ansible-playbook\\b|npm\\b|pnpm\\b|yarn\\b|rclone\\b|s3cmd\\b|coscli\\b|ossutil\\b|obsutil\\b|docker\\b|podman\\b|nerdctl\\b|buildah\\b)\\S+\\b[^\\n|;&]*\\s--(?:config|config-file|config-path|env-file|dotenv|credentials-file|credential-file|key-file|secrets-file|secret-file)(?:=|\\s+)\\S*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|(?:^|[;&|\\n`])\\s*(?!openssl\\b|docker\\b|podman\\b|nerdctl\\b|buildah\\b)\\S+\\b[^\\n|;&]*\\s(?:-c|-f)(?:\\s+|=)\\S*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "tls_certificate_check_disabled",
                                    "TLS certificate verification disabled",
                                    pattern(
                                            "\\b(?:curl|wget|aria2c|curlie)\\b[^\\n]*(?:\\s-k(?:\\s|$)|\\s--insecure\\b|\\s--no-check-certificate\\b|\\s--check-certificate\\s*=\\s*off\\b|\\s--allow-untrusted(?:\\s|$)|\\s--verify\\s*=\\s*(?:no|false|0)\\b|\\s--verify\\s+(?:no|false|0)\\b)|\\b(?:npm|pnpm|yarn)\\s+config\\s+set\\s+(?:strict-ssl|strictSsl)\\s+false\\b|\\bpip(?:3)?\\b[^\\n]*\\s--trusted-host(?:=|\\s+)\\S+|\\bpoetry\\s+config\\s+certificates\\.[A-Za-z0-9_.-]+\\.cert\\s+false\\b|(?:^|[;&|\\n`])\\s*(?:PYTHONHTTPSVERIFY\\s*=\\s*0|NODE_TLS_REJECT_UNAUTHORIZED\\s*=\\s*0)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "git_tls_certificate_check_disabled",
                                    "Git TLS certificate verification disabled",
                                    pattern(
                                            "(?:^|[;&|\\n`])\\s*(?:GIT_SSL_NO_VERIFY\\s*=\\s*(?:true|1|yes)\\s+git\\b|git\\s+-c\\s+http\\.sslVerify\\s*=\\s*false\\b|git\\s+config\\s+(?:--global\\s+)?http\\.sslVerify\\s+false\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "code_tls_certificate_check_disabled",
                                    "code disables TLS certificate verification",
                                    pattern(
                                            "(?:verify\\s*=\\s*False\\b|rejectUnauthorized\\s*[:=]\\s*false\\b|NODE_TLS_REJECT_UNAUTHORIZED\\s*=\\s*['\"]?0['\"]?)"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "system_trust_store_change",
                                    "system trust store changed",
                                    pattern(
                                            "\\b(?:update-ca-certificates\\b|trust\\s+anchor\\b|update-ca-trust\\s+(?:extract|enable)\\b|security\\s+add-trusted-cert\\b|certutil(?:\\.exe)?\\s+-addstore\\b|Import-Certificate\\b(?=[^\\n]*-CertStoreLocation\\s+Cert:\\\\LocalMachine))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "system_package_source_trust_change",
                                    "system package source or trust configuration changed",
                                    pattern(
                                            "\\b(?:apt-key\\s+(?:add|adv)\\b|add-apt-repository\\b|rpm\\s+--import\\b|yum-config-manager\\s+--add-repo\\b|dnf\\s+config-manager\\s+--add-repo\\b|zypper\\s+(?:addrepo|ar)\\b|brew\\s+tap(?:\\s|$)|choco\\s+source\\s+(?:add|remove|disable|enable)\\b|winget\\s+source\\s+(?:add|remove|reset|update)\\b|scoop\\s+bucket\\s+(?:add|rm|remove)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "system_package_signature_bypass",
                                    "system package signature verification bypassed",
                                    pattern(
                                            "\\b(?:apt(?:-get)?\\s+(?:-[^\\s]+\\s+)*install\\b(?=[^\\n]*--allow-unauthenticated\\b)|yum\\s+[^\\n]*--nogpgcheck\\b|dnf\\s+[^\\n]*--nogpgcheck\\b|zypper\\s+[^\\n]*--no-gpg-checks\\b|rpm\\s+[^\\n]*(?:--nosignature|--nodigest)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "plaintext_cli_password_option",
                                    "send credential through plaintext CLI password option",
                                    pattern(
                                            "\\b(?:sshpass\\s+-(?:p|P)\\s+\\S+|mysql(?:admin|dump)?\\b[^\\n]*(?:\\s-p\\S+|\\s--password(?:=|\\s+)\\S+)|mariadb(?:-dump)?\\b[^\\n]*(?:\\s-p\\S+|\\s--password(?:=|\\s+)\\S+)|(?:psql|pg_dump|pg_restore)\\b[^\\n]*(?:\\s-W\\s+\\S+|\\s--password(?:=|\\s+)\\S+)|(?:mongo|mongosh)\\b[^\\n]*(?:\\s-p\\s+\\S+|\\s--password(?:=|\\s+)\\S+)|cockroach\\b[^\\n]*(?:\\s--password(?:=|\\s+)\\S+)|redis-cli\\b[^\\n]*(?:\\s-a\\s+\\S+|\\s--pass(?:=|\\s+)\\S+)|PGPASSWORD=\\S+\\s+(?:psql|pg_dump|pg_restore)\\b|MYSQL_PWD=\\S+\\s+mysql\\b|REDISCLI_AUTH=\\S+\\s+redis-cli\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cli_login_credential_option",
                                    "login command includes credential option",
                                    pattern(
                                            "\\b(?:(?:docker|podman|nerdctl|buildah)\\s+login\\b[^\\n]*(?:--password(?:=|\\s+)\\S+|-p\\s+\\S+|--password-stdin\\b)|helm\\s+registry\\s+login\\b[^\\n]*(?:--password(?:=|\\s+)\\S+|--password-stdin\\b)|(?:oras|crane|skopeo)\\s+(?:login|auth\\s+login)\\b[^\\n]*(?:--password(?:=|\\s+)\\S+|-p\\s+\\S+|--password-stdin\\b)|gh\\s+auth\\s+login\\b[^\\n]*--with-token\\b|npm\\s+login\\b[^\\n]*(?:--password(?:=|\\s+)\\S+|--auth-type\\s+legacy)|az\\s+login\\b[^\\n]*--password(?:=|\\s+)\\S+|doctl\\s+auth\\s+init\\b[^\\n]*--access-token(?:=|\\s+)\\S+|fly(?:ctl)?\\s+auth\\s+login\\b[^\\n]*--access-token(?:=|\\s+)\\S+|vercel\\s+login\\b[^\\n]*--token(?:=|\\s+)\\S+|netlify\\s+login\\b[^\\n]*--auth(?:=|\\s+)\\S+|wrangler\\s+login\\b[^\\n]*--api-token(?:=|\\s+)\\S+|aliyun\\s+configure\\b[^\\n]*(?:--access-key-id|--access-key-secret|--sts-token)(?:=|\\s+)\\S+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_history_erasure",
                                    "erase shell or credential command history",
                                    pattern(
                                            "\\b(?:history\\s+(?:-c|-w\\s+(?:/dev/null|NUL|nul))|fc\\s+-p\\s*/dev/null|unset\\s+HISTFILE\\b|(?:export\\s+)?HISTFILE\\s*=\\s*(?:/dev/null|['\"]{2})|(?:export\\s+)?HIST(?:FILE)?SIZE\\s*=\\s*0\\b|set\\s+\\+o\\s+history\\b|Clear-History\\b|Remove-Item\\b[^\\n]*(?:ConsoleHost_history\\.txt|PSReadLine|\\.bash_history|\\.zsh_history|\\.mysql_history|\\.psql_history|\\.rediscli_history|\\.sqlite_history|\\.python_history|\\.node_repl_history)|rm\\s+[^\\n]*(?:\\.bash_history|\\.zsh_history|\\.mysql_history|\\.psql_history|\\.rediscli_history|\\.sqlite_history|\\.python_history|\\.node_repl_history|ConsoleHost_history\\.txt)|del\\s+[^\\n]*(?:ConsoleHost_history\\.txt|\\.bash_history|\\.zsh_history|\\.mysql_history|\\.psql_history|\\.rediscli_history|\\.sqlite_history|\\.python_history|\\.node_repl_history)|Set-PSReadLineOption\\s+-HistorySaveStyle\\s+SaveNothing)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "audit_log_erasure",
                                    "audit or event log erasure",
                                    pattern(
                                            "\\b(?:journalctl\\b(?=[^\\n]*--vacuum-(?:time|size|files)\\b)|rm\\s+[^\\n]*(?:/var/log|/var/audit|/var/lib/systemd/journal|/run/log/journal)|truncate\\s+[^\\n]*(?:/var/log|/var/audit|/var/lib/systemd/journal|/run/log/journal)|wevtutil\\s+(?:cl|clear-log|clear)\\b|Clear-EventLog\\b|Remove-EventLog\\b|auditctl\\s+-D\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_audit_policy_disabled",
                                    "Linux audit policy or service disabled",
                                    pattern(
                                            "\\b(?:auditctl\\s+-e\\s*0\\b|systemctl\\s+[^\\n]*(?:stop|disable|mask)\\s+auditd(?:\\.service)?\\b|service\\s+auditd\\s+(?:stop|disable)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "git_remote_credential_url",
                                    "Git remote URL contains credentials",
                                    pattern(
                                            "\\bgit\\s+(?:remote\\s+(?:add|set-url)|config\\s+(?:--global\\s+)?url\\.)[^\\n]*(?:https?|ssh)://[^\\s/@:]+:[^\\s/@]+@"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "git_credential_store_change",
                                    "Git credential helper store changed",
                                    pattern(
                                            "\\bgit\\s+credential\\s+(?:approve|reject|store|erase)\\b|\\bgit\\s+config\\s+(?:--global\\s+|--system\\s+|--local\\s+)?credential\\.helper\\s+['\"]?store\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "ssh_tunnel_network_exposure",
                                    "SSH tunnel exposes a broad listen address",
                                    pattern(
                                            "\\bssh\\b(?=[^\\n]*(?:-o\\s*GatewayPorts\\s*=\\s*(?:yes|clientspecified)|-g\\b|-(?:L|R|D)\\s*['\"]?(?:0\\.0\\.0\\.0|\\[?:::\\]?|\\*)[:\\]]|-(?:L|R|D)\\s*['\"]?\\[[^\\]]*\\]:|-(?:L|R|D)\\s+['\"]?\\[[^\\]]*\\]:))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_disable_firewall",
                                    "Linux firewall disabled or flushed",
                                    pattern(
                                            "\\b(?:ufw\\s+(?:disable|reset)|firewall-cmd\\s+--panic-off|systemctl\\s+[^\\n]*(?:stop|disable|mask)\\s+(?:firewalld|ufw)\\b|iptables\\s+-(?:F|X)\\b|iptables\\s+-P\\s+(?:INPUT|FORWARD|OUTPUT)\\s+ACCEPT\\b|nft\\s+(?:flush\\s+ruleset|delete\\s+table)\\b|pfctl\\s+(?:-d\\b|-F\\s+(?:all|rules|nat|states)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "linux_disable_mac_policy",
                                    "Linux mandatory access control disabled",
                                    pattern(
                                            "\\b(?:setenforce\\s+0|(?:sed\\b|perl\\b|tee\\b|Set-Content\\b|Out-File\\b)[^\\n]*(?:SELINUX\\s*=\\s*disabled|/etc/selinux/config)|aa-(?:teardown|disable)\\b|systemctl\\s+[^\\n]*(?:stop|disable|mask)\\s+apparmor\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "macos_security_policy_weaken",
                                    "macOS security policy weakened",
                                    pattern(
                                            "\\b(?:spctl\\s+--(?:master|global)-disable|xattr\\s+(?:-[^\\s]*d[^\\s]*\\s+)?com\\.apple\\.quarantine\\b|tccutil\\s+reset\\b|csrutil\\s+(?:disable|authenticated-root\\s+disable)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_fleet_command_execution",
                                    "remote fleet command execution",
                                    pattern(
                                            "\\b(?:ansible\\s+(?:all|'\\*'|\"\\*\")\\b[^\\n]*(?:-m\\s+(?:shell|command|raw)|-a\\s+\\S)|ansible-playbook\\b(?=[^\\n]*(?:--become\\b|-b\\b|--limit\\s+(?:all|'\\*'|\"\\*\")))|salt\\s+(?:'\\*'|\"\\*\"|\\*)\\s+cmd\\.run\\b|pssh\\b|pdsh\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "stop_service",
                                    "stop/restart system service",
                                    pattern(
                                            "\\b(?:systemctl\\s+(-[^\\s]+\\s+)*(stop|restart|disable|mask)|service\\s+\\S+\\s+(?:stop|restart)|launchctl\\s+(?:bootout|unload|disable))\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "unix_cron_persistence_change",
                                    "Unix cron persistence change",
                                    pattern(
                                            "(?:\\bcrontab\\s+(?:-[er]\\b|-u\\s+\\S+\\s+-[er]\\b|-(?:\\s|$))|\\|\\s*crontab\\s+-?(?:\\s|$))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "local_admin_permission_change",
                                    "local administrator or sudo permission change",
                                    pattern(
                                            "\\b(?:usermod\\b(?=[^\\n]*(?:-aG|--append\\s+--groups)[^\\n]*(?:sudo|wheel|admin|docker)\\b)|gpasswd\\s+-a\\s+\\S+\\s+(?:sudo|wheel|admin|docker)\\b|net(?:\\.exe)?\\s+localgroup\\s+Administrators\\b|dscl\\s+\\.\\s+-append\\s+/Groups/(?:admin|wheel)\\s+GroupMembership\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_local_account_change",
                                    "Windows local user account changed",
                                    pattern(
                                            "\\b(?:net(?:\\.exe)?\\s+user\\s+\\S+\\s+(?:/add|/delete|/active\\s*:\\s*(?:yes|no)|/expires\\s*:\\s*never|/passwordchg\\s*:\\s*no|/passwordreq\\s*:\\s*no|\\S+\\s*/add)\\b|net(?:\\.exe)?\\s+localgroup\\s+(?:\"[^\"]*(?:Remote\\s+Desktop\\s+Users|Remote\\s+Management\\s+Users)[^\"]*\"|'[^']*(?:Remote\\s+Desktop\\s+Users|Remote\\s+Management\\s+Users)[^']*'|(?:RemoteDesktopUsers|RemoteManagementUsers))\\s+\\S+\\s+/(?:add|delete)\\b|(?:New|Set|Enable|Disable|Remove)-LocalUser\\b|(?:Add|Remove)-LocalGroupMember\\b(?=[^\\n]*(?:Administrators|Remote\\s+Desktop\\s+Users|Remote\\s+Management\\s+Users)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "system_time_tamper",
                                    "system time or time sync changed",
                                    pattern(
                                            "\\b(?:timedatectl\\s+(?:set-time|set-timezone|set-ntp\\s+false)|date\\s+(?:-s|--set)\\b|hwclock\\s+(?:--systohc|--hctosys)\\b|Set-Date\\b|w32tm\\s+/config\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kill_all",
                                    "kill all processes",
                                    pattern("\\bkill\\s+-9\\s+-1\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "pkill_force",
                                    "force kill processes",
                                    pattern("\\bpkill\\s+-9\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "fork_bomb",
                                    "fork bomb",
                                    pattern(
                                            ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "gateway_stop_restart",
                                    "stop/restart gateway (kills running agents)",
                                    pattern(
                                            "\\b(?:solonclaw)\\s+gateway\\s+(stop|restart)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "app_update_restart",
                                    "agent update (restarts gateway, kills running agents)",
                                    pattern("\\b(?:solonclaw)\\s+update\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "gateway_run_detached",
                                    "start gateway outside managed lifecycle",
                                    pattern(
                                            "gateway\\s+run\\b.*(&\\s*$|&\\s*;|\\bdisown\\b|\\bsetsid\\b)|\\bnohup\\b.*gateway\\s+run\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kill_agent_process",
                                    "kill agent/gateway process (self-termination)",
                                    pattern(
                                            "\\b(pkill|killall)\\b.*\\b(solonclaw|gateway)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kill_pgrep_expansion",
                                    "kill process via process lookup expansion (self-termination)",
                                    pattern(
                                            "\\bkill\\b.*\\$\\(\\s*(?:pgrep|pidof)\\b|\\bkill\\b.*`\\s*(?:pgrep|pidof)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "copy_into_etc",
                                    "copy/move file into /etc/",
                                    pattern("\\b(cp|mv|install)\\b.*\\s/etc/"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "copy_into_project_sensitive",
                                    "overwrite project env/config file",
                                    pattern(
                                            "\\b(cp|mv|install)\\b.*\\s[\"']?"
                                                    + PROJECT_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "delete_sensitive_file",
                                    "delete sensitive credential file",
                                    pattern(
                                            "\\b(?:rm|Remove-Item|ri|del|erase)\\b[^\\n|;&]*\\s[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + "(?:\\s+(?:-[A-Za-z][A-Za-z0-9]*(?::\\$?(?:true|false))?|/[A-Za-z?]+))*"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sed_inplace_etc",
                                    "in-place edit of system config",
                                    pattern(
                                            "\\bsed\\s+-[^\\s]*i.*\\s/etc/|\\bsed\\s+--in-place\\b.*\\s/etc/"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "script_heredoc",
                                    "script execution via heredoc",
                                    pattern("\\b(python[23]?|perl|ruby|node)\\s+(?:-\\s+)?<<"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "curl_pipe_shell",
                                    "pipe remote content to shell",
                                    pattern("\\b(curl|wget)\\b.*\\|\\s*(ba)?sh\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "remote_content_pipe_interpreter",
                                    "pipe remote content to script interpreter",
                                    pattern(
                                            "\\b(?:curl|wget)\\b.*\\|\\s*(?:(?:sudo|doas|pkexec)\\s+(?:-[^\\s]+\\s+)*|env\\s+(?:\\w+=\\S*\\s+)*)?(?:bash|sh|zsh|ksh|fish|pwsh|powershell(?:\\.exe)?|python[23]?|perl|ruby|node)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "remote_archive_extract_execute",
                                    "download remote archive, extract it, then execute extracted content",
                                    pattern(
                                            "\\b(?:curl|wget)\\b(?=[^\\n]*(?:https?|ftp)://)(?=[^\\n]*(?:\\s(?:-o|-O|--output|--output-document)(?:=|\\s+)\\S+|>\\s*\\S+\\.(?:tar|tgz|tbz2|txz|zip|gz|bz2|xz)))"
                                                    + "[^\\n]*(?:&&|;|\\|\\|)[^\\n]*\\b(?:tar|bsdtar|gtar|unzip)\\b"
                                                    + "[^\\n]*(?:&&|;|\\|\\|)[^\\n]*(?:(?:bash|sh|zsh|ksh|fish|python[23]?|perl|ruby|node)\\s+[^\\s;&|]+|(?:\\./|/|[A-Za-z]:[\\\\/])[^\\s;&|]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_download_execute",
                                    "download remote file, then execute it",
                                    pattern(
                                            "\\b(?:curl|wget)\\b(?=[^\\n]*(?:https?|ftp)://)(?=[^\\n]*(?:\\s(?:-o|-O|--output|--output-document)(?:=|\\s+)\\S+|>\\s*\\S+))"
                                                    + "[^\\n]*(?:&&|;|\\|\\|)[^\\n]*(?:(?:bash|sh|zsh|ksh|fish|python[23]?|perl|ruby|node)\\s+[^\\s;&|]+|(?:source|\\.)\\s+[^\\s;&|]+|(?:chmod\\s+\\+x\\s+[^\\s;&|]+\\s*(?:&&|;|\\|\\|)\\s*)?(?:\\./|/|[A-Za-z]:[\\\\/])[^\\s;&|]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "docker_destructive_prune",
                                    "Docker destructive prune",
                                    pattern(
                                            "\\bdocker\\s+(?:system|container|image|volume|network)\\s+prune\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "docker_force_remove",
                                    "Docker force remove",
                                    pattern(
                                            "\\b(?:docker|podman|nerdctl|buildah)\\s+(?:rm|rmi)\\b(?=[^\\n]*(?:-(?!-)[^\\s]*f|--force\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "docker_compose_lifecycle",
                                    "Docker Compose lifecycle command",
                                    pattern(
                                            "\\b(?:docker\\s+compose|docker-compose|podman\\s+compose)\\s+(?:restart|stop|kill|down)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "docker_container_lifecycle",
                                    "Docker container lifecycle command",
                                    pattern(
                                            "\\b(?:docker|podman|nerdctl)\\s+(?:restart|stop|kill)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "docker_privileged_or_host_mount",
                                    "Docker privileged container or host mount",
                                    pattern(
                                            "\\b(?:docker|podman|nerdctl)\\s+(?:run|create)\\b(?=[^\\n]*(?:--privileged\\b|--(?:pid|ipc|uts|network|cgroupns|userns)(?:=|\\s+)host\\b|--cap-add(?:=|\\s+)(?:SYS_ADMIN|ALL|NET_ADMIN|SYS_PTRACE|DAC_READ_SEARCH|SYS_MODULE|SYS_RAWIO|SYS_TIME)\\b|--security-opt(?:=|\\s+)(?:seccomp|apparmor)=(?:unconfined|disabled)\\b|--device(?:=|\\s+)/dev/|(?:-v|--volume)\\s+(?:/\\s*:|/var/run/docker\\.sock\\b|[/\\\\]{2}\\.[/\\\\]pipe[/\\\\]docker_engine\\b)|--mount\\s+[^\\n]*(?:source|src)\\s*=\\s*(?:/\\s*(?:,|$)|/var/run/docker\\.sock\\b|[/\\\\]{2}\\.[/\\\\]pipe[/\\\\]docker_engine\\b)))|\\b(?:docker|podman|nerdctl)\\s+exec\\b(?=[^\\n]*(?:--privileged\\b|(?:-u|--user)(?:=|\\s+)root\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "container_secret_exposure",
                                    "container command exposes secret material",
                                    pattern(
                                            "\\b(?:docker|podman|nerdctl|buildah)\\s+(?:build|buildx\\s+build|run|create)\\b(?=[^\\n]*(?:(?:--build-arg|--env|-e)(?:=|\\s+)[A-Za-z_][A-Za-z0-9_]*(?:TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|API_?KEY)[A-Za-z0-9_]*\\s*=\\s*\\S+|--env-file(?:=|\\s+)\\S*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)\\S*|--secret(?:=|\\s+)\\S*(?:src|source|env)\\s*=\\s*\\S*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)\\S*|--ssh(?:=|\\s+)\\S*(?:~|\\$HOME|\\$env:HOME|%USERPROFILE%|\\.{1,2})[/\\\\]\\.ssh[/\\\\]\\S*))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kubectl_delete",
                                    "Kubernetes resource delete",
                                    pattern("\\bkubectl\\s+(?:-[^\\s]+\\s+)*delete\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kubectl_exec",
                                    "Kubernetes pod command execution",
                                    pattern("\\bkubectl\\s+(?:-[^\\s]+\\s+)*exec\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kubectl_remote_apply",
                                    "Kubernetes remote manifest apply",
                                    pattern(
                                            "\\bkubectl\\s+(?:-[^\\s]+\\s+)*apply\\b(?=[^\\n]*(?:-f|--filename)\\s+(?:https?|wss?)://)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kubectl_context_or_credential_change",
                                    "Kubernetes context or credential configuration changed",
                                    pattern(
                                            "\\bkubectl\\s+(?:-[^\\s]+\\s+)*config\\s+(?:set-credentials|set-context|use-context|unset|delete-context|delete-user)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kubectl_network_exposure",
                                    "Kubernetes local proxy or port-forward exposes a broad listen address",
                                    pattern(
                                            "\\bkubectl\\s+(?:-[^\\s]+\\s+)*(?:port-forward|proxy)\\b(?=[^\\n]*(?:(?:--address(?:=|\\s+)"
                                                    + BROAD_LISTEN_ADDRESS
                                                    + "(?:\\s|$))|(?:--accept-hosts(?:=|\\s+)(?:\\.\\*|['\"]?\\^?\\.\\*\\$?['\"]?|\\S*\\*\\S*))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "local_service_network_exposure",
                                    "local development service exposes a broad listen address",
                                    pattern(
                                            "\\b(?:python(?:3)?\\s+-m\\s+http\\.server|vite|next\\s+dev|webpack-dev-server|npm\\s+run\\s+(?:dev|start|serve)|pnpm\\s+(?:dev|start|serve)|yarn\\s+(?:dev|start|serve)|bun\\s+(?:dev|start|serve))\\b(?=[^\\n]*(?:--(?:host|hostname|bind|listen|address)(?:=|\\s+)"
                                                    + BROAD_LISTEN_ADDRESS
                                                    + "(?:\\s|$)|-(?:H|b)\\s+"
                                                    + BROAD_LISTEN_ADDRESS
                                                    + "(?:\\s|$)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "helm_uninstall",
                                    "Helm release uninstall",
                                    pattern("\\bhelm\\s+(?:uninstall|delete)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "helm_repository_configuration_change",
                                    "Helm repository configuration changed",
                                    pattern("\\bhelm\\s+repo\\s+(?:add|remove|update)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "terraform_destroy",
                                    "Terraform destroy",
                                    pattern("\\b(?:terraform|tofu|terragrunt)\\s+destroy\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "terraform_auto_approve_apply",
                                    "Terraform apply with auto approval",
                                    pattern(
                                            "\\b(?:terraform|tofu|terragrunt)\\s+apply\\b(?=[^\\n]*-auto-approve\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "terraform_state_sensitive_read",
                                    "Terraform state sensitive read",
                                    pattern(
                                            "\\b(?:terraform|tofu|terragrunt)\\s+state\\s+(?:pull|show)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "aws_destructive_resource",
                                    "AWS destructive resource operation",
                                    pattern(
                                            "\\baws\\s+\\S+\\s+(?:delete|terminate|remove|deregister|detach)-[a-z0-9-]+\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "domestic_cloud_destructive_resource",
                                    "Domestic cloud destructive resource operation",
                                    pattern(
                                            "\\b(?:aliyun\\s+(?:ecs|vpc|slb|rds)\\s+(?:Delete|Release|Stop|Reboot)[A-Za-z]+\\b|(?:tccli|qcloud)\\s+(?:cvm|vpc|clb|cdb)\\s+(?:Terminate|Delete|Release)[A-Za-z]+\\b|huaweicloud\\s+(?:ecs|evs|vpc|rds)\\s+(?:Delete|NovaDelete|BatchDelete|Terminate)[A-Za-z]+\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "aws_s3_recursive_remove",
                                    "AWS S3 recursive remove",
                                    pattern(
                                            "\\baws\\s+s3\\s+rm\\b(?=[^\\n]*(?:--recursive\\b|s3://))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "domestic_object_storage_recursive_remove",
                                    "Domestic object storage recursive remove",
                                    pattern(
                                            "\\b(?:ossutil|coscli|obsutil)\\s+(?:rm|delete)\\b(?=[^\\n]*(?:\\s-r\\b|\\s--recursive\\b|oss://|cos://|obs://))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "object_storage_exposure_change",
                                    "object storage ACL or policy made public",
                                    pattern(
                                            "\\b(?:ossutil|coscli|obsutil)\\b(?=[^\\n]*(?:\\b(?:set-?acl|bucket\\s+acl|setpolicy|put-?policy|policy|acl)\\b|--(?:acl|policy|grant-[a-z-]+)\\b))(?=[^\\n]*(?:\\bpublic-read(?:-write)?\\b|\\bpublic-readwrite\\b|\\bread-write\\b|\\beveryone\\b|\\ball-users\\b|\\banonymous\\b))|\\baws\\s+s3(?:api)?\\b(?=[^\\n]*(?:acl|policy))(?=[^\\n]*(?:public-read(?:-write)?|everyone|all-users|Principal\\s*['\"]?\\s*:\\s*['\"]?\\*))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cloud_iam_permission_change",
                                    "Cloud IAM permission change",
                                    pattern(
                                            "\\b(?:aws\\s+iam\\s+(?:attach|put|create|update|set|add)-[a-z0-9-]+|gcloud\\s+\\S+(?:\\s+\\S+)*\\s+add-iam-policy-binding\\b|az\\s+role\\s+(?:assignment\\s+create|definition\\s+(?:create|update))|aliyun\\s+ram\\s+(?:AttachPolicyToUser|AttachPolicyToRole|CreatePolicy|UpdatePolicy|AddUserToGroup)\\b|(?:tccli|qcloud)\\s+cam\\s+(?:AttachUserPolicy|AttachRolePolicy|CreatePolicy|UpdatePolicy|AddUser)\\b|huaweicloud\\s+iam\\s+(?:KeystoneCreateUserGroup|KeystoneAssociateUserGroup|CreateAgency|UpdateAgency|CreateRole)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cloud_network_exposure_change",
                                    "Cloud network exposure rule changed",
                                    pattern(
                                            "\\b(?:aws\\s+ec2\\s+(?:authorize-security-group-(?:ingress|egress)|modify-security-group-rules)\\b|gcloud\\s+compute\\s+firewall-rules\\s+(?:create|update)\\b|az\\s+network\\s+nsg\\s+rule\\s+(?:create|update)\\b|aliyun\\s+ecs\\s+(?:AuthorizeSecurityGroup|ModifySecurityGroupRule)\\b|(?:tccli|qcloud)\\s+cvm\\s+(?:AuthorizeSecurityGroupIngress|ModifySecurityGroupPolicies)\\b|huaweicloud\\s+(?:vpc\\s+AddSecurityGroupRule|ecs\\s+NovaCreateSecurityGroupRule)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "gcloud_delete",
                                    "Google Cloud resource delete",
                                    pattern(
                                            "\\bgcloud\\s+(?:-[^\\s]+\\s+)*[a-z0-9_-]+(?:\\s+[a-z0-9_-]+)*\\s+delete\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "azure_delete",
                                    "Azure resource delete",
                                    pattern(
                                            "\\baz\\s+(?:-[^\\s]+\\s+)*[a-z0-9_-]+(?:\\s+[a-z0-9_-]+)*\\s+delete\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "git_reset_hard",
                                    "git reset --hard (destroys uncommitted changes)",
                                    pattern("\\bgit\\s+reset\\s+--hard\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "git_force_push",
                                    "git force push (rewrites remote history)",
                                    pattern("\\bgit\\s+push\\b.*(--force|-f)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "git_clean_force",
                                    "git clean with force (deletes untracked files)",
                                    pattern(
                                            "\\bgit\\s+clean\\b(?=[^\\n]*(?:-(?!-)[^\\s]*f|--force\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "git_branch_delete",
                                    "git branch delete",
                                    caseSensitivePattern("\\bgit\\s+branch\\s+-[dD]\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "chmod_execute_script",
                                    "chmod +x followed by immediate execution",
                                    pattern(
                                            "\\bchmod\\s+\\+x\\b.*[;&|]+\\s*(?:(?:bash|sh|zsh|ksh)\\s+[^\\s;&|]+|(?:\\./|/|[A-Za-z]:[\\\\/])[^\\s;&|]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sql_delete_no_where",
                                    "SQL DELETE without WHERE",
                                    pattern("\\bDELETE\\s+FROM\\b(?!.*\\bWHERE\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "sql_update_no_where",
                                    "SQL UPDATE without WHERE",
                                    pattern(
                                            "\\bUPDATE\\s+[A-Za-z0-9_.`\"\\[\\]-]+\\s+SET\\b(?!.*\\bWHERE\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "sql_truncate",
                                    "SQL TRUNCATE",
                                    pattern("\\bTRUNCATE\\s+(TABLE)?\\s*\\w"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "sql_drop_statement",
                                    "SQL DROP statement",
                                    pattern(
                                            "\\bDROP\\s+(?:DATABASE|SCHEMA|TABLE)\\s+(?:IF\\s+EXISTS\\s+)?[A-Za-z0-9_.`\"\\[\\]-]+"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "database_dropdb",
                                    "database drop command",
                                    pattern("\\b(?:dropdb|mysqladmin\\s+drop)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "database_flush",
                                    "database cache flush",
                                    pattern("\\b(?:redis-cli\\s+)?FLUSH(?:ALL|DB)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "mongodb_destructive_eval",
                                    "MongoDB destructive shell evaluation",
                                    pattern(
                                            "\\b(?:mongo|mongosh)\\b(?=[^\\n]*(?:--eval\\b|-eval\\b))[^\\n]*(?:dropDatabase\\s*\\(|\\.drop\\s*\\(|deleteMany\\s*\\(\\s*\\{\\s*\\}\\s*\\))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "volume_delete",
                                    "storage volume or filesystem deletion",
                                    pattern(
                                            "\\b(?:lvremove|vgremove|pvremove|zfs\\s+destroy|btrfs\\s+subvolume\\s+delete|wipefs\\b(?=[^\\n]*(?:-a|--all)\\b)|cryptsetup\\s+(?:luksErase|erase|remove))\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "snapshot_delete",
                                    "local filesystem snapshot deletion",
                                    pattern(
                                            "\\b(?:snapper\\s+delete|tmutil\\s+delete|diskutil\\s+apfs\\s+deleteSnapshot)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "backup_prune_delete",
                                    "backup repository prune or deletion",
                                    pattern(
                                            "\\b(?:restic\\s+(?:forget|prune)|borg\\s+(?:delete|prune)|duplicity\\s+(?:remove|cleanup))\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_remove_item",
                                    "PowerShell recursive delete",
                                    pattern(
                                            "\\b(?:Remove-Item|ri|rm)\\b(?=[^\\n]*(?:-Recurse\\b|-rec\\b|-r\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_del_force",
                                    "Windows force delete",
                                    pattern(
                                            "\\b(?:del|erase)\\b(?=.*(?:^|\\s)/s\\b)(?=.*(?:^|\\s)/[fq]\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_rmdir_force",
                                    "Windows recursive directory delete",
                                    pattern(
                                            "\\b(rmdir|rd)\\b(?=.*(?:^|\\s)/s\\b)(?=.*(?:^|\\s)/q\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_format",
                                    "Windows format volume",
                                    pattern("\\bformat\\s+[a-z]:"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_clear_disk",
                                    "PowerShell clear disk",
                                    pattern("\\bClear-Disk\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_remove_partition",
                                    "PowerShell remove partition",
                                    pattern("\\bRemove-Partition\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_format_volume",
                                    "PowerShell format volume",
                                    pattern("\\bFormat-Volume\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_diskpart_script",
                                    "Windows diskpart script execution",
                                    pattern("\\bdiskpart(?:\\.exe)?\\b(?=[^\\n]*(?:/s\\b|-s\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_taskkill",
                                    "Windows force kill",
                                    pattern("\\btaskkill\\b.*\\s[-/]f\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_stop_process",
                                    "PowerShell force stop process",
                                    pattern("\\b(?:Stop-Process|spps)\\b.*(?:-Force\\b|-fo\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_reg_delete",
                                    "Windows registry delete",
                                    pattern("\\breg(?:\\.exe)?\\s+delete\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Windows Defender\\b[^\\n]*(?:DisableAntiSpyware|DisableAntiVirus|DisableRealtimeMonitoring|DisableBehaviorMonitoring|DisableRoutinelyTakingAction|DisableSpecialRunningModes)\\b[^\\n]*(?:/d\\s+1\\b|/d\\s+0x1\\b|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Windows Defender\\b[^\\n]*ServiceKeepAlive\\b[^\\n]*(?:/d\\s+0\\b|/d\\s+0x0\\b|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Windows Defender\\\\Features\\b[^\\n]*TamperProtection\\b[^\\n]*(?:/d\\s+0\\b|/d\\s+0x0\\b|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender cloud sample registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Windows Defender\\\\Spynet\\b[^\\n]*(?:(?:(?:SpyNetReporting|SpynetReporting|MAPSReporting)\\b[^\\n]*(?:/d\\s+0\\b|/d\\s+0x0\\b|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:SubmitSamplesConsent\\b[^\\n]*(?:/d\\s+2\\b|/d\\s+0x2\\b|-Value\\s+2\\b|-Value\\s+0x2\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows security registry policy weakened",
                                    pattern(
                                            "\\breg(?:\\.exe)?\\s+add\\b[^\\n]*(?:(?:Windows Defender\\b[^\\n]*(?:DisableAntiSpyware|DisableAntiVirus|DisableRealtimeMonitoring|DisableBehaviorMonitoring|DisableRoutinelyTakingAction|DisableSpecialRunningModes)\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1))|(?:Windows Defender\\b[^\\n]*ServiceKeepAlive\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:Windows Defender\\b[^\\n]*(?:SpyNetReporting|SpynetReporting|MAPSReporting)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:Windows Defender\\b[^\\n]*SubmitSamplesConsent\\b[^\\n]*(?:/d\\s+2|/d\\s+0x2))|(?:Windows Defender\\\\Features\\b[^\\n]*TamperProtection\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:DisableAntiSpyware|DisableRealtimeMonitoring|DisableBehaviorMonitoring)|(?:Policies\\\\System\\b[^\\n]*(?:EnableLUA|ConsentPromptBehaviorAdmin|PromptOnSecureDesktop)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:Terminal Server\\b[^\\n]*fDenyTSConnections\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:Terminal Server\\\\WinStations\\\\RDP-Tcp\\b[^\\n]*UserAuthentication\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:Remote Assistance\\b[^\\n]*fAllowToGetHelp\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1))|(?:Control\\\\Lsa\\b[^\\n]*(?:RunAsPPL|RunAsPPLBoot|LsaCfgFlags|LimitBlankPasswordUse)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:SecurityProviders\\\\WDigest\\b[^\\n]*UseLogonCredential\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1))|(?:PowerShell\\\\\\d+\\\\PowerShellEngine\\b[^\\n]*ExecutionPolicy\\b[^\\n]*(?:Bypass|Unrestricted))|(?:PowerShell\\\\(?:ScriptBlockLogging|Transcription|ModuleLogging)\\b[^\\n]*(?:Enable(?:ScriptBlockLogging|Transcripting|ModuleLogging)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0)))|(?:Policies\\\\Microsoft\\\\Windows\\\\PowerShell\\b[^\\n]*(?:Enable(?:ScriptBlockLogging|Transcripting|ModuleLogging)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows security registry policy weakened through PowerShell",
                                    pattern(
                                            "\\b(?:New|Set)-ItemProperty\\b[^\\n]*(?:(?:Windows Defender\\b[^\\n]*(?:DisableAntiSpyware|DisableAntiVirus|DisableRealtimeMonitoring|DisableBehaviorMonitoring|DisableRoutinelyTakingAction|DisableSpecialRunningModes)\\b[^\\n]*(?:-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Windows Defender\\b[^\\n]*ServiceKeepAlive\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Windows Defender\\b[^\\n]*(?:SpyNetReporting|SpynetReporting|MAPSReporting)\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Windows Defender\\b[^\\n]*SubmitSamplesConsent\\b[^\\n]*(?:-Value\\s+2\\b|-Value\\s+0x2\\b))|(?:Windows Defender\\\\Features\\b[^\\n]*TamperProtection\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:DisableAntiSpyware|DisableRealtimeMonitoring|DisableBehaviorMonitoring)|(?:Policies\\\\System\\b[^\\n]*(?:EnableLUA|ConsentPromptBehaviorAdmin|PromptOnSecureDesktop)\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Terminal Server\\b[^\\n]*fDenyTSConnections\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Terminal Server\\\\WinStations\\\\RDP-Tcp\\b[^\\n]*UserAuthentication\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Remote Assistance\\b[^\\n]*fAllowToGetHelp\\b[^\\n]*(?:-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Control\\\\Lsa\\b[^\\n]*(?:RunAsPPL|RunAsPPLBoot|LsaCfgFlags|LimitBlankPasswordUse)\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:SecurityProviders\\\\WDigest\\b[^\\n]*UseLogonCredential\\b[^\\n]*(?:-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:PowerShell\\\\\\d+\\\\PowerShellEngine\\b[^\\n]*ExecutionPolicy\\b[^\\n]*(?:Bypass|Unrestricted))|(?:PowerShell\\\\(?:ScriptBlockLogging|Transcription|ModuleLogging)\\b[^\\n]*(?:Enable(?:ScriptBlockLogging|Transcripting|ModuleLogging)\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b)))|(?:Policies\\\\Microsoft\\\\Windows\\\\PowerShell\\b[^\\n]*(?:Enable(?:ScriptBlockLogging|Transcripting|ModuleLogging)\\b[^\\n]*(?:-Value\\s+0\\b|-Value\\s+0x0\\b))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows SMB security registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:LanmanWorkstation\\\\Parameters\\b[^\\n]*AllowInsecureGuestAuth\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Lanman(?:Workstation|Server)\\\\Parameters\\b[^\\n]*RequireSecuritySignature\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows network logon registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Policies\\\\System\\b[^\\n]*LocalAccountTokenFilterPolicy\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Control\\\\Lsa\\b[^\\n]*(?:(?:LmCompatibilityLevel\\b[^\\n]*(?:/d\\s+[012]\\b|/d\\s+0x[012]\\b|-Value\\s+[012]\\b|-Value\\s+0x[012]\\b))|(?:NoLMHash\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)|DisableRestrictedAdmin\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b)))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows RDP security registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Terminal Server\\\\WinStations\\\\RDP-Tcp\\b[^\\n]*(?:(?:SecurityLayer|MinEncryptionLevel)\\b[^\\n]*(?:/d\\s+[01]\\b|/d\\s+0x[01]\\b|-Value\\s+[01]\\b|-Value\\s+0x[01]\\b)|(?:fDisable(?:Cdm|Clip|Ccm|Cpm|LPT|PNPRedir)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows anonymous access registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Control\\\\Lsa\\b[^\\n]*(?:(?:RestrictAnonymous(?:SAM)?\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:EveryoneIncludesAnonymous\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:RestrictNullSessAccess\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:NullSession(?:Pipes|Shares)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows credential cache registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Control\\\\Lsa\\b[^\\n]*DisableDomainCreds\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:CurrentVersion\\\\Winlogon\\b[^\\n]*CachedLogonsCount\\b[^\\n]*(?:/d\\s+(?:[1-9][0-9]*|0x[1-9a-f][0-9a-f]*)|-Value\\s+(?:[1-9][0-9]*\\b|0x[1-9a-f][0-9a-f]*\\b))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows NTLM registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:MSV1_0|Control\\\\Lsa)\\b[^\\n]*(?:(?:NtlmMin(?:Client|Server)Sec\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Restrict(?:Sending|Receiving)NTLMTraffic\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_account_policy_weaken",
                                    "Windows account password or lockout policy weakened",
                                    pattern(
                                            "\\b(?:net(?:\\.exe)?\\s+accounts\\b(?=[^\\n]*(?:(?:/minpwlen\\s*:\\s*[0-7]\\b)|(?:/lockoutthreshold\\s*:\\s*0\\b)|(?:/maxpwage\\s*:\\s*unlimited\\b)))|(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Control\\\\Lsa\\b[^\\n]*(?:PasswordComplexity|ClearTextPassword)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_account_policy_weaken",
                                    "Windows local administrator password rotation weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:Policies\\\\Microsoft Services\\\\AdmPwd|Policies\\\\Microsoft\\\\Windows\\\\LAPS)\\b[^\\n]*(?:(?:AdmPwdEnabled|BackupDirectory|PasswordExpirationProtectionEnabled)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)|(?:PasswordAgeDays\\b[^\\n]*(?:/d\\s+(?:0|[6-9][0-9]|[1-9][0-9]{2,})|/d\\s+0x0|-Value\\s+(?:0\\b|[6-9][0-9]\\b|[1-9][0-9]{2,}\\b|0x0\\b))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows UAC or installer elevation policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Policies\\\\System\\b[^\\n]*(?:FilterAdministratorToken|EnableInstallerDetection|ValidateAdminCodeSignatures|EnableSecureUIAPaths|EnableVirtualization)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Windows\\\\Installer\\b[^\\n]*AlwaysInstallElevated\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows TLS or strong crypto registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:SCHANNEL\\\\Protocols\\\\(?:SSL\\s+2\\.0|SSL\\s+3\\.0|TLS\\s+1\\.[01])\\\\(?:Server|Client)\\b[^\\n]*(?:(?:Enabled\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:DisabledByDefault\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))))|(?:\\.NETFramework\\\\v[24]\\.0(?:\\.30319)?\\b[^\\n]*(?:SchUseStrongCrypto|SystemDefaultTlsVersions)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows SmartScreen or attachment policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Explorer\\b[^\\n]*(?:SmartScreenEnabled|ShellSmartScreenLevel)\\b[^\\n]*(?:/d\\s+(?:Off|Warn|0|0x0)|-Value\\s+(?:Off|Warn|0\\b|0x0\\b)))|(?:System\\b[^\\n]*(?:EnableSmartScreen|ConfigureAppInstallControl)\\b[^\\n]*(?:/d\\s+(?:Anywhere|0|0x0)|-Value\\s+(?:Anywhere|0\\b|0x0\\b)))|(?:Attachments\\b[^\\n]*SaveZoneInformation\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows virtualization based security or credential guard weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:DeviceGuard\\b[^\\n]*(?:EnableVirtualizationBasedSecurity|RequirePlatformSecurityFeatures|HypervisorEnforcedCodeIntegrity)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Policies\\\\Microsoft\\\\Windows\\\\DeviceGuard\\\\Lsa\\b[^\\n]*LsaCfgFlags\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_registry_weaken",
                                    "Windows application control policy weakened",
                                    pattern(
                                            "\\b(?:sc(?:\\.exe)?\\s+config\\s+AppIDSvc\\s+start\\s*=\\s*disabled|Set-Service\\b(?=[^\\n]*-(?:Name|DisplayName)\\s+(?:AppIDSvc|\"[^\"]*Application Identity[^\"]*\"|'[^']*Application Identity[^']*'))(?=[^\\n]*-StartupType\\s+Disabled\\b)|Set-AppLockerPolicy\\b(?=[^\\n]*-(?:XMLPolicy|DefaultRule)\\b)|Remove-Item\\b[^\\n]*AppLocker\\\\(?:Executable|Script|Msi|PackagedApp)Rules\\b|(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Safer\\\\CodeIdentifiers\\b[^\\n]*DefaultLevel\\b[^\\n]*(?:/d\\s+0x40000|/d\\s+262144|-Value\\s+0x40000\\b|-Value\\s+262144\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_take_ownership",
                                    "Windows ownership takeover",
                                    pattern("\\btakeown\\b(?=[^\\n]*(?:[-/]r\\b|[-/]f\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_acl_rewrite",
                                    "Windows ACL rewrite",
                                    pattern(
                                            "\\bicacls\\b(?=[^\\n]*(?:[-/](?:grant|deny|remove|reset|setowner)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_execution_policy_weaken",
                                    "PowerShell execution policy weakened",
                                    pattern(
                                            "(?:\\bSet-ExecutionPolicy\\b(?=[^\\n]*(?:Bypass|Unrestricted)\\b)|\\b(?:powershell|pwsh)(?:\\.exe)?\\b(?=[^\\n]*(?:-ExecutionPolicy|-ep)\\s+(?:Bypass|Unrestricted)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_powershell_policy_weaken",
                                    "PowerShell AMSI or language-mode protection weakened",
                                    pattern(
                                            "(?:\\b(?:powershell|pwsh)(?:\\.exe)?\\b[^\\n]*(?:AmsiUtils|amsiInitFailed|amsiSession|amsiContext)|\\b(?:Set-Item|Set-ItemProperty|setx)\\b[^\\n]*__PSLockdownPolicy\\b[^\\n]*(?:\\s0\\b|-Value\\s+0\\b)|\\$ExecutionContext\\.SessionState\\.LanguageMode\\s*=\\s*[\"']?FullLanguage[\"']?)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_powershell_encoded_command",
                                    "PowerShell encoded command execution",
                                    pattern(
                                            "\\b(?:powershell|pwsh)(?:\\.exe)?\\b(?=[^\\n]*(?:[-/](?:EncodedCommand|EncodedArguments|enc|e))\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_powershell_remote_execute",
                                    "PowerShell remote content execution",
                                    pattern(
                                            "\\b(?:DownloadString|Invoke-WebRequest|Invoke-RestMethod|iwr|irm|curl|wget)\\b[^\\n]*\\|\\s*(?:Invoke-Expression|IEX)\\b|\\bDownloadFile\\s*\\([^\\n]*(?:https?://)[^\\n]*(?:;|&&|\\|\\|)[^\\n]*(?:Start-Process|&\\s*['\"]?\\.?[/\\\\]|cmd\\s+/c|powershell|pwsh)|\\b(?:Invoke-WebRequest|Invoke-RestMethod|iwr|irm|Start-BitsTransfer)\\b[^\\n]*(?:https?://)[^\\n]*(?:-(?:OutFile|Destination)\\s+|-(?:OutFile|Destination):)\\S*\\.(?:ps1|psm1|bat|cmd|exe|msi|vbs|js|hta)\\b[^\\n]*(?:;|&&|\\|\\|)[^\\n]*(?:Start-Process|&\\s*['\"]?\\.?[/\\\\]|powershell|pwsh|cmd\\s+/c)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_powershell_invoke_expression",
                                    "PowerShell dynamic expression execution",
                                    pattern("\\b(?:Invoke-Expression|IEX)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_lolbin_remote_execution",
                                    "Windows signed binary remote execution",
                                    pattern(
                                            "\\b(?:mshta|regsvr32|rundll32|certutil|bitsadmin|msiexec|installutil|regasm)(?:\\.exe)?\\b(?=[^\\n]*(?:https?://|javascript:|-urlcache\\b|/transfer\\b|scrobj\\.dll|/i\\s+https?://))|\\bwmic(?:\\.exe)?\\s+process\\s+call\\s+create\\b(?=[^\\n]*(?:https?://|powershell|cmd\\s+/c))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_audit_policy_disabled",
                                    "Windows audit policy or event log disabled",
                                    pattern(
                                            "\\b(?:auditpol(?:\\.exe)?\\s+/(?:clear|remove)\\b|auditpol(?:\\.exe)?\\s+/set\\b(?=[^\\n]*(?:/success\\s*:\\s*disable|/failure\\s*:\\s*disable))|wevtutil(?:\\.exe)?\\s+sl\\s+(?:Security|System|Application)\\b(?=[^\\n]*(?:/e\\s*:\\s*false|/enabled\\s*:\\s*false|/ms\\s*:\\s*(?:0|[1-9][0-9]{0,4})\\b))|Limit-EventLog\\b(?=[^\\n]*(?:-LogName\\s+(?:Security|System|Application)\\b))(?=[^\\n]*-MaximumSize\\s+(?:\\d{1,4}(?:KB|MB|Bytes)?|0)\\b)|Set-LogProperties\\b(?=[^\\n]*(?:-LogName\\s+(?:Security|System|Application)\\b))(?=[^\\n]*-MaximumSizeInBytes\\s+(?:0|[1-9][0-9]{0,5})\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_audit_policy_disabled",
                                    "Windows audit registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Policies\\\\System\\\\Audit\\b[^\\n]*SCENoApplyLegacyAuditPolicy\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Control\\\\Lsa\\b[^\\n]*(?:AuditBaseObjects|AuditBaseDirectories|CrashOnAuditFail)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_firewall",
                                    "Windows firewall disabled",
                                    pattern(
                                            "\\b(?:netsh\\s+advfirewall\\s+set\\s+(?:allprofiles|currentprofile|domainprofile|privateprofile|publicprofile)\\s+state\\s+off|netsh\\s+advfirewall\\s+firewall\\s+set\\s+rule\\b[^\\n]*\\bnew\\s+enable\\s*=\\s*no\\b|Set-NetFirewallProfile\\b(?=[^\\n]*-Enabled\\s+(?:\\$?false|0)\\b)|(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*FirewallPolicy\\\\(?:DomainProfile|PublicProfile|StandardProfile)\\b[^\\n]*EnableFirewall\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_firewall",
                                    "Windows firewall logging disabled",
                                    pattern(
                                            "\\b(?:Set-NetFirewallProfile\\b(?=[^\\n]*-(?:LogAllowed|LogBlocked)\\s+(?:\\$?false|0)\\b)|(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*FirewallPolicy\\\\(?:DomainProfile|PublicProfile|StandardProfile)\\\\Logging\\b[^\\n]*(?:LogDroppedPackets|LogSuccessfulConnections)\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_firewall_inbound_allow",
                                    "Windows inbound firewall allow rule added",
                                    pattern(
                                            "\\b(?:(?:New|Set)-NetFirewallRule\\b(?=[^\\n]*-Direction\\s+Inbound\\b)(?=[^\\n]*-Action\\s+Allow\\b)|(?:New|Set)-NetFirewallRule\\b(?=[^\\n]*-Action\\s+Allow\\b)(?=[^\\n]*-RemoteAddress\\s+(?:Any|0\\.0\\.0\\.0/0|::/0)\\b)|Set-NetFirewallProfile\\b(?=[^\\n]*-DefaultInboundAction\\s+Allow\\b)|(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*FirewallPolicy\\\\(?:DomainProfile|PublicProfile|StandardProfile)\\b[^\\n]*DefaultInboundAction\\b[^\\n]*(?:/d\\s+2|/d\\s+0x2|-Value\\s+2\\b|-Value\\s+0x2\\b)|Enable-NetFirewallRule\\b(?=[^\\n]*(?:-DisplayName|-Name|-Group)\\s+(?:\"[^\"]*(?:RDP|Remote\\s+Desktop|Remote\\s+Assistance|WinRM|OpenSSH|SSH)[^\"]*\"|'[^']*(?:RDP|Remote\\s+Desktop|Remote\\s+Assistance|WinRM|OpenSSH|SSH)[^']*'|\\S*(?:RDP|RemoteDesktop|RemoteAssistance|WinRM|OpenSSH|SSH)\\S*))|netsh\\s+advfirewall\\s+set\\s+(?:allprofiles|currentprofile|domainprofile|privateprofile|publicprofile)\\s+firewallpolicy\\s+allowinbound\\b|netsh\\s+advfirewall\\s+firewall\\s+add\\s+rule\\b(?=[^\\n]*\\bdir\\s*=\\s*in\\b)(?=[^\\n]*\\baction\\s*=\\s*allow\\b)|netsh\\s+advfirewall\\s+firewall\\s+set\\s+rule\\b(?=[^\\n]*\\bnew\\s+dir\\s*=\\s*in\\b)(?=[^\\n]*\\baction\\s*=\\s*allow\\b)|netsh\\s+advfirewall\\s+firewall\\s+set\\s+rule\\b(?=[^\\n]*(?:\\bgroup\\s*=\\s*\"?[^\"]*(?:Remote\\s+Desktop|Remote\\s+Assistance|WinRM|OpenSSH|SSH)[^\"]*\"?|\\bname\\s*=\\s*\"?[^\"]*(?:Remote\\s+Desktop|Remote\\s+Assistance|RDP|WinRM|OpenSSH|SSH)[^\"]*\"?))(?=[^\\n]*\\bnew\\s+enable\\s*=\\s*yes\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_firewall_inbound_allow",
                                    "Windows remote administration firewall group enabled",
                                    pattern(
                                            "\\b(?:Enable-NetFirewallRule\\b(?=[^\\n]*(?:-DisplayName|-DisplayGroup|-Name|-Group)\\s+(?:\"[^\"]*(?:WMI|Windows\\s+Management\\s+Instrumentation|Remote\\s+Event\\s+Log|Remote\\s+Service\\s+Management)[^\"]*\"|'[^']*(?:WMI|Windows\\s+Management\\s+Instrumentation|Remote\\s+Event\\s+Log|Remote\\s+Service\\s+Management)[^']*'|\\S*(?:WMI|RemoteEventLog|RemoteServiceManagement)\\S*))|netsh\\s+advfirewall\\s+firewall\\s+set\\s+rule\\b(?=[^\\n]*(?:\\bgroup\\s*=\\s*\"?[^\"]*(?:Windows\\s+Management\\s+Instrumentation|WMI|Remote\\s+Event\\s+Log|Remote\\s+Service\\s+Management)[^\"]*\"?|\\bname\\s*=\\s*\"?[^\"]*(?:WMI|Remote\\s+Event\\s+Log|Remote\\s+Service\\s+Management)[^\"]*\"?))(?=[^\\n]*\\bnew\\s+enable\\s*=\\s*yes\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_firewall_inbound_allow",
                                    "Windows inbound firewall registry rule added",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*FirewallRules\\b(?=[^\\n]*(?:Action=Allow|Action\\s*=\\s*Allow))(?=[^\\n]*(?:Dir=In|Direction=In|Dir\\s*=\\s*In|Direction\\s*=\\s*In))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender protection disabled",
                                    pattern(
                                            "\\bSet-MpPreference\\b(?=[^\\n]*(?:-(?:DisableRealtimeMonitoring|DisableBehaviorMonitoring|DisableIOAVProtection|DisableScriptScanning|DisableIntrusionPreventionSystem|DisableEmailScanning|DisableBlockAtFirstSeen|DisableArchiveScanning|DisableRemovableDriveScanning|DisableScanningMappedNetworkDrivesForFullScan|DisableScanningNetworkFiles|DisableCloudProtection)\\s+(?:\\$?true|1)\\b|-EnableControlledFolderAccess\\s+(?:Disabled|0)\\b|-SubmitSamplesConsent\\s+(?:NeverSend|2)\\b|-PUAProtection\\s+(?:Disabled|0)\\b|-MAPSReporting\\s+(?:Disabled|0)\\b|-AttackSurfaceReductionRules_Action\\s+(?:Disabled|0)\\b|-ThreatIDDefaultAction_Actions\\s+(?:Allow|NoAction)\\b|-(?:Low|Moderate|High|Severe|Unknown)ThreatDefaultAction\\s+(?:Allow|NoAction)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender realtime registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Windows Defender\\\\Real-Time Protection\\b[^\\n]*(?:Disable(?:RealtimeMonitoring|BehaviorMonitoring|OnAccessProtection|ScanOnRealtimeEnable|IOAVProtection|ScriptScanning|IntrusionPreventionSystem)\\b[^\\n]*(?:/d\\s+1\\b|/d\\s+0x1\\b|-Value\\s+1\\b|-Value\\s+0x1\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender visibility or notifications disabled",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Windows Defender\\\\Reporting\\b[^\\n]*Disable(?:Enhanced)?Notifications\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Windows Defender\\\\UX Configuration\\b[^\\n]*UILockdown\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:Explorer\\b[^\\n]*HideSCAHealth\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender cloud protection policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Windows Defender\\b[^\\n]*(?:(?:MpEngine\\b[^\\n]*MpCloudBlockLevel\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:(?:SignatureDisableUpdateOnStartupWithoutEngine|DisableCatchupFullScan|DisableCatchupQuickScan)\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender Exploit Guard registry policy weakened",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:Windows Defender\\\\Windows Defender Exploit Guard\\\\Controlled Folder Access\\b[^\\n]*EnableControlledFolderAccess\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b))|(?:Windows Defender\\\\Windows Defender Exploit Guard\\\\Network Protection\\b[^\\n]*EnableNetworkProtection\\b[^\\n]*(?:/d\\s+[01]\\b|/d\\s+0x[01]\\b|-Value\\s+[01]\\b|-Value\\s+0x[01]\\b))|(?:Windows Defender\\\\Windows Defender Exploit Guard\\\\ASR\\\\Rules\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0|-Value\\s+0\\b|-Value\\s+0x0\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_defender_exclusion",
                                    "Windows Defender exclusion or protection rule changed",
                                    pattern(
                                            "\\b(?:Add-MpPreference|Set-MpPreference|Remove-MpPreference)\\b(?=[^\\n]*(?:-ExclusionPath|-ExclusionProcess|-ExclusionExtension|-ExclusionIpAddress|-AttackSurfaceReductionRules_(?:Ids|Actions))\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_defender_exclusion",
                                    "Windows Defender exclusion registry policy changed",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Windows Defender\\\\Exclusions\\\\(?:Paths|Processes|Extensions|IpAddresses)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_defender_exclusion",
                                    "Windows Defender controlled-folder access allow list changed",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*Windows Defender\\\\Windows Defender Exploit Guard\\\\Controlled Folder Access\\\\(?:AllowedApplications|ProtectedFolders)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_update_policy_weaken",
                                    "Windows update policy weakened",
                                    pattern(
                                            "\\b(?:(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*(?:(?:WindowsUpdate\\\\AU\\b[^\\n]*(?:NoAutoUpdate\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b)|AUOptions\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))|(?:WindowsUpdate\\b[^\\n]*DisableWindowsUpdateAccess\\b[^\\n]*(?:/d\\s+1|/d\\s+0x1|-Value\\s+1\\b|-Value\\s+0x1\\b))))|sc(?:\\.exe)?\\s+config\\s+(?:wuauserv|UsoSvc|bits)\\s+start\\s*=\\s*disabled|Set-Service\\b(?=[^\\n]*-(?:Name|DisplayName)\\s+(?:wuauserv|UsoSvc|bits|\"[^\"]*(?:Windows Update|Update Orchestrator|Background Intelligent Transfer)[^\"]*\"|'[^']*(?:Windows Update|Update Orchestrator|Background Intelligent Transfer)[^']*'))(?=[^\\n]*-StartupType\\s+Disabled\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender scheduled task disabled",
                                    pattern(
                                            "\\b(?:schtasks(?:\\.exe)?\\s+/change\\b(?=[^\\n]*/disable\\b)(?=[^\\n]*\\\\Microsoft\\\\Windows\\\\Windows\\s+Defender\\\\)|Disable-ScheduledTask\\b(?=[^\\n]*-TaskPath\\s+['\"]?\\\\Microsoft\\\\Windows\\\\Windows\\s+Defender\\\\))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_security_task_disabled",
                                    "Windows security maintenance scheduled task disabled",
                                    pattern(
                                            "\\b(?:schtasks(?:\\.exe)?\\s+/change\\b(?=[^\\n]*/disable\\b)(?=[^\\n]*(?:Windows\\s+Defender|WindowsUpdate|UpdateOrchestrator|WindowsBackup|SystemRestore|BitLocker))|Disable-ScheduledTask\\b(?=[^\\n]*(?:-TaskPath\\s+['\"]?\\\\Microsoft\\\\Windows\\\\(?:Windows\\s+Defender|WindowsUpdate|UpdateOrchestrator|WindowsBackup|SystemRestore|BitLocker)|-TaskName\\s+['\"]?(?:Windows\\s+Defender|WindowsUpdate|Scheduled\\s+Start|SR|BitLocker))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender service stopped or disabled",
                                    pattern(
                                            "\\b(?:(?:sc(?:\\.exe)?\\s+(?:stop|pause|delete)\\s+(?:WinDefend|WdNisSvc|Sense|SecurityHealthService)\\b)|(?:sc(?:\\.exe)?\\s+config\\s+(?:WinDefend|WdNisSvc|Sense|SecurityHealthService)\\s+start\\s*=\\s*disabled)|(?:(?:Stop-Service|Suspend-Service)\\b(?=[^\\n]*-(?:Name|DisplayName)\\s+(?:WinDefend|WdNisSvc|Sense|SecurityHealthService|\"[^\"]*(?:Microsoft Defender|Windows Defender|Security Health)[^\"]*\"|'[^']*(?:Microsoft Defender|Windows Defender|Security Health)[^']*')))|(?:Set-Service\\b(?=[^\\n]*-(?:Name|DisplayName)\\s+(?:WinDefend|WdNisSvc|Sense|SecurityHealthService|\"[^\"]*(?:Microsoft Defender|Windows Defender|Security Health)[^\"]*\"|'[^']*(?:Microsoft Defender|Windows Defender|Security Health)[^']*'))(?=[^\\n]*(?:-StartupType\\s+Disabled\\b|-Status\\s+Stopped\\b))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_stop_service",
                                    "Windows service stopped or disabled",
                                    pattern(
                                            "\\b(?:sc(?:\\.exe)?\\s+(?:stop|pause|delete|config\\s+\\S+\\s+start\\s*=\\s*disabled)|(?:Stop-Service|Suspend-Service)\\b(?=[^\\n]*(?:-Force\\b|-Name\\s+|-DisplayName\\s+))|Set-Service\\b(?=[^\\n]*(?:-StartupType\\s+Disabled\\b|-Status\\s+Stopped\\b)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_stop_service",
                                    "Windows security service disabled through registry",
                                    pattern(
                                            "\\b(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*SYSTEM\\\\CurrentControlSet\\\\Services\\\\(?:WinDefend|WdNisSvc|Sense|wscsvc|SecurityHealthService|mpssvc|EventLog)\\b[^\\n]*(?:\\bStart\\b[^\\n]*(?:/d\\s+4|/d\\s+0x4|-Value\\s+4\\b|-Value\\s+0x4\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_remote_service_enabled",
                                    "Windows remote management service enabled",
                                    pattern(
                                            "\\b(?:Enable-PSRemoting\\b|winrm\\s+quickconfig\\b|sc(?:\\.exe)?\\s+config\\s+(?:WinRM|RemoteRegistry|TermService|RemoteAccess|sshd)\\s+start\\s*=\\s*(?:auto|demand)|(?:reg(?:\\.exe)?\\s+add|(?:New|Set)-ItemProperty\\b)\\b[^\\n]*SYSTEM\\\\CurrentControlSet\\\\Services\\\\(?:WinRM|RemoteRegistry|TermService|RemoteAccess|sshd)\\b[^\\n]*(?:\\bStart\\b[^\\n]*(?:/d\\s+(?:2|3|0x2|0x3)|-Value\\s+(?:2|3|0x2|0x3)\\b))|Set-Service\\b(?=[^\\n]*-(?:Name|DisplayName)\\s+(?:WinRM|RemoteRegistry|TermService|RemoteAccess|sshd|\"[^\"]*(?:WinRM|Remote Registry|Remote Desktop|Routing and Remote Access|OpenSSH)[^\"]*\"|'[^']*(?:WinRM|Remote Registry|Remote Desktop|Routing and Remote Access|OpenSSH)[^']*'))(?=[^\\n]*-StartupType\\s+(?:Automatic|Manual))|Start-Service\\b(?=[^\\n]*-(?:Name|DisplayName)\\s+(?:WinRM|RemoteRegistry|TermService|RemoteAccess|sshd|\"[^\"]*(?:WinRM|Remote Registry|Remote Desktop|Routing and Remote Access|OpenSSH)[^\"]*\"|'[^']*(?:WinRM|Remote Registry|Remote Desktop|Routing and Remote Access|OpenSSH)[^']*')))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_remote_auth_weaken",
                                    "Windows remote management authentication or encryption weakened",
                                    pattern(
                                            "\\b(?:winrm\\s+set\\s+winrm/config/(?:service|client)(?:/auth)?\\b(?=[^\\n]*(?:AllowUnencrypted\\s*=\\s*\"?true\"?|Basic\\s*=\\s*\"?true\"?|CredSSP\\s*=\\s*\"?true\"?|TrustedHosts\\s*=\\s*\"?\\*\"?))|Set-Item\\b[^\\n]*WSMan:\\\\localhost\\\\(?:Service|Client)(?:\\\\Auth)?\\\\(?:AllowUnencrypted|Basic|CredSSP|TrustedHosts)\\b[^\\n]*(?:-Value\\s+(?:\\$?true\\b|1\\b|\"?\\*\"?(?:\\s|$))))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_service_privilege_or_recovery_change",
                                    "Windows service privilege or recovery policy changed",
                                    pattern(
                                            "\\b(?:sc(?:\\.exe)?\\s+config\\s+\\S+\\s+obj\\s*=\\s*\"?(?:LocalSystem|NT\\s+AUTHORITY\\\\SYSTEM|Administrator|\\.\\\\Administrator)\"?(?=\\s|$)|sc(?:\\.exe)?\\s+failure\\s+\\S+\\s+actions\\s*=\\s*(?:restart|run|reboot)(?:/|\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_persistence_registration",
                                    "Windows scheduled task or startup persistence",
                                    pattern(
                                            "\\b(?:schtasks(?:\\.exe)?\\s+/create|Register-ScheduledTask\\b|New-ScheduledTask\\b|reg(?:\\.exe)?\\s+(?:add|copy)\\b[^\\n]*(?:\\\\CurrentVersion\\\\Run(?:Once)?\\b|\\\\RunServices(?:Once)?\\b)|(?:New|Set)-ItemProperty\\b[^\\n]*(?:\\\\CurrentVersion\\\\Run(?:Once)?\\b|\\\\RunServices(?:Once)?\\b)|(?:New-CimInstance|Set-WmiInstance|New-WmiObject)\\b[^\\n]*(?:__EventFilter|CommandLineEventConsumer|ActiveScriptEventConsumer|__FilterToConsumerBinding)|(?:copy|xcopy|robocopy|Copy-Item|Set-Content|Add-Content|Out-File)\\b[^\\n]*(?:\\\\Microsoft\\\\Windows\\\\Start\\s+Menu\\\\Programs\\\\Startup\\\\|\\\\Start Menu\\\\Programs\\\\Startup\\\\))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_export_credentials",
                                    "Windows credential or certificate export",
                                    pattern(
                                            "\\b(?:Export-PfxCertificate|ConvertFrom-SecureString|vaultcmd(?:\\.exe)?\\s+/exportcreds\\b|Export-Clixml\\b[^\\n]*(?:credential|secret|token|password)|Get-Credential\\b[^\\n]*\\|[^\\n]*Export-Clixml)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_credential_material_dump",
                                    "Windows credential material dumped",
                                    pattern(
                                            "\\b(?:procdump(?:64)?(?:\\.exe)?\\b[^\\n]*\\blsass(?:\\.exe)?\\b|rundll32(?:\\.exe)?\\s+comsvcs\\.dll,\\s*MiniDump\\b[^\\n]*\\blsass\\b|reg(?:\\.exe)?\\s+save\\s+HKLM\\\\(?:SAM|SECURITY|SYSTEM)\\b|ntdsutil(?:\\.exe)?\\b[^\\n]*(?:ifm|create\\s+full|activate\\s+instance\\s+ntds)|esentutl(?:\\.exe)?\\b[^\\n]*(?:ntds\\.dit|\\\\SAM\\b|\\\\SECURITY\\b|\\\\SYSTEM\\b)|(?:copy|xcopy|robocopy|Copy-Item)\\b[^\\n]*(?:HarddiskVolumeShadowCopy[^\\n]*(?:\\\\SAM\\b|\\\\SECURITY\\b|\\\\SYSTEM\\b|ntds\\.dit)|\\\\Microsoft\\\\(?:Credentials|Protect)\\\\|\\\\Microsoft\\\\Crypto\\\\RSA\\\\|\\\\(?:Google\\\\Chrome|Microsoft\\\\Edge|BraveSoftware\\\\Brave-Browser)\\\\User Data\\\\[^\\n]*(?:Login Data|Local State|Cookies)\\b|\\\\Mozilla\\\\Firefox\\\\Profiles\\\\[^\\n]*(?:logins\\.json|key4\\.db|cookies\\.sqlite))|(?:mimikatz|pypykatz|secretsdump(?:\\.py)?|lsassy|nanodump|dumpert)\\b|sekurlsa::)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_credential_manager_read",
                                    "Windows credential manager read",
                                    pattern(
                                            "\\b(?:cmdkey(?:\\.exe)?\\s+/list\\b|vaultcmd(?:\\.exe)?\\s+/(?:listcreds|listvaults)\\b|rundll32(?:\\.exe)?\\s+keymgr\\.dll,KRShowKeyMgr\\b|(?:Get-StoredCredential|Get-VaultCredential|Get-SecretInfo|Get-Secret|Get-SecretVault|Unlock-SecretVault)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_credential_manager_change",
                                    "Windows credential manager changed",
                                    pattern(
                                            "\\b(?:cmdkey(?:\\.exe)?\\s+/(?:add|delete)\\b|vaultcmd(?:\\.exe)?\\s+/(?:addcreds|deletecreds)\\b|(?:New|Set|Remove)-StoredCredential\\b|New-Credential\\b|(?:New|Set|Remove)-Secret\\b|(?:Register|Unregister|Set)-SecretVault\\b|Remove-VaultCredential\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "powershell_sensitive_file_write",
                                    "PowerShell write to sensitive credential file",
                                    pattern(
                                            "(?:\\b(?:Set-Content|Add-Content|Out-File|sc|ac)\\b[^\\n]*(?:-(?:Path|LiteralPath|FilePath)\\b\\s*(?::|=|\\s+)\\s*)?[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + "|\\b(?:New-Item|ni)\\b(?=[^\\n|;&]*\\s-Value\\b)[^\\n]*(?:-(?:Path|LiteralPath|Name)\\b\\s*(?::|=|\\s+)\\s*)?[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + "|\\b(?:New-Item|ni)\\b\\s+[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?\\s+[^\\s|;&]+)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "powershell_sensitive_file_copy",
                                    "PowerShell copy or move to sensitive credential file",
                                    pattern(
                                            "\\b(?:Copy-Item|Move-Item|cp|cpi|mv|mi)\\b[^\\n]*(?:(?:-Destination|-Path|-LiteralPath)(?:\\s+|[:=]))?[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_sensitive_file_copy",
                                    "Windows copy or move to sensitive credential file",
                                    pattern(
                                            "\\b(?:copy|move)\\b[^\\n]*\\s[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?(?:\\s+(?:/[A-Za-z?]+|-[^\\s]+))*"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_delete_shadow_copies",
                                    "Windows shadow copy deletion",
                                    pattern(
                                            "\\bvssadmin\\s+(?:delete|create|list)\\s+shadows\\b|\\bwmic(?:\\.exe)?\\s+shadowcopy\\s+(?:call\\s+create|list)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_delete_backup",
                                    "Windows backup deletion",
                                    pattern(
                                            "\\b(?:wbadmin\\s+delete\\s+(?:systemstatebackup|backup|catalog)|Remove-ComputerRestorePoint\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_recovery",
                                    "Windows recovery disabled or boot entry removed",
                                    pattern(
                                            "\\b(?:reagentc\\s+[-/]disable|bcdedit\\s+[-/](?:delete|deletevalue|set)\\b|vssadmin\\s+resize\\s+shadowstorage\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_bitlocker_protection_weaken",
                                    "Windows BitLocker protection disabled or suspended",
                                    pattern(
                                            "\\b(?:Disable-BitLocker|Suspend-BitLocker|Remove-BitLockerKeyProtector|manage-bde(?:\\.exe)?\\s+(?:-(?:off|protectors\\s+-(?:disable|delete))|/(?:off|protectors\\s+/(?:disable|delete))))\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "python_rmtree",
                                    "Python recursive delete",
                                    pattern("\\bshutil\\.rmtree\\s*\\("),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_os_remove",
                                    "Python file delete",
                                    pattern("\\bos\\.(remove|unlink)\\s*\\("),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_os_system",
                                    "Python shell execution",
                                    pattern("\\bos\\.system\\s*\\("),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_subprocess_credential_file_output",
                                    "Python subprocess reads credential file content",
                                    pattern(
                                            "\\bsubprocess\\.(?:run|Popen|call|check_call|check_output)\\s*\\([^\\n]*(?:cat|type|Get-Content|gc)[^\\n)]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_subprocess",
                                    "Python subprocess execution",
                                    pattern(
                                            "\\bsubprocess\\.(run|Popen|call|check_call|check_output)\\s*\\("),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_unsafe_deserialization",
                                    "Python unsafe deserialization",
                                    pattern(
                                            "\\b(?:pickle|cPickle|dill)\\.loads?\\s*\\(|\\byaml\\.load\\s*\\((?![^\\n)]*SafeLoader)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_dynamic_code_execution",
                                    "Python dynamic code execution",
                                    pattern(
                                            "(?<![\\w.])(?:eval|exec)\\s*\\(|\\bcompile\\s*\\([^\\n)]*,[^\\n)]*,\\s*['\"]exec['\"]"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_http_credential_header_send",
                                    "Python sends credential through HTTP header",
                                    pattern(
                                            "\\b(?:requests|httpx)\\.(?:request|get|post|put|patch|delete)\\s*\\([^\\n]*(?:headers\\s*=\\s*\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*:|headers\\s*=\\s*dict\\s*\\([^\\n)]*"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "\\s*=)|\\burllib\\.request\\.Request\\s*\\([^\\n]*(?:headers\\s*=\\s*\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*:|add_header\\s*\\(\\s*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*,)|\\b[A-Za-z_][A-Za-z0-9_]*\\.add_header\\s*\\(\\s*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*,|\\b[A-Za-z_][A-Za-z0-9_]*\\.headers\\.update\\s*\\(\\s*\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*:"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_base64_stdout",
                                    "Python prints base64-encoded credential file content",
                                    pattern(
                                            "(?:\\b(?:print|sys\\.(?:stdout|stderr)\\.write|(?:logging|logger)\\.(?:debug|info|warning|warn|error|exception|critical))\\s*\\([^\\n]*(?:base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\(\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\)))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\(\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:print|sys\\.(?:stdout|stderr)\\.write|(?:logging|logger)\\.(?:debug|info|warning|warn|error|exception|critical))\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_stdout",
                                    "Python prints credential file content",
                                    pattern(
                                            "\\b(?:print|sys\\.(?:stdout|stderr)\\.write|(?:logging|logger)\\.(?:debug|info|warning|warn|error|exception|critical))\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_variable_stdout",
                                    "Python prints credential file content variable",
                                    pattern(
                                            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:print|sys\\.(?:stdout|stderr)\\.write|(?:logging|logger)\\.(?:debug|info|warning|warn|error|exception|critical))\\s*\\([^\\n)]*\\b\\1\\b"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_base64_exception_output",
                                    "Python exception exposes base64-encoded credential file content",
                                    pattern(
                                            "(?:\\braise\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^\\n]*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[\\s\\S]{0,1200}\\braise\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_exception_output",
                                    "Python exception exposes credential file content",
                                    pattern(
                                            "(?:\\braise\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\braise\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_base64_debug_artifact_write",
                                    "Python writes base64-encoded credential file content into debug artifact",
                                    pattern(
                                            "(?:\\b(?:open|(?:pathlib\\.)?Path)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n)]*\\)[^\\n]{0,240}(?:write|write_text|write_bytes)\\s*\\([^\\n]*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[\\s\\S]{0,1200}\\b(?:open|(?:pathlib\\.)?Path)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n)]*\\)[^\\n]{0,240}(?:write|write_text|write_bytes)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_debug_artifact_write",
                                    "Python writes credential file content into debug artifact",
                                    pattern(
                                            "(?:\\b(?:open|(?:pathlib\\.)?Path)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n)]*\\)[^\\n]{0,240}(?:write|write_text|write_bytes)\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:open|(?:pathlib\\.)?Path)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n)]*\\)[^\\n]{0,240}(?:write|write_text|write_bytes)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_base64_archive_artifact_write",
                                    "Python writes base64-encoded credential file content into archive artifact",
                                    pattern(
                                            "(?:\\b(?:zipfile\\.[A-Za-z0-9_]+|tarfile\\.[A-Za-z0-9_]+)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:write|writestr|add|addfile)\\s*\\([^\\n]*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[\\s\\S]{0,1200}\\b(?:zipfile\\.[A-Za-z0-9_]+|tarfile\\.[A-Za-z0-9_]+)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:write|writestr|add|addfile)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_archive_artifact_write",
                                    "Python writes credential file content into archive artifact",
                                    pattern(
                                            "(?:\\b(?:zipfile\\.[A-Za-z0-9_]+|tarfile\\.[A-Za-z0-9_]+)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:write|writestr|add|addfile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:zipfile\\.[A-Za-z0-9_]+|tarfile\\.[A-Za-z0-9_]+)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:write|writestr|add|addfile)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_base64_clipboard_export",
                                    "Python copies base64-encoded credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:pyperclip|clipboard)\\.(?:copy|set)\\s*\\([^\\n]*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[\\s\\S]{0,1200}\\b(?:pyperclip|clipboard)\\.(?:copy|set)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_clipboard_export",
                                    "Python copies credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:pyperclip|clipboard)\\.(?:copy|set)\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:pyperclip|clipboard)\\.(?:copy|set)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_base64_notification_output",
                                    "Python notification exposes base64-encoded credential file content",
                                    pattern(
                                            "(?:\\b(?:notify2|plyer\\.notification|notification)\\.(?:notify|Notification)\\s*\\([^\\n]*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[\\s\\S]{0,1200}\\b(?:notify2|plyer\\.notification|notification)\\.(?:notify|Notification)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_notification_output",
                                    "Python notification exposes credential file content",
                                    pattern(
                                            "(?:\\b(?:notify2|plyer\\.notification|notification)\\.(?:notify|Notification)\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:notify2|plyer\\.notification|notification)\\.(?:notify|Notification)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_http_credential_file_variable_send",
                                    "Python sends credential file content variable through HTTP",
                                    pattern(
                                            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\)|base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\(\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\)))[\\s\\S]{0,1200}\\b(?:requests|httpx)\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:data|content|json|files)\\s*=\\s*(?:\\1\\b|\\{[^\\n}]*\\1\\b|dict\\s*\\([^\\n)]*\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_http_credential_body_send",
                                    "Python sends credential through HTTP body",
                                    pattern(
                                            "\\b(?:requests|httpx)\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:json|data)\\s*=\\s*(?:\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "[\"']\\s*:|dict\\s*\\([^\\n)]*"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "\\s*=)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_http_credential_file_send",
                                    "Python sends credential file through HTTP",
                                    pattern(
                                            "\\b(?:requests|httpx)\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:files|data|content)\\s*=\\s*(?:\\{[^\\n}]*open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|(?:pathlib\\.)?Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(|base64\\.(?:b64encode|urlsafe_b64encode|standard_b64encode)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc))"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "js_child_process_credential_file_output",
                                    "Node child_process reads credential file content",
                                    pattern(
                                            "(?:\\bchild_process\\.(?:exec|execSync|spawn|spawnSync|execFile|execFileSync)|\\brequire\\s*\\(\\s*['\"]child_process['\"]\\s*\\)\\s*\\.(?:exec|execSync|spawn|spawnSync|execFile|execFileSync))\\s*\\([^\\n]*(?:cat|type|Get-Content|gc)[^\\n)]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_child_process",
                                    "Node child_process execution",
                                    pattern(
                                            "\\bchild_process\\.(exec|execSync|spawn|spawnSync|execFile|execFileSync)\\s*\\("),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_require_child_process",
                                    "Node child_process import",
                                    pattern("require\\s*\\(\\s*['\"]child_process['\"]\\s*\\)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_dynamic_code_execution",
                                    "Node dynamic code execution",
                                    pattern(
                                            "\\b(?:eval|Function)\\s*\\(|\\bnew\\s+Function\\s*\\(|\\bvm\\.runIn(?:ThisContext|NewContext|Context)\\s*\\("),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_http_credential_header_send",
                                    "JavaScript sends credential through HTTP header",
                                    pattern(
                                            "\\b(?:fetch|axios\\.(?:request|get|post|put|patch|delete))\\s*\\([^\\n]*(?:headers\\s*:\\s*\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*:|headers\\s*:\\s*new\\s+Headers\\s*\\(\\s*\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*:)|\\bheaders\\s*\\.\\s*(?:set|append)\\s*\\(\\s*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*,|\\baxios\\.defaults\\.headers(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\s*\\[\\s*[\"']"
                                                    + SENSITIVE_HTTP_HEADER_NAME
                                                    + "[\"']\\s*\\]\\s*="),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_base64_stdout",
                                    "JavaScript prints base64-encoded credential file content",
                                    pattern(
                                            "(?:\\b(?:console\\.(?:log|info|warn|error)|process\\.(?:stdout|stderr)\\.write)\\s*\\([^\\n]*(?:Buffer\\.from\\s*\\(\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))\\s*\\)\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:Buffer\\.from\\s*\\(\\s*)?(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))(?:\\s*\\))?\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)[\\s\\S]{0,1200}\\b(?:console\\.(?:log|info|warn|error)|process\\.(?:stdout|stderr)\\.write)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_stdout",
                                    "JavaScript prints credential file content",
                                    pattern(
                                            "\\b(?:console\\.(?:log|info|warn|error)|process\\.(?:stdout|stderr)\\.write)\\s*\\([^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_variable_stdout",
                                    "JavaScript prints credential file content variable",
                                    pattern(
                                            "\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\b(?:console\\.(?:log|info|warn|error)|process\\.(?:stdout|stderr)\\.write)\\s*\\([^\\n)]*\\b\\1\\b"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_base64_exception_output",
                                    "JavaScript exception exposes base64-encoded credential file content",
                                    pattern(
                                            "(?:\\bthrow\\s+new\\s+Error\\s*\\([^\\n]*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))[\\s\\S]{0,1200}\\bthrow\\s+new\\s+Error\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_exception_output",
                                    "JavaScript exception exposes credential file content",
                                    pattern(
                                            "(?:\\bthrow\\s+new\\s+Error\\s*\\([^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\bthrow\\s+new\\s+Error\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_base64_debug_artifact_write",
                                    "JavaScript writes base64-encoded credential file content into debug artifact",
                                    pattern(
                                            "(?:\\bfs\\.(?:writeFileSync|writeFile|appendFileSync|appendFile)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n,]*,[^\\n]*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))[\\s\\S]{0,1200}\\bfs\\.(?:writeFileSync|writeFile|appendFileSync|appendFile)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n,]*,[^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_debug_artifact_write",
                                    "JavaScript writes credential file content into debug artifact",
                                    pattern(
                                            "(?:\\bfs\\.(?:writeFileSync|writeFile|appendFileSync|appendFile)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n,]*,[^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\bfs\\.(?:writeFileSync|writeFile|appendFileSync|appendFile)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n,]*,[^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_base64_archive_artifact_write",
                                    "JavaScript writes base64-encoded credential file content into archive artifact",
                                    pattern(
                                            "(?:\\b(?:archiver|zip|tar)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:append|file|directory|add|entry)\\s*\\([^\\n]*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))[\\s\\S]{0,1200}\\b(?:archiver|zip|tar)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:append|file|directory|add|entry)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_archive_artifact_write",
                                    "JavaScript writes credential file content into archive artifact",
                                    pattern(
                                            "(?:\\b(?:archiver|zip|tar)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:append|file|directory|add|entry)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\b(?:archiver|zip|tar)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:append|file|directory|add|entry)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_base64_clipboard_export",
                                    "JavaScript copies base64-encoded credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:clipboardy|clipboard|navigator\\.clipboard)\\.(?:writeSync|write|writeText|copy)\\s*\\([^\\n]*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))[\\s\\S]{0,1200}\\b(?:clipboardy|clipboard|navigator\\.clipboard)\\.(?:writeSync|write|writeText|copy)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_clipboard_export",
                                    "JavaScript copies credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:clipboardy|clipboard|navigator\\.clipboard)\\.(?:writeSync|write|writeText|copy)\\s*\\([^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\b(?:clipboardy|clipboard|navigator\\.clipboard)\\.(?:writeSync|write|writeText|copy)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_base64_notification_output",
                                    "JavaScript notification exposes base64-encoded credential file content",
                                    pattern(
                                            "(?:\\b(?:new\\s+Notification|notifier\\.(?:notify|send)|nodeNotifier\\.(?:notify|send))\\s*\\([^\\n]*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:Buffer\\.from\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\)|(?:fs\\.readFileSync|await\\s+fs\\.promises\\.readFile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\\n]*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))[\\s\\S]{0,1200}\\b(?:new\\s+Notification|notifier\\.(?:notify|send)|nodeNotifier\\.(?:notify|send))\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_notification_output",
                                    "JavaScript notification exposes credential file content",
                                    pattern(
                                            "(?:\\b(?:new\\s+Notification|notifier\\.(?:notify|send)|nodeNotifier\\.(?:notify|send))\\s*\\([^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\b(?:new\\s+Notification|notifier\\.(?:notify|send)|nodeNotifier\\.(?:notify|send))\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_http_credential_file_variable_send",
                                    "JavaScript sends credential file content variable through HTTP",
                                    pattern(
                                            "\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:(?:Buffer\\.from\\s*\\(\\s*)?(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))(?:\\s*\\))?(?:\\s*\\)*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))?)[\\s\\S]{0,1200}(?:\\bfetch\\s*\\([^\\n]*(?:body\\s*:\\s*\\1\\b|JSON\\.stringify\\s*\\(\\s*\\{[^\\n}]*\\1\\b)|\\baxios\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:data\\s*:\\s*\\1\\b|\\{[^\\n}]*\\1\\b))"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_http_credential_body_send",
                                    "JavaScript sends credential through HTTP body",
                                    pattern(
                                            "\\b(?:fetch|axios\\.(?:request|post|put|patch))\\s*\\([^\\n]*(?:body\\s*:\\s*JSON\\.stringify\\s*\\(\\s*\\{[^\\n}]*[\"']"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "[\"']\\s*:|data\\s*:\\s*\\{[^\\n}]*[\"']?"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "[\"']?\\s*:)|\\baxios\\.(?:post|put|patch)\\s*\\([^,\\n]+,\\s*\\{[^\\n}]*[\"']?"
                                                    + SENSITIVE_REQUEST_FIELD_NAME
                                                    + "[\"']?\\s*:"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_http_credential_file_send",
                                    "JavaScript sends credential file through HTTP",
                                    pattern(
                                            "\\b(?:fetch|axios\\.(?:request|post|put|patch))\\s*\\([^\\n]*(?:body|data)\\s*:\\s*(?:(?:Buffer\\.from\\s*\\(\\s*)?(?:fs\\.readFileSync\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|fs\\.createReadStream\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])(?:[^\\n)]*\\)\\s*)?(?:\\s*\\)*\\.toString\\s*\\(\\s*[\"']base64[\"']\\s*\\))?)|\\bform(?:Data)?\\.append\\s*\\([^\\n]*(?:(?:fs\\.createReadStream|fs\\.readFileSync)\\s*\\(|await\\s+fs\\.promises\\.readFile\\s*\\()\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_fs_remove",
                                    "Node file delete",
                                    pattern("\\bfs\\.(rm|rmSync|unlink|unlinkSync)\\s*\\("),
                                    ToolNameConstants.EXECUTE_JS)));

    /** HARDLINERULES的统一常量值。 */
    static final List<DangerRule> HARDLINE_RULES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            new DangerRule(
                                    "hardline_delete_root",
                                    "recursive delete of root filesystem",
                                    pattern(
                                            HARDLINE_COMMAND_POSITION
                                                    + "rm\\s+(-[^\\s]*\\s+)*(/|/\\*|/ \\*)(\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_delete_system_dir",
                                    "recursive delete of system directory",
                                    pattern(
                                            HARDLINE_COMMAND_POSITION
                                                    + "rm\\s+(-[^\\s]*\\s+)*(/home|/home/\\*|/root|/root/\\*|/etc|/etc/\\*|/usr|/usr/\\*|/var|/var/\\*|/bin|/bin/\\*|/sbin|/sbin/\\*|/boot|/boot/\\*|/lib|/lib/\\*)(\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_delete_home",
                                    "recursive delete of home directory",
                                    pattern(
                                            HARDLINE_COMMAND_POSITION
                                                    + "rm\\s+(-[^\\s]*\\s+)*(~|\\$HOME|\\$\\{HOME\\}|\\$env:HOME|\\$env:USERPROFILE|%USERPROFILE%|%HOMEPATH%)(/?|/\\*)?(\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_mkfs",
                                    "format filesystem (mkfs)",
                                    pattern("\\bmkfs(\\.[a-z0-9]+)?\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_dd_device",
                                    "dd to raw block device",
                                    pattern(
                                            "\\bdd\\b[^\\n]*\\bof=[\"']?/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_redirect_device",
                                    "redirect to raw block device",
                                    pattern(
                                            ">\\s*[\"']?/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_shutdown",
                                    "system shutdown/reboot",
                                    pattern(
                                            HARDLINE_COMMAND_POSITION
                                                    + "(shutdown(?!\\.exe)(?!\\s*/)|reboot|halt|poweroff|init\\s+[06]|telinit\\s+[06]|systemctl\\s+(poweroff|reboot|halt|kexec))\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_kill_all",
                                    "kill all processes",
                                    pattern("\\bkill\\s+(-[^\\s]+\\s+)*-1\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_fork_bomb",
                                    "fork bomb",
                                    pattern(
                                            ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_format",
                                    "format Windows volume",
                                    pattern(
                                            "\\b(?:format\\s+[a-z]:|Format-Volume\\b)(\\s|$|[^\\n]*\\b(?:-DriveLetter|-Partition|-FileSystem)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_shutdown",
                                    "Windows shutdown/reboot",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:cmd(?:\\.exe)?\\s+/c\\s+)?(?:(?:powershell|pwsh)(?:\\.exe)?\\s+(?:-[^\\s]+\\s+)*(?:(?:-Command|-c)\\s+)?)?(?:shutdown(?:\\.exe)?\\s+/(?:r|s|p|g|sg)|Restart-Computer|Stop-Computer)\\b|\\bStart-Process\\b(?=[^\\n]*(?:powershell|pwsh|shutdown(?:\\.exe)?))(?=[^\\n]*(?:shutdown(?:\\.exe)?\\s+/(?:r|s|p|g|sg)|Restart-Computer|Stop-Computer))[^\\n]*)"),
                                    ToolNameConstants.EXECUTE_SHELL)));

    /**
     * 执行pattern相关逻辑。
     *
     * @param regex regex 参数。
     * @return 返回pattern结果。
     */
    static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /**
     * 执行caseSensitivePattern相关逻辑。
     *
     * @param regex regex 参数。
     * @return 返回case Sensitive Pattern结果。
     */
    private static Pattern caseSensitivePattern(String regex) {
        return Pattern.compile(regex, Pattern.DOTALL);
    }

    /**
     * 执行ruleSamples相关逻辑。
     *
     * @param rules rules 参数。
     * @param max max 参数。
     * @return 返回rule Samples结果。
     */
    static List<String> ruleSamples(List<DangerRule> rules, int max) {
        List<String> samples = new ArrayList<String>();
        if (rules == null) {
            return samples;
        }
        int limit = Math.max(0, max);
        for (DangerRule rule : rules) {
            if (samples.size() >= limit) {
                break;
            }
            if (rule != null && StrUtil.isNotBlank(rule.getPatternKey())) {
                samples.add(rule.getPatternKey());
            }
        }
        return samples;
    }

    /**
     * 按偏好顺序从当前规则目录采样，避免策略摘要展示已经不存在的规则键。
     *
     * @param rules 规则目录。
     * @param max 最大返回数量。
     * @param preferredKeys 期望展示的规则键。
     * @return 返回仍存在于规则目录中的采样键。
     */
    static List<String> preferredRuleSamples(
            List<DangerRule> rules, int max, String... preferredKeys) {
        List<String> samples = new ArrayList<String>();
        if (rules == null || preferredKeys == null || max <= 0) {
            return samples;
        }
        Set<String> available = new LinkedHashSet<String>();
        for (DangerRule rule : rules) {
            if (rule != null && StrUtil.isNotBlank(rule.getPatternKey())) {
                available.add(rule.getPatternKey());
            }
        }
        for (String preferredKey : preferredKeys) {
            if (samples.size() >= max) {
                break;
            }
            if (available.contains(preferredKey) && !samples.contains(preferredKey)) {
                samples.add(preferredKey);
            }
        }
        return samples;
    }

    /** 返回终端护栏摘要键列表，避免策略摘要和真实检测分支漂移。 */
    static List<String> terminalGuardrailKeys() {
        return TERMINAL_GUARDRAIL_KEYS;
    }

    /** 返回包含动态元数据 URL 规则在内的硬阻断规则数量。 */
    static int hardlineRuleCount() {
        return HARDLINE_RULES.size() + 1;
    }

    /** 返回硬阻断策略覆盖工具列表。 */
    static List<String> hardlineCoveredTools() {
        return HARDLINE_COVERED_TOOLS;
    }

    /** 返回硬阻断策略覆盖类别列表。 */
    static List<String> hardlineBlockedCategories() {
        return HARDLINE_BLOCKED_CATEGORIES;
    }

    /**
     * 执行hardlineRuleSamples相关逻辑。
     *
     * @param max max 参数。
     * @return 返回hardline Rule Samples结果。
     */
    static List<String> hardlineRuleSamples(int max) {
        List<String> samples = ruleSamples(HARDLINE_RULES, max);
        if (max > 0 && !samples.contains(HARDLINE_METADATA_URL_RULE_KEY)) {
            if (samples.size() >= max) {
                samples.remove(samples.size() - 1);
            }
            samples.add(HARDLINE_METADATA_URL_RULE_KEY);
        }
        return samples;
    }

    /** 承载DangerRule相关状态和辅助逻辑。 */
    static final class DangerRule {
        /** 记录DangerRule中的pattern键。 */
        private final String patternKey;

        /** 记录DangerRule中的描述。 */
        private final String description;

        /** 记录DangerRule中的pattern。 */
        private final Pattern pattern;

        /** 保存工具集合，维持调用顺序或去重语义。 */
        private final Set<String> tools;

        /**
         * 创建Danger Rule实例，并注入运行所需依赖。
         *
         * @param patternKey pattern键标识或键值。
         * @param description 描述参数。
         * @param pattern pattern 参数。
         * @param tools tools 参数。
         */
        private DangerRule(
                String patternKey, String description, Pattern pattern, String... tools) {
            this.patternKey = patternKey;
            this.description = description;
            this.pattern = pattern;
            this.tools = new LinkedHashSet<String>(Arrays.asList(tools));
        }

        /**
         * 执行matches相关逻辑。
         *
         * @param toolName 工具名称。
         * @param code code 参数。
         * @return 返回matches结果。
         */
        boolean matches(String toolName, String code) {
            return tools.contains(toolName) && pattern.matcher(code).find();
        }

        /**
         * 读取Pattern键。
         *
         * @return 返回读取到的Pattern键。
         */
        String getPatternKey() {
            return patternKey;
        }

        /**
         * 读取Description。
         *
         * @return 返回读取到的Description。
         */
        String getDescription() {
            return description;
        }
    }
}
