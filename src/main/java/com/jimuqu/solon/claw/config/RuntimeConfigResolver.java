package com.jimuqu.solon.claw.config;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.constants.RuntimePathConstants;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.noear.snack4.ONode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** 运行时配置解析器，统一处理 runtime/config.yml 中的可写配置项。 */
public class RuntimeConfigResolver {
    private static final Object LOCK = new Object();
    private static volatile RuntimeConfigResolver current;
    private static final Map<String, String> KEY_PATHS = buildKeyPaths();

    private final File configFile;
    private volatile long lastLoadedAt;
    private volatile Map<String, Object> fileValues = Collections.emptyMap();

    private RuntimeConfigResolver(File configFile) {
        this.configFile = configFile;
        reload();
    }

    /** 基于已解析的运行时根目录初始化全局解析器。 */
    public static RuntimeConfigResolver initialize(String runtimeHome) {
        File homeDir = resolveRuntimeHome(runtimeHome);
        File configFile = FileUtil.file(homeDir, "config.yml");
        synchronized (LOCK) {
            if (current == null || !current.configFile.equals(configFile)) {
                current = new RuntimeConfigResolver(configFile);
            } else {
                current.reloadIfNeeded();
            }
            return current;
        }
    }

    /** 返回当前解析器；若尚未初始化，则使用默认 runtime 目录。 */
    public static RuntimeConfigResolver getInstance() {
        RuntimeConfigResolver instance = current;
        if (instance == null) {
            instance = initialize(RuntimePathConstants.RUNTIME_HOME);
        } else {
            instance.reloadIfNeeded();
        }
        return instance;
    }

    /** 读取生效配置值。 */
    public static String getValue(String key) {
        return getInstance().get(key);
    }

    /** 按配置路径读取原始配置值，保留 List/Map 类型。 */
    public static Object getRawValue(String key) {
        return getInstance().getRaw(key);
    }

    /** Jimuqu cfg_get 对齐入口：按嵌套路径读取 runtime/config.yml 的原始值。 */
    public static Object cfgGet(String path, Object defaultValue) {
        return getInstance().getByPath(path, defaultValue);
    }

    /** 返回 runtime/config.yml 文件路径。 */
    public File configFile() {
        return configFile;
    }

    /** 读取指定键的生效值。 */
    public String get(String key) {
        reloadIfNeeded();
        String path = resolvePath(key);
        if (StrUtil.isBlank(path)) {
            return null;
        }
        return stringify(fileValues.get(path));
    }

    /** 读取指定键的原始文件值。 */
    public Object getRaw(String key) {
        reloadIfNeeded();
        String path = resolvePath(key);
        if (StrUtil.isBlank(path)) {
            return null;
        }
        return fileValues.get(path);
    }

    /** 读取指定嵌套路径的原始文件值。 */
    public Object getByPath(String path, Object defaultValue) {
        reloadIfNeeded();
        if (StrUtil.isBlank(path)) {
            return defaultValue;
        }
        Object value = fileValues.get(path);
        return value == null ? defaultValue : value;
    }

