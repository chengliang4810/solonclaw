package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.AgentSettingConstants;
import com.jimuqu.solon.claw.support.constants.ToolNameConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.flow.FlowContext;

/** 危险命令审批服务。 */
public class DangerousCommandApprovalService {
    public static final String DELIVERY_MODE_APPROVAL_CARD = "dangerous_command_approval_card";
    public static final String CARD_ACTION_KEY = "solonclaw_action";
    public static final String CARD_SCOPE_KEY = "scope";
    public static final String CARD_APPROVAL_ID_KEY = "approvalId";
    public static final String CARD_ACTION_APPROVE = "dangerous_approve";
    public static final String CARD_ACTION_DENY = "dangerous_deny";

    private static final String CONTEXT_PENDING_APPROVAL = "_dangerous_command_pending_";
    private static final String CONTEXT_PENDING_APPROVAL_QUEUE =
            "_dangerous_command_pending_queue_";
    private static final String CONTEXT_SESSION_APPROVALS = "_dangerous_command_session_approvals_";
    private static final String CONTEXT_SESSION_YOLO = "_dangerous_command_session_yolo_";
    private static final String CONTEXT_ONCE_APPROVALS = "_dangerous_command_once_approvals_";
    private static final long CURRENT_THREAD_APPROVAL_TTL_MILLIS = 30000L;
    private static final ThreadLocal<Map<String, Long>> CURRENT_THREAD_APPROVED_COMMANDS =
            new ThreadLocal<Map<String, Long>>();
    private static final int APPROVAL_SELECTOR_PREFIX_MIN_LENGTH = 8;

