package com.jimuqu.solon.claw.skillhub.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.service.SkillGuardService;
import com.jimuqu.solon.claw.skillhub.model.Finding;
import com.jimuqu.solon.claw.skillhub.model.InstallDecision;
import com.jimuqu.solon.claw.skillhub.model.ScanResult;
import com.jimuqu.solon.claw.skillhub.support.SkillIgnoreSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Java 版技能静态扫描器。 */
public class DefaultSkillGuardService implements SkillGuardService {
    private static final int MAX_FILE_COUNT = 50;
    private static final long MAX_TOTAL_SIZE_KB = 1024L;
    private static final long MAX_SINGLE_FILE_KB = 256L;

    private static final Set<String> TRUSTED_REPOS =
            new LinkedHashSet<String>(
                    java.util.Arrays.asList(
                            "openai/skills",
                            "anthropics/skills",
                            "huggingface/skills",
                            "NVIDIA/skills"));

    private static final List<ThreatPattern> THREAT_PATTERNS =
            java.util.Arrays.asList(
                    new ThreatPattern(
                            "hardcoded_secret",
                            "critical",
                            "credential_exposure",
                            "(api[_-]?key|token|secret|password)\\s*[=:]\\s*[\"'][A-Za-z0-9+/=_-]{20,}",
                            "possible hardcoded secret"),
                    new ThreatPattern(
                            "embedded_private_key",
                            "critical",
                            "credential_exposure",
                            "-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----",
                            "embedded private key"),
                    new ThreatPattern(
                            "github_token_leaked",
                            "critical",
                            "credential_exposure",
                            "ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{80,}",
                            "GitHub personal access token in skill content"),
                    new ThreatPattern(
                            "openai_key_leaked",
                            "critical",
                            "credential_exposure",
                            "sk-[A-Za-z0-9]{20,}",
                            "possible OpenAI API key in skill content"),
                    new ThreatPattern(
                            "anthropic_key_leaked",
                            "critical",
                            "credential_exposure",
                            "sk-ant-[A-Za-z0-9_-]{90,}",
                            "possible Anthropic API key in skill content"),
                    new ThreatPattern(
                            "aws_access_key_leaked",
                            "critical",
                            "credential_exposure",
                            "AKIA[0-9A-Z]{16}",
                            "AWS access key ID in skill content"),
                    new ThreatPattern(
                            "env_exfil_curl",
                            "critical",
                            "exfiltration",
                            "curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)",
                            "curl command interpolating secret environment variable"),
                    new ThreatPattern(
                            "env_exfil_wget",
                            "critical",
                            "exfiltration",
                            "wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)",
                            "wget command interpolating secret environment variable"),
                    new ThreatPattern(
                            "env_exfil_fetch",
                            "critical",
                            "exfiltration",
                            "fetch\\s*\\([^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|API)",
                            "fetch call interpolating secret environment variable"),
                    new ThreatPattern(
                            "env_exfil_http",
                            "critical",
                            "exfiltration",
                            "httpx?\\.(get|post|put|patch)\\s*\\([^\\n]*(KEY|TOKEN|SECRET|PASSWORD)",
                            "HTTP library call with secret variable"),
                    new ThreatPattern(
                            "env_exfil_requests",
                            "critical",
                            "exfiltration",
                            "requests\\.(get|post|put|patch)\\s*\\([^\\n]*(KEY|TOKEN|SECRET|PASSWORD)",
                            "requests library call with secret variable"),
                    new ThreatPattern(
                            "encoded_exfil",
                            "high",
                            "exfiltration",
                            "base64[^\\n]*env",
                            "base64 encoding combined with environment access"),
                    new ThreatPattern(
                            "ssh_dir_access",
                            "high",
                            "exfiltration",
                            "\\$HOME/\\.ssh|~/\\.ssh",
                            "references user SSH directory"),
                    new ThreatPattern(
                            "aws_dir_access",
                            "high",
                            "exfiltration",
                            "\\$HOME/\\.aws|~/\\.aws",
                            "references user AWS credentials directory"),
                    new ThreatPattern(
                            "gpg_dir_access",
                            "high",
                            "exfiltration",
                            "\\$HOME/\\.gnupg|~/\\.gnupg",
                            "references user GPG keyring"),
                    new ThreatPattern(
                            "kube_dir_access",
                            "high",
                            "exfiltration",
                            "\\$HOME/\\.kube|~/\\.kube",
                            "references Kubernetes config directory"),
                    new ThreatPattern(
                            "docker_dir_access",
                            "high",
                            "exfiltration",
                            "\\$HOME/\\.docker|~/\\.docker",
                            "references Docker config directory"),
                    new ThreatPattern(
                            "runtime_env_access",
                            "critical",
                            "exfiltration",
                            "\\$HOME/\\.solon-claw/\\.env|~/\\.solon-claw/\\.env|runtime/\\.env|runtime/auth\\.json|runtime/cache/bws_cache\\.json|runtime/mcp-tokens",
                            "directly references local runtime secrets or token stores"),
                    new ThreatPattern(
                            "read_secrets_file",
                            "critical",
                            "exfiltration",
                            "cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|id_rsa|id_ed25519|token\\.json|auth\\.json|bws_cache\\.json)",
                            "reads known secrets file"),
                    new ThreatPattern(
                            "powershell_read_secrets_file",
                            "critical",
                            "exfiltration",
                            "\\b(Get-Content|gc|type)\\b[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|token\\.json|auth\\.json)",
                            "reads known secrets file through PowerShell or cmd"),
                    new ThreatPattern(
                            "python_open_secrets_file",
                            "critical",
                            "exfiltration",
                            "\\b(open|Path\\s*\\([^\\)]*\\)\\.read_text|Path\\s*\\([^\\)]*\\)\\.read_bytes)\\s*\\([^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|token\\.json|auth\\.json)",
                            "reads known secrets file through Python"),
                    new ThreatPattern(
                            "node_read_secrets_file",
                            "critical",
                            "exfiltration",
                            "\\b(fs\\.)?(readFileSync|readFile)\\s*\\([^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|token\\.json|auth\\.json)",
                            "reads known secrets file through Node.js fs"),
                    new ThreatPattern(
                            "java_read_secrets_file",
                            "critical",
                            "exfiltration",
                            "\\b(Files\\.(readString|readAllBytes|lines)|new\\s+FileInputStream)\\s*\\([^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|token\\.json|auth\\.json)",
                            "reads known secrets file through Java file APIs"),
                    new ThreatPattern(
                            "secret_file_http_upload",
                            "critical",
                            "exfiltration",
                            "\\b(requests|httpx|axios)\\.(post|put|patch)\\s*\\([^\\n]*(open\\s*\\(|readFileSync|Files\\.(readString|readAllBytes))[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|token\\.json|auth\\.json|bws_cache\\.json)",
                            "uploads a known secrets file through an HTTP client"),
                    new ThreatPattern(
                            "scp_secret_upload",
                            "critical",
                            "exfiltration",
                            "\\b(scp|rsync)\\b[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|id_rsa|id_ed25519|token\\.json|auth\\.json|bws_cache\\.json)",
                            "copies known secrets to another host"),
                    new ThreatPattern(
                            "curl_file_upload",
                            "critical",
                            "exfiltration",
                            "curl\\s+[^\\n]*(-F|--form|--data-binary|--upload-file|--data)\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc|id_rsa|id_ed25519|token\\.json|auth\\.json|bws_cache\\.json)",
                            "uploads a known secrets file with curl"),
                    new ThreatPattern(
                            "env_http_upload",
                            "critical",
                            "exfiltration",
                            "(curl|wget|requests\\.(post|put|patch)|httpx?\\.(post|put|patch)|fetch)\\s*[\\(]?[^\\n]*(printenv|os\\.environ|process\\.env|ENV\\[)",
                            "sends environment data through a network client"),
                    new ThreatPattern(
                            "dump_all_env",
                            "high",
                            "exfiltration",
                            "printenv|env\\s*\\|",
                            "dumps all environment variables"),
                    new ThreatPattern(
                            "python_os_environ",
                            "high",
                            "exfiltration",
                            "os\\.environ\\b(?!\\s*\\.get\\s*\\(\\s*[\"']PATH)",
                            "accesses os.environ"),
                    new ThreatPattern(
                            "python_getenv_secret",
                            "critical",
                            "exfiltration",
                            "os\\.getenv\\s*\\(\\s*[^\\)]*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)",
                            "reads secret via os.getenv"),
                    new ThreatPattern(
                            "node_process_env",
                            "high",
                            "exfiltration",
                            "process\\.env\\[",
                            "accesses process.env"),
                    new ThreatPattern(
                            "ruby_env_secret",
                            "critical",
                            "exfiltration",
                            "ENV\\[.*(KEY|TOKEN|SECRET|PASSWORD)",
                            "reads secret via Ruby ENV"),
                    new ThreatPattern(
                            "dns_exfil",
                            "critical",
                            "exfiltration",
                            "\\b(dig|nslookup|host)\\s+[^\\n]*\\$",
                            "DNS lookup with variable interpolation"),
                    new ThreatPattern(
                            "tmp_staging",
                            "critical",
                            "exfiltration",
                            ">\\s*/tmp/[^\\s]*\\s*&&\\s*(curl|wget|nc|python)",
                            "writes to /tmp then exfiltrates"),
                    new ThreatPattern(
                            "md_image_exfil",
                            "high",
                            "exfiltration",
                            "!\\[.*\\]\\(https?://[^\\)]*\\$\\{?",
                            "markdown image URL with variable interpolation"),
                    new ThreatPattern(
                            "md_link_exfil",
                            "high",
                            "exfiltration",
                            "\\[.*\\]\\(https?://[^\\)]*\\$\\{?",
                            "markdown link with variable interpolation"),
                    new ThreatPattern(
                            "curl_pipe_shell",
                            "critical",
                            "supply_chain",
                            "curl\\s+[^\\n]*\\|\\s*(ba)?sh",
                            "curl piped to shell"),
                    new ThreatPattern(
                            "wget_pipe_shell",
                            "critical",
                            "supply_chain",
                            "wget\\s+[^\\n]*-O\\s*-\\s*\\|\\s*(ba)?sh",
                            "wget piped to shell"),
                    new ThreatPattern(
                            "dangerous_rm",
                            "critical",
                            "destructive",
                            "rm\\s+-rf\\s+/",
                            "recursive delete from root"),
                    new ThreatPattern(
                            "destructive_home_rm",
                            "critical",
                            "destructive",
                            "rm\\s+(-[^\\s]*)?r.*\\$HOME|\\brmdir\\s+.*\\$HOME",
                            "recursive delete targeting home directory"),
                    new ThreatPattern(
                            "insecure_perms",
                            "medium",
                            "destructive",
                            "chmod\\s+777",
                            "sets world-writable permissions"),
                    new ThreatPattern(
                            "system_overwrite",
                            "critical",
                            "destructive",
                            ">\\s*/etc/",
                            "overwrites system configuration file"),
                    new ThreatPattern(
                            "format_filesystem",
                            "critical",
                            "destructive",
                            "\\bmkfs\\b",
                            "formats a filesystem"),
                    new ThreatPattern(
                            "disk_overwrite",
                            "critical",
                            "destructive",
                            "\\bdd\\s+.*if=.*of=/dev/",
                            "raw disk write operation"),
                    new ThreatPattern(
                            "python_rmtree",
                            "high",
                            "destructive",
                            "shutil\\.rmtree\\s*\\(\\s*[\"'/]",
                            "Python rmtree on absolute or root-relative path"),
                    new ThreatPattern(
                            "truncate_system",
                            "critical",
                            "destructive",
                            "truncate\\s+-s\\s*0\\s+/",
                            "truncates system file to zero bytes"),
                    new ThreatPattern(
                            "sudo_usage",
                            "high",
                            "privilege_escalation",
                            "\\bsudo\\b",
                            "uses sudo"),
                    new ThreatPattern(
                            "allowed_tools_field",
                            "high",
                            "privilege_escalation",
                            "^allowed-tools\\s*:",
                            "skill declares allowed-tools"),
                    new ThreatPattern(
                            "setuid_setgid",
                            "critical",
                            "privilege_escalation",
                            "setuid|setgid|cap_setuid",
                            "setuid or setgid privilege escalation mechanism"),
                    new ThreatPattern(
                            "nopasswd_sudo",
                            "critical",
                            "privilege_escalation",
                            "NOPASSWD",
                            "passwordless sudoers entry"),
                    new ThreatPattern(
                            "suid_bit",
                            "critical",
                            "privilege_escalation",
                            "chmod\\s+[u+]?s",
                            "sets SUID or SGID bit"),
                    new ThreatPattern(
                            "python_subprocess",
                            "medium",
                            "execution",
                            "subprocess\\.(run|call|Popen|check_output)\\s*\\(",
                            "Python subprocess execution"),
                    new ThreatPattern(
                            "python_os_system",
                            "high",
                            "execution",
                            "os\\.system\\s*\\(",
                            "os.system shell execution"),
                    new ThreatPattern(
                            "python_os_popen",
                            "high",
                            "execution",
                            "os\\.popen\\s*\\(",
                            "os.popen shell pipe execution"),
                    new ThreatPattern(
                            "node_child_process",
                            "high",
                            "execution",
                            "child_process\\.(exec|spawn|fork)\\s*\\(",
                            "Node.js child process execution"),
                    new ThreatPattern(
                            "java_runtime_exec",
                            "high",
                            "execution",
                            "Runtime\\.getRuntime\\(\\)\\.exec\\(",
                            "Java Runtime.exec shell execution"),
                    new ThreatPattern(
                            "backtick_subshell",
                            "medium",
                            "execution",
                            "`[^`]*\\$\\([^)]+\\)[^`]*`",
                            "backtick string with command substitution"),
                    new ThreatPattern(
                            "prompt_injection_ignore",
                            "critical",
                            "injection",
                            "ignore\\s+(?:\\w+\\s+)*(previous|all|above|prior)\\s+instructions",
                            "prompt injection ignore instructions"),
                    new ThreatPattern(
                            "role_hijack",
                            "high",
                            "injection",
                            "you\\s+are\\s+(?:\\w+\\s+)*now\\s+",
                            "role hijack"),
                    new ThreatPattern(
                            "deception_hide",
                            "critical",
                            "injection",
                            "do\\s+not\\s+(?:\\w+\\s+)*tell\\s+(?:\\w+\\s+)*the\\s+user",
                            "instructs agent to hide information from user"),
                    new ThreatPattern(
                            "sys_prompt_override",
                            "critical",
                            "injection",
                            "system\\s+prompt\\s+override",
                            "attempts to override the system prompt"),
                    new ThreatPattern(
                            "role_pretend",
                            "high",
                            "injection",
                            "pretend\\s+(?:\\w+\\s+)*(you\\s+are|to\\s+be)\\s+",
                            "attempts to make the agent assume a different identity"),
                    new ThreatPattern(
                            "disregard_rules",
                            "critical",
                            "injection",
                            "disregard\\s+(?:\\w+\\s+)*(your|all|any)\\s+(?:\\w+\\s+)*(instructions|rules|guidelines)",
                            "instructs agent to disregard rules"),
                    new ThreatPattern(
                            "leak_system_prompt",
                            "high",
                            "injection",
                            "output\\s+(?:\\w+\\s+)*(system|initial)\\s+prompt",
                            "attempts to extract the system prompt"),
                    new ThreatPattern(
                            "conditional_deception",
                            "high",
                            "injection",
                            "(when|if)\\s+no\\s*one\\s+is\\s+(watching|looking)",
                            "conditional instruction to behave differently when unobserved"),
                    new ThreatPattern(
                            "bypass_restrictions",
                            "critical",
                            "injection",
                            "act\\s+as\\s+(if|though)\\s+(?:\\w+\\s+)*you\\s+(?:\\w+\\s+)*(have\\s+no|don't\\s+have)\\s+(?:\\w+\\s+)*(restrictions|limits|rules)",
                            "instructs agent to act without restrictions"),
                    new ThreatPattern(
                            "translate_execute",
                            "critical",
                            "injection",
                            "translate\\s+.*\\s+into\\s+.*\\s+and\\s+(execute|run|eval)",
                            "translate-then-execute evasion technique"),
                    new ThreatPattern(
                            "html_comment_injection",
                            "high",
                            "injection",
                            "<!--[^>]*(ignore|override|system|secret|hidden)[^>]*-->",
                            "hidden instructions in HTML comments"),
                    new ThreatPattern(
                            "hidden_div",
                            "high",
                            "injection",
                            "<\\s*div\\s+style\\s*=\\s*[\"'][^\"']*display\\s*:\\s*none",
                            "hidden HTML div"),
                    new ThreatPattern(
                            "jailbreak_dan",
                            "critical",
                            "injection",
                            "\\bDAN\\s+mode\\b|Do\\s+Anything\\s+Now",
                            "DAN jailbreak attempt"),
                    new ThreatPattern(
                            "jailbreak_dev_mode",
                            "critical",
                            "injection",
                            "\\bdeveloper\\s+mode\\b.*\\benabled?\\b",
                            "developer mode jailbreak attempt"),
                    new ThreatPattern(
                            "hypothetical_bypass",
                            "high",
                            "injection",
                            "hypothetical\\s+scenario.*(ignore|bypass|override)",
                            "hypothetical scenario used to bypass restrictions"),
                    new ThreatPattern(
                            "educational_pretext",
                            "medium",
                            "injection",
                            "for\\s+educational\\s+purposes?\\s+only",
                            "educational pretext often used to justify harmful content"),
                    new ThreatPattern(
                            "remove_filters",
                            "critical",
                            "injection",
                            "(respond|answer|reply)\\s+without\\s+(?:\\w+\\s+)*(restrictions|limitations|filters|safety)",
                            "instructs agent to respond without safety filters"),
                    new ThreatPattern(
                            "fake_update",
                            "high",
                            "injection",
                            "you\\s+have\\s+been\\s+(?:\\w+\\s+)*(updated|upgraded|patched)\\s+to",
                            "fake update announcement"),
                    new ThreatPattern(
                            "fake_policy",
                            "medium",
                            "injection",
                            "new\\s+policy|updated\\s+guidelines|revised\\s+instructions",
                            "claims new policy or guidelines"),
                    new ThreatPattern(
                            "context_exfil",
                            "high",
                            "exfiltration",
                            "(include|output|print|send|share)\\s+(?:\\w+\\s+)*(conversation|chat\\s+history|previous\\s+messages|context)",
                            "instructs agent to output or share conversation history"),
                    new ThreatPattern(
                            "send_to_url",
                            "high",
                            "exfiltration",
                            "(send|post|upload|transmit)\\s+.*\\s+(to|at)\\s+https?://",
                            "instructs agent to send data to a URL"),
                    new ThreatPattern(
                            "persistence_cron",
                            "medium",
                            "persistence",
                            "\\bcrontab\\b",
                            "modifies cron jobs"),
                    new ThreatPattern(
                            "shell_rc_mod",
                            "medium",
                            "persistence",
                            "\\.(bashrc|zshrc|profile|bash_profile|bash_login|zprofile|zlogin|config/fish/config\\.fish)\\b",
                            "references shell startup file"),
                    new ThreatPattern(
                            "shell_rc_write",
                            "high",
                            "persistence",
                            "(>>|>|tee\\s+-a?)\\s+[^\\n]*(\\.(bashrc|zshrc|profile|bash_profile|bash_login|zprofile|zlogin)|config/fish/config\\.fish)",
                            "writes to a shell startup file"),
                    new ThreatPattern(
                            "ssh_backdoor",
                            "critical",
                            "persistence",
                            "authorized_keys",
                            "modifies SSH authorized keys"),
                    new ThreatPattern(
                            "ssh_keygen",
                            "medium",
                            "persistence",
                            "ssh-keygen",
                            "generates SSH keys"),
                    new ThreatPattern(
                            "systemd_service",
                            "medium",
                            "persistence",
                            "systemd.*\\.service|systemctl\\s+(enable|start)",
                            "references or enables systemd service"),
                    new ThreatPattern(
                            "init_script",
                            "medium",
                            "persistence",
                            "/etc/init\\.d/",
                            "references init.d startup script"),
                    new ThreatPattern(
                            "macos_launchd",
                            "medium",
                            "persistence",
                            "launchctl\\s+(load|bootstrap|enable)|LaunchAgents|LaunchDaemons",
                            "macOS launch agent or daemon persistence"),
                    new ThreatPattern(
                            "sudoers_mod",
                            "critical",
                            "persistence",
                            "/etc/sudoers|visudo",
                            "modifies sudoers"),
                    new ThreatPattern(
                            "git_config_global",
                            "medium",
                            "persistence",
                            "git\\s+config\\s+--global\\s+",
                            "modifies global git configuration"),
                    new ThreatPattern(
                            "autostart_entry",
                            "high",
                            "persistence",
                            "\\.config/autostart|StartupItems|RunOnce|CurrentVersion\\\\Run",
                            "references desktop or OS autostart locations"),
                    new ThreatPattern(
                            "ssh_config_persistence",
                            "high",
                            "persistence",
                            "\\.ssh/(config|authorized_keys|rc)",
                            "references SSH persistence or credential files"),
                    new ThreatPattern(
                            "agent_config_mod",
                            "critical",
                            "persistence",
                            "AGENTS\\.md|CLAUDE\\.md|\\.cursorrules|\\.clinerules",
                            "references agent config files"),
                    new ThreatPattern(
                            "runtime_config_mod",
                            "critical",
                            "persistence",
                            "\\.solon-claw/config\\.yml|runtime/config\\.yml|runtime/config\\.example\\.yml",
                            "references local runtime configuration files"),
                    new ThreatPattern(
                            "other_agent_config",
                            "high",
                            "persistence",
                            "\\.claude/settings|\\.codex/config|\\.qwen/",
                            "references other agent configuration files"),
                    new ThreatPattern(
                            "reverse_shell",
                            "critical",
                            "network",
                            "\\bnc\\s+-[lp]|ncat\\s+-[lp]|\\bsocat\\b",
                            "potential reverse shell listener"),
                    new ThreatPattern(
                            "tunnel_service",
                            "high",
                            "network",
                            "\\bngrok\\b|\\blocaltunnel\\b|\\bserveo\\b|\\bcloudflared\\b",
                            "uses tunneling service for external access"),
                    new ThreatPattern(
                            "hardcoded_ip_port",
                            "medium",
                            "network",
                            "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d{2,5}",
                            "hardcoded IP address with port"),
                    new ThreatPattern(
                            "bind_all_interfaces",
                            "high",
                            "network",
                            "0\\.0\\.0\\.0:\\d+|INADDR_ANY",
                            "binds to all network interfaces"),
                    new ThreatPattern(
                            "bash_reverse_shell",
                            "critical",
                            "network",
                            "/bin/(ba)?sh\\s+-i\\s+.*>\\s*&?\\s*/dev/tcp/",
                            "bash interactive reverse shell via /dev/tcp"),
                    new ThreatPattern(
                            "python_socket_oneliner",
                            "critical",
                            "network",
                            "python[23]?\\s+-c\\s+[\"']import\\s+socket",
                            "Python one-liner socket connection"),
                    new ThreatPattern(
                            "python_socket_connect",
                            "high",
                            "network",
                            "socket\\.connect\\s*\\(\\s*\\(",
                            "Python socket connect to arbitrary host"),
                    new ThreatPattern(
                            "exfil_service",
                            "high",
                            "network",
                            "webhook\\.site|requestbin\\.com|pipedream\\.net|hookbin\\.com",
                            "references known data exfiltration or webhook testing service"),
                    new ThreatPattern(
                            "paste_service",
                            "medium",
                            "network",
                            "pastebin\\.com|hastebin\\.com|ghostbin\\.",
                            "references paste service"),
                    new ThreatPattern(
                            "base64_decode_pipe",
                            "high",
                            "obfuscation",
                            "base64\\s+(-d|--decode)\\s*\\|",
                            "base64 decodes and pipes to execution"),
                    new ThreatPattern(
                            "hex_encoded_string",
                            "medium",
                            "obfuscation",
                            "\\\\x[0-9a-fA-F]{2}.*\\\\x[0-9a-fA-F]{2}.*\\\\x[0-9a-fA-F]{2}",
                            "hex-encoded string"),
                    new ThreatPattern(
                            "eval_string",
                            "high",
                            "obfuscation",
                            "\\beval\\s*\\(\\s*[\"']",
                            "eval with string argument"),
                    new ThreatPattern(
                            "exec_string",
                            "high",
                            "obfuscation",
                            "\\bexec\\s*\\(\\s*[\"']",
                            "exec with string argument"),
                    new ThreatPattern(
                            "echo_pipe_exec",
                            "critical",
                            "obfuscation",
                            "echo\\s+[^\\n]*\\|\\s*(bash|sh|python|perl|ruby|node)",
                            "echo piped to interpreter for execution"),
                    new ThreatPattern(
                            "python_compile_exec",
                            "high",
                            "obfuscation",
                            "compile\\s*\\(\\s*[^\\)]+,\\s*[\"'].*[\"']\\s*,\\s*[\"']exec[\"']\\s*\\)",
                            "Python compile with exec mode"),
                    new ThreatPattern(
                            "python_getattr_builtins",
                            "high",
                            "obfuscation",
                            "getattr\\s*\\(\\s*__builtins__",
                            "dynamic access to Python builtins"),
                    new ThreatPattern(
                            "python_import_os",
                            "high",
                            "obfuscation",
                            "__import__\\s*\\(\\s*[\"']os[\"']\\s*\\)",
                            "dynamic import of os module"),
                    new ThreatPattern(
                            "python_codecs_decode",
                            "medium",
                            "obfuscation",
                            "codecs\\.decode\\s*\\(\\s*[\"']",
                            "codecs.decode obfuscation"),
                    new ThreatPattern(
                            "js_char_code",
                            "medium",
                            "obfuscation",
                            "String\\.fromCharCode|charCodeAt",
                            "JavaScript character code construction"),
                    new ThreatPattern(
                            "js_base64",
                            "medium",
                            "obfuscation",
                            "atob\\s*\\(|btoa\\s*\\(",
                            "JavaScript base64 encode or decode"),
                    new ThreatPattern(
                            "string_reversal",
                            "low",
                            "obfuscation",
                            "\\[::-1\\]",
                            "string reversal"),
                    new ThreatPattern(
                            "chr_building",
                            "high",
                            "obfuscation",
                            "chr\\s*\\(\\s*\\d+\\s*\\)\\s*\\+\\s*chr\\s*\\(\\s*\\d+",
                            "building string from chr calls"),
                    new ThreatPattern(
                            "unicode_escape_chain",
                            "medium",
                            "obfuscation",
                            "\\\\u[0-9a-fA-F]{4}.*\\\\u[0-9a-fA-F]{4}.*\\\\u[0-9a-fA-F]{4}",
                            "chain of unicode escapes"),
                    new ThreatPattern(
                            "path_traversal_deep",
                            "high",
                            "traversal",
                            "\\.\\./\\.\\./\\.\\.",
                            "deep relative path traversal"),
                    new ThreatPattern(
                            "path_traversal",
                            "medium",
                            "traversal",
                            "\\.\\./\\.\\.",
                            "relative path traversal"),
                    new ThreatPattern(
                            "system_passwd_access",
                            "critical",
                            "traversal",
                            "/etc/passwd|/etc/shadow",
                            "references system password files"),
                    new ThreatPattern(
                            "proc_access",
                            "high",
                            "traversal",
                            "/proc/self|/proc/\\d+/",
                            "references proc filesystem"),
                    new ThreatPattern(
                            "dev_shm",
                            "medium",
                            "traversal",
                            "/dev/shm/",
                            "references shared memory staging area"),
                    new ThreatPattern(
                            "crypto_mining",
                            "critical",
                            "mining",
                            "xmrig|stratum\\+tcp|monero|coinhive|cryptonight",
                            "cryptocurrency mining reference"),
                    new ThreatPattern(
                            "mining_indicators",
                            "medium",
                            "mining",
                            "hashrate|nonce.*difficulty",
                            "possible cryptocurrency mining indicators"),
                    new ThreatPattern(
                            "curl_pipe_python",
                            "critical",
                            "supply_chain",
                            "curl\\s+[^\\n]*\\|\\s*python",
                            "curl piped to Python interpreter"),
                    new ThreatPattern(
                            "pep723_inline_deps",
                            "medium",
                            "supply_chain",
                            "#\\s*///\\s*script.*dependencies",
                            "PEP 723 inline script metadata with dependencies"),
                    new ThreatPattern(
                            "unpinned_pip_install",
                            "medium",
                            "supply_chain",
                            "pip\\s+install\\s+(?!-r\\s)(?!.*==)",
                            "pip install without version pinning"),
                    new ThreatPattern(
                            "unpinned_npm_install",
                            "medium",
                            "supply_chain",
                            "npm\\s+install\\s+(?!.*@\\d)",
                            "npm install without version pinning"),
                    new ThreatPattern(
                            "uv_run",
                            "medium",
                            "supply_chain",
                            "uv\\s+run\\s+",
                            "uv run may auto-install dependencies"),
                    new ThreatPattern(
                            "remote_fetch",
                            "medium",
                            "supply_chain",
                            "(curl|wget|httpx?\\.get|requests\\.get|fetch)\\s*[\\(]?\\s*[\"']https?://",
                            "fetches remote resource at runtime"),
                    new ThreatPattern(
                            "git_clone",
                            "medium",
                            "supply_chain",
                            "git\\s+clone\\s+",
                            "clones a git repository at runtime"),
                    new ThreatPattern(
                            "docker_pull",
                            "medium",
                            "supply_chain",
                            "docker\\s+pull\\s+",
                            "pulls a Docker image at runtime"));

