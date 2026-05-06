package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.support.IdSupport;
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

    private static final String SENSITIVE_WRITE_TARGET =
            "(?:/etc/|/dev/sd|(?:~|\\$home|\\$\\{home\\})/\\.ssh(?:/|$)|"
                    + "(?:~|\\$home|\\$\\{home\\})/\\.(?:bashrc|zshrc|profile|bash_profile|zprofile)\\b|"
                    + "(?:~|\\$home|\\$\\{home\\})/\\.(?:netrc|pgpass|npmrc|pypirc)\\b|"
                    + "(?:~/.jimuqu-agent/|~/.hermes/|(?:\\$home|\\$\\{home\\})/\\.jimuqu-agent/|(?:\\$home|\\$\\{home\\})/\\.hermes/|(?:\\$jimuqu_home|\\$\\{jimuqu_home\\}|\\$hermes_home|\\$\\{hermes_home\\})/)\\.env\\b)";
    private static final String PROJECT_SENSITIVE_WRITE_TARGET =
            "(?:(?:/|\\.{1,2}/)?(?:[^\\s/\"'`]+/)*(?:\\.env(?:\\.[^/\\s\"'`]+)*|config\\.ya?ml))";
    private static final String COMMAND_TAIL = "(?:\\s*(?:&&|\\|\\||;).*)?$";
    private static final Pattern SHELL_LEVEL_BACKGROUND =
            pattern("\\b(?:nohup|disown|setsid)\\b");
    private static final Pattern INLINE_BACKGROUND_AMP = pattern("\\s&\\s");
    private static final Pattern TRAILING_BACKGROUND_AMP = pattern("\\s&\\s*(?:#.*)?$");
    private static final Pattern ANSI_CONTROL_SEQUENCE =
            Pattern.compile(
                    "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|P[^\\u001B]*(?:\\u001B\\\\)|[_^][^\\u001B]*(?:\\u001B\\\\)|[@-Z\\\\-_])|[\\u0080-\\u009F]");
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
                                    pattern("\\brm\\s+-(?!-)[^\\s]*r"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "recursive_delete_long_flag",
                                    "recursive delete (long flag)",
                                    pattern("\\brm\\s+--recursive\\b"),
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
                                    "world_writable",
                                    "world/other-writable permissions",
                                    pattern(
                                            "\\bchmod\\s+(-[^\\s]*\\s+)*(777|666|o\\+[rwx]*w|a\\+[rwx]*w)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "world_writable_long_flag",
                                    "recursive world/other-writable (long flag)",
                                    pattern(
                                            "\\bchmod\\s+--recursive\\b.*(777|666|o\\+[rwx]*w|a\\+[rwx]*w)"),
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
                                    "overwrite_etc",
                                    "overwrite system config",
                                    pattern("(>|tee\\b).*?/etc/"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "write_block_device",
                                    "write to block device",
                                    pattern(">\\s*/dev/sd"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "shell_command_flag",
                                    "shell command via -c/-lc flag",
                                    pattern("\\b(bash|sh|zsh|ksh)\\s+-[^\\s]*c(\\s+|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "script_eval_flag",
                                    "script execution via -e/-c flag",
                                    pattern("\\b(python[23]?|perl|ruby|node)\\s+-[ec]\\s+"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "remote_script_process_substitution",
                                    "execute remote script via process substitution",
                                    pattern("\\b(bash|sh|zsh|ksh)\\s+<\\s*<?\\s*\\(\\s*(curl|wget)\\b"),
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
                                    "stop_service",
                                    "stop/restart system service",
                                    pattern(
                                            "\\bsystemctl\\s+(-[^\\s]+\\s+)*(stop|restart|disable|mask)\\b"),
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
                                    pattern("\\b(?:hermes|jimuqu-agent|solon-claw)\\s+gateway\\s+(stop|restart)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "app_update_restart",
                                    "agent update (restarts gateway, kills running agents)",
                                    pattern("\\b(?:hermes|jimuqu-agent|solon-claw)\\s+update\\b"),
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
                                    pattern("\\b(pkill|killall)\\b.*\\b(hermes|jimuqu-agent|solon-claw|gateway|cli\\.py)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "kill_pgrep_expansion",
                                    "kill process via pgrep expansion (self-termination)",
                                    pattern("\\bkill\\b.*\\$\\(\\s*pgrep\\b|\\bkill\\b.*`\\s*pgrep\\b"),
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
                                    pattern("\\b(python[23]?|perl|ruby|node)\\s+<<"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "curl_pipe_shell",
                                    "pipe remote content to shell",
                                    pattern("\\b(curl|wget)\\b.*\\|\\s*(ba)?sh\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
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
                                    pattern("\\bgit\\s+clean\\s+-[^\\s]*f"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "git_branch_force_delete",
                                    "git branch force delete",
                                    pattern("\\bgit\\s+branch\\s+-D\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "chmod_execute_script",
                                    "chmod +x followed by immediate execution",
                                    pattern("\\bchmod\\s+\\+x\\b.*[;&|]+\\s*\\./"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "sql_drop",
                                    "SQL DROP",
                                    pattern("\\bDROP\\s+(TABLE|DATABASE)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL,
                                    ToolNameConstants.EXECUTE_PYTHON,
                                    ToolNameConstants.EXECUTE_JS),
                            new DangerRule(
                                    "sql_delete_no_where",
                                    "SQL DELETE without WHERE",
                                    pattern("\\bDELETE\\s+FROM\\b(?!.*\\bWHERE\\b)"),
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
                                    "windows_remove_item",
                                    "PowerShell recursive delete",
                                    pattern("\\bRemove-Item\\b.*-Recurse\\b.*-Force\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_del_force",
                                    "Windows force delete",
                                    pattern("\\bdel\\b.*\\s/[fq].*\\s/[fq]"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_rmdir_force",
                                    "Windows recursive directory delete",
                                    pattern("\\b(rmdir|rd)\\b.*\\s/s\\b.*\\s/q\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_format",
                                    "Windows format volume",
                                    pattern("\\bformat\\s+[a-z]:"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_taskkill",
                                    "Windows force kill",
                                    pattern("\\btaskkill\\b.*\\s/f\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_stop_process",
                                    "PowerShell force stop process",
                                    pattern("\\bStop-Process\\b.*-Force\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "windows_reg_delete",
                                    "Windows registry delete",
                                    pattern("\\breg\\s+delete\\b"),
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
                                    "python_subprocess",
                                    "Python subprocess execution",
                                    pattern(
                                            "\\bsubprocess\\.(run|Popen|call|check_call|check_output)\\s*\\("),
                                    ToolNameConstants.EXECUTE_PYTHON),
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
                                    pattern("\\brm\\s+(-[^\\s]*\\s+)*(/|/\\*|/ \\*)(\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_delete_system_dir",
                                    "recursive delete of system directory",
                                    pattern(
                                            "\\brm\\s+(-[^\\s]*\\s+)*(/home|/home/\\*|/root|/root/\\*|/etc|/etc/\\*|/usr|/usr/\\*|/var|/var/\\*|/bin|/bin/\\*|/sbin|/sbin/\\*|/boot|/boot/\\*|/lib|/lib/\\*)(\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_delete_home",
                                    "recursive delete of home directory",
                                    pattern("\\brm\\s+(-[^\\s]*\\s+)*(~|\\$HOME)(/?|/\\*)?(\\s|$)"),
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
                                            "\\bdd\\b[^\\n]*\\bof=/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_redirect_device",
                                    "redirect to raw block device",
                                    pattern(">\\s*/dev/(sd|nvme|hd|mmcblk|vd|xvd)[a-z0-9]*\\b"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_shutdown",
                                    "system shutdown/reboot",
                                    pattern(
                                            "(?:^|[;&|\\n`]|\\$\\()\\s*(?:sudo\\s+(?:-[^\\s]+\\s+)*)?(?:env\\s+(?:\\w+=\\S*\\s+)*)?(?:(?:exec|nohup|setsid|time)\\s+)*\\s*(shutdown(?!\\s*/)|reboot|halt|poweroff|init\\s+[06]|telinit\\s+[06]|systemctl\\s+(poweroff|reboot|halt|kexec))\\b"),
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
                                    pattern("\\bformat\\s+[a-z]:(\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_delete_profile",
                                    "recursive delete of Windows user profile",
                                    pattern(
                                            "\\b(Remove-Item|rm|rmdir|rd)\\b[^\\n]*(?:-Recurse|/s)\\b[^\\n]*(?:\\$env:USERPROFILE|%USERPROFILE%|C:\\\\Users(?:\\\\\\*|\\\\[^\\s'\"`|;&<>]+)?)(?:\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_system_dir",
                                    "recursive delete of Windows system directory",
                                    pattern(
                                            "\\b(Remove-Item|rm|rmdir|rd)\\b[^\\n]*(?:-Recurse|/s)\\b[^\\n]*(?:C:\\\\Windows|C:\\\\Program Files|C:\\\\Program Files \\(x86\\)|C:\\\\ProgramData)(?:\\\\\\*|\\s|$)"),
                                    ToolNameConstants.EXECUTE_SHELL),
                            new DangerRule(
                                    "hardline_windows_shutdown",
                                    "Windows shutdown/reboot",
                                    pattern(
                                            "(?:^|[;&|\\n`])\\s*(?:shutdown\\s+/[rs]|Restart-Computer|Stop-Computer)\\b"),
                                    ToolNameConstants.EXECUTE_SHELL)));

    private final GlobalSettingRepository globalSettingRepository;
    private final AppConfig appConfig;
    private final SecurityPolicyService securityPolicyService;
    private final TirithSecurityService tirithSecurityService;
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

    public HITLInterceptor buildInterceptor() {
        return new HITLInterceptor()
                .onTool(
                        ToolNameConstants.EXECUTE_SHELL,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_SHELL, args))
                .onTool(
                        ToolNameConstants.EXECUTE_PYTHON,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_PYTHON, args))
                .onTool(
                        ToolNameConstants.EXECUTE_JS,
                        (trace, args) -> evaluate(trace, ToolNameConstants.EXECUTE_JS, args))
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

        if (scope == ApprovalScope.SESSION) {
            for (String patternKey : pending.effectivePatternKeys()) {
                addSessionApproval(
                        session.getContext(), approvalPattern(pending.getToolName(), patternKey));
            }
        } else if (scope == ApprovalScope.ALWAYS) {
            for (String patternKey : pending.effectivePatternKeys()) {
                String approvalPattern = approvalPattern(pending.getToolName(), patternKey);
                if (isTirithPattern(patternKey)) {
                    addSessionApproval(session.getContext(), approvalPattern);
                } else {
                    addAlwaysApproval(approvalPattern);
                }
            }
        }

        String comment = scope.comment();
        if (StrUtil.isNotBlank(approver)) {
            comment = comment + " 审批人：" + approver.trim();
        }

        HITL.approve(session, pending.getToolName(), comment);
        removePendingApproval(session, pending);
        session.updateSnapshot();
        return true;
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
        storePendingMap(session, createPendingMap(toolName, detection, command));
        session.updateSnapshot();
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
            comment = comment + " 审批人：" + approver.trim();
        }

        HITL.reject(session, pending.getToolName(), comment);
        removePendingApproval(session, pending);
        session.updateSnapshot();
        return true;
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

        return null;
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
        return null;
    }

    public String foregroundBackgroundGuidance(String toolName, String code) {
        if (!ToolNameConstants.EXECUTE_SHELL.equals(toolName)) {
            return null;
        }
        String normalized = normalize(code);
        if (StrUtil.isBlank(normalized) || looksLikeHelpOrVersionCommand(normalized)) {
            return null;
        }

        if (SHELL_LEVEL_BACKGROUND.matcher(normalized).find()) {
            return "BLOCKED: 前台命令使用了 shell 级后台包装（nohup/disown/setsid）。请使用受管的后台进程能力，以便 Agent 跟踪生命周期和输出，然后再单独执行就绪检查或测试。";
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

    public Map<String, Object> buildDeliveryExtras(PlatformType platform, PendingApproval pending) {
        if (platform != PlatformType.FEISHU || pending == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> extras = new LinkedHashMap<String, Object>();
        extras.put("mode", DELIVERY_MODE_APPROVAL_CARD);
        extras.put("approvalId", pending.getApprovalId());
        extras.put("approvalCommand", pending.getCommand());
        extras.put("approvalDescription", pending.getDescription());
        extras.put("approvalToolName", pending.getToolName());
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

    public List<String> listSessionApprovals(AgentSession session) {
        if (session == null) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(loadSessionApprovals(session.getContext()));
    }

    public List<String> listAlwaysApprovals() {
        return new ArrayList<String>(loadAlwaysApprovedPatterns());
    }

    public void clearSessionApprovals(AgentSession session) throws Exception {
        if (session == null) {
            return;
        }
        session.getContext().remove(CONTEXT_SESSION_APPROVALS);
        session.getContext().remove(CONTEXT_PENDING_APPROVAL);
        session.getContext().remove(CONTEXT_PENDING_APPROVAL_QUEUE);
        session.updateSnapshot();
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

        String action = stringValueStatic(map.get(CARD_ACTION_KEY)).toLowerCase(Locale.ROOT);
        String approvalId = stringValueStatic(map.get(CARD_APPROVAL_ID_KEY));
        if (CARD_ACTION_DENY.equals(action)) {
            return StrUtil.isBlank(approvalId) ? "/deny" : "/deny " + approvalId;
        }
        if (!CARD_ACTION_APPROVE.equals(action)) {
            return null;
        }

        String scope = stringValueStatic(map.get(CARD_SCOPE_KEY)).toLowerCase(Locale.ROOT);
        String selector = StrUtil.isBlank(approvalId) ? "" : approvalId + " ";
        if ("always".equals(scope)) {
            return "/approve " + selector + "always";
        }
        if ("session".equals(scope)) {
            return "/approve " + selector + "session";
        }
        return StrUtil.isBlank(approvalId) ? "/approve" : "/approve " + approvalId;
    }

    private String evaluate(ReActTrace trace, String toolName, Map<String, Object> args) {
        String code =
                args == null || args.get("code") == null ? null : String.valueOf(args.get("code"));
        DetectionResult hardline = detectHardline(toolName, code);
        if (hardline != null) {
            trace.setFinalAnswer(buildHardlineMessage(toolName, hardline, code));
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        SecurityPolicyService.FileVerdict fileVerdict = detectUnsafeCommandPath(code);
        if (fileVerdict != null) {
            trace.setFinalAnswer(buildFilePolicyMessage(toolName, fileVerdict));
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

        String foregroundGuidance = foregroundBackgroundGuidance(toolName, code);
        if (foregroundGuidance != null) {
            trace.setFinalAnswer(foregroundGuidance);
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
            persistTraceSnapshot(trace);
            return null;
        }

        DetectionResult detection = detectCombined(toolName, code);
        if (detection == null) {
            persistTraceSnapshot(trace);
            return null;
        }

        if (isHermesYoloModeEnabled()) {
            persistTraceSnapshot(trace);
            return null;
        }

        String approvalMode = approvalMode();
        if ("off".equals(approvalMode)) {
            persistTraceSnapshot(trace);
            return null;
        }

        String approvalKey = combinedApprovalKey(toolName, detection);
        PendingApproval pending = getPendingApproval(trace.getSession());
        if (trace.getContext().getAs(HITL.DECISION_PREFIX + toolName) != null) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
                persistTraceSnapshot(trace);
                return null;
            }
            trace.getContext().remove(HITL.DECISION_PREFIX + toolName);
            persistTraceSnapshot(trace);
        }

        if (isApproved(trace.getContext(), approvalKey)) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            persistTraceSnapshot(trace);
            return null;
        }

        SmartApprovalDecision smartDecision =
                "smart".equals(approvalMode)
                        ? smartApprove(toolName, code, detection, trace.getContext())
                        : null;
        if (smartDecision != null && smartDecision.isApproved()) {
            if (pending != null && approvalKey.equals(pending.approvalKey())) {
                removePendingApproval(trace.getSession(), pending);
            }
            persistTraceSnapshot(trace);
            return null;
        }

        storePendingMap(trace.getSession(), createPendingMap(toolName, detection, code));
        persistTraceSnapshot(trace);
        return buildPendingMessage(toolName, detection, code);
    }

    private SmartApprovalDecision smartApprove(
            String toolName, String code, DetectionResult detection, FlowContext context) {
        if (smartApprovalJudge == null || detection == null || containsTirith(detection)) {
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

    protected String hermesYoloModeEnv() {
        return System.getenv("HERMES_YOLO_MODE");
    }

    private boolean isHermesYoloModeEnabled() {
        String value = StrUtil.nullToEmpty(hermesYoloModeEnv()).trim();
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
        return "approve".equals(mode) || "off".equals(mode) ? "approve" : "deny";
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
        payload.put("toolName", toolName);
        payload.put("patternKey", detection.getPatternKey());
        payload.put("patternKeys", new ArrayList<String>(detection.effectivePatternKeys()));
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
        String normalized = selector.trim();
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
            if (selectorMatches(item, selector)) {
                return item;
            }
        }
        return null;
    }

    private boolean selectorMatches(PendingApproval item, String selector) {
        if (item == null || StrUtil.isBlank(selector)) {
            return false;
        }
        String value = selector.trim();
        return value.equals(item.getApprovalId())
                || value.equals(item.approvalKey())
                || (item.getApprovalId() != null && item.getApprovalId().startsWith(value))
                || (item.approvalKey() != null && item.approvalKey().startsWith(value));
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
        buffer.append("原因：").append(detection.getDescription()).append("\n\n");
        buffer.append("```").append(codeFence(toolName)).append('\n');
        buffer.append(trimPreview(code));
        buffer.append("\n```\n\n");
        buffer.append(
                "回复 `/approve` 执行一次，`/approve session` 记住当前会话，`/approve always` 永久记住，或 `/deny` 取消。");
        return buffer.toString();
    }

    private String buildHardlineMessage(String toolName, DetectionResult detection, String code) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED (hardline): ");
        buffer.append(detection.getDescription());
        buffer.append("。该命令属于不可通过 Agent 执行的高危操作，不能通过 /approve、/approve always 或会话审批绕过。");
        buffer.append("\n工具：").append(toolLabel(toolName)).append("\n\n");
        buffer.append("```").append(codeFence(toolName)).append('\n');
        buffer.append(trimPreview(code));
        buffer.append("\n```");
        return buffer.toString();
    }

    private String buildFilePolicyMessage(
            String toolName, SecurityPolicyService.FileVerdict verdict) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("BLOCKED: 文件安全策略阻止访问：");
        buffer.append(verdict.getMessage());
        buffer.append("\n工具：").append(toolLabel(toolName));
        buffer.append("\n路径：").append(verdict.getPath());
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
                String pattern = approvalPattern(parts.toolName, patternKey);
                if (!loadSessionApprovals(context).contains(pattern)
                        && !loadAlwaysApprovedPatterns().contains(pattern)) {
                    return false;
                }
            }
            return true;
        }

        String approvalPattern = approvalPattern(parts.toolName, parts.patternKey);
        return loadSessionApprovals(context).contains(approvalPattern)
                || loadSessionApprovals(context).contains(approvalKey)
                || loadAlwaysApprovedPatterns().contains(approvalPattern)
                || loadAlwaysApprovedPatterns().contains(approvalKey);
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
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }

        String text = String.valueOf(raw).trim();
        if (text.length() == 0) {
            return values;
        }
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                Object parsed = ONode.deserialize(text, Object.class);
                if (parsed instanceof Collection) {
                    for (Object item : (Collection<?>) parsed) {
                        if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                            values.add(String.valueOf(item).trim());
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

    private boolean containsPattern(Set<String> approvals, String patternKey) {
        if (approvals == null || StrUtil.isBlank(patternKey)) {
            return false;
        }
        String normalizedPattern = patternKey.trim();
        if (approvals.contains(normalizedPattern)) {
            return true;
        }
        for (String approval : approvals) {
            ApprovalKeyParts parts = parseApprovalKey(approval);
            if (parts != null && normalizedPattern.equals(parts.patternKey)) {
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

        String toolName = stringValue(map.get("toolName"));
        String patternKey = stringValue(map.get("patternKey"));
        String description = stringValue(map.get("description"));
        String command = stringValue(map.get("command"));
        String commandHash = stringValue(map.get("commandHash"));
        String approvalKey = stringValue(map.get("approvalKey"));
        String approvalId = stringValue(map.get("approvalId"));
        long createdAt = longValue(map.get("createdAt"));
        long expiresAt = longValue(map.get("expiresAt"));
        if (StrUtil.hasBlank(toolName, patternKey)) {
            return null;
        }

        PendingApproval pending = new PendingApproval();
        pending.setApprovalId(approvalId);
        pending.setToolName(toolName);
        pending.setPatternKey(patternKey);
        pending.setPatternKeys(listValue(map.get("patternKeys")));
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
        String normalized = StrUtil.nullToEmpty(code).replace("\u0000", "");
        normalized = ANSI_CONTROL_SEQUENCE.matcher(normalized).replaceAll("");
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        return normalized.trim();
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
        return StrUtil.nullToEmpty(toolName).trim() + ":" + StrUtil.nullToEmpty(patternKey).trim();
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash =
                    digest.digest(
                            StrUtil.nullToEmpty(normalizedCode).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash dangerous command", e);
        }
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
                    approvalKey,
                    StrUtil.nullToEmpty(toolName)
                            + ":"
                            + StrUtil.nullToEmpty(patternKey)
                            + ":"
                            + StrUtil.nullToEmpty(commandHash));
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