    /** 返回 runtime/config.yml 中的文件值快照。 */
    public Map<String, String> fileValues() {
        reloadIfNeeded();
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : KEY_PATHS.entrySet()) {
            String value = stringify(fileValues.get(entry.getValue()));
            if (value != null) {
                result.put(entry.getValue(), value);
            }
        }
        return result;
    }

    /** 返回生效值快照。 */
    public Map<String, String> effectiveValues(Iterable<String> keys) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String key : keys) {
            result.put(key, get(key));
        }
        return result;
    }

    /** 生成 runtime/config.yml 与生效 AppConfig 的漂移诊断快照。 */
    public Map<String, Object> diagnostics(AppConfig appConfig) {
        reloadIfNeeded();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> unknownKeys = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> legacyKeys = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> effectiveDiffs = new ArrayList<Map<String, Object>>();
        List<String> knownPrefixes = knownDynamicPrefixes(appConfig);
        for (Map.Entry<String, Object> entry : fileValues.entrySet()) {
            String key = entry.getKey();
            if (isRuntimeHomeKey(key)) {
                legacyKeys.add(configKeyItem(key, entry.getValue(), "runtime_home_ignored"));
                continue;
            }
            if (isKnownLegacyKey(key)) {
                legacyKeys.add(configKeyItem(key, entry.getValue(), "legacy_alias"));
                continue;
            }
            if (!isKnownConfigKey(key, knownPrefixes)) {
                unknownKeys.add(configKeyItem(key, entry.getValue(), "unknown"));
            }
        }
        Map<String, Object> effective = appConfig == null ? Collections.<String, Object>emptyMap() : flattenAppConfig(appConfig);
        for (Map.Entry<String, Object> entry : fileValues.entrySet()) {
            String rawKey = entry.getKey();
            String effectiveKey = effectivePath(rawKey);
            if (StrUtil.isBlank(effectiveKey) || !effective.containsKey(effectiveKey)) {
                continue;
            }
            Object rawValue = entry.getValue();
            Object effectiveValue = effective.get(effectiveKey);
            if (!sameConfigValue(rawValue, effectiveValue)) {
                Map<String, Object> diff = new LinkedHashMap<String, Object>();
                diff.put("key", rawKey);
                if (!rawKey.equals(effectiveKey)) {
                    diff.put("effective_key", effectiveKey);
                }
                diff.put("raw_value", safeConfigValue(rawKey, rawValue));
                diff.put("effective_value", safeConfigValue(effectiveKey, effectiveValue));
                effectiveDiffs.add(diff);
            }
        }
        result.put("config_file", configFileReference());
        result.put("raw_key_count", Integer.valueOf(fileValues.size()));
        result.put("unknown_keys", unknownKeys);
        result.put("legacy_keys", legacyKeys);
        result.put("effective_diffs", effectiveDiffs);
        result.put("unknown_count", Integer.valueOf(unknownKeys.size()));
        result.put("legacy_count", Integer.valueOf(legacyKeys.size()));
        result.put("effective_diff_count", Integer.valueOf(effectiveDiffs.size()));
        result.put(
                "has_issues",
                Boolean.valueOf(
                        !unknownKeys.isEmpty() || !legacyKeys.isEmpty() || !effectiveDiffs.isEmpty()));
        return result;
    }

    /** 设置 runtime/config.yml 中的键值。 */
    public synchronized void setFileValue(String key, String value) {
        String path = requirePath(key);
        Map<String, Object> root = loadYamlRoot();
        setNestedValue(root, path, StrUtil.nullToEmpty(value));
        write(root);
    }

    /** 删除 runtime/config.yml 中的键值。 */
    public synchronized void removeFileValue(String key) {
        String path = requirePath(key);
        Map<String, Object> root = loadYamlRoot();
        removeNestedValue(root, path);
        write(root);
    }

    /** 强制重载 runtime/config.yml。 */
    public synchronized void reload() {
        FileUtil.mkParentDirs(configFile);
        if (!configFile.exists()) {
            fileValues = Collections.emptyMap();
            lastLoadedAt = 0L;
            return;
        }

        try {
            Map<String, Object> flattened = new LinkedHashMap<String, Object>();
            Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
            if (parsed instanceof Map) {
                flatten("", sanitizeMap((Map<?, ?>) parsed), flattened);
            }
            fileValues = flattened;
            lastLoadedAt = configFile.lastModified();
        } catch (Exception e) {
            lastLoadedAt = configFile.lastModified();
        }
    }

    private void reloadIfNeeded() {
        if (!configFile.exists()) {
            if (!fileValues.isEmpty()) {
                reload();
            }
            return;
        }
        if (configFile.lastModified() != lastLoadedAt) {
            synchronized (this) {
                if (configFile.lastModified() != lastLoadedAt) {
                    reload();
                }
            }
        }
    }

    private Map<String, Object> loadYamlRoot() {
        if (!configFile.exists()) {
            return new LinkedHashMap<String, Object>();
        }
        Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
        if (!(parsed instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        return sanitizeMap((Map<?, ?>) parsed);
    }

    private void write(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(1);
        FileUtil.mkParentDirs(configFile);
        try {
            File temp = new File(configFile.getParentFile(), configFile.getName() + ".tmp");
            FileUtil.writeUtf8String(new Yaml(options).dump(root), temp);
            try {
                Files.move(
                        temp.toPath(),
                        configFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(temp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write runtime config", e);
        }
        reload();
    }

    private String requirePath(String key) {
        String path = resolvePath(key);
        if (StrUtil.isBlank(path)) {
            throw new IllegalStateException("Unsupported config key: " + key);
        }
        return path;
    }

    private String configFileReference() {
        return "runtime://config.yml";
    }

    private static Map<String, Object> configKeyItem(String key, Object value, String reason) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", key);
        item.put("reason", reason);
        item.put("value", safeConfigValue(key, value));
        return item;
    }

    private static Object safeConfigValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSecretKey(key)) {
            return "***";
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    String childKey = key + "." + entry.getKey();
                    result.put(String.valueOf(entry.getKey()), safeConfigValue(childKey, entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> values = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                values.add(safeConfigValue(key, item));
            }
            return values;
        }
        String text = SecretRedactor.redact(String.valueOf(value), 800);
        if (looksLikePathValue(text)) {
            String name = new File(text).getName();
            return "path://" + (StrUtil.isBlank(name) ? "external" : SecretRedactor.redact(name, 200));
        }
        return text;
    }

    private static boolean looksLikePathValue(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() < 2 || text.contains("\n") || text.contains("\r")) {
            return false;
        }
        if (text.startsWith("runtime://") || text.startsWith("path://")) {
            return false;
        }
        return text.startsWith("/")
                || text.startsWith("~/")
                || text.startsWith("./")
                || text.startsWith("../")
                || text.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static boolean isSecretKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
        return normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("privatekey")
                || normalized.contains("private_key");
    }

    private static boolean isRuntimeHomeKey(String key) {
        return "solonclaw.runtime.home".equals(key);
    }

    private static boolean isKnownLegacyKey(String key) {
        return "provider".equals(key) || "base_url".equals(key) || "baseUrl".equals(key);
    }

    private static boolean isKnownConfigKey(String key, List<String> dynamicPrefixes) {
        if (KEY_PATHS.containsKey(key) || KEY_PATHS.containsValue(key)) {
            return true;
        }
        if ("fallbackProviders".equals(key) || key.startsWith("fallbackProviders.")) {
            return true;
        }
        for (String prefix : dynamicPrefixes) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> knownDynamicPrefixes(AppConfig appConfig) {
        List<String> prefixes = new ArrayList<String>();
        addDynamicPrefix(prefixes, "providers.");
        addDynamicPrefix(prefixes, "pricing.prices");
        addDynamicPrefix(prefixes, "solonclaw.pricing.prices");
        addDynamicPrefix(prefixes, "plugins.enabled");
        addDynamicPrefix(prefixes, "plugins.disabled");
        addDynamicPrefix(prefixes, "solonclaw.plugins.enabled");
        addDynamicPrefix(prefixes, "solonclaw.plugins.disabled");
        addDynamicPrefix(prefixes, "solonclaw.gateway.platforms.");
        addDynamicPrefix(prefixes, "solonclaw.agent.personalities.");
        addDynamicPrefix(prefixes, "solonclaw.channels.wecom.groups.");
        addDynamicPrefix(prefixes, "solonclaw.scheduler.cronApprovalMode");
        addDynamicPrefix(prefixes, "solonclaw.scheduler.enabledToolsets");
        addDynamicPrefix(prefixes, "security.guardrailMode");
        addDynamicPrefix(prefixes, "security.guardrailCronMode");
        addDynamicPrefix(prefixes, "security.guardrailCronScope");
        addDynamicPrefix(prefixes, "security.hardlineAllowlist");
        addDynamicPrefix(prefixes, "security.guardrail_mode");
        addDynamicPrefix(prefixes, "security.guardrail_cron_mode");
        addDynamicPrefix(prefixes, "security.guardrail_cron_scope");
        addDynamicPrefix(prefixes, "security.hardline_allowlist");
        addDynamicPrefix(prefixes, "solonclaw.task.restartDrainTimeoutSeconds");
        addDynamicPrefix(prefixes, "solonclaw.terminal.shellInitFiles");
        addDynamicPrefix(prefixes, "terminal.shell_init_files");
        addDynamicPrefix(prefixes, "solonclaw.terminal.autoSourceBashrc");
        addDynamicPrefix(prefixes, "terminal.auto_source_bashrc");
        addDynamicPrefix(prefixes, "solonclaw.terminal.processWaitTimeoutSeconds");
        addDynamicPrefix(prefixes, "terminal.process_wait_timeout");
        if (appConfig != null) {
            for (String providerKey : appConfig.getProviders().keySet()) {
                addDynamicPrefix(prefixes, "providers." + providerKey + ".");
            }
            for (String personality : appConfig.getAgent().getPersonalities().keySet()) {
                addDynamicPrefix(prefixes, "solonclaw.agent.personalities." + personality + ".");
            }
        }
        return prefixes;
    }

    private static void addDynamicPrefix(List<String> prefixes, String prefix) {
        if (StrUtil.isBlank(prefix) || prefixes.contains(prefix)) {
            return;
        }
        prefixes.add(prefix);
    }

    private static String effectivePath(String rawKey) {
        if (StrUtil.isBlank(rawKey) || isRuntimeHomeKey(rawKey)) {
            return null;
        }
        String mapped = KEY_PATHS.get(rawKey);
        if (StrUtil.isNotBlank(mapped)) {
            return appConfigPath(mapped);
        }
        return appConfigPath(rawKey);
    }

    private static String appConfigPath(String configKey) {
        if (StrUtil.isBlank(configKey)) {
            return null;
        }
        if (configKey.startsWith("solonclaw.")) {
            return configKey.substring("solonclaw.".length());
        }
        if (configKey.startsWith("jimuqu.security.")) {
            return "security." + configKey.substring("jimuqu.security.".length());
        }
        if (configKey.startsWith("jimuqu.approvals.")) {
            return "approvals." + configKey.substring("jimuqu.approvals.".length());
        }
        if (configKey.startsWith("jimuqu.terminal.")) {
            return "terminal." + configKey.substring("jimuqu.terminal.".length());
        }
        if ("tool_output.max_bytes".equals(configKey)) {
            return "task.toolOutputInlineLimit";
        }
        if ("tool_output.turn_budget_bytes".equals(configKey)) {
            return "task.toolOutputTurnBudget";
        }
        if ("tool_output.max_lines".equals(configKey)) {
            return "task.toolOutputMaxLines";
        }
        if ("tool_output.max_line_length".equals(configKey)) {
            return "task.toolOutputMaxLineLength";
        }
        if (configKey.startsWith("web.")) {
            return "web." + configKey.substring("web.".length());
        }
        if (configKey.startsWith("browser.")) {
            return "security.browser" + upperFirst(configKey.substring("browser.".length()));
        }
        if (configKey.startsWith("security.")) {
            return configKey;
        }
        if (configKey.startsWith("approvals.")) {
            return configKey;
        }
        if (configKey.startsWith("terminal.")) {
            return configKey;
        }
        if (configKey.startsWith("providers.") || configKey.startsWith("model.")) {
            return configKey;
        }
        return null;
    }

    private static String upperFirst(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean sameConfigValue(Object rawValue, Object effectiveValue) {
        if (rawValue == null && effectiveValue == null) {
            return true;
        }
        if (rawValue == null || effectiveValue == null) {
            return false;
        }
        if (rawValue instanceof Number || effectiveValue instanceof Number) {
            try {
                return Double.compare(
                                Double.parseDouble(String.valueOf(rawValue).trim()),
                                Double.parseDouble(String.valueOf(effectiveValue).trim()))
                        == 0;
            } catch (Exception ignored) {
                return normalizeValueText(rawValue).equals(normalizeValueText(effectiveValue));
            }
        }
        if (rawValue instanceof Boolean || effectiveValue instanceof Boolean) {
            return Boolean.valueOf(parseBoolean(rawValue)).equals(Boolean.valueOf(parseBoolean(effectiveValue)));
        }
        return normalizeValueText(rawValue).equals(normalizeValueText(effectiveValue));
    }

    private static boolean parseBoolean(Object value) {
        String text = StrUtil.nullToEmpty(String.valueOf(value)).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private static String normalizeValueText(Object value) {
        if (value instanceof List || value instanceof Map) {
            return ONode.serialize(value);
        }
        return StrUtil.nullToEmpty(String.valueOf(value)).trim();
    }

    private static Map<String, Object> flattenAppConfig(AppConfig appConfig) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        flattenBean("", appConfig, result, new HashSet<Object>());
        return result;
    }

    private static void flattenBean(
            String prefix, Object bean, Map<String, Object> output, Set<Object> visited) {
        if (bean == null || visited.contains(bean)) {
            return;
        }
        visited.add(bean);
        try {
            for (PropertyDescriptor descriptor : Introspector.getBeanInfo(bean.getClass()).getPropertyDescriptors()) {
                Method getter = descriptor.getReadMethod();
                String name = descriptor.getName();
                if (getter == null || "class".equals(name)) {
                    continue;
                }
                Object value = getter.invoke(bean);
                String key = prefix.length() == 0 ? name : prefix + "." + name;
                flattenValue(key, value, output, visited);
            }
        } catch (Exception ignored) {
            // 诊断失败不应影响主流程；无法反射的字段会被跳过。
        }
    }

    private static void flattenValue(
            String key, Object value, Map<String, Object> output, Set<Object> visited) {
        if (value == null || isScalar(value)) {
            output.put(key, value);
            return;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    flattenValue(key + "." + entry.getKey(), entry.getValue(), output, visited);
                }
            }
            return;
        }
        if (value instanceof List) {
            output.put(key, value);
            return;
        }
        flattenBean(key, value, output, visited);
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum();
    }

    private String resolvePath(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        String mapped = KEY_PATHS.get(key);
        if (StrUtil.isNotBlank(mapped)) {
            return mapped;
        }
        if (key.startsWith("solonclaw.runtime.")) {
            return null;
        }
        if (key.startsWith("solonclaw.")
                || key.startsWith("providers.")
                || key.startsWith("model.")
                || key.startsWith("fallbackProviders.")) {
            return key;
        }
        return null;
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List) {
            StringBuilder buffer = new StringBuilder();
            for (Object item : (List<?>) value) {
                if (item == null || StrUtil.isBlank(String.valueOf(item))) {
                    continue;
                }
                if (buffer.length() > 0) {
                    buffer.append(',');
                }
                buffer.append(String.valueOf(item).trim());
            }
            return buffer.toString();
        }
        if (value instanceof Map) {
            return ONode.serialize(value);
        }
        return String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object currentValue = cursor.get(parts[i]);
            if (!(currentValue instanceof Map)) {
                currentValue = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], currentValue);
            }
            cursor = (Map<String, Object>) currentValue;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private boolean removeNestedValue(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        List<Map<String, Object>> parents = new ArrayList<Map<String, Object>>();
        List<String> keys = new ArrayList<String>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object currentValue = cursor.get(parts[i]);
            if (!(currentValue instanceof Map)) {
                return false;
            }
            parents.add(cursor);
            keys.add(parts[i]);
            cursor = (Map<String, Object>) currentValue;
        }
        Object removed = cursor.remove(parts[parts.length - 1]);
        if (removed == null) {
            return false;
        }
        for (int i = parents.size() - 1; i >= 0; i--) {
            Object currentValue = parents.get(i).get(keys.get(i));
            if (currentValue instanceof Map && ((Map<?, ?>) currentValue).isEmpty()) {
                parents.get(i).remove(keys.get(i));
            } else {
                break;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeMap(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                value = sanitizeMap((Map<?, ?>) value);
            } else if (value instanceof List) {
                value = sanitizeList((List<?>) value);
            }
            result.put(String.valueOf(entry.getKey()), value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sanitizeList(List<?> input) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : input) {
            if (item instanceof Map) {
                result.add(sanitizeMap((Map<?, ?>) item));
            } else if (item instanceof List) {
                result.add(sanitizeList((List<?>) item));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private static void flatten(String prefix, Map<?, ?> input, Map<String, Object> output) {
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key =
                    prefix.length() == 0
                            ? String.valueOf(entry.getKey())
                            : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<?, ?>) value, output);
            } else {
                output.put(key, value);
            }
        }
    }

    private static File resolveRuntimeHome(String runtimeHome) {
        String raw = StrUtil.blankToDefault(runtimeHome, RuntimePathConstants.RUNTIME_HOME);
        File file = new File(raw);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(System.getProperty("user.dir"), raw);
    }

    private static Map<String, String> buildKeyPaths() {
        Map<String, String> mappings = new LinkedHashMap<String, String>();

        add(mappings, "model.providerKey");
        add(mappings, "model.default");
        add(mappings, "providers.default.name");
        add(mappings, "providers.default.baseUrl");
        add(mappings, "providers.default.apiKey");
        add(mappings, "providers.default.defaultModel");
        add(mappings, "providers.default.dialect");

        addAll(
                mappings,
                "solonclaw.llm.stream",
                "solonclaw.llm.reasoningEffort",
                "solonclaw.llm.temperature",
                "solonclaw.llm.maxTokens",
                "solonclaw.llm.contextWindowTokens",
                "solonclaw.scheduler.enabled",
                "solonclaw.scheduler.tickSeconds",
                "solonclaw.scheduler.wrapResponse",
                "solonclaw.scheduler.scriptTimeoutSeconds",
                "solonclaw.scheduler.inactivityTimeoutSeconds",
                "solonclaw.compression.enabled",
                "solonclaw.compression.thresholdPercent",
                "solonclaw.compression.summaryModel",
                "solonclaw.compression.protectHeadMessages",
                "solonclaw.compression.tailRatio",
                "solonclaw.learning.enabled",
                "solonclaw.learning.toolCallThreshold",
                "solonclaw.learning.auxiliaryTimeoutSeconds",
                "solonclaw.skills.curator.enabled",
                "solonclaw.skills.curator.intervalHours",
                "solonclaw.skills.curator.minIdleHours",
                "solonclaw.skills.curator.staleAfterDays",
                "solonclaw.skills.curator.archiveAfterDays",
                "solonclaw.rollback.enabled",
                "solonclaw.rollback.maxCheckpointsPerSource",
                "solonclaw.rollback.maxFileSizeMb",
                "solonclaw.rollback.excludePatterns",
                "solonclaw.display.toolProgress",
                "solonclaw.display.showReasoning",
                "solonclaw.display.resumeDisplay",
                "solonclaw.display.resume_display",
                "solonclaw.display.toolPreviewLength",
                "solonclaw.display.progressThrottleMs",
                "solonclaw.display.runtimeFooter.enabled",
                "solonclaw.display.runtimeFooter.fields",
                "solonclaw.gateway.allowedUsers",
                "solonclaw.gateway.allowAllUsers",
                "solonclaw.gateway.injectionSecret",
                "solonclaw.gateway.injectionMaxBodyBytes",
                "solonclaw.gateway.injectionReplayWindowSeconds",
                "solonclaw.dashboard.accessToken",
                "solonclaw.agent.heartbeat.intervalMinutes",
                "solonclaw.react.maxSteps",
                "solonclaw.react.retryMax",
                "solonclaw.react.retryDelayMs",
                "solonclaw.react.delegateMaxSteps",
                "solonclaw.react.delegateRetryMax",
                "solonclaw.react.delegateRetryDelayMs",
                "solonclaw.react.summarizationEnabled",
                "solonclaw.react.summarizationMaxMessages",
                "solonclaw.react.summarizationMaxTokens",
                "solonclaw.react.toolLoopWarningsEnabled",
                "solonclaw.react.toolLoopHardStopEnabled",
                "solonclaw.react.toolLoopExactFailureWarnAfter",
                "solonclaw.react.toolLoopExactFailureBlockAfter",
                "solonclaw.react.toolLoopSameToolFailureWarnAfter",
                "solonclaw.react.toolLoopSameToolFailureHaltAfter",
                "solonclaw.react.toolLoopNoProgressWarnAfter",
                "solonclaw.react.toolLoopNoProgressBlockAfter",
                "tool_loop_guardrails.warnings_enabled",
                "tool_loop_guardrails.hard_stop_enabled",
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
                "solonclaw.task.busyPolicy",
                "solonclaw.task.staleAfterMinutes",
                "solonclaw.task.subagentMaxConcurrency",
                "solonclaw.task.subagentMaxDepth",
                "solonclaw.task.toolOutputInlineLimit",
                "solonclaw.task.toolOutputTurnBudget",
                "solonclaw.task.toolOutputMaxLines",
                "solonclaw.task.toolOutputMaxLineLength",
                "tool_output.max_bytes",
                "tool_output.turn_budget_bytes",
                "tool_output.max_lines",
                "tool_output.max_line_length",
                "solonclaw.task.mediaCacheTtlHours",
                "solonclaw.browser.rewriteLoopbackUrls",
                "solonclaw.browser.rewrite_loopback_urls",
                "solonclaw.browser.loopbackHostAlias",
                "solonclaw.browser.loopback_host_alias",
                "jimuqu.security.allowPrivateUrls",
                "security.allowPrivateUrls",
                "jimuqu.security.allow_private_urls",
                "security.allow_private_urls",
                "jimuqu.browser.allow_private_urls",
                "browser.allow_private_urls",
                "jimuqu.security.websiteBlocklist.enabled",
                "jimuqu.security.websiteBlocklist.domains",
                "jimuqu.security.websiteBlocklist.sharedFiles",
                "jimuqu.security.website_blocklist.enabled",
                "jimuqu.security.website_blocklist.domains",
                "jimuqu.security.website_blocklist.shared_files",
                "security.websiteBlocklist.enabled",
                "security.websiteBlocklist.domains",
                "security.websiteBlocklist.sharedFiles",
                "security.website_blocklist.enabled",
                "security.website_blocklist.domains",
                "security.website_blocklist.shared_files",
                "jimuqu.security.tirithEnabled",
                "jimuqu.security.tirithPath",
                "jimuqu.security.tirithTimeoutSeconds",
                "jimuqu.security.tirithFailOpen",
                "jimuqu.security.tirith_enabled",
                "jimuqu.security.tirith_path",
                "jimuqu.security.tirith_timeout",
                "jimuqu.security.tirith_fail_open",
                "security.tirithEnabled",
                "security.tirithPath",
                "security.tirithTimeoutSeconds",
                "security.tirithFailOpen",
                "security.tirith_enabled",
                "security.tirith_path",
                "security.tirith_timeout",
                "security.tirith_fail_open",
                "security.guardrailMode",
                "security.guardrailCronMode",
                "security.guardrailCronScope",
                "security.hardlineAllowlist",
                "security.guardrail_mode",
                "security.guardrail_cron_mode",
                "security.guardrail_cron_scope",
                "security.hardline_allowlist",
                "solonclaw.web.searchBackend",
                "solonclaw.web.search_backend",
                "solonclaw.web.braveSearchApiKey",
                "solonclaw.web.brave_search_api_key",
                "web.backend",
                "web.search_backend",
                "web.brave_search_api_key",
                "jimuqu.approvals.mode",
                "jimuqu.approvals.cronMode",
                "jimuqu.approvals.cron_mode",
                "jimuqu.approvals.subagentAutoApprove",
                "jimuqu.approvals.subagent_auto_approve",
                "delegation.subagent_auto_approve",
                "jimuqu.approvals.timeoutSeconds",
                "jimuqu.approvals.timeout",
                "jimuqu.approvals.gatewayTimeoutSeconds",
                "jimuqu.approvals.gateway_timeout",
                "jimuqu.approvals.mcpReloadConfirm",
                "jimuqu.approvals.mcp_reload_confirm",
                "approvals.mode",
                "approvals.cronMode",
                "approvals.cron_mode",
                "approvals.subagentAutoApprove",
                "approvals.subagent_auto_approve",
                "approvals.timeoutSeconds",
                "approvals.timeout",
                "approvals.gatewayTimeoutSeconds",
                "approvals.gateway_timeout",
                "approvals.mcpReloadConfirm",
                "approvals.mcp_reload_confirm",
                "jimuqu.terminal.credentialFiles",
                "jimuqu.terminal.credential_files",
                "terminal.credentialFiles",
                "terminal.credential_files",
                "jimuqu.terminal.envPassthrough",
                "jimuqu.terminal.env_passthrough",
                "terminal.envPassthrough",
                "terminal.env_passthrough",
                "jimuqu.terminal.sudoPassword",
                "jimuqu.terminal.sudo_password",
                "terminal.sudoPassword",
                "terminal.sudo_password",
                "jimuqu.terminal.writeSafeRoot",
                "jimuqu.terminal.write_safe_root",
                "terminal.writeSafeRoot",
                "terminal.write_safe_root",
                "solonclaw.terminal.maxForegroundTimeoutSeconds",
                "terminal.max_foreground_timeout",
                "solonclaw.terminal.foregroundMaxRetries",
                "terminal.foreground_max_retries",
                "solonclaw.terminal.foregroundRetryBaseDelaySeconds",
                "terminal.foreground_retry_base_delay",
                "solonclaw.mcp.enabled",
                "solonclaw.update.repo",
                "solonclaw.update.releaseApiUrl",
                "solonclaw.update.tagsApiUrl",
                "solonclaw.update.httpProxy",
                "solonclaw.tests.liveAi.enabled",
                "solonclaw.tests.dingtalk.privateOpenConversationId",
                "solonclaw.tests.dingtalk.privateUserId",
                "solonclaw.integrations.github.token",
                "solonclaw.integrations.github.cliToken",
                "solonclaw.integrations.github.appId",
                "solonclaw.integrations.github.privateKeyPath",
                "solonclaw.integrations.github.installationId",
                "solonclaw.pdf.fontPath");
        mappings.put("model.maxTokens", "solonclaw.llm.maxTokens");
        mappings.put("model.max_tokens", "solonclaw.llm.maxTokens");

        addChannelMappings(
                mappings,
                "feishu",
                "appId",
                "appSecret",
                "websocketUrl",
                "botOpenId",
                "botUserId",
                "botName",
                "toolProgress");
        addAll(
                mappings,
                "solonclaw.channels.feishu.comment.enabled",
                "solonclaw.channels.feishu.comment.pairingFile",
                "solonclaw.display.platforms.feishu.runtimeFooter.enabled");
        addChannelMappings(
                mappings,
                "dingtalk",
                "clientId",
                "clientSecret",
                "robotCode",
                "coolAppCode",
                "streamUrl",
                "toolProgress",
                "progressCardTemplateId");
        addAll(
                mappings,
                "solonclaw.channels.dingtalk.aiCardStreaming.enabled",
                "solonclaw.display.platforms.dingtalk.runtimeFooter.enabled");
        addChannelMappings(mappings, "wecom", "botId", "secret", "websocketUrl", "toolProgress");
        addAll(
                mappings,
                "solonclaw.channels.wecom.groupMemberAllowedUsers",
                "solonclaw.display.platforms.wecom.runtimeFooter.enabled");
        addChannelMappings(
                mappings,
                "weixin",
                "token",
                "accountId",
                "baseUrl",
                "cdnBaseUrl",
                "longPollUrl",
                "splitMultilineMessages",
                "sendChunkDelaySeconds",
                "sendChunkRetries",
                "sendChunkRetryDelaySeconds",
                "toolProgress");
        add(mappings, "solonclaw.display.platforms.weixin.runtimeFooter.enabled");
        addChannelMappings(
                mappings,
                "qqbot",
                "appId",
                "clientSecret",
                "apiDomain",
                "websocketUrl",
                "markdownSupport",
                "toolProgress");
        add(mappings, "solonclaw.display.platforms.qqbot.runtimeFooter.enabled");
        addChannelMappings(
                mappings,
                "yuanbao",
                "appId",
                "appSecret",
                "botId",
                "apiDomain",
                "websocketUrl",
                "toolProgress");
        add(mappings, "solonclaw.display.platforms.yuanbao.runtimeFooter.enabled");
        return mappings;
    }

    private static void addChannelMappings(
            Map<String, String> mappings, String channelName, String... extraFields) {
        String base = "solonclaw.channels." + channelName + ".";
        addAll(
                mappings,
                base + "enabled",
                base + "allowedUsers",
                base + "allowAllUsers",
                base + "unauthorizedDmBehavior",
                base + "dmPolicy",
                base + "groupPolicy",
                base + "groupAllowedUsers",
                base + "allowedChats");
        for (String field : extraFields) {
            add(mappings, base + field);
        }
        mappings.put(base + "allowed_chats", base + "allowedChats");
    }

    private static void addAll(Map<String, String> mappings, String... paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            add(mappings, path);
        }
    }

    private static void add(Map<String, String> mappings, String path) {
        mappings.put(path, path);
    }
}
