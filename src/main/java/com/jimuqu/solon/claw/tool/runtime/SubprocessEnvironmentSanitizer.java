package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 承载子进程Environment清理器相关状态和辅助逻辑。 */
public final class SubprocessEnvironmentSanitizer {
    /** FORCEPREFIX的统一常量值。 */
    public static final String FORCE_PREFIX = "_SOLONCLAW_FORCE_";

    /** 环境变量名称正则的统一常量值。 */
    private static final Pattern ENV_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 安全环境变量前缀列表的统一常量值。 */
    private static final String[] SAFE_ENV_PREFIXES =
            new String[] {
                "PATH",
                "HOME",
                "USER",
                "USERNAME",
                "USERPROFILE",
                "LANG",
                "LC_",
                "TERM",
                "TMPDIR",
                "TMP",
                "TEMP",
                "SHELL",
                "LOGNAME",
                "XDG_",
                "PYTHONPATH",
                "VIRTUAL_ENV",
                "CONDA",
                "SYSTEMROOT",
                "WINDIR",
                "COMSPEC",
                "PATHEXT"
            };

    /** 安全上下文环境变量名称列表的统一常量值。 */
    private static final String[] SAFE_CONTEXT_ENV_NAMES =
            new String[] {"SOLONCLAW_HOME", "SOLONCLAW_PROFILE"};

    /** 子进程可继承的精确 JVM、证书和代理运行变量，不使用宽前缀避免误放行同名前缀凭据。 */
    private static final String[] SAFE_RUNTIME_ENV_NAMES =
            new String[] {
                "JAVA_HOME",
                "MAVEN_HOME",
                "SSL_CERT_FILE",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
                "NO_PROXY"
            };

    /** 密钥环境变量SUBSTRINGS的统一常量值。 */
    private static final String[] SECRET_ENV_SUBSTRINGS =
            new String[] {"KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH"};

    /** SANEPOSIX路径的统一常量值。 */
    private static final String SANE_POSIX_PATH =
            "/opt/homebrew/bin:/opt/homebrew/sbin:"
                    + "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** 提供方环境变量BLOCK列表的统一常量值。 */
    private static final Set<String> PROVIDER_ENV_BLOCKLIST = providerEnvBlocklist();

    /** 配置环境变量PASSTHROUGHBLOCK列表的统一常量值。 */
    private static final Set<String> CONFIG_ENV_PASSTHROUGH_BLOCKLIST =
            configEnvPassthroughBlocklist();

    /** 技能环境变量PASSTHROUGH的统一常量值。 */
    private static final ThreadLocal<Set<String>> SKILL_ENV_PASSTHROUGH =
            new ThreadLocal<Set<String>>();

    /** 创建子进程Environment清理器实例。 */
    private SubprocessEnvironmentSanitizer() {}