    @Override
    public ScanResult scanSkill(File skillPath, String source) throws Exception {
        ScanResult result = new ScanResult();
        result.setSkillName(skillPath.getName());
        result.setSource(source);
        result.setTrustLevel(resolveTrustLevel(source));
        result.setScannedAt(DateUtil.now());

        List<Finding> findings = new ArrayList<Finding>();
        if (skillPath.isDirectory()) {
            List<File> scanFiles = SkillIgnoreSupport.includedFiles(skillPath);
            findings.addAll(checkStructure(skillPath, scanFiles));
            for (File file : scanFiles) {
                findings.addAll(scanFile(skillPath, file));
            }
        } else if (skillPath.isFile()) {
            findings.addAll(scanFile(skillPath.getParentFile(), skillPath));
        }

        result.setFindings(findings);
        result.setVerdict(determineVerdict(findings));
        result.setSummary(buildSummary(result));
        return result;
    }

    @Override
    public InstallDecision shouldAllowInstall(ScanResult result, boolean force) {
        InstallDecision decision = new InstallDecision();
        String trustLevel =
                StrUtil.blankToDefault(result.getTrustLevel(), "community")
                        .toLowerCase(Locale.ROOT);
        String verdict =
                StrUtil.blankToDefault(result.getVerdict(), "dangerous")
                        .toLowerCase(Locale.ROOT);

        if ("builtin".equals(trustLevel)) {
            decision.setAllowed(true);
            decision.setReason("Allowed builtin source");
            return decision;
        }

        if ("trusted".equals(trustLevel) && !"dangerous".equals(verdict)) {
            decision.setAllowed(true);
            decision.setReason("Allowed trusted source");
            return decision;
        }

        if ("trusted".equals(trustLevel) && "dangerous".equals(verdict)) {
            decision.setAllowed(false);
            decision.setReason("Blocked trusted source with dangerous verdict; force does not override");
            return decision;
        }

        if ("agent-created".equals(trustLevel)) {
            if ("safe".equals(verdict) || "caution".equals(verdict)) {
                decision.setAllowed(true);
                decision.setReason("Allowed agent-created source");
                return decision;
            }
            decision.setAllowed(false);
            decision.setRequiresConfirmation(true);
            decision.setReason(
                    "Agent-created skill has dangerous findings; remove the flagged content and retry");
            return decision;
        }

        if (force && "caution".equals(verdict)) {
            decision.setAllowed(true);
            decision.setReason("Force installed despite caution verdict");
            return decision;
        }

        if ("community".equals(trustLevel) && "dangerous".equals(verdict)) {
            decision.setAllowed(false);
            decision.setReason("Blocked community source with dangerous verdict; force does not override");
            return decision;
        }

        if ("dangerous".equals(verdict)) {
            decision.setAllowed(false);
            decision.setReason("Blocked dangerous verdict");
            return decision;
        }

        if ("community".equals(trustLevel) && "caution".equals(verdict)) {
            decision.setAllowed(false);
            decision.setRequiresConfirmation(true);
            decision.setReason("Community source with findings requires confirmation");
            return decision;
        }

        decision.setAllowed(true);
        decision.setReason("Allowed");
        return decision;
    }

