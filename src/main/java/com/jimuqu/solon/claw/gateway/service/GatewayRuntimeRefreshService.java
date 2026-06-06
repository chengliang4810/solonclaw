package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.noear.solon.Solon;
import org.noear.solon.core.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** 运行时配置刷新服务。 */
public class GatewayRuntimeRefreshService {
    /** 日志的统一常量值。 */
    private static final Logger log = LoggerFactory.getLogger(GatewayRuntimeRefreshService.class);

    /** ABSOLUTE路径的统一常量值。 */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?<![A-Za-z0-9_])(?:[A-Za-z]:)?[/\\\\][^\\s\"'<>|;?#]*");

    /** 注入应用配置，用于消息网关运行时刷新。 */
    private final AppConfig appConfig;

    /** 记录消息网关运行时刷新中的渠道连接管理器。 */
    private final ChannelConnectionManager channelConnectionManager;

    /** 记录消息网关运行时刷新中的最近一次配置Mtime。 */
    private volatile long lastConfigMtime;

    /** 记录消息网关运行时刷新中的最近一次Failure。 */
    private volatile RefreshFailure lastFailure;

    /**
     * 创建消息网关运行时刷新服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     * @param channelConnectionManager 渠道连接Manager参数。
     */
    public GatewayRuntimeRefreshService(
            AppConfig appConfig, ChannelConnectionManager channelConnectionManager) {
        this.appConfig = appConfig;
        this.channelConnectionManager = channelConnectionManager;
        this.lastConfigMtime = fileMtime(appConfig.getRuntime().getConfigFile());
    }

    /**
     * 刷新If Needed。
     *
     * @return 返回If Needed结果。
     */
    public RefreshResult refreshIfNeeded() {
        long configMtime = fileMtime(appConfig.getRuntime().getConfigFile());
        if (configMtime == lastConfigMtime) {
            return RefreshResult.skipped(runtimeConfigReference(runtimeConfigFile()), "配置文件未变化。");
        }
        return refreshNow();
    }

    /**
     * 刷新Now。
     *
     * @return 返回Now结果。
     */
    public synchronized RefreshResult refreshNow() {
        return refreshInternal(true);
    }

    /**
     * 刷新配置Only。
     *
     * @return 返回配置Only结果。
     */
    public synchronized RefreshResult refreshConfigOnly() {
        return refreshInternal(false);
    }

    /**
     * 刷新Internal。
     *
     * @param reconnectChannels reconnectChannels 参数。
     * @return 返回Internal结果。
     */
    private RefreshResult refreshInternal(boolean reconnectChannels) {
        File configFile = runtimeConfigFile();
        ValidationResult validation = validateRuntimeConfig(configFile);
        if (!validation.isSuccess()) {
            String message = safeMessage(validation.message);
            log.warn("Skip runtime refresh because config validation failed: {}", message);
            recordFailure(configFile, "validation", message, true);
            return RefreshResult.failure(runtimeConfigReference(configFile), message);
        }

        AppConfig latest;
        try {
            Props props = Solon.cfg() == null ? new Props() : new Props(Solon.cfg());
            props.put("solonclaw.runtime.home", appConfig.getRuntime().getHome());
            if (Solon.cfg() == null) {
                latest = AppConfig.load(props);
            } else {
                latest = AppConfig.load(props);
            }
        } catch (Throwable e) {
            log.debug(
                    "Skip runtime refresh because config reload failed: errorType={}, error={}",
                    e.getClass().getSimpleName(),
                    safeError(e));
            recordFailure(configFile, e.getClass().getSimpleName(), safeError(e), false);
            return RefreshResult.failure(runtimeConfigReference(configFile), safeError(e));
        }
        appConfig.applyFrom(latest);
        lastConfigMtime = fileMtime(appConfig.getRuntime().getConfigFile());
        lastFailure = null;
        if (!reconnectChannels) {
            return RefreshResult.success(runtimeConfigReference(configFile), false, "运行时配置已刷新。");
        }
        channelConnectionManager.refreshAll();
        return RefreshResult.success(runtimeConfigReference(configFile), true, "运行时配置已刷新，渠道连接已重连。");
    }

    /**
     * 执行文件Mtime相关逻辑。
     *
     * @param path 文件或目录路径。
     * @return 返回文件Mtime结果。
     */
    private long fileMtime(String path) {
        if (path == null) {
            return 0L;
        }
        File file = new File(path);
        return file.exists() ? file.lastModified() : 0L;
    }

    /**
     * 执行运行时配置文件相关逻辑。
     *
     * @return 返回运行时配置文件结果。
     */
    private File runtimeConfigFile() {
        String path = appConfig.getRuntime().getConfigFile();
        if (StrUtil.isNotBlank(path)) {
            return new File(path);
        }
        return new File(
                StrUtil.blankToDefault(
                        appConfig.getRuntime().getHome(), RuntimePathConstants.RUNTIME_HOME),
                RuntimePathConstants.CONFIG_FILE_NAME);
    }

    /**
     * 执行运行时配置引用相关逻辑。
     *
     * @param configFile 文件或目录路径参数。
     * @return 返回运行时配置Reference结果。
     */
    private String runtimeConfigReference(File configFile) {
        return "runtime://" + RuntimePathConstants.CONFIG_FILE_NAME;
    }

    /**
     * 执行lastFailureSnapshot相关逻辑。
     *
     * @return 返回last Failure Snapshot结果。
     */
    public Map<String, Object> lastFailureSnapshot() {
        RefreshFailure failure = lastFailure;
        if (failure == null) {
            return null;
        }
        return failure.toMap();
    }

    /**
     * 记录Failure。
     *
     * @param configFile 文件或目录路径参数。
     * @param type 类型参数。
     * @param message 平台消息或错误消息。
     * @param validationFailure validationFailure标识或键值。
     */
    private void recordFailure(
            File configFile, String type, String message, boolean validationFailure) {
        lastFailure =
                new RefreshFailure(
                        System.currentTimeMillis(),
                        StrUtil.blankToDefault(type, "refresh_error"),
                        runtimeConfigReference(configFile),
                        safeMessage(message),
                        validationFailure);
    }

    /**
     * 生成安全展示用的消息。
     *
     * @param message 平台消息或错误消息。
     * @return 返回safe消息结果。
     */
    private String safeMessage(String message) {
        return ABSOLUTE_PATH
                .matcher(SecretRedactor.redact(message, 1000))
                .replaceAll("[REDACTED_PATH]");
    }

    /**
     * 校验运行时配置。
     *
     * @param configFile 文件或目录路径参数。
     * @return 返回运行时配置。
     */
    private ValidationResult validateRuntimeConfig(File configFile) {
        if (configFile == null || !configFile.exists()) {
            return ValidationResult.success();
        }
        Object parsed;
        String content = "";
        try {
            content = FileUtil.readUtf8String(configFile);
            parsed = new Yaml().load(content);
        } catch (Exception e) {
            return ValidationResult.failure(
                    "runtime/config.yml 格式错误："
                            + safeError(e)
                            + "；配置片段="
                            + SecretRedactor.redact(content, 1000));
        }
        if (parsed == null) {
            return ValidationResult.success();
        }
        if (!(parsed instanceof Map)) {
            return ValidationResult.failure("runtime/config.yml 顶层必须是 YAML 对象。");
        }

        Map<String, Object> root = sanitizeMap((Map<?, ?>) parsed);
        ValidationResult containers = validateContainerTypes(root);
        if (!containers.isSuccess()) {
            return containers;
        }

        Map<String, Object> flattened = new LinkedHashMap<String, Object>();
        flatten("", root, flattened);
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            ValidationResult typed = validateValueType(entry.getKey(), entry.getValue());
            if (!typed.isSuccess()) {
                return typed;
            }
        }
        return ValidationResult.success();
    }

    /**
     * 校验Container Types。
     *
     * @param root root 参数。
     * @return 返回Container Types结果。
     */
    private ValidationResult validateContainerTypes(Map<String, Object> root) {
        for (String path : MAP_PATHS) {
            Object value = getByPath(root, path);
            if (value != null && !(value instanceof Map)) {
                return ValidationResult.failure(path + " 必须是 YAML 对象。");
            }
        }
        Object fallbackProviders = getByPath(root, "fallbackProviders");
        if (fallbackProviders != null && !(fallbackProviders instanceof java.util.List)) {
            return ValidationResult.failure("fallbackProviders 必须是 YAML 列表。");
        }
        Object providers = getByPath(root, "providers");
        if (providers instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) providers).entrySet()) {
                if (!(entry.getValue() instanceof Map)) {
                    return ValidationResult.failure(
                            "providers." + entry.getKey() + " 必须是 YAML 对象。");
                }
            }
        }
        if (fallbackProviders instanceof java.util.List) {
            int index = 0;
            for (Object item : (java.util.List<?>) fallbackProviders) {
                if (!(item instanceof Map)) {
                    return ValidationResult.failure(
                            "fallbackProviders[" + index + "] 必须是 YAML 对象。");
                }
                index++;
            }
        }
        return ValidationResult.success();
    }

    /**
     * 校验Value类型。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回Value类型结果。
     */
    private ValidationResult validateValueType(String key, Object value) {
        if (INT_KEYS.contains(key) || hasSuffix(key, INT_SUFFIXES)) {
            return validateInteger(key, value);
        }
        if (DOUBLE_KEYS.contains(key) || hasSuffix(key, DOUBLE_SUFFIXES)) {
            return validateDouble(key, value);
        }
        if (BOOLEAN_KEYS.contains(key) || hasSuffix(key, BOOLEAN_SUFFIXES)) {
            return validateBoolean(key, value);
        }
        if (LIST_KEYS.contains(key) || hasSuffix(key, LIST_SUFFIXES)) {
            if (value instanceof java.util.List || value instanceof String) {
                return ValidationResult.success();
            }
            return ValidationResult.failure(key + " 必须是 YAML 列表或逗号分隔字符串。");
        }
        return ValidationResult.success();
    }

    /**
     * 校验Integer。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回Integer结果。
     */
    private ValidationResult validateInteger(String key, Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            try {
                Integer.parseInt(String.valueOf(value).trim());
                return ValidationResult.success();
            } catch (Exception ignored) {
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
        if (value instanceof String) {
            try {
                Integer.parseInt(((String) value).trim());
                return ValidationResult.success();
            } catch (Exception ignored) {
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
        return ValidationResult.failure(key + " 必须是整数。");
    }

    /**
     * 校验Double。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回Double结果。
     */
    private ValidationResult validateDouble(String key, Object value) {
        if (value instanceof Number) {
            return ValidationResult.success();
        }
        if (value instanceof String) {
            try {
                Double.parseDouble(((String) value).trim());
                return ValidationResult.success();
            } catch (Exception ignored) {
                // 保留此处实现约束，避免后续维护时破坏既有行为。
            }
        }
        return ValidationResult.failure(key + " 必须是数字。");
    }

    /**
     * 校验Boolean。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回Boolean结果。
     */
    private ValidationResult validateBoolean(String key, Object value) {
        if (value instanceof Boolean) {
            return ValidationResult.success();
        }
        if (value instanceof Number) {
            int number = ((Number) value).intValue();
            if (number == 0 || number == 1) {
                return ValidationResult.success();
            }
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text)
                    || "false".equalsIgnoreCase(text)
                    || "1".equals(text)
                    || "0".equals(text)
                    || "yes".equalsIgnoreCase(text)
                    || "no".equalsIgnoreCase(text)) {
                return ValidationResult.success();
            }
        }
        return ValidationResult.failure(key + " 必须是布尔值。");
    }

    /**
     * 判断是否存在Suffix。
     *
     * @param key 配置键或映射键。
     * @param suffixes suffixes 参数。
     * @return 如果Suffix满足条件则返回 true，否则返回 false。
     */
    private boolean hasSuffix(String key, Set<String> suffixes) {
        if (key == null) {
            return false;
        }
        for (String suffix : suffixes) {
            if (key.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param e 捕获到的异常。
     * @return 返回safe Error结果。
     */
    private String safeError(Throwable e) {
        if (e == null) {
            return "";
        }
        return safeMessage(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
    }

    /**
     * 清理Map。
     *
     * @param raw 原始输入值。
     * @return 返回Map结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = sanitizeMap((Map<?, ?>) value);
            } else if (value instanceof java.util.List) {
                value = sanitizeList((java.util.List<?>) value);
            }
            result.put(String.valueOf(entry.getKey()), value);
        }
        return result;
    }

    /**
     * 清理List。
     *
     * @param raw 原始输入值。
     * @return 返回List结果。
     */
    @SuppressWarnings("unchecked")
    private java.util.List<Object> sanitizeList(java.util.List<?> raw) {
        java.util.List<Object> result = new java.util.ArrayList<Object>();
        for (Object item : raw) {
            if (item instanceof Map) {
                result.add(sanitizeMap((Map<?, ?>) item));
            } else if (item instanceof java.util.List) {
                result.add(sanitizeList((java.util.List<?>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 根据路径读取对应数据。
     *
     * @param root root 参数。
     * @param path 文件或目录路径。
     * @return 返回按路径读取得到的结果。
     */
    @SuppressWarnings("unchecked")
    private Object getByPath(Map<String, Object> root, String path) {
        if (root == null || StrUtil.isBlank(path)) {
            return null;
        }
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 执行flatten相关逻辑。
     *
     * @param prefix prefix 参数。
     * @param input 输入参数。
     * @param output 命令执行输出文本。
     */
    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> input, Map<String, Object> output) {
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = prefix.length() == 0 ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, output);
            } else {
                output.put(key, value);
            }
        }
    }

    /**
     * 写入Of。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回Of结果。
     */
    private static Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    /** 承载刷新Failure相关状态和辅助逻辑。 */
    private static class RefreshFailure {
        /** 记录刷新Failure中的failed时间。 */
        private final long failedAt;

        /** 记录刷新Failure中的类型。 */
        private final String type;

        /** 记录刷新Failure中的配置文件。 */
        private final String configFile;

        /** 记录刷新Failure中的消息。 */
        private final String message;

        /** 是否启用validationFailure。 */
        private final boolean validationFailure;

        /**
         * 创建刷新Failure实例，并注入运行所需依赖。
         *
         * @param failedAt failedAt 参数。
         * @param type 类型参数。
         * @param configFile 文件或目录路径参数。
         * @param message 平台消息或错误消息。
         * @param validationFailure validationFailure标识或键值。
         */
        private RefreshFailure(
                long failedAt,
                String type,
                String configFile,
                String message,
                boolean validationFailure) {
            this.failedAt = failedAt;
            this.type = type;
            this.configFile = configFile;
            this.message = message;
            this.validationFailure = validationFailure;
        }

        /**
         * 转换为Map。
         *
         * @return 返回转换后的Map。
         */
        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("failed_at", Long.valueOf(failedAt));
            map.put("type", type);
            map.put("config_file", configFile);
            map.put("message", message);
            map.put("validation_failure", Boolean.valueOf(validationFailure));
            return map;
        }
    }

    /** 表示刷新结果，携带调用方后续判断所需信息。 */
    public static class RefreshResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 是否启用refreshed。 */
        private final boolean refreshed;

        /** 是否启用reconnectedChannels。 */
        private final boolean reconnectedChannels;

        /** 记录刷新中的配置文件。 */
        private final String configFile;

        /** 记录刷新中的消息。 */
        private final String message;

        /**
         * 创建刷新结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param refreshed refreshed 参数。
         * @param reconnectedChannels reconnectedChannels 参数。
         * @param configFile 文件或目录路径参数。
         * @param message 平台消息或错误消息。
         */
        private RefreshResult(
                boolean success,
                boolean refreshed,
                boolean reconnectedChannels,
                String configFile,
                String message) {
            this.success = success;
            this.refreshed = refreshed;
            this.reconnectedChannels = reconnectedChannels;
            this.configFile = configFile;
            this.message = message;
        }

        /**
         * 执行success相关逻辑。
         *
         * @param configFile 文件或目录路径参数。
         * @param reconnectedChannels reconnectedChannels 参数。
         * @param message 平台消息或错误消息。
         * @return 返回success结果。
         */
        public static RefreshResult success(
                String configFile, boolean reconnectedChannels, String message) {
            return new RefreshResult(true, true, reconnectedChannels, configFile, message);
        }

        /**
         * 执行skipped相关逻辑。
         *
         * @param configFile 文件或目录路径参数。
         * @param message 平台消息或错误消息。
         * @return 返回skipped结果。
         */
        public static RefreshResult skipped(String configFile, String message) {
            return new RefreshResult(true, false, false, configFile, message);
        }

        /**
         * 执行failure相关逻辑。
         *
         * @param configFile 文件或目录路径参数。
         * @param message 平台消息或错误消息。
         * @return 返回failure结果。
         */
        public static RefreshResult failure(String configFile, String message) {
            return new RefreshResult(false, false, false, configFile, message);
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 判断是否Refreshed。
         *
         * @return 如果Refreshed满足条件则返回 true，否则返回 false。
         */
        public boolean isRefreshed() {
            return refreshed;
        }

        /**
         * 判断是否Reconnected Channels。
         *
         * @return 如果Reconnected Channels满足条件则返回 true，否则返回 false。
         */
        public boolean isReconnectedChannels() {
            return reconnectedChannels;
        }

        /**
         * 读取配置文件。
         *
         * @return 返回读取到的配置文件。
         */
        public String getConfigFile() {
            return configFile;
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

    /** 表示Validation结果，携带调用方后续判断所需信息。 */
    private static class ValidationResult {
        /** 是否启用success。 */
        private final boolean success;

        /** 记录Validation中的消息。 */
        private final String message;

        /**
         * 创建Validation结果实例，并注入运行所需依赖。
         *
         * @param success success 参数。
         * @param message 平台消息或错误消息。
         */
        private ValidationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        /**
         * 执行success相关逻辑。
         *
         * @return 返回success结果。
         */
        static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        /**
         * 执行failure相关逻辑。
         *
         * @param message 平台消息或错误消息。
         * @return 返回failure结果。
         */
        static ValidationResult failure(String message) {
            return new ValidationResult(false, message);
        }

        /**
         * 判断是否Success。
         *
         * @return 如果Success满足条件则返回 true，否则返回 false。
         */
        boolean isSuccess() {
            return success;
        }
    }

    /** 映射路径列表的统一常量值。 */
    private static final Set<String> MAP_PATHS =
            setOf(
                    "providers",
                    "model",
                    "approvals",
                    "solonclaw",
                    "solonclaw.llm",
                    "solonclaw.scheduler",
                    "solonclaw.compression",
                    "solonclaw.learning",
                    "solonclaw.skills",
                    "solonclaw.skills.curator",
                    "solonclaw.rollback",
                    "solonclaw.display",
                    "solonclaw.display.runtimeFooter",
                    "solonclaw.display.platforms",
                    "solonclaw.gateway",
                    "solonclaw.dashboard",
                    "solonclaw.agent",
                    "solonclaw.agent.heartbeat",
                    "solonclaw.react",
                    "solonclaw.trace",
                    "solonclaw.task",
                    "solonclaw.browser",
                    "solonclaw.security",
                    "solonclaw.mcp",
                    "solonclaw.channels",
                    "solonclaw.channels.feishu",
                    "solonclaw.channels.dingtalk",
                    "solonclaw.channels.wecom",
                    "solonclaw.channels.weixin",
                    "solonclaw.channels.qqbot",
                    "solonclaw.channels.yuanbao");

    /** 整型KEYS的统一常量值。 */
    private static final Set<String> INT_KEYS =
            setOf(
                    "solonclaw.llm.maxTokens",
                    "solonclaw.llm.contextWindowTokens",
                    "solonclaw.scheduler.tickSeconds",
                    "solonclaw.compression.protectHeadMessages",
                    "solonclaw.learning.toolCallThreshold",
                    "solonclaw.learning.auxiliaryTimeoutSeconds",
                    "solonclaw.skills.curator.intervalHours",
                    "solonclaw.skills.curator.staleAfterDays",
                    "solonclaw.skills.curator.archiveAfterDays",
                    "solonclaw.rollback.maxCheckpointsPerSource",
                    "solonclaw.display.toolPreviewLength",
                    "solonclaw.display.progressThrottleMs",
                    "solonclaw.display.resumeDisplay",
                    "solonclaw.display.resume_display",
                    "solonclaw.gateway.injectionMaxBodyBytes",
                    "solonclaw.gateway.injectionReplayWindowSeconds",
                    "solonclaw.agent.heartbeat.intervalMinutes",
                    "solonclaw.react.maxSteps",
                    "solonclaw.react.retryMax",
                    "solonclaw.react.retryDelayMs",
                    "solonclaw.react.delegateMaxSteps",
                    "solonclaw.react.delegateRetryMax",
                    "solonclaw.react.delegateRetryDelayMs",
                    "solonclaw.react.summarizationMaxMessages",
                    "solonclaw.react.summarizationMaxTokens",
                    "solonclaw.react.toolLoopExactFailureWarnAfter",
                    "solonclaw.react.toolLoopExactFailureBlockAfter",
                    "solonclaw.react.toolLoopSameToolFailureWarnAfter",
                    "solonclaw.react.toolLoopSameToolFailureHaltAfter",
                    "solonclaw.react.toolLoopNoProgressWarnAfter",
                    "solonclaw.react.toolLoopNoProgressBlockAfter",
                    "tool_loop_guardrails.warn_after.exact_failure",
                    "tool_loop_guardrails.warn_after.same_tool_failure",
                    "tool_loop_guardrails.warn_after.idempotent_no_progress",
                    "tool_loop_guardrails.hard_stop_after.exact_failure",
                    "tool_loop_guardrails.hard_stop_after.same_tool_failure",
                    "tool_loop_guardrails.hard_stop_after.idempotent_no_progress",
                    "tool_loop_guardrails.exact_failure_warn_after",
                    "tool_loop_guardrails.exact_failure_block_after",
                    "tool_loop_guardrails.same_tool_failure_warn_after",
                    "tool_loop_guardrails.same_tool_failure_halt_after",
                    "tool_loop_guardrails.no_progress_warn_after",
                    "tool_loop_guardrails.no_progress_block_after",
                    "solonclaw.trace.retentionDays",
                    "solonclaw.trace.maxAttempts",
                    "solonclaw.trace.toolPreviewLength",
                    "solonclaw.task.staleAfterMinutes",
                    "solonclaw.task.subagentMaxConcurrency",
                    "solonclaw.task.subagentMaxDepth",
                    "solonclaw.task.toolOutputInlineLimit",
                    "solonclaw.task.toolOutputTurnBudget",
                    "solonclaw.task.toolOutputMaxLines",
                    "solonclaw.task.toolOutputMaxLineLength",
                    "solonclaw.task.mediaCacheTtlHours",
                    "security.tirithTimeoutSeconds",
                    "security.tirith_timeout");

    /** 双精度KEYS的统一常量值。 */
    private static final Set<String> DOUBLE_KEYS =
            setOf(
                    "solonclaw.llm.temperature",
                    "solonclaw.compression.thresholdPercent",
                    "solonclaw.compression.tailRatio",
                    "solonclaw.skills.curator.minIdleHours");

    /** 布尔KEYS的统一常量值。 */
    private static final Set<String> BOOLEAN_KEYS =
            setOf(
                    "solonclaw.llm.stream",
                    "solonclaw.scheduler.enabled",
                    "solonclaw.scheduler.wrapResponse",
                    "solonclaw.compression.enabled",
                    "solonclaw.learning.enabled",
                    "solonclaw.skills.curator.enabled",
                    "solonclaw.rollback.enabled",
                    "solonclaw.display.showReasoning",
                    "solonclaw.display.runtimeFooter.enabled",
                    "solonclaw.gateway.allowAllUsers",
                    "solonclaw.react.summarizationEnabled",
                    "solonclaw.react.toolLoopWarningsEnabled",
                    "solonclaw.react.toolLoopHardStopEnabled",
                    "solonclaw.browser.rewriteLoopbackUrls",
                    "solonclaw.browser.rewrite_loopback_urls",
                    "tool_loop_guardrails.warnings_enabled",
                    "tool_loop_guardrails.hard_stop_enabled",
                    "security.allowPrivateUrls",
                    "security.allow_private_urls",
                    "security.tirithEnabled",
                    "security.tirith_enabled",
                    "security.tirithFailOpen",
                    "security.tirith_fail_open",
                    "approvals.mcpReloadConfirm",
                    "approvals.mcp_reload_confirm",
                    "security.websiteBlocklist.enabled",
                    "security.website_blocklist.enabled",
                    "solonclaw.mcp.enabled");

    /** 列表KEYS的统一常量值。 */
    private static final Set<String> LIST_KEYS =
            setOf(
                    "solonclaw.display.runtimeFooter.fields",
                    "solonclaw.gateway.allowedUsers",
                    "security.websiteBlocklist.domains",
                    "security.websiteBlocklist.sharedFiles",
                    "security.website_blocklist.domains",
                    "security.website_blocklist.shared_files",
                    "solonclaw.terminal.credentialFiles",
                    "solonclaw.terminal.writeSafeRoot");

    /** 整型后缀列表的统一常量值。 */
    private static final Set<String> INT_SUFFIXES =
            setOf(
                    ".sendChunkRetries",
                    ".toolPreviewLength",
                    ".progressThrottleMs",
                    ".tirithTimeoutSeconds",
                    ".maxForegroundTimeoutSeconds");

    /** 双精度后缀列表的统一常量值。 */
    private static final Set<String> DOUBLE_SUFFIXES =
            setOf(".sendChunkDelaySeconds", ".sendChunkRetryDelaySeconds");

    /** 布尔后缀列表的统一常量值。 */
    private static final Set<String> BOOLEAN_SUFFIXES =
            setOf(
                    ".enabled",
                    ".allowAllUsers",
                    ".splitMultilineMessages",
                    ".comment.enabled",
                    ".aiCardStreaming.enabled",
                    ".markdownSupport",
                    ".runtimeFooter.enabled");

    /** 列表后缀列表的统一常量值。 */
    private static final Set<String> LIST_SUFFIXES =
            setOf(".allowedUsers", ".groupAllowedUsers", ".runtimeFooter.fields");
}