    /**
     * 构建当前策略配置摘要。
     *
     * @param appConfig 应用运行配置。
     * @return 返回策略Summary结果。
     */
    public static Map<String, Object> policySummary(AppConfig appConfig) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("enabled", Boolean.TRUE);
        summary.put("defaultDenyUnknownEnv", Boolean.TRUE);
        summary.put("safePrefixCount", Integer.valueOf(SAFE_ENV_PREFIXES.length));
        summary.put("safeContextEnvCount", Integer.valueOf(SAFE_CONTEXT_ENV_NAMES.length));
        summary.put("safeRuntimeEnvCount", Integer.valueOf(SAFE_RUNTIME_ENV_NAMES.length));
        summary.put("secretSubstringCount", Integer.valueOf(SECRET_ENV_SUBSTRINGS.length));
        summary.put("providerBlocklistCount", Integer.valueOf(PROVIDER_ENV_BLOCKLIST.size()));
        summary.put(
                "configPassthroughBlocklistCount",
                Integer.valueOf(CONFIG_ENV_PASSTHROUGH_BLOCKLIST.size()));
        summary.put(
                "configuredPassthroughCount", Integer.valueOf(envPassthrough(appConfig).size()));
        summary.put("decisionProbeSupported", Boolean.TRUE);
        summary.put("decisionProbeValueRedacted", Boolean.TRUE);
        summary.put("decisionProbeEffectiveNameSupported", Boolean.TRUE);
        summary.put("decisionProbeVisibilitySupported", Boolean.TRUE);
        summary.put("decisionProbeSourceSupported", Boolean.TRUE);
        summary.put("decisionCategories", decisionCategories());
        summary.put("skillScopedPassthroughSupported", Boolean.TRUE);
        summary.put("skillScopedPassthroughThreadLocal", Boolean.TRUE);
        summary.put("providerBlocklistOverridesPassthrough", Boolean.TRUE);
        summary.put("forcePrefixSupported", Boolean.TRUE);
        summary.put("forcePrefix", FORCE_PREFIX);
        summary.put("forcePrefixRequiresValidEnvName", Boolean.TRUE);
        summary.put("secretNameSubstringsBlocked", Boolean.TRUE);
        summary.put("runtimeSafetyTogglesBlocked", Boolean.TRUE);
        summary.put("channelSecretsBlocked", Boolean.TRUE);
        summary.put("toolBackendSecretsBlocked", Boolean.TRUE);
        summary.put("gatewaySecretsBlocked", Boolean.TRUE);
        summary.put("pathFallbackEnabledForPosix", Boolean.TRUE);
        summary.put("windowsPathFallbackDisabled", Boolean.TRUE);
        return summary;
    }

    /**
     * 执行决策Categories相关逻辑。
     *
     * @return 返回decision Categories结果。
     */
    public static List<Map<String, Object>> decisionCategories() {
        List<Map<String, Object>> categories = new ArrayList<Map<String, Object>>();
        categories.add(category("decision", "force"));
        categories.add(category("decision", "allow"));
        categories.add(category("decision", "provider-blocked"));
        categories.add(category("decision", "high-risk"));
        categories.add(category("decision", "block"));
        categories.add(category("visibility", "visible"));
        categories.add(category("visibility", "redacted"));
        categories.add(category("visibility", "hidden"));
        categories.add(category("source", "force-prefix"));
        categories.add(category("source", "configured-or-skill-passthrough"));
        categories.add(category("source", "safe-prefix-or-context"));
        categories.add(category("source", "provider-blocklist-overrides-passthrough"));
        categories.add(category("source", "provider-blocklist"));
        categories.add(category("source", "secret-name-substring"));
        categories.add(category("source", "high-risk-runtime-name"));
        categories.add(category("source", "default-deny-unknown"));
        categories.add(category("source", "invalid-env-name"));
        return categories;
    }

    /**
     * 执行probeDecisions相关逻辑。
     *
     * @param env 环境变量参数。
     * @param appConfig 应用运行配置。
     * @return 返回probe Decisions结果。
     */
    public static List<Map<String, Object>> probeDecisions(
            Map<String, String> env, AppConfig appConfig) {
        return probeDecisions(env, appConfig, false);
    }

    /**
     * 执行probeDecisions相关逻辑。
     *
     * @param env 环境变量参数。
     * @param appConfig 应用运行配置。
     * @param redactNames redactNames 参数。
     * @return 返回probe Decisions结果。
     */
    public static List<Map<String, Object>> probeDecisions(
            Map<String, String> env, AppConfig appConfig, boolean redactNames) {
        List<Map<String, Object>> decisions = new ArrayList<Map<String, Object>>();
        if (env == null || env.isEmpty()) {
            return decisions;
        }
        Set<String> passthrough = envPassthrough(appConfig);
        passthrough.addAll(currentSkillEnvironmentPassthrough());
        for (String name : env.keySet()) {
            decisions.add(probeDecision(name, appConfig, passthrough, redactNames));
        }
        return decisions;
    }

    /**
     * 执行probe决策相关逻辑。
     *
     * @param name 名称参数。
     * @param appConfig 应用运行配置。
     * @return 返回probe Decision结果。
     */
    public static Map<String, Object> probeDecision(String name, AppConfig appConfig) {
        return probeDecision(name, appConfig, false);
    }

    /**
     * 执行probe决策相关逻辑。
     *
     * @param name 名称参数。
     * @param appConfig 应用运行配置。
     * @param redactNames redactNames 参数。
     * @return 返回probe Decision结果。
     */
    public static Map<String, Object> probeDecision(
            String name, AppConfig appConfig, boolean redactNames) {
        Set<String> passthrough = envPassthrough(appConfig);
        passthrough.addAll(currentSkillEnvironmentPassthrough());
        return probeDecision(name, appConfig, passthrough, redactNames);
    }

    /**
     * 执行probe决策相关逻辑。
     *
     * @param name 名称参数。
     * @param appConfig 应用运行配置。
     * @param passthrough passthrough 参数。
     * @param redactNames redactNames 参数。
     * @return 返回probe Decision结果。
     */
    private static Map<String, Object> probeDecision(
            String name, AppConfig appConfig, Set<String> passthrough, boolean redactNames) {
        String rawName = StrUtil.nullToEmpty(name);
        String normalizedName = normalizeEnvName(rawName);
        String forcedName = forcedName(rawName);
        boolean validName = normalizedName != null;
        boolean forced = forcedName != null;
        boolean configuredPassthrough = isEnvPassthrough(rawName, passthrough);
        boolean providerBlocked = isProviderEnvBlocked(rawName);
        boolean highRisk = isHighRiskEnvName(rawName);
        boolean secretName = isSecretEnvName(rawName);
        boolean safeName = isSafeEnvName(rawName);
        String decision;
        String reason;
        String outputName = forced ? forcedName : (validName ? normalizedName : null);
        String source;
        String visibility;
        boolean allowed;
        if (forced) {
            decision = "force";
            reason = "force-prefix";
            source = "force-prefix";
            allowed = true;
        } else if (configuredPassthrough && providerBlocked) {
            decision = "provider-blocked";
            reason = "provider-blocklist-overrides-passthrough";
            source = "provider-blocklist-overrides-passthrough";
            allowed = false;
        } else if (configuredPassthrough) {
            decision = "allow";
            reason = "configured-or-skill-passthrough";
            source = "configured-or-skill-passthrough";
            allowed = true;
        } else if (providerBlocked) {
            decision = "provider-blocked";
            reason = "provider-blocklist";
            source = "provider-blocklist";
            allowed = false;
        } else if (secretName) {
            decision = "high-risk";
            reason = "secret-name-substring";
            source = "secret-name-substring";
            allowed = false;
        } else if (highRisk && !safeName) {
            decision = "high-risk";
            reason = "high-risk-runtime-name";
            source = "high-risk-runtime-name";
            allowed = false;
        } else if (safeName) {
            decision = "allow";
            reason = "safe-prefix-or-context";
            source = "safe-prefix-or-context";
            allowed = true;
        } else {
            decision = "block";
            reason = validName ? "default-deny-unknown" : "invalid-env-name";
            source = validName ? "default-deny-unknown" : "invalid-env-name";
            allowed = false;
        }
        if (allowed) {
            visibility = "visible";
        } else if (forced || validName) {
            visibility = "redacted";
        } else {
            visibility = "hidden";
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", safeProbeText(rawName, redactNames));
        result.put("normalizedName", safeProbeText(normalizedName, redactNames));
        result.put("effectiveName", safeProbeText(outputName, redactNames));
        result.put("outputName", safeProbeText(outputName, redactNames));
        result.put("decision", decision);
        result.put("visibility", visibility);
        result.put("source", source);
        result.put("allowed", Boolean.valueOf(allowed));
        result.put("blocked", Boolean.valueOf(!allowed));
        result.put("reason", reason);
        result.put("forced", Boolean.valueOf(forced));
        result.put("configuredPassthrough", Boolean.valueOf(configuredPassthrough));
        result.put("providerBlocked", Boolean.valueOf(providerBlocked));
        result.put("highRisk", Boolean.valueOf(highRisk));
        result.put("secretName", Boolean.valueOf(secretName));
        result.put("safeName", Boolean.valueOf(safeName));
        result.put("validName", Boolean.valueOf(validName || forced));
        result.put("valueIncluded", Boolean.FALSE);
        result.put("policyOnly", Boolean.TRUE);
        return result;
    }

    /**
     * 执行category相关逻辑。
     *
     * @param dimension dimension 参数。
     * @param value 待规范化或校验的原始值。
     * @return 返回category结果。
     */
    private static Map<String, Object> category(String dimension, String value) {
        Map<String, Object> category = new LinkedHashMap<String, Object>();
        category.put("dimension", dimension);
        category.put("value", value);
        return category;
    }

    /**
     * 执行清理相关逻辑。
     *
     * @param env 环境变量参数。
     */
    public static void sanitize(Map<String, String> env) {
        sanitize(env, null);
    }

    /**
     * 执行清理相关逻辑。
     *
     * @param env 环境变量参数。
     * @param appConfig 应用运行配置。
     */
    public static void sanitize(Map<String, String> env, AppConfig appConfig) {
        if (env == null) {
            return;
        }
        Set<String> passthrough = envPassthrough(appConfig);
        passthrough.addAll(currentSkillEnvironmentPassthrough());
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
        String path = pathWithSaneFallback(env.get("PATH"), isWindows());
        if (path != null) {
            env.put("PATH", path);
        }
    }

    /**
     * 判断是否Safe Env名称。
     *
     * @param name 名称参数。
     * @return 如果Safe Env名称满足条件则返回 true，否则返回 false。
     */
    public static boolean isSafeEnvName(String name) {
        String value = StrUtil.nullToEmpty(name);
        String upper = value.toUpperCase(Locale.ROOT);
        for (String safeName : SAFE_CONTEXT_ENV_NAMES) {
            if (safeName.equals(upper)) {
                return true;
            }
        }
        for (String safeName : SAFE_RUNTIME_ENV_NAMES) {
            if (safeName.equals(upper)) {
                return true;
            }
        }
        for (String prefix : SAFE_ENV_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否密钥Env名称。
     *
     * @param name 名称参数。
     * @return 如果密钥Env名称满足条件则返回 true，否则返回 false。
     */
    public static boolean isSecretEnvName(String name) {
        String upper = StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT);
        for (String marker : SECRET_ENV_SUBSTRINGS) {
            if (upper.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否提供方Env 块ed。
     *
     * @param name 名称参数。
     * @return 如果提供方Env 块ed满足条件则返回 true，否则返回 false。
     */
    public static boolean isProviderEnvBlocked(String name) {
        String normalized = StrUtil.nullToEmpty(name).toUpperCase(Locale.ROOT);
        return PROVIDER_ENV_BLOCKLIST.contains(normalized)
                || normalized.startsWith("SOLONCLAW_CHANNEL_")
                || (normalized.startsWith("SOLONCLAW_PROVIDER_") && normalized.endsWith("_API_KEY"))
                || "SOLONCLAW_SPEECH_API_KEY".equals(normalized);
    }

    /**
     * 判断是否High Risk Env名称。
     *
     * @param name 名称参数。
     * @return 如果High Risk Env名称满足条件则返回 true，否则返回 false。
     */
    public static boolean isHighRiskEnvName(String name) {
        String normalized = normalizeEnvName(name);
        return normalized != null
                && (isProviderEnvBlocked(normalized)
                        || CONFIG_ENV_PASSTHROUGH_BLOCKLIST.contains(normalized)
                        || isSecretEnvName(normalized));
    }

    /**
     * 校验Configured Env Passthrough。
     *
     * @param names names 参数。
     * @param configKey 配置键标识或键值。
     */
    public static void validateConfiguredEnvPassthrough(List<String> names, String configKey) {
        if (names == null || names.isEmpty()) {
            return;
        }
        String key = StrUtil.blankToDefault(configKey, "terminal.envPassthrough");
        for (String name : names) {
            String value = StrUtil.nullToEmpty(name).trim();
            if (StrUtil.isBlank(value)) {
                continue;
            }
            String normalized = normalizeEnvName(value);
            if (normalized == null) {
                throw new IllegalStateException(key + " contains invalid env var name: " + value);
            }
            if (normalized.startsWith(FORCE_PREFIX)) {
                throw new IllegalStateException(
                        key + " must not use reserved force prefix: " + FORCE_PREFIX);
            }
            if (isConfiguredEnvPassthroughBlocked(normalized)) {
                throw new IllegalStateException(
                        key + " must not include high-risk env var: " + normalized);
            }
        }
    }

    /**
     * 判断是否Configured Env Passthrough 块ed。
     *
     * @param normalizedName normalized名称参数。
     * @return 如果Configured Env Passthrough 块ed满足条件则返回 true，否则返回 false。
     */
    private static boolean isConfiguredEnvPassthroughBlocked(String normalizedName) {
        return isProviderEnvBlocked(normalizedName)
                || CONFIG_ENV_PASSTHROUGH_BLOCKLIST.contains(normalizedName);
    }

    /**
     * 执行with技能EnvironmentPassthrough相关逻辑。
     *
     * @param names names 参数。
     * @return 返回with技能Environment Passthrough结果。
     */
    public static AutoCloseable withSkillEnvironmentPassthrough(List<String> names) {
        final Set<String> previous = SKILL_ENV_PASSTHROUGH.get();
        Set<String> next = previous == null ? new HashSet<String>() : new HashSet<String>(previous);
        if (names != null) {
            for (String name : names) {
                String normalized = normalizeEnvName(name);
                if (normalized != null && !isProviderEnvBlocked(normalized)) {
                    next.add(normalized);
                }
            }
        }
        if (next.isEmpty()) {
            SKILL_ENV_PASSTHROUGH.remove();
        } else {
            SKILL_ENV_PASSTHROUGH.set(next);
        }
        return new AutoCloseable() {
            /** 关闭当前组件持有的运行资源。 */
            @Override
            public void close() {
                if (previous == null || previous.isEmpty()) {
                    SKILL_ENV_PASSTHROUGH.remove();
                } else {
                    SKILL_ENV_PASSTHROUGH.set(previous);
                }
            }
        };
    }

    /**
     * 注册技能Environment Passthrough。
     *
     * @param names names 参数。
     */
    public static void registerSkillEnvironmentPassthrough(List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        Set<String> current = SKILL_ENV_PASSTHROUGH.get();
        Set<String> next = current == null ? new HashSet<String>() : new HashSet<String>(current);
        for (String name : names) {
            String normalized = normalizeEnvName(name);
            if (normalized != null && !isProviderEnvBlocked(normalized)) {
                next.add(normalized);
            }
        }
        if (next.isEmpty()) {
            SKILL_ENV_PASSTHROUGH.remove();
        } else {
            SKILL_ENV_PASSTHROUGH.set(next);
        }
    }

    /** 清理技能Environment Passthrough。 */
    public static void clearSkillEnvironmentPassthrough() {
        SKILL_ENV_PASSTHROUGH.remove();
    }

    /**
     * 判断是否Env Passthrough。
     *
     * @param name 名称参数。
     * @param passthrough passthrough 参数。
     * @return 如果Env Passthrough满足条件则返回 true，否则返回 false。
     */
    private static boolean isEnvPassthrough(String name, Set<String> passthrough) {
        if (passthrough == null || passthrough.isEmpty()) {
            return false;
        }
        String normalized = normalizeEnvName(name);
        return normalized != null && passthrough.contains(normalized);
    }

    /**
     * 执行环境变量Passthrough相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回env Passthrough结果。
     */
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
            String value = normalizeEnvName(item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * 执行当前技能EnvironmentPassthrough相关逻辑。
     *
     * @return 返回当前技能Environment Passthrough结果。
     */
    private static Set<String> currentSkillEnvironmentPassthrough() {
        Set<String> values = SKILL_ENV_PASSTHROUGH.get();
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<String>(values);
    }

    /**
     * 规范化Env名称。
     *
     * @param name 名称参数。
     * @return 返回Env名称结果。
     */
    private static String normalizeEnvName(String name) {
        String value = StrUtil.nullToEmpty(name).trim();
        if (StrUtil.isBlank(value) || !ENV_NAME_PATTERN.matcher(value).matches()) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /**
     * 执行路径WithSane兜底相关逻辑。
     *
     * @param path 文件或目录路径。
     * @param windows Windows参数。
     * @return 返回路径With Sane兜底结果。
     */
    static String pathWithSaneFallback(String path, boolean windows) {
        if (windows) {
            return path;
        }
        String value = StrUtil.nullToEmpty(path);
        if (value.length() == 0) {
            return SANE_POSIX_PATH;
        }
        String[] parts = value.split(":");
        for (String part : parts) {
            if ("/usr/bin".equals(part)) {
                return value;
            }
        }
        return value + ":" + SANE_POSIX_PATH;
    }

    /**
     * 生成安全展示用的Probe文本。
     *
     * @param value 待规范化或校验的原始值。
     * @param redactNames redactNames 参数。
     * @return 返回safe Probe Text结果。
     */
    private static String safeProbeText(String value, boolean redactNames) {
        String clean = SecretRedactor.stripDisplayControls(StrUtil.nullToEmpty(value)).trim();
        if (!redactNames) {
            return clean;
        }
        return SecretRedactor.redactTokensOnly(clean, 200);
    }

    /**
     * 执行forced名称相关逻辑。
     *
     * @param name 名称参数。
     * @return 返回forced名称结果。
     */
    private static String forcedName(String name) {
        String value = StrUtil.nullToEmpty(name);
        if (value.startsWith(FORCE_PREFIX) && value.length() > FORCE_PREFIX.length()) {
            return normalizeEnvName(value.substring(FORCE_PREFIX.length()));
        }
        return null;
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 执行配置环境变量Passthrough块list相关逻辑。
     *
     * @return 返回配置Env Passthrough 块list结果。
     */
    private static Set<String> configEnvPassthroughBlocklist() {
        Set<String> values = new HashSet<String>();
        String[] names =
                new String[] {
                    "PATH",
                    "LD_PRELOAD",
                    "LD_LIBRARY_PATH",
                    "DYLD_INSERT_LIBRARIES",
                    "DYLD_LIBRARY_PATH",
                    "PYTHONPATH",
                    "PYTHONHOME",
                    "NODE_OPTIONS",
                    "JAVA_TOOL_OPTIONS",
                    "JAVA_OPTS",
                    "MAVEN_OPTS",
                    "GRADLE_OPTS",
                    "EDITOR",
                    "VISUAL",
                    "SHELL",
                    "BASH_ENV",
                    "ENV",
                    "PROMPT_COMMAND"
                };
        for (String name : names) {
            values.add(name);
        }
        return values;
    }

    /**
     * 执行提供方环境变量块list相关逻辑。
     *
     * @return 返回提供方Env 块list结果。
     */
    private static Set<String> providerEnvBlocklist() {
        Set<String> values = new HashSet<String>();
        String[] names =
                new String[] {
                    "OPENAI_API_KEY",
                    "OPENAI_BASE_URL",
                    "OPENAI_ORG_ID",
                    "OPENAI_ORGANIZATION",
                    "ANTHROPIC_API_KEY",
                    "ANTHROPIC_BASE_URL",
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
                    "NVIDIA_API_KEY",
                    "MISTRAL_API_KEY",
                    "GROQ_API_KEY",
                    "TOGETHER_API_KEY",
                    "PERPLEXITY_API_KEY",
                    "COHERE_API_KEY",
                    "FIREWORKS_API_KEY",
                    "XAI_API_KEY",
                    "HELICONE_API_KEY",
                    "PARALLEL_API_KEY",
                    "LLM_MODEL",
                    "EXA_API_KEY",
                    "FIRECRAWL_API_KEY",
                    "FIRECRAWL_API_URL",
                    "FIRECRAWL_GATEWAY_URL",
                    "TAVILY_API_KEY",
                    "SEARXNG_URL",
                    "BROWSERBASE_API_KEY",
                    "BROWSERBASE_PROJECT_ID",
                    "BROWSER_USE_API_KEY",
                    "FAL_KEY",
                    "ELEVENLABS_API_KEY",
                    "VOICE_TOOLS_OPENAI_KEY",
                    "TOOL_GATEWAY_DOMAIN",
                    "TOOL_GATEWAY_SCHEME",
                    "TOOL_GATEWAY_USER_TOKEN",
                    "TERMINAL_SSH_HOST",
                    "TERMINAL_SSH_USER",
                    "TERMINAL_SSH_KEY",
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
                    "TELEGRAM_BOT_TOKEN",
                    "TELEGRAM_HOME_CHANNEL",
                    "TELEGRAM_HOME_CHANNEL_NAME",
                    "DISCORD_BOT_TOKEN",
                    "DISCORD_HOME_CHANNEL",
                    "DISCORD_HOME_CHANNEL_NAME",
                    "DISCORD_REQUIRE_MENTION",
                    "DISCORD_FREE_RESPONSE_CHANNELS",
                    "DISCORD_AUTO_THREAD",
                    "SLACK_BOT_TOKEN",
                    "SLACK_APP_TOKEN",
                    "SLACK_HOME_CHANNEL",
                    "SLACK_HOME_CHANNEL_NAME",
                    "SLACK_ALLOWED_USERS",
                    "WHATSAPP_ENABLED",
                    "WHATSAPP_MODE",
                    "WHATSAPP_ALLOWED_USERS",
                    "SIGNAL_HTTP_URL",
                    "SIGNAL_ACCOUNT",
                    "SIGNAL_ALLOWED_USERS",
                    "SIGNAL_GROUP_ALLOWED_USERS",
                    "SIGNAL_HOME_CHANNEL",
                    "SIGNAL_HOME_CHANNEL_NAME",
                    "SIGNAL_IGNORE_STORIES",
                    "HASS_TOKEN",
                    "HASS_URL",
                    "EMAIL_ADDRESS",
                    "EMAIL_PASSWORD",
                    "EMAIL_IMAP_HOST",
                    "EMAIL_SMTP_HOST",
                    "EMAIL_HOME_ADDRESS",
                    "EMAIL_HOME_ADDRESS_NAME",
                    "GATEWAY_ALLOWED_USERS",
                    "GATEWAY_ALLOW_ALL_USERS",
                    "GATEWAY_INJECTION_SECRET",
                    "GH_TOKEN",
                    "GITHUB_TOKEN",
                    "GITHUB_APP_ID",
                    "GITHUB_APP_PRIVATE_KEY_PATH",
                    "GITHUB_APP_INSTALLATION_ID",
                    "MODAL_TOKEN_ID",
                    "MODAL_TOKEN_SECRET",
                    "DAYTONA_API_KEY",
                    "VERCEL_OIDC_TOKEN",
                    "VERCEL_TOKEN",
                    "VERCEL_PROJECT_ID",
                    "VERCEL_TEAM_ID",
                    "HONCHO_API_KEY",
                    "WANDB_API_KEY",
                    "TINKER_API_KEY"
                };
        for (String name : names) {
            values.add(name);
        }
        return values;
    }
}