    @Override
    public String formatReport(ScanResult result) {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Scan: ")
                .append(result.getSkillName())
                .append(" (")
                .append(result.getSource())
                .append("/")
                .append(result.getTrustLevel())
                .append(")")
                .append(" Verdict: ")
                .append(result.getVerdict().toUpperCase(Locale.ROOT));
        for (Finding finding : result.getFindings()) {
            buffer.append('\n')
                    .append("  ")
                    .append(
                            StrUtil.padAfter(
                                    finding.getSeverity().toUpperCase(Locale.ROOT), 8, ' '))
                    .append(" ")
                    .append(StrUtil.padAfter(finding.getCategory(), 14, ' '))
                    .append(" ")
                    .append(finding.getFile())
                    .append(":")
                    .append(finding.getLine())
                    .append(" \"")
                    .append(finding.getMatch())
                    .append("\"");
        }
        InstallDecision decision = shouldAllowInstall(result, false);
        buffer.append('\n')
                .append("Decision: ")
                .append(
                        decision.isAllowed()
                                ? "ALLOWED"
                                : decision.isRequiresConfirmation()
                                        ? "NEEDS CONFIRMATION"
                                        : "BLOCKED")
                .append(" - ")
                .append(decision.getReason());
        return buffer.toString();
    }

    private List<Finding> checkStructure(File skillDir, List<File> files) {
        List<Finding> findings = new ArrayList<Finding>();
        long totalSize = 0L;
        int fileCount = 0;
        for (File file : files) {
            fileCount++;
            totalSize += file.length();
            String rel = relativePath(skillDir, file);
            if (file.length() > MAX_SINGLE_FILE_KB * 1024L) {
                findings.add(
                        finding(
                                "oversized_file",
                                "medium",
                                "structural",
                                rel,
                                0,
                                (file.length() / 1024L) + "KB",
                                "single file too large"));
            }
            String ext = FileUtil.extName(file).toLowerCase(Locale.ROOT);
            if (java.util.Arrays.asList("exe", "dll", "so", "dylib", "bin", "msi", "dmg")
                    .contains(ext)) {
                findings.add(
                        finding(
                                "binary_file",
                                "critical",
                                "structural",
                                rel,
                                0,
                                ext,
                                "binary file in skill"));
            }
        }

        if (fileCount > MAX_FILE_COUNT) {
            findings.add(
                    finding(
                            "too_many_files",
                            "medium",
                            "structural",
                            "(directory)",
                            0,
                            String.valueOf(fileCount),
                            "too many files"));
        }
        if (totalSize > MAX_TOTAL_SIZE_KB * 1024L) {
            findings.add(
                    finding(
                            "oversized_skill",
                            "high",
                            "structural",
                            "(directory)",
                            0,
                            (totalSize / 1024L) + "KB",
                            "skill too large"));
        }
        return findings;
    }