    private static final String PATH_SEPARATOR = "[\\\\/]";
    private static final String HOME_PATH_PREFIX =
            "(?:~|\\$home|\\$\\{home\\}|\\$env:home|\\$env:userprofile|%userprofile%|%homepath%)";
    private static final String AGENT_HOME_PATH_PREFIX =
            "(?:\\$jimuqu_home|\\$\\{jimuqu_home\\}|\\$env:jimuqu_home|%jimuqu_home%|"
                    + "\\$jimuqu_home|\\$\\{jimuqu_home\\}|\\$env:jimuqu_home|%jimuqu_home%)";
    private static final String SHELL_PROFILE_WRITE_TARGET =
            HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.(?:bashrc|zshrc|profile|bash_profile|zprofile)\\b";
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
                    + "\\.(?:jimuqu-agent|Jimuqu)"
                    + PATH_SEPARATOR
                    + "\\.env\\b|"
                    + AGENT_HOME_PATH_PREFIX
                    + PATH_SEPARATOR
                    + "\\.env\\b)";
    private static final String PROJECT_SENSITIVE_WRITE_TARGET =
            "(?:(?<![A-Za-z0-9_.-])(?:[/\\\\]|\\.{1,2}[/\\\\])?(?:[^\\s/\\\\\"'`]+[/\\\\])*(?:\\.env(?:\\.[^/\\\\\\s\"'`]+)*|\\.envrc|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|config\\.ya?ml|credentials(?:\\.(?:json|toml|tfrc\\.json))?|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[^/\\\\\\s\"'`]*\\.json|auth\\.json|oauth_creds\\.json|token\\.json|pip\\.conf|settings\\.xml|nuget\\.config))";
    private static final String POWERSHELL_SENSITIVE_WRITE_TARGET =
            "(?:" + PROJECT_SENSITIVE_WRITE_TARGET + "|" + SENSITIVE_WRITE_TARGET + ")";
    private static final String CREDENTIAL_PERMISSION_TARGET =
            "(?:(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:(?:[^\\s/\\\\\"'`]+)[/\\\\])*(?:\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.docker|\\.azure|\\.gemini|\\.cargo|\\.terraform\\.d|\\.m2|\\.gem|\\.nuget|\\.config[/\\\\](?:gh|gcloud|gemini|pip))[/\\\\][^\\s\"'`]+|(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?|\\.netrc|\\.git-credentials|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|auth\\.json|oauth_creds\\.json|token\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|id_(?:rsa|ed25519|ecdsa|dsa)(?:_sk)?))";
    private static final String REMOTE_CREDENTIAL_FILE_TARGET =
            "(?:[\"']?(?:(?:~|\\$HOME|\\$env:[A-Za-z_][A-Za-z0-9_]*|%[A-Za-z_][A-Za-z0-9_]*%|\\.{1,2})[/\\\\])?(?:(?:[^\\s/\\\\\"'`:=]+)[/\\\\])*(?:\\.env(?:\\.[A-Za-z0-9_.-]+)?|\\.envrc|\\.netrc|\\.git-credentials|\\.pgpass|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|auth\\.json|\\.credentials\\.json|\\.anthropic_oauth\\.json|oauth_creds\\.json|client_secrets?\\.json|token\\.json|application_default_credentials\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[A-Za-z0-9_.-]*\\.json|authorized_keys|kubeconfig|id_(?:rsa|ed25519|ecdsa|dsa)(?:_sk)?|(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx))[\"']?(?:\\s|$|:))";
    private static final String NETWORK_CREDENTIAL_FILE_TARGET =
            "(?:\\.env|\\.envrc|\\.netrc|\\.git-credentials|\\.pgpass|\\.npmrc|\\.yarnrc|\\.pnpmrc|\\.pypirc|\\.curlrc|\\.wgetrc|credentials(?:\\.(?:json|toml|tfrc\\.json))?|credential|secret|token(?:\\.json)?|auth\\.json|\\.credentials\\.json|\\.anthropic_oauth\\.json|oauth|oauth_creds\\.json|client_secrets?(?:\\.json)?|application_default_credentials\\.json|service[_-]account(?:[_-]key)?\\.json|google-credentials\\.json|firebase-adminsdk[A-Za-z0-9_.-]*\\.json|api-?key|(?:private|secret|credentials?|token|oauth|service[_-]account|api-?key|id_)[A-Za-z0-9_.-]*\\.(?:pem|key|p12|pfx)|id_(?:rsa|ed25519|ecdsa|dsa))";
    private static final String SENSITIVE_ENV_NAME =
            "(?:[A-Za-z_][A-Za-z0-9_]*(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH)[A-Za-z0-9_]*)";
    private static final String SENSITIVE_HTTP_HEADER_NAME =
            "(?:authorization|proxy[_.-]?authorization|cookie|(?:x[_.-]?)?api[_.-]?(?:key|token)|apikey|(?:x[_.-]?)?access[_.-]?(?:key|token)|x[_.-]?auth[_.-]?token|(?:x[_.-]?)?secret[_.-]?key)";
    private static final String SENSITIVE_REQUEST_FIELD_NAME =
            "(?:access[_.\\s-]?(?:key|token)|refresh[_.\\s-]?token|id[_.\\s-]?token|auth[_.\\s-]?token|api[_.\\s-]?(?:key|token)|token|secret|secret[_.\\s-]?key|client[_.\\s-]?secret|password|passwd|credential|authorization)";
    private static final String COMMAND_TAIL = "(?:\\s*(?:(?:&&|\\|\\||;).*)?$|\\s*$)";
    private static final String BROAD_LISTEN_ADDRESS = "(?:0\\.0\\.0\\.0|\\[?::\\]?|\\*)";
    private static final String HARDLINE_COMMAND_POSITION =
            "(?:^|[;&|\\n`]|\\$\\()\\s*(?:(?:sudo|doas|pkexec)\\s+(?:-[^\\s]+\\s+)*|runas\\s+(?:/(?:user|profile|env|netonly|savecred):\\S+\\s+)*)?(?:env\\s+(?:(?:-[^\\s]+|--[^\\s]+|\\w+=\\S*)\\s+)*)?(?:(?:exec|nohup|setsid|time)\\s+)*\\s*";
    private static final String SHELL_COMMAND_START =
            "(?:^|[;&|\\n`]|\\$\\()\\s*(?:(?:sudo|doas|pkexec)\\s+(?:-[^\\s]+\\s+)*)?";
    private static final String KUBECTL_OPTION_PREFIX =
            "(?:\\s+(?:--?[A-Za-z0-9-]+)(?:=\\S+|\\s+\\S+)?)*";
    private static final Pattern SHELL_LEVEL_BACKGROUND =
            pattern("\\b(?:nohup|disown|setsid)\\b");
    private static final Pattern DETACHED_TERMINAL_SESSION =
            pattern(
                    "\\b(?:tmux\\s+new-session\\b(?=[^\\n]*(?:\\s-d\\b|\\s--detach\\b))|screen\\s+(?:-[^\\s]*d[^\\s]*m[^\\s]*|-[^\\s]*m[^\\s]*d[^\\s]*)\\b|systemd-run\\b|cmd(?:\\.exe)?\\s+/c\\s+start\\b(?![^\\n]*\\s/(?:wait|w)\\b)|(?:^|[;&|\\n])\\s*start(?:\\.exe)?\\s+(?![^\\n]*\\s/(?:wait|w)\\b))");
    private static final Pattern POWERSHELL_BACKGROUND_JOB =
            pattern("\\b(?:start-process|start-job|start-threadjob)\\b");
    private static final Pattern POWERSHELL_WAIT_TRUE_FLAG =
            pattern("\\s-wait(?:\\s|$|:(?:\\$?true|1)\\b|=(?:\\$?true|1)\\b)");
    private static final Pattern POWERSHELL_WAIT_FALSE_FLAG =
            pattern("\\s-wait(?:\\s*:(?:\\$?false|0)\\b|\\s*=(?:\\$?false|0)\\b|\\s+(?:\\$?false|0)\\b)");
    private static final Pattern INLINE_BACKGROUND_AMP = pattern("\\s&\\s");
    private static final Pattern TRAILING_BACKGROUND_AMP = pattern("\\s&\\s*(?:#.*)?$");
    private static final Pattern PYTHON_SHELL_EXEC_CALL =
            pattern(
                    "\\b(?:os\\.system|subprocess\\.(?:run|Popen|call|check_call|check_output))\\s*\\(");
    private static final Pattern APPROVAL_SELECTOR_TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Set<String> COMMAND_ARGUMENT_KEYS =
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
    private static final List<Pattern> LONG_LIVED_FOREGROUND_PATTERNS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            pattern("\\b(?:npm|pnpm|yarn|bun)\\s+(?:run\\s+)?(?:dev|start|serve|watch)\\b"),
                            pattern("\\bdocker\\s+compose\\s+up\\b"),
                            pattern("\\bnext\\s+dev\\b"),
                            pattern("\\bvite(?:\\s|$)"),
                            pattern("\\bnodemon\\b"),
                            pattern("\\buvicorn\\b"),
                            pattern("\\bgunicorn\\b"),
                            pattern("\\bpython(?:3)?\\s+-m\\s+http\\.server\\b")));

    private static final List<DangerRule> RULES =
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
                                    pattern("\\b(python[23]?|perl|ruby|node)\\s+-[ec](?:\\s+|(?=['\"]))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_script_process_substitution",
                                    "execute remote script via process substitution",
                                    pattern("\\b(bash|sh|zsh|ksh)\\s+<\\s*<?\\s*\\(\\s*(curl|wget)\\b"),
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
                                    pattern("\\btee\\b.*[\"']?" + SENSITIVE_WRITE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_redirection",
                                    "overwrite system file via redirection",
                                    pattern(">>?\\s*[\"']?" + SENSITIVE_WRITE_TARGET),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "project_sensitive_tee",
                                    "overwrite project env/config via tee",
                                    pattern(
                                            "\\btee\\b.*[\"']?"
                                                    + PROJECT_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"
                                                    + COMMAND_TAIL),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "project_sensitive_redirection",
                                    "overwrite project env/config via redirection",
                                    pattern(
                                            ">>?\\s*[\"']?"
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
                                                    + "(?:\\}|%|!)?[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy)\\b|\\bprintenv\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy)\\b|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:(?:-(?:Value|InputObject)\\b\\s*(?::|=|\\s+)\\s*)?)(?:\\$env:|%)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "%?|\\b(?:Set-Clipboard|scb)\\b[^\\n]*\\$\\{env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\}|(?:\\$env:|\\$\\{env:|\\[Environment\\]::GetEnvironmentVariable\\(\\s*['\"]?)"
                                                    + SENSITIVE_ENV_NAME
                                                    + "(?:['\"]?\\)|\\})?[^\\n|;&]*\\|\\s*(?:Set-Clipboard|scb)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sensitive_file_clipboard_export",
                                    "copy credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:pbcopy|clip(?:\\.exe)?|xclip|xsel|wl-copy|Set-Clipboard|scb)\\b|\\b(?:Set-Clipboard|scb)\\b[^\\n]*(?:-(?:Path|LiteralPath)\\b\\s*(?::|=|\\s+)\\s*)"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "|\\(\\s*(?:Get-Content|gc)\\b[^\\n|;&)]*"
                                                    + CREDENTIAL_PERMISSION_TARGET
                                                    + "[^\\n|;&)]*\\)\\s*\\|\\s*(?:Set-Clipboard|scb)\\b)"),
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
                                                    + "|\\b(?:Get-Content|gc)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:\\[Convert\\]::ToBase64String|ConvertTo-SecureString\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_hash_output",
                                    "hash credential file content",
                                    pattern(
                                            "(?:\\b(?:sha(?:1|224|256|384|512)?sum|md5sum|b2sum|cksum|shasum)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bopenssl\\s+(?:dgst|sha(?:1|224|256|384|512)|md5)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bcertutil(?:\\.exe)?\\s+-hashfile\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "|\\bGet-FileHash\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
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
                                                    + "\\s*=\\s*\\S+|(?:Set-Item|New-Item)\\s+Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s+\\S+|(?:export|declare\\s+-x|typeset\\s+-x)\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "=\\S+|(?:cmd(?:\\.exe)?\\s+/c\\s+)?set\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "=\\S+|(?:Set-Item|New-Item|Set-Content)\\s+(?:-[A-Za-z]+\\s+)*Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s+\\S+|Remove-Item\\s+(?:-[A-Za-z]+\\s+)*Env:"
                                                    + SENSITIVE_ENV_NAME
                                                    + "|setx\\s+"
                                                    + SENSITIVE_ENV_NAME
                                                    + "\\s+\\S+|\\[Environment\\]::SetEnvironmentVariable\\(\\s*['\"]?"
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
                                                    + "(?:httpie|https?|xh)\\b[^\\n]*\\s[\"']?\\s*"
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
                                            "(?:\\bprintenv\\s+|\\becho\\s+\\$\\{?|\\becho\\s+%|\\becho\\s+!|\\bprintf\\b[^\\n|;&]*(?:\\$\\{?|!)|\\b(?:Get-Item|Get-Content|Get-ChildItem|gci|dir|ls)\\s+(?:-[A-Za-z]+\\s+)*Env:|\\$\\{env:|\\$env:|%|\\[Environment\\]::GetEnvironmentVariable\\(\\s*['\"]?)(?:"
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
                                    "kubernetes_credential_config_read",
                                    "read Kubernetes credential configuration",
                                    pattern(
                                            "\\bkubectl"
                                                    + KUBECTL_OPTION_PREFIX
                                                    + "\\s+config\\s+view\\b(?=[^\\n]*--raw\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "cloud_cli_credential_config_read",
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
                                                    + "\\s*:)|\\b(?:httpie|https?|xh)\\b[^\\n]*\\s[\"']?\\s*"
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
                                                    + "[\"']?\\s*:\\s*[\"']?\\S+)|\\baria2c\\b[^\\n]*\\s--(?:http-user|http-passwd|ftp-user|ftp-passwd|proxy-user|proxy-passwd)(?:=|\\s+)\\S+|\\b(?:httpie|https?|xh)\\b[^\\n]*\\s"
                                                    + "(?:--auth(?:=|\\s+)\\S+|-a(?:=|\\s+)?\\S+)|\\b(?:httpie|https?|xh)\\b[^\\n]*\\s"
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
                                                    + "\\S*[\"']?)|\\baria2c\\b[^\\n]*\\s--(?:load-cookies|certificate|private-key|ca-certificate)(?:=|\\s+)\\S+|\\b(?:httpie|https?|xh)\\b[^\\n]*\\s(?:[^\\s'\"|;&]+@|@)\\S*"
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
                                                    + "\\S*[\"']?)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "powershell_webclient_credential_file_send",
                                    "PowerShell WebClient sends credential file",
                                    pattern(
                                            "(?:New-Object\\s+Net\\.WebClient|\\[Net\\.WebClient\\]::new\\s*\\(\\s*\\)|\\[System\\.Net\\.WebClient\\]::new\\s*\\(\\s*\\))[^\\n]*\\.Upload(?:File|Data|String)\\s*\\([^\\n]*[\"']\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "\\S*[\"']|(?:New-Object\\s+Net\\.WebClient|\\[Net\\.WebClient\\]::new\\s*\\(\\s*\\)|\\[System\\.Net\\.WebClient\\]::new\\s*\\(\\s*\\))[^\\n]*\\.Upload(?:Data|String)\\s*\\([^\\n]*\\b(?:Get-Content|gc)\\b[^\\n)]*[\"']?\\S*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
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
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_filtered_output",
                                    "filter credential file content to terminal",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:nl|cut|sort|uniq|findstr(?:\\.exe)?)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:Select-String|sls)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_structured_output",
                                    "parse credential file content to terminal",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:jq|yq)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "|\\b(?:Get-Content|gc|Import-Clixml)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:ConvertFrom-Json|ConvertFrom-Csv|ConvertFrom-StringData)\\b|\\b(?:ConvertFrom-Json|ConvertFrom-Csv|ConvertFrom-StringData)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + ")"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_transcript_output",
                                    "transcript credential file content",
                                    pattern(
                                            "(?:\\b(?:cat|type|Get-Content|gc)\\b[^\\n|;&]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n|;&]*\\|\\s*(?:tee\\b|Out-String\\b|Out-Default\\b)|(?:^|[;&|\\n`])\\s*script\\b[^\\n|;&]*\\s-c\\s+[\"'][^\"'\\n]*(?:cat|type|Get-Content|gc)\\b[^\"'\\n]*"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
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
                                                    + ")|\\bAdd-History\\b[^\\n|;&]*(?:\\(\\s*(?:Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|"
                                                    + REMOTE_CREDENTIAL_FILE_TARGET
                                                    + "))"),
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
                                                    + "[^\\n|;&]*\\|\\s*(?:head|tail|less|more|bat|batcat|most|pg|Out-Host|Select-Object|select)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_substitution_output",
                                    "print credential file content through command substitution",
                                    pattern(
                                            "(?:(?:^|[;&|\\n`])\\s*(?:echo|printf|Write-Output|Write-Host)\\b[^\\n|;&]*(?:\\$\\([^\\n)]*\\b(?:cat|type|Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)|`[^`\\n]*(?:cat|type|Get-Content|gc)\\b[^`\\n]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^`\\n]*`|\\(\\s*(?:Get-Content|gc)\\b[^\\n)]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
                                                    + "[^\\n)]*\\)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "credential_file_terminal_output",
                                    "print credential file content to terminal",
                                    pattern(
                                            "(?:\\b(?:cat|type|head|tail|less|more|sed|awk|grep|Get-Content|gc)\\b(?![^\\n|;&]*(?:\\||>|>>))[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
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
                                                    + "|(?:^|[;&|\\n`])\\s*(?:Get-Item|gi|Get-ChildItem|gci)\\b[^\\n|;&]*"
                                                    + NETWORK_CREDENTIAL_FILE_TARGET
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
                                            "\\b(?:curl|wget|aria2c)\\b[^\\n]*(?:\\s-k(?:\\s|$)|\\s--insecure\\b|\\s--no-check-certificate\\b|\\s--check-certificate\\s*=\\s*off\\b|\\s--allow-untrusted(?:\\s|$))|\\b(?:npm|pnpm|yarn)\\s+config\\s+set\\s+(?:strict-ssl|strictSsl)\\s+false\\b|\\bpip(?:3)?\\b[^\\n]*\\s--trusted-host(?:=|\\s+)\\S+|\\bpoetry\\s+config\\s+certificates\\.[A-Za-z0-9_.-]+\\.cert\\s+false\\b|(?:^|[;&|\\n`])\\s*(?:PYTHONHTTPSVERIFY\\s*=\\s*0|NODE_TLS_REJECT_UNAUTHORIZED\\s*=\\s*0)\\b"),
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
                                    pattern("\\b(?:Jimuqu|jimuqu-agent|solon-claw)\\s+gateway\\s+(stop|restart)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "app_update_restart",
                                    "agent update (restarts gateway, kills running agents)",
                                    pattern("\\b(?:Jimuqu|jimuqu-agent|solon-claw)\\s+update\\b"),
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
                                    pattern("\\b(pkill|killall)\\b.*\\b(Jimuqu|jimuqu-agent|solon-claw|gateway|cli\\.py)\\b"),
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
                                    "sed_inplace_etc",
                                    "in-place edit of system config",
                                    pattern("\\bsed\\s+-[^\\s]*i.*\\s/etc/|\\bsed\\s+--in-place\\b.*\\s/etc/"),
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
                                    pattern("\\b(?:terraform|tofu|terragrunt)\\s+apply\\b(?=[^\\n]*-auto-approve\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "terraform_state_sensitive_read",
                                    "Terraform state sensitive read",
                                    pattern("\\b(?:terraform|tofu|terragrunt)\\s+state\\s+(?:pull|show)\\b"),
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
                                    pattern("\\bgit\\s+clean\\b(?=[^\\n]*(?:-(?!-)[^\\s]*f|--force\\b))"),
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
                                    pattern("\\bUPDATE\\s+[A-Za-z0-9_.`\"\\[\\]-]+\\s+SET\\b(?!.*\\bWHERE\\b)"),
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
                                    pattern("\\bDROP\\s+(?:DATABASE|SCHEMA|TABLE)\\s+(?:IF\\s+EXISTS\\s+)?[A-Za-z0-9_.`\"\\[\\]-]+"),
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
                                    pattern("\\b(?:del|erase)\\b(?=.*(?:^|\\s)/s\\b)(?=.*(?:^|\\s)/[fq]\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_rmdir_force",
                                    "Windows recursive directory delete",
                                    pattern("\\b(rmdir|rd)\\b(?=.*(?:^|\\s)/s\\b)(?=.*(?:^|\\s)/q\\b)"),
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
                                    "windows_security_registry_weaken",
                                    "Windows security registry policy weakened",
                                    pattern(
                                            "\\breg(?:\\.exe)?\\s+add\\b[^\\n]*(?:(?:Windows Defender|DisableAntiSpyware|DisableRealtimeMonitoring|DisableBehaviorMonitoring)|(?:Policies\\\\System\\b[^\\n]*EnableLUA\\b[^\\n]*(?:/d\\s+0|/d\\s+0x0))|(?:PowerShell\\\\\\d+\\\\PowerShellEngine\\b[^\\n]*ExecutionPolicy\\b[^\\n]*(?:Bypass|Unrestricted)))"),
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
                                    "windows_powershell_encoded_command",
                                    "PowerShell encoded command execution",
                                    pattern(
                                            "\\b(?:powershell|pwsh)(?:\\.exe)?\\b(?=[^\\n]*(?:[-/](?:EncodedCommand|enc|e))\\b)"),
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
                                            "\\b(?:auditpol(?:\\.exe)?\\s+/(?:clear|remove)\\b|auditpol(?:\\.exe)?\\s+/set\\b(?=[^\\n]*(?:/success\\s*:\\s*disable|/failure\\s*:\\s*disable))|wevtutil(?:\\.exe)?\\s+sl\\s+(?:Security|System|Application)\\b(?=[^\\n]*(?:/e\\s*:\\s*false|/enabled\\s*:\\s*false)))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_firewall",
                                    "Windows firewall disabled",
                                    pattern(
                                            "\\b(?:netsh\\s+advfirewall\\s+set\\s+(?:allprofiles|currentprofile|domainprofile|privateprofile|publicprofile)\\s+state\\s+off|netsh\\s+advfirewall\\s+firewall\\s+set\\s+rule\\b[^\\n]*\\bnew\\s+enable\\s*=\\s*no\\b|Set-NetFirewallProfile\\b(?=[^\\n]*-Enabled\\s+(?:\\$?false|0)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_firewall_inbound_allow",
                                    "Windows inbound firewall allow rule added",
                                    pattern(
                                            "\\b(?:(?:New|Set)-NetFirewallRule\\b(?=[^\\n]*-Direction\\s+Inbound\\b)(?=[^\\n]*-Action\\s+Allow\\b)|netsh\\s+advfirewall\\s+firewall\\s+add\\s+rule\\b(?=[^\\n]*\\bdir\\s*=\\s*in\\b)(?=[^\\n]*\\baction\\s*=\\s*allow\\b)|netsh\\s+advfirewall\\s+firewall\\s+set\\s+rule\\b(?=[^\\n]*\\bnew\\s+dir\\s*=\\s*in\\b)(?=[^\\n]*\\baction\\s*=\\s*allow\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_disable_defender",
                                    "Windows Defender protection disabled",
                                    pattern(
                                            "\\bSet-MpPreference\\b(?=[^\\n]*(?:-(?:DisableRealtimeMonitoring|DisableBehaviorMonitoring|DisableIOAVProtection|DisableScriptScanning|DisableIntrusionPreventionSystem|DisableEmailScanning|DisableBlockAtFirstSeen|DisableArchiveScanning|DisableRemovableDriveScanning|DisableScanningMappedNetworkDrivesForFullScan)\\s+(?:\\$?true|1)\\b|-EnableControlledFolderAccess\\s+(?:Disabled|0)\\b|-SubmitSamplesConsent\\s+(?:NeverSend|2)\\b|-PUAProtection\\s+(?:Disabled|0)\\b|-AttackSurfaceReductionRules_Action\\s+(?:Disabled|0)\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_defender_exclusion",
                                    "Windows Defender exclusion added",
                                    pattern(
                                            "\\b(?:Add-MpPreference|Set-MpPreference)\\b(?=[^\\n]*(?:-ExclusionPath|-ExclusionProcess|-ExclusionExtension|-ExclusionIpAddress)\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_stop_service",
                                    "Windows service stopped or disabled",
                                    pattern(
                                            "\\b(?:sc(?:\\.exe)?\\s+(?:stop|pause|delete|config\\s+\\S+\\s+start\\s*=\\s*disabled)|(?:Stop-Service|Suspend-Service)\\b(?=[^\\n]*(?:-Force\\b|-Name\\s+|-DisplayName\\s+))|Set-Service\\b(?=[^\\n]*(?:-StartupType\\s+Disabled\\b|-Status\\s+Stopped\\b)))"),
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
                                            "\\b(?:schtasks(?:\\.exe)?\\s+/create|Register-ScheduledTask\\b|New-ScheduledTask\\b|reg(?:\\.exe)?\\s+(?:add|copy)\\b[^\\n]*(?:\\\\CurrentVersion\\\\Run(?:Once)?\\b|\\\\RunServices(?:Once)?\\b)|(?:copy|xcopy|robocopy|Copy-Item|Set-Content|Add-Content|Out-File)\\b[^\\n]*(?:\\\\Microsoft\\\\Windows\\\\Start\\s+Menu\\\\Programs\\\\Startup\\\\|\\\\Start Menu\\\\Programs\\\\Startup\\\\))"),
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
                                            "\\b(?:procdump(?:64)?(?:\\.exe)?\\b[^\\n]*\\blsass(?:\\.exe)?\\b|rundll32(?:\\.exe)?\\s+comsvcs\\.dll,\\s*MiniDump\\b[^\\n]*\\blsass\\b|reg(?:\\.exe)?\\s+save\\s+HKLM\\\\(?:SAM|SECURITY|SYSTEM)\\b|ntdsutil(?:\\.exe)?\\b[^\\n]*(?:ifm|create\\s+full|activate\\s+instance\\s+ntds)|esentutl(?:\\.exe)?\\b[^\\n]*(?:ntds\\.dit|\\\\SAM\\b|\\\\SECURITY\\b|\\\\SYSTEM\\b)|(?:copy|xcopy|robocopy|Copy-Item)\\b[^\\n]*HarddiskVolumeShadowCopy[^\\n]*(?:\\\\SAM\\b|\\\\SECURITY\\b|\\\\SYSTEM\\b|ntds\\.dit)|(?:mimikatz|pypykatz|secretsdump(?:\\.py)?|lsassy|nanodump|dumpert)\\b|sekurlsa::)"),
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
                                            "\\b(?:Set-Content|Add-Content|Out-File|sc|ac)\\b[^\\n]*(?:-Path\\s+|-LiteralPath\\s+|-FilePath\\s+)?[\"']?"
                                                    + POWERSHELL_SENSITIVE_WRITE_TARGET
                                                    + "[\"']?"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "powershell_sensitive_file_copy",
                                    "PowerShell copy or move to sensitive credential file",
                                    pattern(
                                            "\\b(?:Copy-Item|Move-Item|cp|cpi|mv|mi)\\b[^\\n]*(?:(?:-Destination|-Path|-LiteralPath)\\s+)?[\"']?"
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
                                    "python_credential_file_stdout",
                                    "Python prints credential file content",
                                    pattern(
                                            "\\b(?:print|sys\\.(?:stdout|stderr)\\.write|(?:logging|logger)\\.(?:debug|info|warning|warn|error|exception|critical))\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_variable_stdout",
                                    "Python prints credential file content variable",
                                    pattern(
                                            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:print|sys\\.(?:stdout|stderr)\\.write|(?:logging|logger)\\.(?:debug|info|warning|warn|error|exception|critical))\\s*\\([^\\n)]*\\b\\1\\b"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_exception_output",
                                    "Python exception exposes credential file content",
                                    pattern(
                                            "(?:\\braise\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\braise\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_debug_artifact_write",
                                    "Python writes credential file content into debug artifact",
                                    pattern(
                                            "(?:\\b(?:open|Path)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n)]*\\)[^\\n]{0,240}(?:write|write_text|write_bytes)\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:open|Path)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n)]*\\)[^\\n]{0,240}(?:write|write_text|write_bytes)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_archive_artifact_write",
                                    "Python writes credential file content into archive artifact",
                                    pattern(
                                            "\\b(?:zipfile\\.[A-Za-z0-9_]+|tarfile\\.[A-Za-z0-9_]+)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:write|writestr|add|addfile)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_clipboard_export",
                                    "Python copies credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:pyperclip|clipboard)\\.(?:copy|set)\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:pyperclip|clipboard)\\.(?:copy|set)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_credential_file_notification_output",
                                    "Python notification exposes credential file content",
                                    pattern(
                                            "(?:\\b(?:notify2|plyer\\.notification|notification)\\.(?:notify|Notification)\\s*\\([^\\n]*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:notify2|plyer\\.notification|notification)\\.(?:notify|Notification)\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_PYTHON),
                            new DangerRule(
                                    "python_http_credential_file_variable_send",
                                    "Python sends credential file content variable through HTTP",
                                    pattern(
                                            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)\\.read\\s*\\(\\s*\\)|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\(\\s*\\))[\\s\\S]{0,1200}\\b(?:requests|httpx)\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:data|content|json|files)\\s*=\\s*(?:\\1\\b|\\{[^\\n}]*\\1\\b|dict\\s*\\([^\\n)]*\\1\\b)"),
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
                                            "\\b(?:requests|httpx)\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:files|data|content)\\s*=\\s*(?:\\{[^\\n}]*open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|open\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|Path\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']\\s*\\)\\.read_(?:text|bytes)\\s*\\()"),
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
                                    "js_credential_file_exception_output",
                                    "JavaScript exception exposes credential file content",
                                    pattern(
                                            "(?:\\bthrow\\s+new\\s+Error\\s*\\([^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\bthrow\\s+new\\s+Error\\s*\\([^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_debug_artifact_write",
                                    "JavaScript writes credential file content into debug artifact",
                                    pattern(
                                            "(?:\\bfs\\.(?:writeFileSync|writeFile|appendFileSync|appendFile)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n,]*,[^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\bfs\\.(?:writeFileSync|writeFile|appendFileSync|appendFile)\\s*\\(\\s*[\"'][^\"'\\n]*(?:debug|trace|transcript|console|stdout|stderr|junit|test-output|test_result|test-results)[^\"'\\n]*\\.(?:log|txt|out|err|xml|json)[\"'][^\\n,]*,[^\\n)]*\\b\\1\\b)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_archive_artifact_write",
                                    "JavaScript writes credential file content into archive artifact",
                                    pattern(
                                            "\\b(?:archiver|zip|tar)\\s*\\([^\\n]*(?:debug|trace|artifact|artifacts|report|test-output|test-results)[^\"'\\n]*\\.(?:zip|tar|tgz|tar\\.gz)[\\s\\S]{0,1200}\\b(?:append|file|directory|add|entry)\\s*\\([^\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_credential_file_clipboard_export",
                                    "JavaScript copies credential file content to clipboard",
                                    pattern(
                                            "(?:\\b(?:clipboardy|clipboard|navigator\\.clipboard)\\.(?:writeSync|write|writeText|copy)\\s*\\([^\\n]*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)|await\\s+fs\\.promises\\.readFile\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\))[\\s\\S]{0,1200}\\b(?:clipboardy|clipboard|navigator\\.clipboard)\\.(?:writeSync|write|writeText|copy)\\s*\\([^\\n)]*\\b\\1\\b)"),
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
                                            "\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*fs\\.(?:readFileSync|createReadStream)\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'][^\\n)]*\\)[\\s\\S]{0,1200}(?:\\bfetch\\s*\\([^\\n]*(?:body\\s*:\\s*\\1\\b|JSON\\.stringify\\s*\\(\\s*\\{[^\\n}]*\\1\\b)|\\baxios\\.(?:request|post|put|patch)\\s*\\([^\\n]*(?:data\\s*:\\s*\\1\\b|\\{[^\\n}]*\\1\\b))"),
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
                                            "\\b(?:fetch|axios\\.(?:request|post|put|patch))\\s*\\([^\\n]*(?:body|data)\\s*:\\s*(?:fs\\.readFileSync\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']|fs\\.createReadStream\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"'])|\\bform(?:Data)?\\.append\\s*\\([^\\n]*fs\\.createReadStream\\s*\\(\\s*[\"'][^\"'\\n]*(?:\\.env|credentials|credential|secret|token|oauth|service[_-]account|api-?key|\\.netrc|\\.npmrc|\\.pypirc|\\.curlrc)[^\"'\\n]*[\"']"),
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "js_fs_remove",
                                    "Node file delete",
                                    pattern("\\bfs\\.(rm|rmSync|unlink|unlinkSync)\\s*\\("),
                                    ToolNameConstants.EXECUTE_JS)));

    private static final List<DangerRule> HARDLINE_RULES =
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
                                    "hardline_disk_partition_table_destroy",
                                    "destroy raw disk partition table or signatures",
                                    pattern(
                                            HARDLINE_COMMAND_POSITION
                                                    + "(?=(?:[^\\n]*[\"']?/dev/(?:sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*))(?:wipefs\\b(?=[^\\n]*(?:-a\\b|--all\\b))|blkdiscard\\b|sgdisk\\b(?=[^\\n]*(?:--zap-all\\b|-Z\\b|--clear\\b|\\s-o\\b))|sfdisk\\b(?=[^\\n]*(?:--delete\\b|--wipe\\s+always\\b|--wipe-partitions\\s+always\\b))|parted\\b(?=[^\\n]*\\bmklabel\\b))"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_redirect_device",
                                    "redirect to raw block device",
                                    pattern(">\\s*[\"']?/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*\\b"),
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
                                    "hardline_windows_clear_disk",
                                    "clear Windows disk",
                                    pattern("\\bClear-Disk\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_remove_partition",
                                    "remove Windows partition",
                                    pattern("\\bRemove-Partition\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_diskpart_destructive",
                                    "destructive Windows diskpart operation",
                                    pattern(
                                            "\\bdiskpart(?:\\.exe)?\\b(?=[\\s\\S]*\\b(?:clean(?:\\s+all)?|delete\\s+partition|delete\\s+volume|format\\s+fs=|convert\\s+(?:gpt|mbr))\\b)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_delete_drive_root",
                                    "recursive delete of Windows drive root",
                                    pattern(
                                            "\\b(Remove-Item|ri|rm|rmdir|rd|del|erase)\\b(?=[^\\n]*(?:-Recurse\\b|-r\\b|/s\\b))[^\\n]*\\b[a-z]:\\\\(?:\\*|\\.)?(?:\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_delete_profile",
                                    "recursive delete of Windows user profile",
                                    pattern(
                                            "\\b(Remove-Item|ri|rm|rmdir|rd|del|erase)\\b(?=[^\\n]*(?:-Recurse\\b|-r\\b|/s\\b))[^\\n]*(?:\\$env:USERPROFILE|%USERPROFILE%|C:\\\\Users(?:\\\\\\*|\\\\[^\\s'\"`|;&<>]+)?)(?:\\\\\\*)?(?:\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_system_dir",
                                    "recursive delete of Windows system directory",
                                    pattern(
                                            "\\b(Remove-Item|ri|rm|rmdir|rd|del|erase)\\b(?=[^\\n]*(?:-Recurse\\b|-r\\b|/s\\b))[^\\n]*(?:C:\\\\Windows|C:\\\\Program Files|C:\\\\Program Files \\(x86\\)|C:\\\\ProgramData)(?:\\\\\\*)?(?:\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_shutdown",
                                    "Windows shutdown/reboot",
                                    pattern(
                                            "(?:^|[;&|\\n`])\\s*(?:(?:cmd(?:\\.exe)?\\s+/c\\s+)|(?:(?:powershell|pwsh)(?:\\.exe)?\\s+(?:-[^\\s]+\\s+)*(?:(?:-Command|-c)\\s+)?))?(?:shutdown(?:\\.exe)?\\s+/(?:r|s|p|g|sg)|Restart-Computer|Stop-Computer)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL)));
    private static final Map<String, Set<String>> APPROVAL_KEY_ALIASES =
            buildApprovalKeyAliases();

    private final GlobalSettingRepository globalSettingRepository;
    private final AppConfig appConfig;
    private final SecurityPolicyService securityPolicyService;
    private final TirithSecurityService tirithSecurityService;
    private final List<ApprovalObserver> approvalObservers =
            new CopyOnWriteArrayList<ApprovalObserver>();
    private SmartApprovalJudge smartApprovalJudge;

    public DangerousCommandApprovalService(GlobalSettingRepository globalSettingRepository) {
        this(globalSettingRepository, null, null, null);
    }

    public DangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            SecurityPolicyService securityPolicyService) {
        this(globalSettingRepository, null, securityPolicyService, null);
    }

    public DangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService) {
        this(globalSettingRepository, appConfig, securityPolicyService, null);
    }

    public DangerousCommandApprovalService(
            GlobalSettingRepository globalSettingRepository,
            AppConfig appConfig,
            SecurityPolicyService securityPolicyService,
            TirithSecurityService tirithSecurityService) {
        this.globalSettingRepository = globalSettingRepository;
        this.appConfig = appConfig;
        this.securityPolicyService = securityPolicyService;
        this.tirithSecurityService = tirithSecurityService;
    }

    public void setSmartApprovalJudge(SmartApprovalJudge smartApprovalJudge) {
        this.smartApprovalJudge = smartApprovalJudge;
    }

    public boolean hasSmartApprovalJudge() {
        return smartApprovalJudge != null;
    }

    public void addApprovalObserver(ApprovalObserver observer) {
        if (observer != null && !approvalObservers.contains(observer)) {
            approvalObservers.add(observer);
        }
    }

    public void removeApprovalObserver(ApprovalObserver observer) {
        if (observer != null) {
            approvalObservers.remove(observer);
        }
    }

    public HITLInterceptor buildInterceptor() {
        return new HITLInterceptor()
                .onTool(
                        ToolNameConstants.EXECUTE_SHELL,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_SHELL, args))
                .onTool(
                        "shell",
                        (trace, args) ->
                                evaluateAlias(
                                        trace,
                                        "shell",
                                        ToolNameConstants.EXECUTE_SHELL,
                                        args))
                .onTool(
                        "bash",
                        (trace, args) ->
                                evaluateAlias(
                                        trace,
                                        "bash",
                                        ToolNameConstants.EXECUTE_SHELL,
                                        args))
                .onTool(
                        "executeShell",
                        (trace, args) ->
                                evaluateAlias(
                                        trace,
                                        "executeShell",
                                        ToolNameConstants.EXECUTE_SHELL,
                                        args))
                .onTool(
                        "execute_shell_command",
                        (trace, args) ->
                                evaluateAlias(
                                        trace,
                                        "execute_shell_command",
                                        ToolNameConstants.EXECUTE_SHELL,
                                        args))
                .onTool(
                        ToolNameConstants.EXECUTE_PYTHON,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_PYTHON, args))
                .onTool(
                        ToolNameConstants.EXECUTE_JS,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_JS, args))
                .onTool(
                        ToolNameConstants.EXECUTE_CODE,
                        (trace, args) ->
                                evaluateCodeCommand(
                                        trace,
                                        ToolNameConstants.EXECUTE_CODE,
                                        codeArg(args)))
                .onTool(
                        ToolNameConstants.TERMINAL,
                        (trace, args) -> evaluateTerminalTool(trace, args))
                .onTool(
                        "run_terminal",
                        (trace, args) ->
                                evaluateTerminalAlias(trace, "run_terminal", args))
                .onTool(
                        "terminal_run",
                        (trace, args) ->
                                evaluateTerminalAlias(trace, "terminal_run", args))
                .onTool(
                        ToolNameConstants.PROCESS,
                        (trace, args) -> evaluateProcessTool(trace, args))
                .onTool("call_tool", (trace, args) -> evaluateGatewayCallTool(trace, args))
                .onTool(
                        ToolNameConstants.FILE_READ,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.FILE_READ, args))
                .onTool(
                        ToolNameConstants.FILE_WRITE,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.FILE_WRITE, args))
                .onTool(
                        ToolNameConstants.FILE_LIST,
                        (trace, args) -> evaluateFileTool(trace, ToolNameConstants.FILE_LIST, args))
                  .onTool(
                          ToolNameConstants.FILE_DELETE,
                          (trace, args) ->
                                  evaluateFileTool(trace, ToolNameConstants.FILE_DELETE, args))
                  .onTool(
                          ToolNameConstants.PATCH,
                          (trace, args) -> evaluateFileTool(trace, ToolNameConstants.PATCH, args))
                  .onTool(
                          ToolNameConstants.WEBFETCH,
                        (trace, args) -> evaluateUrlTool(trace, ToolNameConstants.WEBFETCH, args))
                .onTool(
                        ToolNameConstants.WEBSEARCH,
                        (trace, args) -> evaluateUrlTool(trace, ToolNameConstants.WEBSEARCH, args))
                .onTool(
                        ToolNameConstants.CODESEARCH,
                        (trace, args) ->
                                evaluateUrlTool(trace, ToolNameConstants.CODESEARCH, args));
    }

    public PendingApproval getPendingApproval(AgentSession session) {
        List<PendingApproval> pendingApprovals = listPendingApprovals(session);
        return pendingApprovals.isEmpty() ? null : pendingApprovals.get(0);
    }

    public List<PendingApproval> listPendingApprovals(AgentSession session) {
        if (session == null) {
            return new ArrayList<PendingApproval>();
        }
        List<PendingApproval> pending = pendingQueueFrom(session.getContext());
        if (prunePendingApprovals(session, pending)) {
            pending = pendingQueueFrom(session.getContext());
        }
        return pending;
    }

    public PendingApproval selectPendingApproval(AgentSession session, String selector) {
        return findPendingApproval(session, selector);
    }

    public PendingApproval getPendingApproval(
            com.jimuqu.solon.claw.core.model.SessionRecord sessionRecord) {
        List<PendingApproval> pendingApprovals = listPendingApprovals(sessionRecord);
        return pendingApprovals.isEmpty() ? null : pendingApprovals.get(0);
    }

    public List<PendingApproval> listPendingApprovals(
            com.jimuqu.solon.claw.core.model.SessionRecord sessionRecord) {
        if (sessionRecord == null || StrUtil.isBlank(sessionRecord.getAgentSnapshotJson())) {
            return new ArrayList<PendingApproval>();
        }

        try {
            Object parsed = ONode.deserialize(sessionRecord.getAgentSnapshotJson(), Object.class);
            if (!(parsed instanceof Map)) {
                return new ArrayList<PendingApproval>();
            }
            Map<?, ?> snapshot = (Map<?, ?>) parsed;
            return filterActivePendingApprovals(pendingQueueFrom(snapshot));
        } catch (Exception ignored) {
            return new ArrayList<PendingApproval>();
        }
    }

    public boolean approve(AgentSession session, ApprovalScope scope, String approver)
            throws Exception {
        return approve(session, null, scope, approver);
    }

    public boolean approve(AgentSession session, String selector, ApprovalScope scope, String approver)
            throws Exception {
        PendingApproval pending = findPendingApproval(session, selector);
        if (pending == null) {
            return false;
        }

        ApprovalScope effectiveScope = scope == null ? ApprovalScope.ONCE : scope;
        if (effectiveScope == ApprovalScope.SESSION) {
            for (String patternKey : pending.effectivePatternKeys()) {
                addSessionApproval(
                        session.getContext(), approvalPattern(pending.getToolName(), patternKey));
            }
        } else if (effectiveScope == ApprovalScope.ALWAYS) {
            for (String patternKey : pending.effectivePatternKeys()) {
                String approvalPattern = approvalPattern(pending.getToolName(), patternKey);
                if (isTirithPattern(patternKey)) {
                    addSessionApproval(session.getContext(), approvalPattern);
                } else {
                    addAlwaysApproval(approvalPattern);
                }
            }
        }

        String comment = effectiveScope.comment();
        if (StrUtil.isNotBlank(approver)) {
            comment = comment + " 审批人：" + redactedApprover(approver);
        }

        if (effectiveScope == ApprovalScope.ONCE) {
            addOnceApproval(session.getContext(), pending.approvalKey());
        }
        HITL.approve(session, pending.getToolName(), comment);
        removePendingApproval(session, pending);
        session.updateSnapshot();
        notifyApprovalResponse(session, pending, effectiveScope.name().toLowerCase(Locale.ROOT), approver);
        return true;
    }

    public int approveAll(AgentSession session, ApprovalScope scope, String approver)
            throws Exception {
        ApprovalScope effectiveScope = scope == null ? ApprovalScope.ONCE : scope;
        List<PendingApproval> pendingApprovals = listPendingApprovals(session);
        int approved = 0;
        for (PendingApproval pending : pendingApprovals) {
            String selector = approvalSelector(pending);
            if (approve(session, selector, effectiveScope, approver)) {
                approved++;
            }
        }
        return approved;
    }

    public void storePendingApproval(
            AgentSession session,
            String toolName,
            String patternKey,
            String description,
            String command) {
        if (session == null) {
            return;
        }

        DetectionResult detection = new DetectionResult();
        detection.setPatternKey(patternKey);
        detection.setPatternKeys(Collections.singletonList(patternKey));
        detection.setDescription(description);
        detection.setNormalizedCode(normalize(command));
        Map<String, Object> pendingMap = createPendingMap(toolName, detection, command);
        storePendingMap(session, pendingMap);
        session.updateSnapshot();
        notifyApprovalRequest(session, toPendingApproval(pendingMap));
    }

    public boolean reject(AgentSession session, String approver) {
        return reject(session, null, approver);
    }

    public boolean reject(AgentSession session, String selector, String approver) {
        PendingApproval pending = findPendingApproval(session, selector);
        if (pending == null) {
            return false;
        }

        String comment = "危险命令未获批准，已取消执行。";
        if (StrUtil.isNotBlank(approver)) {
            comment = comment + " 审批人：" + redactedApprover(approver);
        }

        HITL.reject(session, pending.getToolName(), comment);
        removePendingApproval(session, pending);
        session.updateSnapshot();
        notifyApprovalResponse(session, pending, "deny", approver);
        return true;
    }

    public int rejectAll(AgentSession session, String approver) {
        List<PendingApproval> pendingApprovals = listPendingApprovals(session);
        int rejected = 0;
        for (PendingApproval pending : pendingApprovals) {
            String selector = approvalSelector(pending);
            if (reject(session, selector, approver)) {
                rejected++;
            }
        }
        return rejected;
    }

    public DetectionResult detect(String toolName, String code) {
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }

        for (DangerRule rule : RULES) {
            if (!rule.matches(toolName, normalized)) {
                continue;
            }

            DetectionResult result = new DetectionResult();
            result.setPatternKey(rule.getPatternKey());
            result.setPatternKeys(Collections.singletonList(rule.getPatternKey()));
            result.setDescription(rule.getDescription());
            result.setNormalizedCode(normalized);
            return result;
        }

        DetectionResult blockedPath = detectCommandPathForApproval(toolName, normalized);
        if (blockedPath != null) {
            return blockedPath;
        }

        return null;
    }

    private DetectionResult detectCommandPathForApproval(String toolName, String normalized) {
        if (!isCommandSecurityTool(toolName)) {
            return null;
        }
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkConfiguredCredentialCommandPaths(normalized);
        if (verdict.isAllowed()) {
            return null;
        }
        DetectionResult result = new DetectionResult();
        result.setPatternKey("credential_command_path_access");
        result.setPatternKeys(Collections.singletonList("credential_command_path_access"));
        result.setDescription(
                StrUtil.blankToDefault(verdict.getMessage(), "命令访问敏感凭据路径"));
        result.setNormalizedCode(normalized);
        return result;
    }

    private boolean isCommandSecurityTool(String toolName) {
        return ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                || ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                || ToolNameConstants.EXECUTE_JS.equals(toolName)
                || ToolNameConstants.EXECUTE_CODE.equals(toolName);
    }

    public DetectionResult detectHardline(String toolName, String code) {
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        for (DangerRule rule : HARDLINE_RULES) {
            if (!rule.matches(toolName, normalized)) {
                continue;
            }
            DetectionResult result = new DetectionResult();
            result.setPatternKey(rule.getPatternKey());
            result.setPatternKeys(Collections.singletonList(rule.getPatternKey()));
            result.setDescription(rule.getDescription());
            result.setNormalizedCode(normalized);
            result.setHardline(true);
            return result;
        }
        DetectionResult blockedUrl = detectHardlineCommandUrl(toolName, normalized);
        if (blockedUrl != null) {
            return blockedUrl;
        }
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            for (String shellCommand : extractPythonShellCommands(normalized)) {
                DetectionResult result =
                        detectHardline(ToolNameConstants.EXECUTE_SHELL, shellCommand);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private DetectionResult detectHardlineCommandUrl(String toolName, String normalized) {
        if (securityPolicyService == null
                || (!ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                        && !ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                        && !ToolNameConstants.EXECUTE_JS.equals(toolName)
                        && !ToolNameConstants.EXECUTE_CODE.equals(toolName))) {
            return null;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkCommandAlwaysBlockedUrls(normalized);
        if (verdict.isAllowed()) {
            return null;
        }
        DetectionResult result = new DetectionResult();
        result.setPatternKey("hardline_metadata_url");
        result.setPatternKeys(Collections.singletonList("hardline_metadata_url"));
        result.setDescription(verdict.getMessage());
        result.setNormalizedCode(normalized);
        result.setHardline(true);
        return result;
    }

    public String foregroundBackgroundGuidance(String toolName, String code) {
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized) || looksLikeHelpOrVersionCommand(normalized)) {
            return null;
        }
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            for (String shellCommand : extractPythonShellCommands(normalized)) {
                String guidance =
                        foregroundBackgroundGuidance(ToolNameConstants.EXECUTE_SHELL, shellCommand);
                if (guidance != null) {
                    return "BLOCKED: Python 脚本中的 shell 调用需要改用受管后台进程能力。\n" + guidance;
                }
            }
            return null;
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            String childProcessCommand = extractJavaScriptChildProcessCommand(normalized);
            if (StrUtil.isNotBlank(childProcessCommand)) {
                String guidance =
                        foregroundBackgroundGuidance(
                                ToolNameConstants.EXECUTE_SHELL, childProcessCommand);
                if (guidance != null) {
                    return "BLOCKED: Node 脚本中的 child_process 调用需要改用受管后台进程能力。\n"
                            + guidance;
                }
            }
            return null;
        }
        if (!ToolNameConstants.EXECUTE_SHELL.equals(toolName)) {
            return null;
        }

        if (SHELL_LEVEL_BACKGROUND.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了 shell 级后台包装（nohup/disown/setsid）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出，然后再单独执行就绪检查或测试。";
        }
        if (DETACHED_TERMINAL_SESSION.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了脱离当前终端的会话启动器（tmux/screen/systemd-run/start /B）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出。";
        }
        if (isPowerShellBackgroundLaunch(normalized)) {
            return "BLOCKED: 前台命令使用了 PowerShell 后台启动命令（Start-Process/Start-Job/Start-ThreadJob）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出，然后再单独执行就绪检查或测试。";
        }
        if (INLINE_BACKGROUND_AMP.matcher(normalized).find()
                || TRAILING_BACKGROUND_AMP.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了 '&' 后台执行。请使用受管的后台进程能力启动长驻进程，然后在后续命令中执行健康检查或测试。";
        }
        for (Pattern pattern : LONG_LIVED_FOREGROUND_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return "BLOCKED: 该前台命令看起来会启动长驻服务或 watch 进程。请改用受管的后台进程能力，等待健康检查或日志信号后，再用单独命令运行测试。";
            }
        }
        return null;
    }

    private boolean isPowerShellBackgroundLaunch(String normalized) {
        if (!POWERSHELL_BACKGROUND_JOB.matcher(normalized).find()) {
            return false;
        }
        if (!pattern("\\bstart-process\\b").matcher(normalized).find()) {
            return true;
        }
        return POWERSHELL_WAIT_FALSE_FLAG.matcher(normalized).find()
                || !POWERSHELL_WAIT_TRUE_FLAG.matcher(normalized).find();
    }

    public Map<String, Object> approvalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("mode", approvalMode());
        summary.put("cronMode", cronApprovalMode());
        summary.put("subagentAutoApprove", Boolean.valueOf(isSubagentAutoApproveEnabled()));
        summary.put("cronApprovalPolicy", cronApprovalPolicySummary());
        summary.put("subagentApprovalPolicy", subagentApprovalPolicySummary());
        summary.put("smartJudgeConfigured", Boolean.valueOf(hasSmartApprovalJudge()));
        summary.put("smartApprovalPolicy", smartApprovalPolicySummary());
        summary.put("tirithApprovalPolicy", tirithApprovalPolicySummary());
        summary.put("dangerousRuleCount", Integer.valueOf(RULES.size()));
        summary.put("hardlineRuleCount", Integer.valueOf(HARDLINE_RULES.size() + 1));
        summary.put("dangerousRuleSamples", ruleSamples(RULES, 8));
        summary.put(
                "domesticCloudRuleSamples",
                Arrays.asList(
                        "domestic_cloud_cli_credential_config_change",
                        "domestic_object_storage_recursive_remove",
                        "object_storage_exposure_change"));
        summary.put(
                "cloudStorageRuleSamples",
                Arrays.asList(
                        "aws_s3_recursive_remove",
                        "domestic_object_storage_recursive_remove",
                        "remote_credential_file_transfer",
                        "object_storage_exposure_change"));
        summary.put(
                "credentialHandlingRuleSamples",
                Arrays.asList(
                        "sensitive_environment_read",
                        "sensitive_clipboard_export",
                        "sensitive_file_clipboard_export",
                        "network_credential_file_send",
                        "remote_credential_file_transfer"));
        summary.put(
                "secretStoreRuleSamples",
                Arrays.asList(
                        "secret_store_read",
                        "secret_store_write",
                        "secret_store_destroy",
                        "encrypted_secret_file_decrypt"));
        summary.put("networkCredentialFieldAliasDetection", Boolean.TRUE);
        summary.put("sensitiveHttpHeaderAliasDetection", Boolean.TRUE);
        summary.put("rawCredentialFileUploadDetection", Boolean.TRUE);
        summary.put("sensitiveClipboardExportDetection", Boolean.TRUE);
        summary.put("credentialFileClipboardExportDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileClipboardExportDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileClipboardExportDetection", Boolean.TRUE);
        summary.put("codeCredentialFileStdoutDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileStdoutDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileVariableStdoutDetection", Boolean.TRUE);
        summary.put("pythonCredentialFileLogWriteDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileStdoutDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileVariableStdoutDetection", Boolean.TRUE);
        summary.put("javascriptCredentialFileLogWriteDetection", Boolean.TRUE);
        summary.put("codeCredentialFileVariableStdoutDetection", Boolean.TRUE);
        summary.put("codeHttpCredentialDisclosureDetection", Boolean.TRUE);
        summary.put("codeHttpCredentialFileDisclosureDetection", Boolean.TRUE);
        summary.put("codeHttpCredentialFileVariableDisclosureDetection", Boolean.TRUE);
        summary.put("powershellCredentialFileHttpDisclosureDetection", Boolean.TRUE);
        summary.put("configuredCredentialCommandPathDetection", Boolean.TRUE);
        summary.put("recursiveStructuredToolArgsDetection", Boolean.TRUE);
        summary.put("nestedArrayCommandArgumentDetection", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("privateUrlPolicyPrechecked", Boolean.TRUE);
        summary.put("credentialUrlPolicyPrechecked", Boolean.TRUE);
        summary.put("websitePolicyPrechecked", Boolean.TRUE);
        summary.put("unsafeUrlBlockedBeforeApproval", Boolean.TRUE);
        summary.put("unsafeUrlApprovalBypassAllowed", Boolean.FALSE);
        summary.put("hardlineRuleSamples", hardlineRuleSamples(8));
        summary.put("hardlinePolicy", hardlinePolicySummary());
        summary.put("terminalGuardrailCount", Integer.valueOf(4 + LONG_LIVED_FOREGROUND_PATTERNS.size()));
        summary.put(
                "terminalGuardrails",
                Arrays.asList(
                        "shell_level_background",
                        "powershell_background_job",
                        "inline_background_ampersand",
                        "long_lived_foreground"));
        summary.put("sudoRewriteConfigured", Boolean.valueOf(isSudoPasswordConfigured()));
        summary.put("backgroundProcessGuard", Boolean.TRUE);
        summary.put("terminalGuardrailPolicy", terminalGuardrailPolicySummary());
        summary.put("approvalTimeoutSeconds", Integer.valueOf(approvalTimeoutSeconds()));
        summary.put("gatewayTimeoutSeconds", Integer.valueOf(approvalGatewayTimeoutSeconds()));
        summary.put("alwaysApprovalCount", Integer.valueOf(listAlwaysApprovals().size()));
        summary.put("slashConfirmPolicy", slashConfirmPolicySummary());
        summary.put("approvalCardPolicy", approvalCardPolicySummary());
        summary.put("auditLogPolicy", approvalAuditPolicySummary());
        summary.put("mcpReloadPolicy", mcpReloadPolicySummary());
        summary.put("approvalLifecyclePolicy", approvalLifecyclePolicySummary());
        summary.put("description", "Dangerous commands require approval, hardline commands are blocked, and foreground terminal commands are guarded against unmanaged long-running background work.");
        return summary;
    }

    public Map<String, Object> hardlinePolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("ruleCount", Integer.valueOf(HARDLINE_RULES.size() + 1));
        summary.put("ruleSamples", hardlineRuleSamples(12));
        summary.put(
                "coveredTools",
                Arrays.asList(
                        ToolNameConstants.EXECUTE_SHELL,
                        ToolNameConstants.EXECUTE_CODE,
                        ToolNameConstants.EXECUTE_PYTHON,
                        ToolNameConstants.EXECUTE_JS));
        summary.put(
                "blockedCategories",
                Arrays.asList(
                        "root_or_system_recursive_delete",
                        "filesystem_format_or_raw_device_write",
                        "system_shutdown_or_reboot",
                        "kill_all_or_fork_bomb",
                        "windows_disk_or_profile_destruction",
                        "metadata_url_access"));
        summary.put("metadataUrlBlocked", Boolean.valueOf(securityPolicyService != null));
        summary.put("codeToolShellExtractionCovered", Boolean.TRUE);
        summary.put("pythonShellExtractionCovered", Boolean.TRUE);
        summary.put("javascriptChildProcessExtractionCovered", Boolean.TRUE);
        summary.put("approvalBypassAllowed", Boolean.FALSE);
        summary.put("slashApproveBypassAllowed", Boolean.FALSE);
        summary.put("sessionApprovalBypassAllowed", Boolean.FALSE);
        summary.put("alwaysApprovalBypassAllowed", Boolean.FALSE);
        summary.put("yoloBypassAllowed", Boolean.FALSE);
        summary.put("smartApprovalBypassAllowed", Boolean.FALSE);
        summary.put("blockingDecision", "block");
        summary.put("approvalRequired", Boolean.FALSE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("description", "Hardline commands are blocked before approval handling and cannot be bypassed by slash approvals, session approvals, always approvals, smart approval, or yolo mode.");
        return summary;
    }

    public Map<String, Object> smartApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        boolean smartMode = "smart".equals(approvalMode());
        boolean judgeConfigured = hasSmartApprovalJudge();
        summary.put("mode", approvalMode());
        summary.put("smartMode", Boolean.valueOf(smartMode));
        summary.put("judgeConfigured", Boolean.valueOf(judgeConfigured));
        summary.put("active", Boolean.valueOf(smartMode && judgeConfigured));
        summary.put("decisionTypes", Arrays.asList("approve", "escalate", "deny"));
        summary.put("approveWritesSessionApproval", Boolean.TRUE);
        summary.put("approveMarksCurrentThread", Boolean.TRUE);
        summary.put("escalateFallsBackToHumanApproval", Boolean.TRUE);
        summary.put("denyBlocksExecution", Boolean.TRUE);
        summary.put("judgeFailureFallsBackToHumanApproval", Boolean.TRUE);
        summary.put("hardlinePrechecked", Boolean.TRUE);
        summary.put("filePolicyPrechecked", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("terminalGuardrailPrechecked", Boolean.TRUE);
        summary.put("tirithFindingsIncluded", Boolean.TRUE);
        summary.put("subagentPolicyRunsAfterSmartApproval", Boolean.TRUE);
        summary.put("approvalCardFallback", Boolean.TRUE);
        summary.put("reasonStoredInBlockMessage", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("description", "Smart approval only evaluates commands that remain approvable after hardline, file, URL, and terminal guardrail checks; approvals become session-scoped while escalations fall back to human confirmation.");
        return summary;
    }

    public Map<String, Object> tirithApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("scannerConfigured", Boolean.valueOf(tirithSecurityService != null));
        summary.put("scanRunsInApprovalMode", Boolean.valueOf(!"off".equals(approvalMode())));
        summary.put("patternKeyPrefix", "tirith:");
        summary.put("emptyFindingsPatternKey", "tirith:security_scan");
        summary.put("findingsBecomePatternKeys", Boolean.TRUE);
        summary.put("combinedWithLocalDangerRules", Boolean.TRUE);
        summary.put("permanentApprovalAllowed", Boolean.FALSE);
        summary.put("alwaysScopeDowngradedToSession", Boolean.TRUE);
        summary.put("approvalCardAlwaysHidden", Boolean.TRUE);
        summary.put("smartApprovalCanApproveSessionOnly", Boolean.TRUE);
        summary.put("smartApprovalCanDeny", Boolean.TRUE);
        summary.put("pendingMessageBlocksAlwaysScope", Boolean.TRUE);
        summary.put("descriptionRedacted", Boolean.TRUE);
        summary.put("description", "Tirith findings are converted into tirith:* approval patterns, can be combined with local dangerous rules, and never create permanent approvals.");
        return summary;
    }

    public Map<String, Object> cronApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        String mode = cronApprovalMode();
        summary.put("mode", mode);
        summary.put("autoApproveDangerousCommands", Boolean.valueOf("approve".equals(mode)));
        summary.put("defaultDecision", "approve".equals(mode) ? "approve" : "deny");
        summary.put("configKeys", Arrays.asList("approvals.cronMode", "scheduler.cronApprovalMode"));
        summary.put("approveAliases", Arrays.asList("approve", "allow", "off", "yes"));
        summary.put("denyAliases", Arrays.asList("deny", "false", "default"));
        summary.put("runsWithoutHumanApproval", Boolean.TRUE);
        summary.put("hardlineAlwaysBlocked", Boolean.TRUE);
        summary.put("filePolicyPrechecked", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("terminalGuardrailPrechecked", Boolean.TRUE);
        summary.put("dangerousPatternCheckedBeforeRun", Boolean.TRUE);
        summary.put("requiresExplicitApproveMode", Boolean.TRUE);
        summary.put("scriptContentChecked", Boolean.TRUE);
        summary.put("description", "Cron runs without a live approver, so dangerous commands are denied unless approvals.cronMode resolves to approve; hardline commands remain blocked.");
        return summary;
    }

    public Map<String, Object> subagentApprovalPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        boolean autoApprove = isSubagentAutoApproveEnabled();
        summary.put("autoApproveDangerousCommands", Boolean.valueOf(autoApprove));
        summary.put("defaultDecision", autoApprove ? "approve_once" : "deny");
        summary.put("configKey", "approvals.subagentAutoApprove");
        summary.put("runKind", "subagent");
        summary.put("hardlinePrechecked", Boolean.TRUE);
        summary.put("filePolicyPrechecked", Boolean.TRUE);
        summary.put("urlPolicyPrechecked", Boolean.TRUE);
        summary.put("terminalGuardrailPrechecked", Boolean.TRUE);
        summary.put("smartApprovalRunsBeforeSubagentPolicy", Boolean.TRUE);
        summary.put("humanApprovalPromptSuppressed", Boolean.TRUE);
        summary.put("currentThreadApprovalWhenAutoApproved", Boolean.TRUE);
        summary.put("pendingApprovalCreatedWhenDenied", Boolean.FALSE);
        summary.put("denyMessageIncludesConfigHint", Boolean.TRUE);
        summary.put("description", "Subagent runs do not wait for human approval: approvable dangerous commands are denied by default or approved once only when approvals.subagentAutoApprove is enabled.");
        return summary;
    }

    public Map<String, Object> slashConfirmPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("commands", Arrays.asList("/approve", "/deny"));
        summary.put("selectorSupported", Boolean.TRUE);
        summary.put("listSupported", Boolean.TRUE);
        summary.put("statusAliasSupported", Boolean.TRUE);
        summary.put("approveAllSupported", Boolean.TRUE);
        summary.put("denyAllSupported", Boolean.TRUE);
        summary.put("clearSessionSupported", Boolean.TRUE);
        summary.put("clearAlwaysSupported", Boolean.TRUE);
        summary.put("clearAllSupported", Boolean.TRUE);
        summary.put("scopes", Arrays.asList("once", "session", "always"));
        summary.put("defaultScope", "once");
        summary.put(
                "managementCommands",
                Arrays.asList(
                        "/approve list",
                        "/approve status",
                        "/approve clear session",
                        "/approve clear always",
                        "/approve clear all",
                        "/deny list",
                        "/deny status",
                        "/deny all"));
        summary.put("pendingQueueSupported", Boolean.TRUE);
        summary.put("pendingQueueContextKey", CONTEXT_PENDING_APPROVAL_QUEUE);
        summary.put("legacyPendingContextKey", CONTEXT_PENDING_APPROVAL);
        summary.put("pendingListHidesApprovalKey", Boolean.TRUE);
        summary.put("approvalKeySelectorHidden", Boolean.TRUE);
        summary.put("pendingListUsesSafeSelector", Boolean.TRUE);
        summary.put("pendingListShowsPatternKey", Boolean.TRUE);
        summary.put("sessionApprovalListShowsCountOnly", Boolean.TRUE);
        summary.put("alwaysApprovalListShowsCountOnly", Boolean.TRUE);
        summary.put("approvalCardDeliveryMode", DELIVERY_MODE_APPROVAL_CARD);
        summary.put("approvalCardPlatforms", Arrays.asList(PlatformType.FEISHU.name(), PlatformType.QQBOT.name()));
        summary.put("approvalCardActionKey", CARD_ACTION_KEY);
        summary.put("approvalCardApproveAction", CARD_ACTION_APPROVE);
        summary.put("approvalCardDenyAction", CARD_ACTION_DENY);
        summary.put("approvalCardScopeKey", CARD_SCOPE_KEY);
        summary.put("approvalCardApprovalIdKey", CARD_APPROVAL_ID_KEY);
        summary.put("permanentApprovalAllowedExceptTirith", Boolean.TRUE);
        summary.put("tirithAlwaysDowngradedToSession", Boolean.TRUE);
        summary.put("selectorTokenPattern", APPROVAL_SELECTOR_TOKEN.pattern());
        summary.put(
                "selectorPrefixMinLength",
                Integer.valueOf(APPROVAL_SELECTOR_PREFIX_MIN_LENGTH));
        summary.put("unsafeSelectorRejected", Boolean.TRUE);
        summary.put("approverRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("approvalMetadataRedacted", Boolean.TRUE);
        summary.put("observerEventsRedacted", Boolean.TRUE);
        summary.put("approvalTimeoutSeconds", Integer.valueOf(approvalTimeoutSeconds()));
        summary.put("gatewayTimeoutSeconds", Integer.valueOf(approvalGatewayTimeoutSeconds()));
        summary.put("description", "Slash approval commands can approve or deny one pending item, all pending items, or an id selector, with once/session/always scopes, hidden approval keys in list output, and redacted approval metadata.");
        return summary;
    }

    public Map<String, Object> approvalCardPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("deliveryMode", DELIVERY_MODE_APPROVAL_CARD);
        summary.put("supportedPlatforms", Arrays.asList(PlatformType.FEISHU.name(), PlatformType.QQBOT.name()));
        summary.put("unsupportedPlatformsReturnEmptyExtras", Boolean.TRUE);
        summary.put("actionKey", CARD_ACTION_KEY);
        summary.put("approveAction", CARD_ACTION_APPROVE);
        summary.put("denyAction", CARD_ACTION_DENY);
        summary.put("scopeKey", CARD_SCOPE_KEY);
        summary.put("approvalIdKey", CARD_APPROVAL_ID_KEY);
        summary.put("scopeOptions", Arrays.asList("once", "session", "always"));
        summary.put("defaultScope", "once");
        summary.put("approvalIdSelectorSupported", Boolean.TRUE);
        summary.put("selectorTokenPattern", APPROVAL_SELECTOR_TOKEN.pattern());
        summary.put("unsafeSelectorRejected", Boolean.TRUE);
        summary.put("outboundApprovalIdSanitized", Boolean.TRUE);
        summary.put("unsafeApprovalIdFallsBackToKeySelector", Boolean.TRUE);
        summary.put("secretLikeApprovalIdFallsBackToKeySelector", Boolean.TRUE);
        summary.put("secretLikeInboundApprovalIdRejected", Boolean.TRUE);
        summary.put("approveCommandGenerated", Boolean.TRUE);
        summary.put("denyCommandGenerated", Boolean.TRUE);
        summary.put("alwaysScopeCommandGenerated", Boolean.TRUE);
        summary.put("sessionScopeCommandGenerated", Boolean.TRUE);
        summary.put("domesticCardLabelsLocalized", Boolean.TRUE);
        summary.put("feishuChineseCardLabels", Boolean.TRUE);
        summary.put("qqbotSessionActionSupported", Boolean.TRUE);
        summary.put("tirithPermanentApprovalHidden", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("descriptionPreviewRedacted", Boolean.TRUE);
        summary.put("toolNameRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedactedInExtras", Boolean.TRUE);
        summary.put("rawCommandRedactedInExtras", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedactedInExtras", Boolean.TRUE);
        summary.put("semicolonUrlParameterRedacted", Boolean.TRUE);
        summary.put("fragmentUrlParameterRedacted", Boolean.TRUE);
        summary.put("description", "Approval card extras are only emitted for supported domestic card platforms, use safe approval selectors in outbound card payloads, map card actions back to /approve or /deny commands with redacted previews, and expose localized card labels plus session-scope channel actions.");
        return summary;
    }

    public Map<String, Object> approvalAuditPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("observerCount", Integer.valueOf(approvalObservers.size()));
        summary.put("requestEvents", Boolean.TRUE);
        summary.put("responseEvents", Boolean.TRUE);
        summary.put("eventTypes", Arrays.asList("request", "response"));
        summary.put("repositoryBackedWhenConfigured", Boolean.TRUE);
        summary.put("observerFailureIsolated", Boolean.TRUE);
        summary.put("approverRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("descriptionRedacted", Boolean.TRUE);
        summary.put("approvalKeyRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("commandHashStored", Boolean.TRUE);
        summary.put("patternKeysStored", Boolean.TRUE);
        summary.put("timestampsStored", Boolean.TRUE);
        summary.put("recentDashboardViewSupported", Boolean.TRUE);
        summary.put("manualRevocationAudited", Boolean.TRUE);
        summary.put("description", "Approval request and response events can be persisted with redacted command previews, approvers, descriptions, pattern keys, command hashes, and approval timestamps.");
        return summary;
    }

    public Map<String, Object> mcpReloadPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        boolean confirmRequired =
                appConfig == null
                        || appConfig.getApprovals() == null
                        || appConfig.getApprovals().isMcpReloadConfirm();
        summary.put("command", "/reload-mcp");
        summary.put("confirmRequired", Boolean.valueOf(confirmRequired));
        summary.put("configKey", "approvals.mcpReloadConfirm");
        summary.put("slashConfirmBacked", Boolean.TRUE);
        summary.put("directRunAlias", "now");
        summary.put("alwaysConfirmAlias", "always");
        summary.put("persistentDisableSupported", Boolean.TRUE);
        summary.put("runtimeConfigPersisted", Boolean.TRUE);
        summary.put("toolChangeNoticeInjected", Boolean.TRUE);
        summary.put("changedServerSummary", Boolean.TRUE);
        summary.put("toolCountSummary", Boolean.TRUE);
        summary.put("oauthUrlSafetyCovered", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("reloadHistoryNoticeRedacted", Boolean.TRUE);
        summary.put("description", "MCP reload can require slash confirmation, supports now/always overrides, persists the confirmation flag, and records tool-change notices for the next model turn.");
        return summary;
    }

    public Map<String, Object> approvalLifecyclePolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("pendingQueueContextKey", CONTEXT_PENDING_APPROVAL_QUEUE);
        summary.put("legacyPendingContextKey", CONTEXT_PENDING_APPROVAL);
        summary.put("pendingListPrunedBeforeRead", Boolean.TRUE);
        summary.put("selectorSupported", Boolean.TRUE);
        summary.put("listSupported", Boolean.TRUE);
        summary.put("statusAliasSupported", Boolean.TRUE);
        summary.put("approveAllSupported", Boolean.TRUE);
        summary.put("rejectAllSupported", Boolean.TRUE);
        summary.put("clearSessionSupported", Boolean.TRUE);
        summary.put("clearAlwaysSupported", Boolean.TRUE);
        summary.put("clearAllSupported", Boolean.TRUE);
        summary.put("scopes", Arrays.asList("once", "session", "always"));
        summary.put("onceScopeStoresContextKey", CONTEXT_ONCE_APPROVALS);
        summary.put("sessionScopeStoresContextKey", CONTEXT_SESSION_APPROVALS);
        summary.put("alwaysScopeUsesGlobalSettings", Boolean.TRUE);
        summary.put("tirithAlwaysScopeDowngradedToSession", Boolean.TRUE);
        summary.put("currentThreadApprovalTtlMillis", Long.valueOf(CURRENT_THREAD_APPROVAL_TTL_MILLIS));
        summary.put("currentThreadApprovalEnabled", Boolean.TRUE);
        summary.put("selectorTokenPattern", APPROVAL_SELECTOR_TOKEN.pattern());
        summary.put("unsafeSelectorRejected", Boolean.TRUE);
        summary.put("bulkRejectUsesSafeSelector", Boolean.TRUE);
        summary.put("approveRemovesPendingApproval", Boolean.TRUE);
        summary.put("rejectRemovesPendingApproval", Boolean.TRUE);
        summary.put("sessionSnapshotUpdated", Boolean.TRUE);
        summary.put("approvalRequestObserved", Boolean.TRUE);
        summary.put("approvalResponseObserved", Boolean.TRUE);
        summary.put("approverRedacted", Boolean.TRUE);
        summary.put("approvalKeyRedacted", Boolean.TRUE);
        summary.put("commandPreviewRedacted", Boolean.TRUE);
        summary.put("encodedUrlParameterRedacted", Boolean.TRUE);
        summary.put("description", "Approval lifecycle stores queued approvals in session context, supports once/session/always scopes, downgrades scanner findings to session scope, updates snapshots, and emits redacted request/response events.");
        return summary;
    }

    public Map<String, Object> terminalGuardrailPolicySummary() {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("backgroundShellWrappersBlocked", Arrays.asList("nohup", "disown", "setsid"));
        summary.put(
                "detachedSessionLaunchersBlocked",
                Arrays.asList("tmux new-session -d", "screen -dmS", "systemd-run", "cmd /c start /B"));
        summary.put(
                "powershellBackgroundCommandsBlocked",
                Arrays.asList("Start-Process", "Start-Job", "Start-ThreadJob"));
        summary.put("powershellStartProcessRequiresWait", Boolean.TRUE);
        summary.put("powershellStartProcessNoNewWindowNotEnough", Boolean.TRUE);
        summary.put("powershellStartProcessPassThruNotEnough", Boolean.TRUE);
        summary.put("inlineAmpersandBlocked", Boolean.TRUE);
        summary.put("trailingAmpersandBlocked", Boolean.TRUE);
        summary.put("longLivedForegroundBlocked", Boolean.TRUE);
        summary.put("longLivedForegroundPatternCount", Integer.valueOf(LONG_LIVED_FOREGROUND_PATTERNS.size()));
        summary.put(
                "longLivedForegroundSamples",
                Arrays.asList(
                        "npm run dev",
                        "docker compose up",
                        "vite",
                        "python -m http.server"));
        summary.put("codeToolShellExtractionCovered", Boolean.TRUE);
        summary.put(
                "codeToolShellSources",
                Arrays.asList("execute_code", "execute_python", "execute_js"));
        summary.put("commandPathPrechecked", Boolean.TRUE);
        summary.put("credentialPathPrechecked", Boolean.TRUE);
        summary.put("downloadOutputPathPrechecked", Boolean.TRUE);
        summary.put("downloadOutputDetachedOptionPrechecked", Boolean.TRUE);
        summary.put("networkUploadSourcePathPrechecked", Boolean.TRUE);
        summary.put("proxyUrlPrechecked", Boolean.TRUE);
        summary.put("preproxyUrlPrechecked", Boolean.TRUE);
        summary.put("systemDnsCommandPrechecked", Boolean.TRUE);
        summary.put("systemProxyCommandPrechecked", Boolean.TRUE);
        summary.put("windowsRegistryProxyCommandPrechecked", Boolean.TRUE);
        summary.put("hostsAndResolverPathPrechecked", Boolean.TRUE);
        summary.put("managedBackgroundProcessRequired", Boolean.TRUE);
        summary.put("processRegistryBacked", Boolean.TRUE);
        summary.put("sudoRewriteConfigured", Boolean.valueOf(isSudoPasswordConfigured()));
        summary.put("sudoPasswordRedacted", Boolean.TRUE);
        summary.put("foregroundMaxTimeoutSeconds", Integer.valueOf(maxForegroundTimeoutSeconds()));
        summary.put("foregroundMaxRetries", Integer.valueOf(foregroundMaxRetries()));
        summary.put("foregroundRetryBaseDelaySeconds", Integer.valueOf(foregroundRetryBaseDelaySeconds()));
        summary.put("description", "Foreground terminal guardrails block unmanaged background wrappers, inline background operators, credential path access, unsafe proxy/preproxy URLs, system DNS/proxy changes, hosts/resolver writes, download output or network upload source credential paths, and common long-running dev/server commands, with managed background process guidance and redacted sudo support.");
        return summary;
    }

    public Map<String, Object> buildDeliveryExtras(PlatformType platform, PendingApproval pending) {
        if ((platform != PlatformType.FEISHU && platform != PlatformType.QQBOT)
                || pending == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", DELIVERY_MODE_APPROVAL_CARD);
        extras.put("approvalId", safeApprovalSelector(pending));
        extras.put("approvalCommand", redactApprovalDisplay(pending.getCommand(), 3000));
        extras.put("approvalDescription", redactApprovalDisplay(pending.getDescription(), 1000));
        extras.put("approvalToolName", redactApprovalDisplay(pending.getToolName(), 200));
        extras.put("approvalAllowAlways", Boolean.valueOf(pending.isPermanentApprovalAllowed()));
        return extras;
    }

    public boolean isSessionApproved(AgentSession session, String patternKey) {
        if (session == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        return containsPattern(loadSessionApprovals(session.getContext()), patternKey);
    }

    public boolean isSessionApproved(
            AgentSession session, String toolName, String patternKey, String command) {
        if (session == null || StrUtil.hasBlank(toolName, patternKey)) {
            return false;
        }
        Set<String> approvals = loadSessionApprovals(session.getContext());
        return approvals.contains(approvalPattern(toolName, patternKey))
                || approvals.contains(approvalKey(toolName, patternKey, normalize(command)));
    }

    public boolean isAlwaysApproved(String patternKey) {
        if (StrUtil.isBlank(patternKey)) {
            return false;
        }
        return containsPattern(loadAlwaysApprovedPatterns(), patternKey);
    }

    public boolean isAlwaysApproved(String toolName, String patternKey, String command) {
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return false;
        }
        Set<String> approvals = loadAlwaysApprovedPatterns();
        return approvals.contains(approvalPattern(toolName, patternKey))
                || approvals.contains(approvalKey(toolName, patternKey, normalize(command)));
    }

    public static boolean consumeCurrentThreadApproval(String toolName, String command) {
        Map<String, Long> approvals = CURRENT_THREAD_APPROVED_COMMANDS.get();
        if (approvals == null || approvals.isEmpty()) {
            return false;
        }
        removeExpiredCurrentThreadApprovals(approvals);
        String key = currentThreadApprovalKey(toolName, command);
        Long expiresAt = approvals.remove(key);
        if (approvals.isEmpty()) {
            CURRENT_THREAD_APPROVED_COMMANDS.remove();
        }
        return expiresAt != null && expiresAt.longValue() >= System.currentTimeMillis();
    }

    public List<String> listSessionApprovals(AgentSession session) {
        if (session == null) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(loadSessionApprovals(session.getContext()));
    }

    public List<String> listAlwaysApprovals() {
        return new ArrayList<String>(loadAlwaysApprovedPatterns());
    }

    public boolean revokeAlwaysApproval(String approvalPattern) throws Exception {
        String normalized = cleanApprovalValue(approvalPattern);
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        Set<String> approvals = loadAlwaysApprovedPatterns();
        boolean removed = approvals.remove(normalized);
        if (!removed) {
            return false;
        }
        if (globalSettingRepository != null) {
            globalSettingRepository.set(
                    AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                    ONode.serialize(new ArrayList<String>(approvals)));
        }
        return true;
    }

    public void clearSessionApprovals(AgentSession session) throws Exception {
        if (session == null) {
            return;
        }
        session.getContext().remove(CONTEXT_SESSION_APPROVALS);
        session.getContext().remove(CONTEXT_ONCE_APPROVALS);
        session.getContext().remove(CONTEXT_PENDING_APPROVAL);
        session.getContext().remove(CONTEXT_PENDING_APPROVAL_QUEUE);
        session.getContext().remove(CONTEXT_SESSION_YOLO);
        session.updateSnapshot();
    }

    public boolean enableSessionYolo(AgentSession session) throws Exception {
        return setSessionYolo(session, true);
    }

    public boolean disableSessionYolo(AgentSession session) throws Exception {
        return setSessionYolo(session, false);
    }

    public boolean toggleSessionYolo(AgentSession session) throws Exception {
        return setSessionYolo(session, !isSessionYoloEnabled(session));
    }

    public boolean isSessionYoloEnabled(AgentSession session) {
        return session != null && truthy(session.getContext().get(CONTEXT_SESSION_YOLO));
    }

    public void clearAlwaysApprovals() throws Exception {
        if (globalSettingRepository != null) {
            globalSettingRepository.set(
                    AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                    ONode.serialize(new ArrayList<String>()));
        }
    }

    public static String commandFromCardActionPayload(Object raw) {
        Map<?, ?> map = raw instanceof Map ? (Map<?, ?>) raw : parseStaticMap(raw);
        if (map == null) {
            return null;
        }

        String action = safeCardToken(map.get(CARD_ACTION_KEY)).toLowerCase(Locale.ROOT);
        String approvalId = safeApprovalSelectorToken(map.get(CARD_APPROVAL_ID_KEY));
        if (approvalId == null) {
            return null;
        }
        if (CARD_ACTION_DENY.equals(action)) {
            return StrUtil.isBlank(approvalId) ? "/deny" : "/deny " + approvalId;
        }
        if (!CARD_ACTION_APPROVE.equals(action)) {
            return null;
        }

        String scope = safeCardToken(map.get(CARD_SCOPE_KEY)).toLowerCase(Locale.ROOT);
        String selector = StrUtil.isBlank(approvalId) ? "" : approvalId + " ";
        if ("always".equals(scope)) {
            return "/approve " + selector + "always";
        }
        if ("session".equals(scope)) {
            return "/approve " + selector + "session";
        }
        return StrUtil.isBlank(approvalId) ? "/approve" : "/approve " + approvalId;
    }

    private static String safeCardToken(Object value) {
        return SecretRedactor.stripDisplayControls(
                        TerminalAnsiSanitizer.stripAnsi(stringValueStatic(value)))
                .trim();
    }

    public static String safeApprovalSelectorToken(Object value) {
        String token = safeCardToken(value);
        if (StrUtil.isBlank(token)) {
            return "";
        }
        if (!APPROVAL_SELECTOR_TOKEN.matcher(token).matches()) {
            return null;
        }
        return token.equals(SecretRedactor.redact(token, token.length() + 128)) ? token : null;
    }

    private String evaluate(ReActTrace trace, String toolName, Map<String, Object> args) {
        return evaluateCommand(trace, toolName, toolName, codeArg(args));
    }

    private String evaluateAlias(
            ReActTrace trace, String actualToolName, String canonicalToolName, Map<String, Object> args) {
        return evaluateCommand(trace, actualToolName, canonicalToolName, codeArg(args));
    }

    private String codeArg(Map<String, Object> args) {
        return commandLikeArg(args);
    }

    private String evaluateTerminalTool(ReActTrace trace, Map<String, Object> args) {
        String command = commandLikeArg(args);
        return evaluateCommand(
                trace, ToolNameConstants.TERMINAL, ToolNameConstants.EXECUTE_SHELL, command);
    }

    private String evaluateTerminalAlias(ReActTrace trace, String actualToolName, Map<String, Object> args) {
        String command = commandLikeArg(args);
        return evaluateCommand(trace, actualToolName, ToolNameConstants.EXECUTE_SHELL, command);
    }

    private String evaluateProcessTool(ReActTrace trace, Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        String action =
                args.get("action") == null
                        ? ""
                        : StrUtil.nullToEmpty(String.valueOf(args.get("action")))
                                .trim()
                                .toLowerCase(Locale.ROOT);
        if (!"start".equals(action)) {
            return null;
        }
        String command = commandLikeArg(args);
        return evaluateCommand(
                trace, ToolNameConstants.PROCESS, ToolNameConstants.EXECUTE_SHELL, command);
    }

    private String commandLikeArg(Map<String, Object> args) {
        return commandLikeArg(args, 0);
    }

    private String commandLikeArg(Object raw, int depth) {
        if (raw == null || depth > 6) {
            return null;
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            for (String key : COMMAND_ARGUMENT_KEYS) {
                if (map.containsKey(key)) {
                    String value = commandValueToString(map.get(key), depth + 1);
                    if (StrUtil.isNotBlank(value)) {
                        return value;
                    }
                }
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
                if (COMMAND_ARGUMENT_KEYS.contains(key)) {
                    String value = commandValueToString(entry.getValue(), depth + 1);
                    if (StrUtil.isNotBlank(value)) {
                        return value;
                    }
                }
            }
            for (Object value : map.values()) {
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
            return null;
        }
        if (raw instanceof Iterable) {
            for (Object value : (Iterable<?>) raw) {
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
            return null;
        }
        if (raw.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                String nested = commandLikeArg(java.lang.reflect.Array.get(raw, i), depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String commandValueToString(Object raw, int depth) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof CharSequence) {
            return String.valueOf(raw);
        }
        if (raw instanceof Iterable) {
            StringBuilder buffer = new StringBuilder();
            for (Object value : (Iterable<?>) raw) {
                if (value == null) {
                    continue;
                }
                if (value instanceof CharSequence
                        || value instanceof Number
                        || value instanceof Boolean) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(String.valueOf(value));
                    continue;
                }
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(nested);
                }
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        if (raw.getClass().isArray()) {
            StringBuilder buffer = new StringBuilder();
            int length = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < length; i++) {
                Object value = java.lang.reflect.Array.get(raw, i);
                if (value == null) {
                    continue;
                }
                if (value instanceof CharSequence
                        || value instanceof Number
                        || value instanceof Boolean) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(String.valueOf(value));
                    continue;
                }
                String nested = commandLikeArg(value, depth + 1);
                if (StrUtil.isNotBlank(nested)) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(nested);
                }
            }
            return buffer.length() == 0 ? null : buffer.toString();
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return String.valueOf(raw);
        }
        return commandLikeArg(raw, depth + 1);
    }

    private String evaluateGatewayCallTool(ReActTrace trace, Map<String, Object> args) {
        String toolName = gatewayToolName(args);
        if (StrUtil.isBlank(toolName)) {
            return null;
        }
        String normalized = canonicalGatewayToolName(toolName);
        GatewayToolArgsResult parsedArgs = gatewayToolArgs(args);
        if (isGatewaySecurityTool(normalized) && !parsedArgs.isValid()) {
            blockMalformedGatewayToolArgs(trace, normalized, parsedArgs);
            return null;
        }
        Map<String, Object> toolArgs = parsedArgs.getArgs();
        boolean hadInnerDecision =
                trace != null
                        && trace.getContext() != null
                        && trace.getContext().getAs(HITL.DECISION_PREFIX + normalized) != null;
        String result;
        if (ToolNameConstants.EXECUTE_SHELL.equals(normalized)
                || ToolNameConstants.EXECUTE_PYTHON.equals(normalized)
                || ToolNameConstants.EXECUTE_JS.equals(normalized)) {
            result = evaluate(trace, normalized, toolArgs);
        } else if (ToolNameConstants.EXECUTE_CODE.equals(normalized)) {
            result =
                    evaluateCodeCommand(
                            trace,
                            ToolNameConstants.EXECUTE_CODE,
                            codeArg(toolArgs));
        } else if (ToolNameConstants.TERMINAL.equals(normalized)) {
            result = evaluateTerminalTool(trace, toolArgs);
        } else if (ToolNameConstants.PROCESS.equals(normalized)) {
            result = evaluateProcessTool(trace, toolArgs);
        } else if (isFileSecurityTool(normalized)) {
            result = evaluateFileTool(trace, normalized, toolArgs);
        } else if (isUrlSecurityTool(normalized)) {
            result = evaluateUrlTool(trace, normalized, toolArgs);
        } else {
            return null;
        }
        if (hadInnerDecision) {
            clearGatewayInnerDecisionAfterApproval(trace, normalized, result);
        }
        return result;
    }

    private String canonicalGatewayToolName(String toolName) {
        String normalized = StrUtil.nullToEmpty(toolName).trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("shell".equals(lower)
                || "bash".equals(lower)
                || "exec".equals(lower)
                || "execute-shell".equals(lower)
                || "exec_shell".equals(lower)
                || "execute_shell_command".equals(lower)
                || "exec_command".equals(lower)
                || "run_shell".equals(lower)
                || "run_command".equals(lower)
                || "execcommand".equals(lower)
                || "exec_command".equals(lower)
                || "exec_cmd".equals(lower)
                || "executeshell".equals(lower)) {
            return ToolNameConstants.EXECUTE_SHELL;
        }
        if ("run_terminal".equals(lower)
                || "terminal_run".equals(lower)
                || "terminal_exec".equals(lower)
                || "terminal_execute".equals(lower)) {
            return ToolNameConstants.TERMINAL;
        }
        if ("start_process".equals(lower)
                || "process_start".equals(lower)
                || "run_process".equals(lower)
                || "background_process".equals(lower)
                || "managed_process".equals(lower)
                || "process-run".equals(lower)
                || "process-start".equals(lower)) {
            return ToolNameConstants.PROCESS;
        }
        if ("python".equals(lower)
                || "python_exec".equals(lower)
                || "python_execute".equals(lower)
                || "run_python".equals(lower)
                || "execute-python".equals(lower)) {
            return ToolNameConstants.EXECUTE_PYTHON;
        }
        if ("js".equals(lower)
                || "node".equals(lower)
                || "nodejs".equals(lower)
                || "javascript".equals(lower)
                || "run_js".equals(lower)
                || "run_javascript".equals(lower)
                || "execute-javascript".equals(lower)
                || "execute-js".equals(lower)) {
            return ToolNameConstants.EXECUTE_JS;
        }
        if ("code".equals(lower)
                || "run_code".equals(lower)
                || "code_run".equals(lower)
                || "code_exec".equals(lower)
                || "execute-code".equals(lower)) {
            return ToolNameConstants.EXECUTE_CODE;
        }
        if ("web_extract".equals(lower)
                || "web_fetch".equals(lower)
                || "fetch_url".equals(lower)
                || "fetch".equals(lower)
                || "url_fetch".equals(lower)
                || "http_get".equals(lower)
                || "get_url".equals(lower)
                || "read_url".equals(lower)) {
            return ToolNameConstants.WEBFETCH;
        }
        if ("web_search".equals(lower)
                || "search_web".equals(lower)
                || "search".equals(lower)
                || "internet_search".equals(lower)) {
            return ToolNameConstants.WEBSEARCH;
        }
        if ("code_search".equals(lower)
                || "search_code".equals(lower)
                || "search_files".equals(lower)
                || "file_search".equals(lower)) {
            return ToolNameConstants.CODESEARCH;
        }
        if ("read_file".equals(lower) || "file-read".equals(lower) || "file_read_file".equals(lower)) {
            return ToolNameConstants.FILE_READ;
        }
        if ("write_file".equals(lower)
                || "file-write".equals(lower)
                || "create_file".equals(lower)
                || "file_create".equals(lower)
                || "save_file".equals(lower)) {
            return ToolNameConstants.FILE_WRITE;
        }
        if ("list_file".equals(lower)
                || "list_files".equals(lower)
                || "list_dir".equals(lower)
                || "list_directory".equals(lower)
                || "read_dir".equals(lower)
                || "file-list".equals(lower)) {
            return ToolNameConstants.FILE_LIST;
        }
        if ("delete_file".equals(lower)
                || "remove_file".equals(lower)
                || "unlink_file".equals(lower)
                || "file-delete".equals(lower)
                || "file_remove".equals(lower)) {
            return ToolNameConstants.FILE_DELETE;
        }
        if ("apply_patch".equals(lower)
                || "apply-patch".equals(lower)
                || "patch_apply".equals(lower)
                || "patch-apply".equals(lower)
                || "diff_apply".equals(lower)
                || "diff-apply".equals(lower)
                || "apply_diff".equals(lower)
                || "apply-diff".equals(lower)) {
            return ToolNameConstants.PATCH;
        }
        if ("config_read".equals(lower)) {
            return ToolNameConstants.CONFIG_GET;
        }
        if ("config_write".equals(lower)) {
            return ToolNameConstants.CONFIG_SET;
        }
        if ("config_update_secret".equals(lower)) {
            return ToolNameConstants.CONFIG_SET_SECRET;
        }
        return lower;
    }

    private String gatewayToolName(Map<String, Object> args) {
        if (args == null) {
            return "";
        }
        Object value = args.get("tool_name");
        if (value == null) {
            value = args.get("name");
        }
        if (value == null) {
            value = args.get("tool");
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private GatewayToolArgsResult gatewayToolArgs(Map<String, Object> args) {
        if (args == null) {
            return GatewayToolArgsResult.valid(new LinkedHashMap<String, Object>());
        }
        Object raw = args.get("tool_args");
        if (raw == null) {
            raw = args.get("args");
        }
        if (raw instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return GatewayToolArgsResult.valid(result);
        }
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.length() == 0) {
            return GatewayToolArgsResult.valid(new LinkedHashMap<String, Object>());
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            if (parsed instanceof Map) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return GatewayToolArgsResult.valid(result);
            }
            return GatewayToolArgsResult.invalid("tool_args 必须是 JSON 对象。", text);
        } catch (Exception ignored) {
            return GatewayToolArgsResult.invalid("tool_args 不是合法 JSON。", text);
        }
    }

    private boolean isGatewaySecurityTool(String toolName) {
        return ToolNameConstants.EXECUTE_SHELL.equals(toolName)
                || ToolNameConstants.EXECUTE_PYTHON.equals(toolName)
                || ToolNameConstants.EXECUTE_JS.equals(toolName)
                || ToolNameConstants.EXECUTE_CODE.equals(toolName)
                || ToolNameConstants.TERMINAL.equals(toolName)
                || ToolNameConstants.PROCESS.equals(toolName)
                || isFileSecurityTool(toolName)
                || isUrlSecurityTool(toolName);
    }

    private void blockMalformedGatewayToolArgs(
            ReActTrace trace, String toolName, GatewayToolArgsResult parsedArgs) {
        if (trace == null) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("BLOCKED: 工具网关参数格式无效，无法安全检查内层工具调用。");
        message.append("\n工具：").append(toolLabel(toolName));
        message.append("\n原因：").append(parsedArgs.getMessage());
        if (StrUtil.isNotBlank(parsedArgs.getRawText())) {
            message.append("\n参数预览：")
                    .append(SecretRedactor.redact(parsedArgs.getRawText(), 400));
        }
        message.append("\n请把 tool_args 改为 JSON 对象后再重试。");
        trace.setFinalAnswer(message.toString());
        trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        persistTraceSnapshot(trace);
    }

    private boolean isFileSecurityTool(String toolName) {
        return ToolNameConstants.FILE_READ.equals(toolName)
                || ToolNameConstants.FILE_WRITE.equals(toolName)
                || ToolNameConstants.FILE_LIST.equals(toolName)
                || ToolNameConstants.FILE_DELETE.equals(toolName)
                || ToolNameConstants.PATCH.equals(toolName);
    }

    private boolean isUrlSecurityTool(String toolName) {
        return ToolNameConstants.WEBFETCH.equals(toolName)
                || ToolNameConstants.WEBSEARCH.equals(toolName)
                || ToolNameConstants.CODESEARCH.equals(toolName);
    }

    private void clearGatewayInnerDecisionAfterApproval(
            ReActTrace trace, String toolName, String result) {
        if (trace == null
                || trace.getContext() == null
                || StrUtil.isBlank(toolName)
                || result != null
                || org.noear.solon.ai.agent.Agent.ID_END.equals(trace.getRoute())) {
            return;
        }
        trace.getContext().remove(HITL.DECISION_PREFIX + toolName);
        trace.getContext().remove(HITL.LAST_INTERVENED);
    }

    private String evaluateCommand(
            ReActTrace trace, String approvalToolName, String ruleToolName, String code) {
        DetectionResult hardline = detectHardline(ruleToolName, code);
        if (hardline != null) {
            trace.setFinalAnswer(buildHardlineMessage(approvalToolName, hardline, code));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }
        return evaluateCommandWithoutHardline(trace, approvalToolName, ruleToolName, code);
    }

    private String evaluateCodeCommand(ReActTrace trace, String approvalToolName, String code) {
        DetectionResult hardline = detectHardline(ToolNameConstants.EXECUTE_PYTHON, code);
        if (hardline != null) {
            trace.setFinalAnswer(buildHardlineMessage(approvalToolName, hardline, code));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }
        return evaluateCommandWithoutHardline(
                trace, approvalToolName, ToolNameConstants.EXECUTE_PYTHON, code);
    }

    private String evaluateCommandWithoutHardline(
            ReActTrace trace, String approvalToolName, String ruleToolName, String code) {
        SecurityPolicyService.FileVerdict fileVerdict = detectUnsafeCommandPath(code);
        if (fileVerdict != null) {
            trace.setFinalAnswer(buildFilePolicyMessage(approvalToolName, fileVerdict));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        SecurityPolicyService.UrlVerdict urlVerdict = detectUnsafeCommandUrl(code);
        if (urlVerdict != null) {
            trace.setFinalAnswer(buildUrlPolicyMessage(urlVerdict));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        String foregroundGuidance = foregroundBackgroundGuidance(ruleToolName, code);
        if (foregroundGuidance != null) {
            trace.setFinalAnswer(foregroundGuidance);
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        if (isCompatibilityYoloModeEnabled() || isSessionYoloEnabled(trace.getSession())) {
            persistTraceSnapshot(trace);
            return null;
        }

        String approvalMode = approvalMode();
        if ("off".equals(approvalMode)) {
            persistTraceSnapshot(trace);
            return null;
        }

        DetectionResult detection = detectCombined(ruleToolName, code);
        if (detection == null) {
            persistTraceSnapshot(trace);
            return null;
        }

        String approvalKey = combinedApprovalKey(approvalToolName, detection);
        PendingApproval pending = getPendingApproval(trace.getSession());
        if (trace.getContext().getAs(HITL.DECISION_PREFIX + approvalToolName) != null) {
            if ((pending != null && approvalKey.equals(pending.approvalKey()))
                    || consumeOnceApproval(trace.getContext(), approvalKey)) {
                markCurrentThreadApproval(approvalToolName, code);
                removePendingApproval(trace.getSession(), pending);
                persistTraceSnapshot(trace);
                return null;
            }
            trace.getContext().remove(HITL.DECISION_PREFIX + approvalToolName);
            persistTraceSnapshot(trace);
        }

        if (isApproved(trace.getContext(), approvalKey)) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            markCurrentThreadApproval(approvalToolName, code);
            persistTraceSnapshot(trace);
            return null;
        }

        SmartApprovalDecision smartDecision =
                "smart".equals(approvalMode)
                        ? smartApprove(approvalToolName, code, detection, trace.getContext())
                        : null;
        if (smartDecision != null && smartDecision.isApproved()) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            markCurrentThreadApproval(approvalToolName, code);
            persistTraceSnapshot(trace);
            return null;
        }
        if (smartDecision != null && smartDecision.isDenied()) {
            trace.setFinalAnswer(buildSmartDeniedMessage(detection, smartDecision));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        if (isSubagentRun()) {
            if (isSubagentAutoApproveEnabled()) {
                markCurrentThreadApproval(approvalToolName, code);
                persistTraceSnapshot(trace);
                return null;
            }
            trace.setFinalAnswer(buildSubagentDeniedMessage(detection));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        Map<String, Object> pendingMap = createPendingMap(approvalToolName, detection, code);
        storePendingMap(trace.getSession(), pendingMap);
        persistTraceSnapshot(trace);
        notifyApprovalRequest(trace.getSession(), toPendingApproval(pendingMap));
        return buildPendingMessage(approvalToolName, detection, code);
    }

    private SmartApprovalDecision smartApprove(
            String toolName, String code, DetectionResult detection, FlowContext context) {
        if (smartApprovalJudge == null || detection == null) {
            return null;
        }
        try {
            SmartApprovalDecision decision =
                    smartApprovalJudge.judge(toolName, code, detection.getDescription());
            if (decision == null || !decision.isApproved()) {
                return decision;
            }
            for (String patternKey : detection.effectivePatternKeys()) {
                addSessionApproval(context, approvalPattern(toolName, patternKey));
            }
            return decision;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildSmartDeniedMessage(
            DetectionResult detection, SmartApprovalDecision decision) {
        String description =
                detection == null
                        ? "dangerous command"
                        : StrUtil.blankToDefault(detection.getDescription(), detection.getPatternKey());
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED by smart approval: ")
                .append(redactApprovalDisplay(description, 1000))
                .append(". The command was assessed as genuinely dangerous. Do NOT retry.");
        if (decision != null && StrUtil.isNotBlank(decision.getReason())) {
            buffer.append("\n原因：").append(redactApprovalDisplay(decision.getReason(), 1000));
        }
        return buffer.toString();
    }

    private void addOnceApproval(FlowContext context, String approvalKey) {
        if (context == null || StrUtil.isBlank(approvalKey)) {
            return;
        }
        Set<String> approvals = loadOnceApprovals(context);
        approvals.add(approvalKey.trim());
        context.put(CONTEXT_ONCE_APPROVALS, new ArrayList<String>(approvals));
    }

    private boolean consumeOnceApproval(FlowContext context, String approvalKey) {
        if (context == null || StrUtil.isBlank(approvalKey)) {
            return false;
        }
        Set<String> approvals = loadOnceApprovals(context);
        boolean consumed = approvals.remove(approvalKey.trim());
        if (consumed) {
            if (approvals.isEmpty()) {
                context.remove(CONTEXT_ONCE_APPROVALS);
            } else {
                context.put(CONTEXT_ONCE_APPROVALS, new ArrayList<String>(approvals));
            }
        }
        return consumed;
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadOnceApprovals(FlowContext context) {
        Set<String> approvals = new LinkedHashSet<String>();
        if (context == null) {
            return approvals;
        }
        Object raw = context.get(CONTEXT_ONCE_APPROVALS);
        if (raw instanceof Collection) {
            for (Object value : (Collection<Object>) raw) {
                if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                    approvals.add(String.valueOf(value).trim());
                }
            }
            return approvals;
        }
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.length() == 0) {
            return approvals;
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            if (parsed instanceof Collection) {
                for (Object value : (Collection<Object>) parsed) {
                    if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                        approvals.add(String.valueOf(value).trim());
                    }
                }
                return approvals;
            }
        } catch (Exception ignored) {
        }
        approvals.add(text);
        return approvals;
    }

    private DetectionResult detectCombined(String toolName, String code) {
        DetectionResult local = detect(toolName, code);
        TirithSecurityService.ScanResult scan = scanWithTirith(toolName, code);
        if (scan == null || !scan.requiresApproval()) {
            return local;
        }

        List<String> keys = new ArrayList<String>();
        List<String> descriptions = new ArrayList<String>();
        keys.addAll(tirithPatternKeys(scan));
        descriptions.add(tirithDescription(scan));
        if (local != null) {
            keys.addAll(local.effectivePatternKeys());
            descriptions.add(local.getDescription());
        }

        DetectionResult combined = new DetectionResult();
        combined.setPatternKeys(unique(keys));
        combined.setPatternKey(combined.getPatternKeys().isEmpty() ? "tirith:security_scan" : combined.getPatternKeys().get(0));
        combined.setDescription(joinDescriptions(descriptions));
        combined.setNormalizedCode(normalize(code));
        return combined;
    }

    private TirithSecurityService.ScanResult scanWithTirith(String toolName, String code) {
        if (tirithSecurityService == null) {
            return null;
        }
        return tirithSecurityService.checkCommandSecurityForTool(toolName, code);
    }

    private List<String> tirithPatternKeys(TirithSecurityService.ScanResult scan) {
        List<String> keys = new ArrayList<String>();
        if (scan != null) {
            for (TirithSecurityService.Finding finding : scan.getFindings()) {
                if (finding != null && StrUtil.isNotBlank(finding.getRuleId())) {
                    keys.add("tirith:" + finding.getRuleId().trim());
                }
            }
        }
        if (keys.isEmpty()) {
            keys.add("tirith:security_scan");
        }
        return keys;
    }

    private String tirithDescription(TirithSecurityService.ScanResult scan) {
        StringBuilder buffer = new StringBuilder("Security scan");
        if (scan != null && StrUtil.isNotBlank(scan.getAction())) {
            buffer.append(" ").append(scan.getAction());
        }
        if (scan != null && StrUtil.isNotBlank(scan.getSummary())) {
            buffer.append(": ").append(scan.getSummary().trim());
        }
        if (scan != null && !scan.getFindings().isEmpty()) {
            buffer.append(" (");
            int count = 0;
            for (TirithSecurityService.Finding finding : scan.getFindings()) {
                String label = tirithFindingLabel(finding);
                if (StrUtil.isBlank(label)) {
                    continue;
                }
                if (count > 0) {
                    buffer.append("; ");
                }
                buffer.append(label);
                count++;
                if (count >= 3) {
                    break;
                }
            }
            buffer.append(")");
        }
        return buffer.toString();
    }

    private String tirithFindingLabel(TirithSecurityService.Finding finding) {
        if (finding == null) {
            return "";
        }
        String label =
                StrUtil.blankToDefault(
                        finding.getTitle(),
                        StrUtil.blankToDefault(finding.getRuleId(), finding.getDescription()));
        if (StrUtil.isNotBlank(finding.getSeverity())) {
            label = finding.getSeverity() + " " + label;
        }
        return StrUtil.nullToEmpty(label).trim();
    }

    private List<String> unique(Collection<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            String trimmed = value.trim();
            if (!result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String joinDescriptions(Collection<String> descriptions) {
        StringBuilder buffer = new StringBuilder();
        if (descriptions != null) {
            for (String description : descriptions) {
                if (StrUtil.isBlank(description)) {
                    continue;
                }
                if (buffer.length() > 0) {
                    buffer.append("; ");
                }
                buffer.append(description.trim());
            }
        }
        return buffer.length() == 0 ? "Security scan warning" : buffer.toString();
    }

    private SecurityPolicyService.FileVerdict detectUnsafeCommandPath(String code) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict = securityPolicyService.checkCommandPaths(code);
        return verdict.isAllowed() ? null : verdict;
    }

    private SecurityPolicyService.UrlVerdict detectUnsafeCommandUrl(String code) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.UrlVerdict verdict = securityPolicyService.checkCommandUrls(code);
        return verdict.isAllowed() ? null : verdict;
    }

    private String evaluateFileTool(ReActTrace trace, String toolName, Map<String, Object> args) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.FileVerdict verdict =
                securityPolicyService.checkFileToolArgs(toolName, args);
        if (verdict.isAllowed()) {
            return null;
        }
        trace.setFinalAnswer(buildFilePolicyMessage(toolName, verdict));
        trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        persistTraceSnapshot(trace);
        return null;
    }

    private String evaluateUrlTool(ReActTrace trace, String toolName, Map<String, Object> args) {
        if (securityPolicyService == null) {
            return null;
        }
        SecurityPolicyService.UrlVerdict verdict =
                securityPolicyService.checkToolArgs(toolName, args);
        if (verdict.isAllowed()) {
            return null;
        }
        trace.setFinalAnswer(buildUrlPolicyMessage(verdict));
        trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        persistTraceSnapshot(trace);
        return null;
    }

    private void persistTraceSnapshot(ReActTrace trace) {
        if (trace != null && trace.getSession() != null) {
            trace.getSession().updateSnapshot();
        }
    }

    private void notifyApprovalRequest(AgentSession session, PendingApproval pending) {
        if (pending == null || approvalObservers.isEmpty()) {
            return;
        }
        ApprovalRequestEvent event = new ApprovalRequestEvent(sessionId(session), pending);
        for (ApprovalObserver observer : approvalObservers) {
            try {
                observer.onApprovalRequest(event);
            } catch (Exception ignored) {
                // Observer hooks must never affect the safety-critical approval flow.
            }
        }
    }

    private void notifyApprovalResponse(
            AgentSession session, PendingApproval pending, String choice, String approver) {
        if (pending == null || approvalObservers.isEmpty()) {
            return;
        }
        ApprovalResponseEvent event =
                new ApprovalResponseEvent(
                        sessionId(session), pending, StrUtil.nullToEmpty(choice), approver);
        for (ApprovalObserver observer : approvalObservers) {
            try {
                observer.onApprovalResponse(event);
            } catch (Exception ignored) {
                // Observer hooks must never affect the safety-critical approval flow.
            }
        }
    }

    private String sessionId(AgentSession session) {
        if (session == null) {
            return "";
        }
        return StrUtil.nullToEmpty(session.getSessionId());
    }

    public String approvalMode() {
        String mode =
                appConfig == null || appConfig.getApprovals() == null
                        ? "on"
                        : appConfig.getApprovals().getMode();
        mode = StrUtil.blankToDefault(mode, "on").trim().toLowerCase(Locale.ROOT);
        if ("false".equals(mode)) {
            return "off";
        }
        if ("true".equals(mode)) {
            return "on";
        }
        if ("off".equals(mode) || "smart".equals(mode)) {
            return mode;
        }
        return "on";
    }

    public boolean isSubagentAutoApproveEnabled() {
        return appConfig != null
                && appConfig.getApprovals() != null
                && appConfig.getApprovals().isSubagentAutoApprove();
    }

    private boolean isSudoPasswordConfigured() {
        return appConfig != null
                && appConfig.getTerminal() != null
                && StrUtil.isNotBlank(appConfig.getTerminal().getSudoPassword());
    }

    private int maxForegroundTimeoutSeconds() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getMaxForegroundTimeoutSeconds();
    }

    private int foregroundMaxRetries() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getForegroundMaxRetries();
    }

    private int foregroundRetryBaseDelaySeconds() {
        return appConfig == null || appConfig.getTerminal() == null
                ? 0
                : appConfig.getTerminal().getForegroundRetryBaseDelaySeconds();
    }

    protected String jimuquYoloModeEnv() {
        return System.getenv("JIMUQU_YOLO_MODE");
    }

    private boolean isCompatibilityYoloModeEnabled() {
        String value = StrUtil.nullToEmpty(jimuquYoloModeEnv()).trim();
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    private void markCurrentThreadApproval(String toolName, String command) {
        if (StrUtil.hasBlank(toolName, command)) {
            return;
        }
        Map<String, Long> approvals = CURRENT_THREAD_APPROVED_COMMANDS.get();
        if (approvals == null) {
            approvals = new LinkedHashMap<String, Long>();
            CURRENT_THREAD_APPROVED_COMMANDS.set(approvals);
        }
        approvals.put(
                currentThreadApprovalKey(toolName, command),
                Long.valueOf(System.currentTimeMillis() + CURRENT_THREAD_APPROVAL_TTL_MILLIS));
    }

    private static String currentThreadApprovalKey(String toolName, String command) {
        return StrUtil.nullToEmpty(toolName).trim() + ":" + normalizeCommand(command);
    }

    private static void removeExpiredCurrentThreadApprovals(Map<String, Long> approvals) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<String>();
        for (Map.Entry<String, Long> entry : approvals.entrySet()) {
            Long expiresAt = entry.getValue();
            if (expiresAt == null || expiresAt.longValue() < now) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            approvals.remove(key);
        }
    }

    private boolean setSessionYolo(AgentSession session, boolean enabled) throws Exception {
        if (session == null) {
            return false;
        }
        if (enabled) {
            session.getContext().put(CONTEXT_SESSION_YOLO, Boolean.TRUE);
        } else {
            session.getContext().remove(CONTEXT_SESSION_YOLO);
        }
        session.updateSnapshot();
        return enabled;
    }

    private boolean truthy(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        String value = String.valueOf(raw).trim();
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    public String cronApprovalMode() {
        String mode =
                appConfig == null || appConfig.getApprovals() == null
                        ? ""
                        : appConfig.getApprovals().getCronMode();
        if (StrUtil.isBlank(mode) && appConfig != null && appConfig.getScheduler() != null) {
            mode = appConfig.getScheduler().getCronApprovalMode();
        }
        mode = StrUtil.blankToDefault(mode, "deny").trim().toLowerCase(Locale.ROOT);
        return "approve".equals(mode)
                        || "off".equals(mode)
                        || "allow".equals(mode)
                        || "yes".equals(mode)
                ? "approve"
                : "deny";
    }

    private boolean isSubagentRun() {
        AgentRunContext current = AgentRunContext.current();
        return current != null
                && "subagent".equalsIgnoreCase(StrUtil.nullToEmpty(current.getRunKind()));
    }

    private String buildSubagentDeniedMessage(DetectionResult detection) {
        String description =
                detection == null
                        ? "dangerous command"
                        : StrUtil.blankToDefault(
                                detection.getDescription(), detection.getPatternKey());
        return "BLOCKED: 子 Agent 默认拒绝可审批危险命令："
                + redactApprovalDisplay(description, 1000)
                + "。如确实需要在可信批处理里允许，请设置 approvals.subagentAutoApprove=true。";
    }

    public int approvalTimeoutSeconds() {
        int value =
                appConfig == null || appConfig.getApprovals() == null
                        ? 60
                        : appConfig.getApprovals().getTimeoutSeconds();
        return value > 0 ? value : 60;
    }

    public int approvalGatewayTimeoutSeconds() {
        int value =
                appConfig == null || appConfig.getApprovals() == null
                        ? 300
                        : appConfig.getApprovals().getGatewayTimeoutSeconds();
        return value > 0 ? value : Math.max(approvalTimeoutSeconds(), 300);
    }

    private long approvalGatewayTimeoutMillis() {
        return approvalGatewayTimeoutSeconds() * 1000L;
    }

    private Map<String, Object> createPendingMap(
            String toolName, DetectionResult detection, String code) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", IdSupport.newId());
        payload.put("toolName", cleanApprovalText(toolName));
        payload.put("patternKey", cleanApprovalText(detection.getPatternKey()));
        payload.put("patternKeys", cleanApprovalList(detection.effectivePatternKeys()));
        payload.put("description", detection.getDescription());
        payload.put("command", StrUtil.nullToEmpty(code));
        payload.put("commandHash", commandHash(detection.getNormalizedCode()));
        payload.put("approvalKey", combinedApprovalKey(toolName, detection));
        payload.put("createdAt", System.currentTimeMillis());
        payload.put("expiresAt", System.currentTimeMillis() + approvalGatewayTimeoutMillis());
        return payload;
    }

    private void storePendingMap(AgentSession session, Map<String, Object> pendingMap) {
        if (session == null || pendingMap == null) {
            return;
        }
        List<Map<String, Object>> queue = pendingMapQueueFrom(session.getContext());
        String approvalKey = stringValue(pendingMap.get("approvalKey"));
        boolean replaced = false;
        for (int i = 0; i < queue.size(); i++) {
            PendingApproval item = toPendingApproval(queue.get(i));
            if (item != null && approvalKey.equals(item.approvalKey())) {
                queue.set(i, pendingMap);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            queue.add(pendingMap);
        }
        session.getContext().put(CONTEXT_PENDING_APPROVAL_QUEUE, queue);
        session.getContext().put(CONTEXT_PENDING_APPROVAL, queue.isEmpty() ? null : queue.get(0));
    }

    private boolean prunePendingApprovals(AgentSession session, List<PendingApproval> pending) {
        if (session == null) {
            return false;
        }
        List<PendingApproval> active = filterActivePendingApprovals(pending);
        if (active.size() == pending.size()) {
            return false;
        }
        for (PendingApproval item : pending) {
            if (item != null && isPendingExpired(item)) {
                notifyApprovalResponse(session, item, "timeout", "");
            }
        }
        writePendingApprovals(session, active);
        session.updateSnapshot();
        return true;
    }

    private List<PendingApproval> filterActivePendingApprovals(List<PendingApproval> pending) {
        List<PendingApproval> active = new ArrayList<PendingApproval>();
        for (PendingApproval item : pending) {
            if (item != null && !isPendingExpired(item)) {
                active.add(item);
            }
        }
        return active;
    }

    private void removePendingApproval(AgentSession session, PendingApproval target) {
        if (session == null || target == null) {
            return;
        }
        List<PendingApproval> retained = new ArrayList<PendingApproval>();
        for (PendingApproval item : pendingQueueFrom(session.getContext())) {
            if (!samePendingApproval(item, target)) {
                retained.add(item);
            }
        }
        writePendingApprovals(session, retained);
    }

    private void writePendingApprovals(AgentSession session, List<PendingApproval> pending) {
        List<Map<String, Object>> queue = new ArrayList<Map<String, Object>>();
        for (PendingApproval item : pending) {
            if (item != null && !isPendingExpired(item)) {
                queue.add(pendingMap(item));
            }
        }
        if (queue.isEmpty()) {
            session.getContext().remove(CONTEXT_PENDING_APPROVAL_QUEUE);
            session.getContext().remove(CONTEXT_PENDING_APPROVAL);
            return;
        }
        session.getContext().put(CONTEXT_PENDING_APPROVAL_QUEUE, queue);
        session.getContext().put(CONTEXT_PENDING_APPROVAL, queue.get(0));
    }

    private PendingApproval findPendingApproval(AgentSession session, String selector) {
        List<PendingApproval> pending = listPendingApprovals(session);
        if (pending.isEmpty()) {
            return null;
        }
        if (StrUtil.isBlank(selector)) {
            return pending.get(0);
        }
        String normalized = SecretRedactor.stripDisplayControls(selector).trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        try {
            int index = Integer.parseInt(normalized);
            if (index >= 1 && index <= pending.size()) {
                return pending.get(index - 1);
            }
        } catch (Exception ignored) {
            // fall through to id/key matching
        }
        for (PendingApproval item : pending) {
            if (selectorMatches(item, normalized)) {
                return item;
            }
        }
        return null;
    }

    private boolean selectorMatches(PendingApproval item, String selector) {
        if (item == null || StrUtil.isBlank(selector)) {
            return false;
        }
        String value = SecretRedactor.stripDisplayControls(selector).trim();
        String approvalId = item.getApprovalId();
        String safeApprovalId = safeApprovalSelectorToken(approvalId);
        String approvalKey = item.approvalKey();
        String opaqueSelector = approvalSelector(item);
        return (StrUtil.isNotBlank(safeApprovalId) && value.equals(safeApprovalId))
                || value.equals(opaqueSelector)
                || (StrUtil.isNotBlank(safeApprovalId)
                        && value.length() >= APPROVAL_SELECTOR_PREFIX_MIN_LENGTH
                        && safeApprovalId.startsWith(value))
                || (StrUtil.isNotBlank(opaqueSelector)
                        && value.length() >= APPROVAL_SELECTOR_PREFIX_MIN_LENGTH
                        && opaqueSelector.startsWith(value));
    }

    public static String approvalSelector(PendingApproval pending) {
        if (pending == null) {
            return "";
        }
        String safeApprovalId = safeApprovalSelectorToken(pending.getApprovalId());
        if (StrUtil.isNotBlank(safeApprovalId)) {
            return safeApprovalId;
        }
        return approvalSelectorFromKey(pending.approvalKey());
    }

    private static String safeApprovalSelector(PendingApproval pending) {
        return approvalSelector(pending);
    }

    public static String approvalSelectorFromKey(String approvalKey) {
        String value = StrUtil.nullToEmpty(approvalKey).trim();
        return value.isEmpty() ? "" : "key_" + sha256Hex(value).substring(0, 24);
    }

    private boolean samePendingApproval(PendingApproval left, PendingApproval right) {
        if (left == null || right == null) {
            return false;
        }
        if (StrUtil.isNotBlank(left.getApprovalId()) && StrUtil.isNotBlank(right.getApprovalId())) {
            return left.getApprovalId().equals(right.getApprovalId());
        }
        return left.approvalKey().equals(right.approvalKey());
    }

    private List<PendingApproval> pendingQueueFrom(Object context) {
        List<PendingApproval> pending = new ArrayList<PendingApproval>();
        if (context == null) {
            return pending;
        }
        Object queue = contextValue(context, CONTEXT_PENDING_APPROVAL_QUEUE);
        Object vars = contextValue(context, "vars");
        if (queue == null && vars instanceof Map) {
            queue = ((Map<?, ?>) vars).get(CONTEXT_PENDING_APPROVAL_QUEUE);
        }
        pending.addAll(toPendingApprovalList(queue));
        if (pending.isEmpty()) {
            Object legacy = contextValue(context, CONTEXT_PENDING_APPROVAL);
            if (legacy == null && vars instanceof Map) {
                legacy = ((Map<?, ?>) vars).get(CONTEXT_PENDING_APPROVAL);
            }
            PendingApproval legacyPending = toPendingApproval(legacy);
            if (legacyPending != null) {
                pending.add(legacyPending);
            }
        }
        return pending;
    }

    private Object contextValue(Object context, String key) {
        if (context instanceof FlowContext) {
            return ((FlowContext) context).get(key);
        }
        if (context instanceof Map) {
            return ((Map<?, ?>) context).get(key);
        }
        return null;
    }

    private List<Map<String, Object>> pendingMapQueueFrom(Object context) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (PendingApproval item : filterActivePendingApprovals(pendingQueueFrom(context))) {
            values.add(pendingMap(item));
        }
        return values;
    }

    private List<PendingApproval> toPendingApprovalList(Object raw) {
        List<PendingApproval> values = new ArrayList<PendingApproval>();
        if (raw == null) {
            return values;
        }
        Object parsed = raw;
        if (!(raw instanceof Collection)) {
            try {
                parsed = ONode.deserialize(String.valueOf(raw), Object.class);
            } catch (Exception ignored) {
                parsed = null;
            }
        }
        if (parsed instanceof Collection) {
            for (Object item : (Collection<?>) parsed) {
                PendingApproval pending = toPendingApproval(item);
                if (pending != null) {
                    values.add(pending);
                }
            }
        }
        return values;
    }

    private Map<String, Object> pendingMap(PendingApproval pending) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("approvalId", pending.getApprovalId());
        payload.put("toolName", pending.getToolName());
        payload.put("patternKey", pending.getPatternKey());
        payload.put("patternKeys", new ArrayList<String>(pending.effectivePatternKeys()));
        payload.put("description", pending.getDescription());
        payload.put("command", StrUtil.nullToEmpty(pending.getCommand()));
        payload.put("commandHash", pending.getCommandHash());
        payload.put("approvalKey", pending.approvalKey());
        payload.put("createdAt", pending.getCreatedAt());
        payload.put("expiresAt", pending.getExpiresAt());
        return payload;
    }

    private boolean isPendingExpired(PendingApproval pending) {
        if (pending == null) {
            return false;
        }
        long expiresAt = pending.getExpiresAt();
        if (expiresAt <= 0L && pending.getCreatedAt() > 0L) {
            expiresAt = pending.getCreatedAt() + approvalGatewayTimeoutMillis();
        }
        return expiresAt > 0L && System.currentTimeMillis() > expiresAt;
    }

    private String buildPendingMessage(String toolName, DetectionResult detection, String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("⚠️ 危险命令需要审批：\n");
        buffer.append("工具：").append(toolLabel(toolName)).append('\n');
        buffer.append("原因：").append(redactApprovalDisplay(detection.getDescription(), 1000)).append("\n\n");
        buffer.append("```").append(codeFence(toolName)).append('\n');
        buffer.append(redactApprovalDisplay(trimPreview(code), 2000));
        buffer.append("\n```\n\n");
        if (containsTirith(detection)) {
            buffer.append(
                    "该安全扫描结果只支持本次或当前会话审批，不能永久记住。回复 `/approve` 执行一次，`/approve session` 记住当前会话，或 `/deny` 取消。");
        } else {
            buffer.append(
                    "回复 `/approve` 执行一次，`/approve session` 记住当前会话，`/approve always` 永久记住，或 `/deny` 取消。");
        }
        return buffer.toString();
    }

    private String redactApprovalDisplay(String value, int maxLength) {
        String normalized =
                SecretRedactor.stripDisplayControls(
                        TerminalAnsiSanitizer.stripAnsi(StrUtil.nullToEmpty(value)));
        return SecretRedactor.redact(normalized, maxLength);
    }

    private static String redactedApprover(String approver) {
        return SecretRedactor.redact(StrUtil.nullToEmpty(approver).trim(), 200);
    }

    private String buildHardlineMessage(String toolName, DetectionResult detection, String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED (hardline): ");
        buffer.append(detection.getDescription());
        buffer.append("。该命令属于不可通过 Agent 执行的高危操作，不能通过 /approve、/approve always 或会话审批绕过。");
        buffer.append("\n工具：").append(toolLabel(toolName)).append("\n\n");
        buffer.append("```").append(codeFence(toolName)).append('\n');
        buffer.append(redactApprovalDisplay(trimPreview(code), 2000));
        buffer.append("\n```");
        return buffer.toString();
    }

    private String buildFilePolicyMessage(
            String toolName, SecurityPolicyService.FileVerdict verdict) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED: 文件安全策略阻止访问：");
        buffer.append(verdict.getMessage());
        buffer.append("\n工具：").append(toolLabel(toolName));
        buffer.append("\n路径：").append(SecretRedactor.redact(verdict.getPath(), 400));
        buffer.append("\n请改用工作区内的普通项目文件，敏感凭据文件不能通过 Agent 工具读取、写入或删除。");
        return buffer.toString();
    }

    private String buildUrlPolicyMessage(SecurityPolicyService.UrlVerdict verdict) {
        return "BLOCKED: URL 安全策略阻止访问："
                + verdict.getMessage()
                + "\nURL: "
                + SecretPreview.safeUrl(verdict.getUrl())
                + "\n请换用公开、可信且符合网站访问策略的地址。";
    }

    private boolean isApproved(FlowContext context, String approvalKey) {
        ApprovalKeyParts parts = parseApprovalKey(approvalKey);
        if (parts == null) {
            return loadSessionApprovals(context).contains(approvalKey)
                    || loadAlwaysApprovedPatterns().contains(approvalKey);
        }

        if (parts.patternKey.indexOf('+') >= 0) {
            String[] patternKeys = parts.patternKey.split("\\+");
            for (String patternKey : patternKeys) {
                if (StrUtil.isBlank(patternKey)) {
                    continue;
                }
                if (!containsApprovalForPattern(
                        loadSessionApprovals(context),
                        loadAlwaysApprovedPatterns(),
                        parts.toolName,
                        patternKey)) {
                    return false;
                }
            }
            return true;
        }

        return containsApprovalForPattern(
                        loadSessionApprovals(context),
                        loadAlwaysApprovedPatterns(),
                        parts.toolName,
                        parts.patternKey)
                || containsApprovalKey(loadSessionApprovals(context), approvalKey)
                || containsApprovalKey(loadAlwaysApprovedPatterns(), approvalKey);
    }

    private void addSessionApproval(FlowContext context, String patternKey) {
        Set<String> approvals = loadSessionApprovals(context);
        approvals.add(patternKey);
        context.put(CONTEXT_SESSION_APPROVALS, new ArrayList<String>(approvals));
    }

    private Set<String> loadSessionApprovals(FlowContext context) {
        return stringSetFrom(context == null ? null : context.get(CONTEXT_SESSION_APPROVALS));
    }

    private void addAlwaysApproval(String patternKey) throws Exception {
        Set<String> approvals = loadAlwaysApprovedPatterns();
        approvals.add(patternKey);
        globalSettingRepository.set(
                AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS,
                ONode.serialize(new ArrayList<String>(approvals)));
    }

    private Set<String> loadAlwaysApprovedPatterns() {
        if (globalSettingRepository == null) {
            return new LinkedHashSet<String>();
        }

        try {
            return stringSetFrom(
                    globalSettingRepository.get(
                            AgentSettingConstants.DANGEROUS_COMMAND_ALWAYS_PATTERNS));
        } catch (Exception ignored) {
            return new LinkedHashSet<String>();
        }
    }

    private Set<String> stringSetFrom(Object raw) {
        Set<String> values = new LinkedHashSet<String>();
        if (raw == null) {
            return values;
        }

        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                String value = cleanApprovalValue(item);
                if (StrUtil.isNotBlank(value)) {
                    values.add(value);
                }
            }
            return values;
        }

        String text = cleanApprovalValue(raw);
        if (text.length() == 0) {
            return values;
        }
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                Object parsed = ONode.deserialize(text, Object.class);
                if (parsed instanceof Collection) {
                    for (Object item : (Collection<?>) parsed) {
                        String value = cleanApprovalValue(item);
                        if (StrUtil.isNotBlank(value)) {
                            values.add(value);
                        }
                    }
                    return values;
                }
            } catch (Exception ignored) {
                // fallback to plain string below
            }
        }

        values.add(text);
        return values;
    }

    private String cleanApprovalValue(Object raw) {
        if (raw == null) {
            return "";
        }
        return SecretRedactor.stripDisplayControls(String.valueOf(raw)).trim();
    }

    private boolean containsPattern(Set<String> approvals, String patternKey) {
        if (approvals == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        return containsApprovalForPattern(approvals, Collections.<String>emptySet(), "", patternKey);
    }

    private boolean containsApprovalForPattern(
            Set<String> sessionApprovals,
            Set<String> alwaysApprovals,
            String toolName,
            String patternKey) {
        for (String alias : approvalKeyAliases(patternKey)) {
            if (containsApprovalKey(sessionApprovals, alias)
                    || containsApprovalKey(alwaysApprovals, alias)) {
                return true;
            }
            if (StrUtil.isNotBlank(toolName)) {
                String toolPattern = approvalPattern(toolName, alias);
                if (containsApprovalKey(sessionApprovals, toolPattern)
                        || containsApprovalKey(alwaysApprovals, toolPattern)) {
                    return true;
                }
            } else if (containsApprovalPatternAnyTool(sessionApprovals, alias)
                    || containsApprovalPatternAnyTool(alwaysApprovals, alias)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsApprovalPatternAnyTool(Set<String> approvals, String patternKey) {
        if (approvals == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        Set<String> aliases = approvalKeyAliases(patternKey);
        for (String approval : approvals) {
            ApprovalKeyParts parts = parseApprovalKey(approval);
            if (parts != null && aliases.contains(parts.patternKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsApprovalKey(Set<String> approvals, String approvalKey) {
        if (approvals == null || StrUtil.isBlank(approvalKey)) {
            return false;
        }
        String normalizedKey = approvalKey.trim();
        if (approvals.contains(normalizedKey)) {
            return true;
        }
        ApprovalKeyParts expected = parseApprovalKey(normalizedKey);
        if (expected == null) {
            return false;
        }
        for (String approval : approvals) {
            ApprovalKeyParts actual = parseApprovalKey(approval);
            if (actual != null
                    && expected.toolName.equals(actual.toolName)
                    && approvalKeyAliases(expected.patternKey).contains(actual.patternKey)) {
                return true;
            }
        }
        return false;
    }

    private PendingApproval toPendingApproval(Object raw) {
        if (raw == null) {
            return null;
        }

        Map<?, ?> map = raw instanceof Map ? (Map<?, ?>) raw : parseMap(String.valueOf(raw));
        if (map == null) {
            return null;
        }

        String toolName = cleanApprovalText(map.get("toolName"));
        String patternKey = cleanApprovalText(map.get("patternKey"));
        String description = stringValue(map.get("description"));
        String command = stringValue(map.get("command"));
        String commandHash = cleanApprovalText(map.get("commandHash"));
        String approvalKey = cleanApprovalText(map.get("approvalKey"));
        String approvalId = cleanApprovalText(map.get("approvalId"));
        long createdAt = longValue(map.get("createdAt"));
        long expiresAt = longValue(map.get("expiresAt"));
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return null;
        }

        PendingApproval pending = new PendingApproval();
        pending.setApprovalId(approvalId);
        pending.setToolName(toolName);
        pending.setPatternKey(patternKey);
        pending.setPatternKeys(cleanApprovalList(listValue(map.get("patternKeys"))));
        pending.setDescription(description);
        pending.setCommand(command);
        pending.setCommandHash(commandHash);
        pending.setApprovalKey(approvalKey);
        pending.setCreatedAt(createdAt);
        pending.setExpiresAt(expiresAt);
        return pending;
    }

    private Map<?, ?> parseMap(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<?, ?> parseStaticMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map) {
            return (Map<?, ?>) raw;
        }
        String text = String.valueOf(raw).trim();
        if (text.length() == 0) {
            return null;
        }
        try {
            Object parsed = ONode.deserialize(text, Object.class);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String cleanApprovalText(Object value) {
        if (value == null) {
            return "";
        }
        return SecretRedactor.stripDisplayControls(String.valueOf(value)).trim();
    }

    private static List<String> cleanApprovalList(List<String> values) {
        List<String> cleaned = new ArrayList<String>();
        if (values == null) {
            return cleaned;
        }
        for (String value : values) {
            String item = cleanApprovalText(value);
            if (StrUtil.isNotBlank(item) && !cleaned.contains(item)) {
                cleaned.add(item);
            }
        }
        return cleaned;
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private List<String> listValue(Object raw) {
        List<String> values = new ArrayList<String>();
        if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    values.add(String.valueOf(item).trim());
                }
            }
        }
        return values;
    }

    private static String stringValueStatic(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toolLabel(String toolName) {
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            return "execute_python";
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            return "execute_js";
        }
        if (StrUtil.isNotBlank(toolName) && !ToolNameConstants.EXECUTE_SHELL.equals(toolName)) {
            return toolName;
        }
        return "execute_shell";
    }

    private String codeFence(String toolName) {
        if (ToolNameConstants.EXECUTE_PYTHON.equals(toolName)) {
            return "python";
        }
        if (ToolNameConstants.EXECUTE_JS.equals(toolName)) {
            return "javascript";
        }
        return "shell";
    }

    private String trimPreview(String code) {
        String normalized = StrUtil.nullToEmpty(code).trim();
        if (normalized.length() <= 400) {
            return normalized;
        }
        return normalized.substring(0, 400) + "\n...";
    }

    private String normalize(String code) {
        return normalizeCommand(code);
    }

    private static String normalizeCommand(String code) {
        String normalized = StrUtil.nullToEmpty(code).replace("\u0000", "");
        normalized = TerminalAnsiSanitizer.stripAnsi(normalized);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("\\\\\\r?\\n", " ");
        return normalized.trim();
    }

    private static List<String> extractPythonShellCommands(String code) {
        if (StrUtil.isBlank(code)) {
            return Collections.emptyList();
        }
        List<String> commands = new ArrayList<String>();
        Matcher matcher = PYTHON_SHELL_EXEC_CALL.matcher(code);
        while (matcher.find()) {
            String command = readFirstShellCommandArgument(code, matcher.end());
            if (StrUtil.isNotBlank(command)) {
                commands.add(command);
            }
        }
        return commands;
    }

    private static String extractJavaScriptChildProcessCommand(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        Pattern callPattern =
                Pattern.compile(
                        "\\b(?:child_process\\.)?(?:exec|execSync|spawn|spawnSync|execFile|execFileSync)\\s*\\(",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = callPattern.matcher(code);
        if (!matcher.find()) {
            return null;
        }
        String firstArgument = readFirstShellCommandArgument(code, matcher.end());
        if (StrUtil.isBlank(firstArgument)) {
            return null;
        }
        String listArguments = readSecondJavaScriptArgumentList(code, matcher.end());
        if (StrUtil.isBlank(listArguments)) {
            return firstArgument;
        }
        return firstArgument + " " + listArguments;
    }

    private static String readSecondJavaScriptArgumentList(String code, int offset) {
        int index = skipWhitespace(code, offset);
        if (index < 0 || index >= code.length()) {
            return null;
        }
        index = skipFirstArgument(code, index);
        index = skipWhitespace(code, index);
        if (index < 0 || index >= code.length() || code.charAt(index) != ',') {
            return null;
        }
        index = skipWhitespace(code, index + 1);
        if (index < 0 || index >= code.length() || code.charAt(index) != '[') {
            return null;
        }
        return readQuotedStringListCommand(code, index + 1);
    }

    private static int skipFirstArgument(String code, int offset) {
        int index = skipWhitespace(code, offset);
        if (index < 0 || index >= code.length()) {
            return -1;
        }
        char current = code.charAt(index);
        if (current == '\'' || current == '"') {
            return skipQuotedString(code, index);
        }
        if (current == '[') {
            return skipBracketedList(code, index);
        }
        return -1;
    }

    private static int skipBracketedList(String code, int offset) {
        if (code == null || offset < 0 || offset >= code.length() || code.charAt(offset) != '[') {
            return -1;
        }
        int depth = 0;
        for (int i = offset; i < code.length(); i++) {
            char current = code.charAt(i);
            if (current == '\'' || current == '"') {
                i = skipQuotedString(code, i) - 1;
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static String readFirstShellCommandArgument(String code, int offset) {
        int index = skipWhitespace(code, offset);
        if (index < 0 || index >= code.length()) {
            return null;
        }
        char current = code.charAt(index);
        if (current == '\'' || current == '"') {
            return readQuotedString(code, index);
        }
        if (current == '[') {
            return readQuotedStringListCommand(code, index + 1);
        }
        return null;
    }

    private static String readQuotedStringListCommand(String code, int offset) {
        List<String> parts = new ArrayList<String>();
        int index = offset;
        while (index >= 0 && index < code.length()) {
            index = skipWhitespace(code, index);
            if (index < 0 || index >= code.length() || code.charAt(index) == ']') {
                break;
            }
            char quote = code.charAt(index);
            if (quote != '\'' && quote != '"') {
                return null;
            }
            String value = readQuotedString(code, index);
            if (value == null) {
                return null;
            }
            parts.add(value);
            index = skipQuotedString(code, index);
            index = skipWhitespace(code, index);
            if (index < 0 || index >= code.length() || code.charAt(index) == ']') {
                break;
            }
            if (code.charAt(index) != ',') {
                return null;
            }
            index++;
        }
        if (parts.isEmpty()) {
            return null;
        }
        StringBuilder command = new StringBuilder();
        for (String part : parts) {
            if (command.length() > 0) {
                command.append(' ');
            }
            command.append(part);
        }
        return command.toString();
    }

    private static String readQuotedString(String code, int offset) {
        if (code == null || offset < 0 || offset >= code.length()) {
            return null;
        }
        char quote = code.charAt(offset);
        if (quote != '\'' && quote != '"') {
            return null;
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = offset + 1; i < code.length(); i++) {
            char current = code.charAt(i);
            if (escaped) {
                value.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote) {
                return value.toString();
            }
            value.append(current);
        }
        return null;
    }

    private static int skipQuotedString(String code, int offset) {
        if (code == null || offset < 0 || offset >= code.length()) {
            return -1;
        }
        char quote = code.charAt(offset);
        if (quote != '\'' && quote != '"') {
            return -1;
        }
        boolean escaped = false;
        for (int i = offset + 1; i < code.length(); i++) {
            char current = code.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == quote) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String code, int offset) {
        if (code == null || offset < 0 || offset > code.length()) {
            return -1;
        }
        int index = offset;
        while (index < code.length() && Character.isWhitespace(code.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean looksLikeHelpOrVersionCommand(String command) {
        String normalized = StrUtil.nullToEmpty(command).toLowerCase(Locale.ROOT).trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized.contains(" --help")
                || normalized.endsWith(" -h")
                || normalized.contains(" --version")
                || normalized.endsWith(" -v");
    }

    private String approvalKey(String toolName, String patternKey, String normalizedCode) {
        return approvalPattern(toolName, patternKey) + ":" + commandHash(normalizedCode);
    }

    private String combinedApprovalKey(String toolName, DetectionResult detection) {
        if (detection == null) {
            return approvalKey(toolName, "", "");
        }
        List<String> patternKeys = detection.effectivePatternKeys();
        if (patternKeys.size() <= 1) {
            return approvalKey(toolName, detection.getPatternKey(), detection.getNormalizedCode());
        }
        StringBuilder buffer = new StringBuilder();
        for (String patternKey : patternKeys) {
            if (buffer.length() > 0) {
                buffer.append('+');
            }
            buffer.append(patternKey);
        }
        return approvalPattern(toolName, buffer.toString())
                + ":"
                + commandHash(detection.getNormalizedCode());
    }

    private String approvalPattern(String toolName, String patternKey) {
        return cleanApprovalText(toolName) + ":" + cleanApprovalText(patternKey);
    }

    private boolean isTirithPattern(String patternKey) {
        return StrUtil.nullToEmpty(patternKey).startsWith("tirith:");
    }

    private boolean containsTirith(DetectionResult detection) {
        if (detection == null) {
            return false;
        }
        for (String patternKey : detection.effectivePatternKeys()) {
            if (isTirithPattern(patternKey)) {
                return true;
            }
        }
        return false;
    }

    private ApprovalKeyParts parseApprovalKey(String approvalKey) {
        if (StrUtil.isBlank(approvalKey)) {
            return null;
        }
        String text = approvalKey.trim();
        int firstColon = text.indexOf(':');
        if (firstColon <= 0 || firstColon >= text.length() - 1) {
            return null;
        }
        String toolName = text.substring(0, firstColon);
        String patternKey = text.substring(firstColon + 1);
        int lastColon = patternKey.lastIndexOf(':');
        if (lastColon > 0 && looksLikeSha256(patternKey.substring(lastColon + 1))) {
            patternKey = patternKey.substring(0, lastColon);
        }
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return null;
        }
        return new ApprovalKeyParts(toolName, patternKey);
    }

    private boolean looksLikeSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private String commandHash(String normalizedCode) {
        return sha256Hex(StrUtil.nullToEmpty(normalizedCode));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash approval value", e);
        }
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static Pattern caseSensitivePattern(String regex) {
        return Pattern.compile(regex, Pattern.DOTALL);
    }

    private static Map<String, Set<String>> buildApprovalKeyAliases() {
        Map<String, Set<String>> aliases = new LinkedHashMap<String, Set<String>>();
        addApprovalKeyAliases(aliases, RULES);
        addApprovalKeyAliases(aliases, HARDLINE_RULES);
        return Collections.unmodifiableMap(aliases);
    }

    private static void addApprovalKeyAliases(
            Map<String, Set<String>> aliases, Collection<DangerRule> rules) {
        if (rules == null) {
            return;
        }
        for (DangerRule rule : rules) {
            if (rule == null) {
                continue;
            }
            addApprovalKeyAliasPair(aliases, rule.getPatternKey(), rule.getDescription());
        }
    }

    private static List<String> ruleSamples(List<DangerRule> rules, int max) {
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

    private static List<String> hardlineRuleSamples(int max) {
        List<String> samples = ruleSamples(HARDLINE_RULES, max);
        if (max > 0 && !samples.contains("hardline_metadata_url")) {
            if (samples.size() >= max) {
                samples.remove(samples.size() - 1);
            }
            samples.add("hardline_metadata_url");
        }
        return samples;
    }

    private static void addApprovalKeyAliasPair(
            Map<String, Set<String>> aliases, String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return;
        }
        String leftValue = left.trim();
        String rightValue = right.trim();
        Set<String> leftAliases = aliases.get(leftValue);
        if (leftAliases == null) {
            leftAliases = new LinkedHashSet<String>();
            aliases.put(leftValue, leftAliases);
        }
        leftAliases.add(leftValue);
        leftAliases.add(rightValue);

        Set<String> rightAliases = aliases.get(rightValue);
        if (rightAliases == null) {
            rightAliases = new LinkedHashSet<String>();
            aliases.put(rightValue, rightAliases);
        }
        rightAliases.add(leftValue);
        rightAliases.add(rightValue);
    }

    private Set<String> approvalKeyAliases(String patternKey) {
        if (StrUtil.isBlank(patternKey)) {
            return Collections.emptySet();
        }
        String normalized = patternKey.trim();
        Set<String> aliases = APPROVAL_KEY_ALIASES.get(normalized);
        if (aliases != null) {
            return aliases;
        }
        return Collections.singleton(normalized);
    }

    public enum ApprovalScope {
        ONCE,
        SESSION,
        ALWAYS;

        public String comment() {
            if (this == SESSION) {
                return "批准执行，并记住当前会话中的危险命令模式。";
            }
            if (this == ALWAYS) {
                return "批准执行，并永久记住危险命令模式。";
            }
            return "批准执行本次危险命令。";
        }
    }

    public static class PendingApproval {
        private String approvalId;
        private String toolName;
        private String patternKey;
        private List<String> patternKeys = new ArrayList<String>();
        private String description;
        private String command;
        private String commandHash;
        private String approvalKey;
        private long createdAt;
        private long expiresAt;

        public String getApprovalId() {
            return approvalId;
        }

        public void setApprovalId(String approvalId) {
            this.approvalId = approvalId;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public String getPatternKey() {
            return patternKey;
        }

        public void setPatternKey(String patternKey) {
            this.patternKey = patternKey;
        }

        public List<String> getPatternKeys() {
            return patternKeys;
        }

        public void setPatternKeys(List<String> patternKeys) {
            this.patternKeys =
                    patternKeys == null
                            ? new ArrayList<String>()
                            : new ArrayList<String>(patternKeys);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public String getCommandHash() {
            return commandHash;
        }

        public void setCommandHash(String commandHash) {
            this.commandHash = commandHash;
        }

        public String getApprovalKey() {
            return approvalKey;
        }

        public void setApprovalKey(String approvalKey) {
            this.approvalKey = approvalKey;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public String approvalKey() {
            return StrUtil.blankToDefault(
                    cleanApprovalText(approvalKey),
                    cleanApprovalText(toolName)
                            + ":"
                            + cleanApprovalText(patternKey)
                            + ":"
                            + cleanApprovalText(commandHash));
        }

        public List<String> effectivePatternKeys() {
            List<String> values = new ArrayList<String>();
            if (patternKeys != null) {
                for (String key : patternKeys) {
                    String value = cleanApprovalText(key);
                    if (StrUtil.isNotBlank(value) && !values.contains(value)) {
                        values.add(value);
                    }
                }
            }
            if (values.isEmpty() && StrUtil.isNotBlank(patternKey)) {
                values.add(cleanApprovalText(patternKey));
            }
            return values;
        }

        public boolean isPermanentApprovalAllowed() {
            for (String patternKey : effectivePatternKeys()) {
                if (StrUtil.nullToEmpty(patternKey).startsWith("tirith:")) {
                    return false;
                }
            }
            return true;
        }
    }

    public static class DetectionResult {
        private String patternKey;
        private List<String> patternKeys = new ArrayList<String>();
        private String description;
        private String normalizedCode;
        private boolean hardline;

        public String getPatternKey() {
            return patternKey;
        }

        public void setPatternKey(String patternKey) {
            this.patternKey = patternKey;
        }

        public List<String> getPatternKeys() {
            return patternKeys;
        }

        public void setPatternKeys(List<String> patternKeys) {
            this.patternKeys =
                    patternKeys == null
                            ? new ArrayList<String>()
                            : new ArrayList<String>(patternKeys);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getNormalizedCode() {
            return normalizedCode;
        }

        public void setNormalizedCode(String normalizedCode) {
            this.normalizedCode = normalizedCode;
        }

        public boolean isHardline() {
            return hardline;
        }

        public void setHardline(boolean hardline) {
            this.hardline = hardline;
        }

        public List<String> effectivePatternKeys() {
            List<String> values = new ArrayList<String>();
            if (patternKeys != null) {
                for (String key : patternKeys) {
                    if (StrUtil.isNotBlank(key) && !values.contains(key.trim())) {
                        values.add(key.trim());
                    }
                }
            }
            if (values.isEmpty() && StrUtil.isNotBlank(patternKey)) {
                values.add(patternKey.trim());
            }
            return values;
        }
    }

    private static class SecretPreview {
        private static String safeUrl(String url) {
            return com.jimuqu.solon.claw.support.SecretRedactor.maskUrl(url);
        }
    }

    private static class GatewayToolArgsResult {
        private final Map<String, Object> args;
        private final boolean valid;
        private final String message;
        private final String rawText;

        private GatewayToolArgsResult(
                Map<String, Object> args, boolean valid, String message, String rawText) {
            this.args = args == null ? new LinkedHashMap<String, Object>() : args;
            this.valid = valid;
            this.message = StrUtil.nullToEmpty(message);
            this.rawText = StrUtil.nullToEmpty(rawText);
        }

        static GatewayToolArgsResult valid(Map<String, Object> args) {
            return new GatewayToolArgsResult(args, true, "", "");
        }

        static GatewayToolArgsResult invalid(String message, String rawText) {
            return new GatewayToolArgsResult(
                    new LinkedHashMap<String, Object>(), false, message, rawText);
        }

        Map<String, Object> getArgs() {
            return args;
        }

        boolean isValid() {
            return valid;
        }

        String getMessage() {
            return message;
        }

        String getRawText() {
            return rawText;
        }
    }

    public interface ApprovalObserver {
        void onApprovalRequest(ApprovalRequestEvent event);

        void onApprovalResponse(ApprovalResponseEvent event);
    }

    public static class ApprovalRequestEvent {
        private final String sessionId;
        private final PendingApproval pendingApproval;
        private final PendingApproval redactedPendingApproval;

        private ApprovalRequestEvent(String sessionId, PendingApproval pendingApproval) {
            this.sessionId =
                    SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(sessionId));
            this.pendingApproval = pendingApproval;
            this.redactedPendingApproval = redactedPendingApproval(pendingApproval);
        }

        public String getSessionId() {
            return sessionId;
        }

        public PendingApproval getPendingApproval() {
            return redactedPendingApproval;
        }

        public String getToolName() {
            return redactedPendingApproval == null
                    ? ""
                    : StrUtil.nullToEmpty(redactedPendingApproval.getToolName());
        }

        public String getCommand() {
            return redactedPendingApproval == null
                    ? ""
                    : StrUtil.nullToEmpty(redactedPendingApproval.getCommand());
        }

        public String getDescription() {
            return redactedPendingApproval == null
                    ? ""
                    : StrUtil.nullToEmpty(redactedPendingApproval.getDescription());
        }

        public List<String> getPatternKeys() {
            return redactedPendingApproval == null
                    ? Collections.<String>emptyList()
                    : redactedPendingApproval.effectivePatternKeys();
        }

        public String getPrimaryPatternKey() {
            List<String> keys = getPatternKeys();
            return keys.isEmpty() ? "" : keys.get(0);
        }
    }

    private static PendingApproval redactedPendingApproval(PendingApproval source) {
        if (source == null) {
            return null;
        }
        PendingApproval copy = new PendingApproval();
        copy.setApprovalId(SecretRedactor.stripDisplayControls(source.getApprovalId()));
        copy.setToolName(SecretRedactor.redact(source.getToolName(), 200));
        copy.setPatternKey(SecretRedactor.redact(source.getPatternKey(), 400));
        copy.setPatternKeys(redactedTextList(source.getPatternKeys(), 400));
        copy.setDescription(SecretRedactor.redact(source.getDescription(), 1000));
        copy.setCommand(SecretRedactor.redact(source.getCommand(), 3000));
        copy.setCommandHash(SecretRedactor.stripDisplayControls(source.getCommandHash()));
        copy.setApprovalKey(SecretRedactor.redact(source.getApprovalKey(), 1000));
        copy.setCreatedAt(source.getCreatedAt());
        copy.setExpiresAt(source.getExpiresAt());
        return copy;
    }

    private static List<String> redactedTextList(List<String> source, int maxLength) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> values = new ArrayList<String>();
        for (String item : source) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            String redacted = SecretRedactor.redact(item, maxLength);
            if (StrUtil.isNotBlank(redacted) && !values.contains(redacted.trim())) {
                values.add(redacted.trim());
            }
        }
        return values;
    }

    public static class ApprovalResponseEvent extends ApprovalRequestEvent {
        private final String choice;
        private final String approver;

        private ApprovalResponseEvent(
                String sessionId, PendingApproval pendingApproval, String choice, String approver) {
            super(sessionId, pendingApproval);
            this.choice = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(choice)).trim();
            this.approver = redactedApprover(approver);
        }

        public String getChoice() {
            return choice;
        }

        public String getApprover() {
            return approver;
        }
    }

    private static class ApprovalKeyParts {
        private final String toolName;
        private final String patternKey;

        private ApprovalKeyParts(String toolName, String patternKey) {
            this.toolName = toolName;
            this.patternKey = patternKey;
        }
    }

    private static class DangerRule {
        private final String patternKey;
        private final String description;
        private final Pattern pattern;
        private final Set<String> tools;

        private DangerRule(
                String patternKey, String description, Pattern pattern, String... tools) {
            this.patternKey = patternKey;
            this.description = description;
            this.pattern = pattern;
            this.tools = new LinkedHashSet<String>(Arrays.asList(tools));
        }

        private boolean matches(String toolName, String code) {
            return tools.contains(toolName) && pattern.matcher(code).find();
        }

        private String getPatternKey() {
            return patternKey;
        }

        private String getDescription() {
            return description;
        }
    }
}
