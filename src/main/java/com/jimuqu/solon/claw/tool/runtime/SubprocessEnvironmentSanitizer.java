package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Hermes-style subprocess environment filtering for local tools. */
public final class SubprocessEnvironmentSanitizer {
    public static final String FORCE_PREFIX = "_JIMUQU_FORCE_";
    public static final String HERMES_FORCE_PREFIX = "_HERMES_FORCE_";

    private static final String[] SAFE_ENV_PREFIXES =
            new String[] {
                "PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "LANG", "LC_", "TERM",
                "TMPDIR", "TMP", "TEMP", "SHELL", "LOGNAME", "XDG_", "PYTHONPATH",
                "VIRTUAL_ENV", "CONDA", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT"
            };
    private static final String[] SECRET_ENV_SUBSTRINGS =
            new String[] {"KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"};
    private static final Set<String> PROVIDER_ENV_BLOCKLIST = providerEnvBlocklist();

    private SubprocessEnvironmentSanitizer() {}

    public static void sanitize(Map<String, String> env) {
        sanitize(env, null);
    }

    public static void sanitize(Map<String, String> env, AppConfig appConfig) {
        if (env == null) {
            return;
        }
        Set<String> passthrough = envPassthrough(appConfig);
        Map<String, String> raw = new LinkedHashMap<String, String>(env);
        env.clear();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey();
            String forcedName = forcedName(name);
            if (forcedName != null) {
                env.put(forcedName, entry.getValue());
                continue;
            }
            if (isEnvPassthrough(name, passthrough) && !isProviderEnvBlocked(name)) {
                env.put(name, entry.getValue());
                continue;
            }
            if (isSecretEnvName(name)) {
                continue;
            }
            if (isSafeEnvName(name)) {
                env.put(name, entry.getValue());
            }
        }
    }

    public static boolean isSafeEnvName(String name) {
        String value = StrUtil.nullToEmpty(name);
        for (String prefix : SAFE_ENV_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSecretEnvName(String name) {
        String upper = StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT);
        for (String marker : SECRET_ENV_SUBSTRINGS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProviderEnvBlocked(String name) {
        return PROVIDER_ENV_BLOCKLIST.contains(StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT));
    }

    private static boolean isEnvPassthrough(String name, Set<String> passthrough) {
        return passthrough != null
                && passthrough.contains(StrUtil.nullToEmpty(name).trim().toUpperCase(Locale.ROOT));
    }

    private static Set<String> envPassthrough(AppConfig appConfig) {
        Set<String> values = new HashSet<String>();
        if (appConfig == null || appConfig.getTerminal() == null) {
            return values;
        }
        List<String> configured = appConfig.getTerminal().getEnvPassthrough();
        if (configured == null) {
            return values;
        }
        for (String item : configured) {
            String value = StrUtil.nullToEmpty(item).trim();
            if (StrUtil.isNotBlank(value)) {
                values.add(value.toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    private static String forcedName(String name) {
        String value = StrUtil.nullToEmpty(name);
        if (value.startsWith(FORCE_PREFIX) && value.length() > FORCE_PREFIX.length()) {
            return value.substring(FORCE_PREFIX.length());
        }
        if (value.startsWith(HERMES_FORCE_PREFIX) && value.length() > HERMES_FORCE_PREFIX.length()) {
            return value.substring(HERMES_FORCE_PREFIX.length());
        }
        return null;
    }

    private static Set<String> providerEnvBlocklist() {
        Set<String> values = new HashSet<String>();
        String[] names =
                new String[] {
                    "OPENAI_API_KEY",
                    "OPENAI_BASE_URL",
                    "ANTHROPIC_API_KEY",
                    "ANTHROPIC_TOKEN",
                    "CLAUDE_CODE_OAUTH_TOKEN",
                    "OLLAMA_HOST",
                    "GEMINI_API_KEY",
                    "GOOGLE_API_KEY",
                    "OPENROUTER_API_KEY",
                    "ZAI_API_KEY",
                    "Z_AI_API_KEY",
                    "GLM_API_KEY",
                    "KIMI_API_KEY",
                    "MOONSHOT_API_KEY",
                    "MINIMAX_API_KEY",
                    "MINIMAX_CN_API_KEY",
                    "DEEPSEEK_API_KEY",
                    "MISTRAL_API_KEY",
                    "GROQ_API_KEY",
                    "TOGETHER_API_KEY",
                    "PERPLEXITY_API_KEY",
                    "COHERE_API_KEY",
                    "FIREWORKS_API_KEY",
                    "XAI_API_KEY",
                    "HELICONE_API_KEY",
                    "LLM_MODEL",
                    "FEISHU_APP_ID",
                    "FEISHU_APP_SECRET",
                    "DINGTALK_CLIENT_ID",
                    "DINGTALK_CLIENT_SECRET",
                    "DINGTALK_ROBOT_CODE",
                    "WECOM_BOT_ID",
                    "WECOM_SECRET",
                    "WEIXIN_TOKEN",
                    "QQBOT_APP_ID",
                    "QQBOT_CLIENT_SECRET",
                    "YUANBAO_APP_ID",
                    "YUANBAO_APP_SECRET",
                    "GATEWAY_ALLOWED_USERS",
                    "GATEWAY_ALLOW_ALL_USERS",
                    "GATEWAY_INJECTION_SECRET",
                    "GH_TOKEN",
                    "GITHUB_TOKEN",
                    "GITHUB_APP_ID",
                    "GITHUB_APP_PRIVATE_KEY_PATH",
                    "GITHUB_APP_INSTALLATION_ID"
                };
        for (String name : names) {
            values.add(name);
        }
        return values;
    }
}