    private List<Finding> scanFile(File root, File file) throws Exception {
        List<Finding> findings = new ArrayList<Finding>();
        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R");
        Set<String> dedupe = new LinkedHashSet<String>();
        for (ThreatPattern pattern : THREAT_PATTERNS) {
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matches(lines[i])) {
                    String key = pattern.getPatternId() + "#" + i;
                    if (dedupe.add(key)) {
                        findings.add(
                                finding(
                                        pattern.getPatternId(),
                                        pattern.getSeverity(),
                                        pattern.getCategory(),
                                        relativePath(root, file),
                                        i + 1,
                                        trim(lines[i], 120),
                                        pattern.getDescription()));
                    }
                }
            }
        }
        return findings;
    }

    private String determineVerdict(List<Finding> findings) {
        boolean hasCritical = false;
        boolean hasHigh = false;
        for (Finding finding : findings) {
            if ("critical".equals(finding.getSeverity())) {
                hasCritical = true;
            }
            if ("high".equals(finding.getSeverity())) {
                hasHigh = true;
            }
        }
        if (hasCritical) {
            return "dangerous";
        }
        if (hasHigh) {
            return "caution";
        }
        return "safe";
    }

    private String resolveTrustLevel(String source) {
        if (StrUtil.isBlank(source)) {
            return "community";
        }
        String normalized = source.trim().replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String prefix : java.util.Arrays.asList("skills-sh/", "skills.sh/", "skils-sh/", "skils.sh/")) {
            if (lower.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
                lower = normalized.toLowerCase(Locale.ROOT);
                break;
            }
        }
        if ("agent-created".equals(lower) || "local".equals(lower) || "manual".equals(lower)) {
            return "agent-created";
        }
        if ("community".equals(lower)
                || "clawhub".equals(lower)
                || "lobehub".equals(lower)
                || "well-known".equals(lower)
                || "claude-marketplace".equals(lower)) {
            return "community";
        }
        if ("official".equals(lower)) {
            return "builtin";
        }
        for (String trusted : TRUSTED_REPOS) {
            if (normalized.equals(trusted) || normalized.startsWith(trusted + "/")) {
                return "trusted";
            }
        }
        return "community";
    }

    private String buildSummary(ScanResult result) {
        if (result.getFindings().isEmpty()) {
            return result.getSkillName() + ": clean scan, no threats detected";
        }
        Set<String> categories = new LinkedHashSet<String>();
        for (Finding finding : result.getFindings()) {
            categories.add(finding.getCategory());
        }
        return result.getSkillName()
                + ": "
                + result.getVerdict()
                + " - "
                + result.getFindings().size()
                + " finding(s) in "
                + String.join(", ", categories);
    }

    private Finding finding(
            String patternId,
            String severity,
            String category,
            String file,
            int line,
            String match,
            String description) {
        Finding finding = new Finding();
        finding.setPatternId(patternId);
        finding.setSeverity(severity);
        finding.setCategory(category);
        finding.setFile(file);
        finding.setLine(line);
        finding.setMatch(match);
        finding.setDescription(description);
        return finding;
    }

    private String relativePath(File root, File file) {
        return SkillIgnoreSupport.relativePath(root, file);
    }

    private String trim(String line, int maxLength) {
        String normalized = StrUtil.nullToEmpty(line).trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private static class ThreatPattern {
        private final String patternId;
        private final String severity;
        private final String category;
        private final Pattern pattern;
        private final String description;

        private ThreatPattern(
                String patternId,
                String severity,
                String category,
                String regex,
                String description) {
            this.patternId = patternId;
            this.severity = severity;
            this.category = category;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.description = description;
        }

        private boolean matches(String line) {
            return pattern.matcher(StrUtil.nullToEmpty(line)).find();
        }

        private String getPatternId() {
            return patternId;
        }

        private String getSeverity() {
            return severity;
        }

        private String getCategory() {
            return category;
        }

        private String getDescription() {
            return description;
        }
    }
}
