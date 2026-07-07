package com.jimuqu.solon.claw.config;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.support.BasicValueSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** 工作区配置解析器，统一处理 workspace/config.yml 中的可写配置项。 */
public class RuntimeConfigResolver {
    /** 工作区配置解析日志，日志内容必须保持低敏。 */
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigResolver.class);

    /** LOCK的统一常量值。 */
    private static final Object LOCK = new Object();

    /** 记录工作区配置Resolver中的当前实例。 */
    private static volatile RuntimeConfigResolver current;

    /** 键路径列表的统一常量值。 */
    private static final Map<String, String> KEY_PATHS = buildKeyPaths();

    /** 记录工作区配置Resolver中的配置文件。 */
    private final File configFile;

    /** 记录工作区配置Resolver中的最近一次加载时间。 */
    private volatile long lastLoadedAt;

    /** 保存文件值s映射，便于按键快速查询。 */
    private volatile Map<String, Object> fileValues = Collections.emptyMap();

    /** 创建工作区配置Resolver实例，并注入运行所需依赖。 */
    private RuntimeConfigResolver(File configFile) {
        this.configFile = configFile;
        reload();
    }

    /** 基于已解析的工作区目录初始化全局解析器。 */
    public static RuntimeConfigResolver initialize(String workspaceHome) {
        File homeDir = resolveWorkspaceHome(workspaceHome);
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

    /** 返回当前解析器；若尚未初始化，则使用默认工作区目录。 */
    public static RuntimeConfigResolver getInstance() {
        RuntimeConfigResolver instance = current;
        if (instance == null) {
            instance = initialize(RuntimePathConstants.DEFAULT_WORKSPACE);
        } else {
            instance.reloadIfNeeded();
        }
        return instance;
    }

    /** 按白名单键读取生效配置值。 */
    public static String getValue(String key) {
        return getInstance().get(key);
    }

    /** 按配置路径读取原始配置值，保留 List/Map 类型。 */
    public static Object getRawValue(String key) {
        return getInstance().getRaw(key);
    }

    /**
     * 执行cfgGet相关逻辑。
     *
     * @param path 文件或目录路径。
     * @param defaultValue 默认值参数。
     * @return 返回cfg Get结果。
     */
    public static Object cfgGet(String path, Object defaultValue) {
        return getInstance().getByPath(path, defaultValue);
    }

    /** 返回 workspace/config.yml 文件路径。 */
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

    /** 返回 workspace/config.yml 中的文件值快照。 */
    public Map<String, String> fileValues() {
        reloadIfNeeded();
        Map<String, String> result = new LinkedHashMap<String, String>();
        Set<String> emitted = new HashSet<String>();
        for (Map.Entry<String, String> entry : KEY_PATHS.entrySet()) {
            String value = stringify(fileValues.get(entry.getValue()));
            if (value != null) {
                result.put(entry.getValue(), value);
                emitted.add(entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry : fileValues.entrySet()) {
            if (emitted.contains(entry.getKey())) {
                continue;
            }
            String value = stringify(entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
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

    /** 生成 workspace/config.yml 与生效 AppConfig 的漂移诊断快照。 */
    public Map<String, Object> diagnostics(AppConfig appConfig) {
        reloadIfNeeded();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> unknownKeys = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> effectiveDiffs = new ArrayList<Map<String, Object>>();
        List<String> knownPrefixes = knownDynamicPrefixes(appConfig);
        for (Map.Entry<String, Object> entry : fileValues.entrySet()) {
            String key = entry.getKey();
            if (!isKnownConfigKey(key, knownPrefixes)) {
                unknownKeys.add(configKeyItem(key, entry.getValue(), "unknown"));
            }
        }
        Map<String, Object> effective =
                appConfig == null
                        ? Collections.<String, Object>emptyMap()
                        : flattenAppConfig(appConfig);
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
                diff.put("raw_value", safeConfigValue(rawKey, rawValue));
                diff.put("effective_value", safeConfigValue(effectiveKey, effectiveValue));
                effectiveDiffs.add(diff);
            }
        }
        result.put("config_file", configFileReference());
        result.put("raw_key_count", Integer.valueOf(fileValues.size()));
        result.put("unknown_keys", unknownKeys);
        result.put("effective_diffs", effectiveDiffs);
        result.put("unknown_count", Integer.valueOf(unknownKeys.size()));
        result.put("effective_diff_count", Integer.valueOf(effectiveDiffs.size()));
        result.put(
                "has_issues", Boolean.valueOf(!unknownKeys.isEmpty() || !effectiveDiffs.isEmpty()));
        return result;
    }

    /** 设置 workspace/config.yml 中的键值。 */
    public synchronized void setFileValue(String key, String value) {
        String path = requirePath(key);
        validateSecretFileValue(path, value);
        Map<String, Object> root = loadYamlRoot();
        setNestedValue(root, path, StrUtil.nullToEmpty(value));
        write(root);
    }

    /**
     * 写入 workspace/config.yml 中的列表值，用于需要保留 YAML 结构的配置项。
     *
     * @param key 配置键。
     * @param values 列表值；调用方负责保证元素结构符合业务配置。
     */
    public synchronized void setFileList(String key, List<? extends Map<String, String>> values) {
        String path = requirePath(key);
        Map<String, Object> root = loadYamlRoot();
        List<Object> copy = new ArrayList<Object>();
        if (values != null) {
            for (Map<String, String> value : values) {
                if (value == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, String> entry : value.entrySet()) {
                    if (StrUtil.isBlank(entry.getKey())) {
                        continue;
                    }
                    item.put(entry.getKey(), StrUtil.nullToEmpty(entry.getValue()).trim());
                }
                copy.add(item);
            }
        }
        setNestedValue(root, path, copy);
        write(root);
    }

    /** 删除 workspace/config.yml 中的键值。 */
    public synchronized void removeFileValue(String key) {
        String path = requirePath(key);
        Map<String, Object> root = loadYamlRoot();
        removeNestedValue(root, path);
        write(root);
    }

    /** 强制重载 workspace/config.yml。 */
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
                ConfigFlattenSupport.flatten(
                        "", BasicValueSupport.sanitizeMap((Map<?, ?>) parsed), flattened);
            }
            fileValues = flattened;
            lastLoadedAt = configFile.lastModified();
        } catch (Exception e) {
            lastLoadedAt = configFile.lastModified();
        }
    }

    /** 执行reloadIfNeeded相关逻辑。 */
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

    /**
     * 加载YAML根用户。
     *
     * @return 返回YAML根用户结果。
     */
    private Map<String, Object> loadYamlRoot() {
        if (!configFile.exists()) {
            return new LinkedHashMap<String, Object>();
        }
        Object parsed = new Yaml().load(FileUtil.readUtf8String(configFile));
        if (!(parsed instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        return BasicValueSupport.sanitizeMap((Map<?, ?>) parsed);
    }

    /**
     * 执行写入相关逻辑。
     *
     * @param root root 参数。
     */
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
            throw new IllegalStateException("Failed to write workspace config", e);
        }
        reload();
    }

    /**
     * 要求路径。
     *
     * @param key 配置键或映射键。
     * @return 返回路径。
     */
    private String requirePath(String key) {
        String path = resolvePath(key);
        if (StrUtil.isBlank(path)) {
            throw new IllegalStateException("Unsupported config key: " + key);
        }
        return path;
    }

    /**
     * 校验密钥配置写入值，避免展示态占位符落盘覆盖真实凭据。
     *
     * @param path 配置文件中的规范化路径。
     * @param value 待写入值。
     */
    private void validateSecretFileValue(String path, String value) {
        if (isSecretKey(path) && SecretValueGuard.isPlaceholderSecret(value)) {
            throw new IllegalArgumentException(path + " 不能使用示例或占位符密钥。");
        }
    }

    /**
     * 执行配置文件引用相关逻辑。
     *
     * @return 返回配置文件Reference结果。
     */
    private String configFileReference() {
        return "workspace://config.yml";
    }

    /**
     * 执行配置键Item相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param reason 原因参数。
     * @return 返回配置键Item结果。
     */
    private static Map<String, Object> configKeyItem(String key, Object value, String reason) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("key", key);
        item.put("reason", reason);
        item.put("value", safeConfigValue(key, value));
        return item;
    }

    /**
     * 生成安全展示用的配置值。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回safe配置Value结果。
     */
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
                    result.put(
                            String.valueOf(entry.getKey()),
                            safeConfigValue(childKey, entry.getValue()));
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
            return "path://"
                    + (StrUtil.isBlank(name) ? "external" : SecretRedactor.redact(name, 200));
        }
        return text;
    }

    /**
     * 判断是否具有路径值特征。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回looks Like路径Value结果。
     */
    private static boolean looksLikePathValue(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        if (text.length() < 2 || text.contains("\n") || text.contains("\r")) {
            return false;
        }
        if (text.startsWith("workspace://") || text.startsWith("path://")) {
            return false;
        }
        return text.startsWith("/")
                || text.startsWith("~/")
                || text.startsWith("./")
                || text.startsWith("../")
                || text.matches("^[A-Za-z]:[\\\\/].*");
    }

    /**
     * 判断是否密钥键。
     *
     * @param key 配置键或映射键。
     * @return 如果密钥键满足条件则返回 true，否则返回 false。
     */
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

    /**
     * 判断是否启动级路径键；这些键只允许来自启动配置，不能写入 workspace/config.yml。
     *
     * @param key 配置键或映射键。
     * @return 如果启动级路径键满足条件则返回 true，否则返回 false。
     */
    private static boolean isStartupPathKey(String key) {
        return "solonclaw.workspace".equals(key);
    }

    /**
     * 判断是否Known配置键。
     *
     * @param key 配置键或映射键。
     * @param dynamicPrefixes dynamicPrefixes 参数。
     * @return 如果Known配置键满足条件则返回 true，否则返回 false。
     */
    private static boolean isKnownConfigKey(String key, List<String> dynamicPrefixes) {
        if (KEY_PATHS.containsKey(key) || KEY_PATHS.containsValue(key)) {
            return true;
        }
        if ("fallbackProviders".equals(key)) {
            return true;
        }
        for (String prefix : dynamicPrefixes) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行knownDynamicPrefixes相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回known Dynamic Prefixes结果。
     */
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
        addDynamicPrefix(prefixes, "solonclaw.scheduler.enabledToolsets");
        addDynamicPrefix(prefixes, "security.fileGuardrailMode");
        addDynamicPrefix(prefixes, "security.urlGuardrailMode");
        addDynamicPrefix(prefixes, "security.guardrailMode");
        addDynamicPrefix(prefixes, "security.guardrailCronMode");
        addDynamicPrefix(prefixes, "security.guardrailCronScope");
        addDynamicPrefix(prefixes, "security.hardlineAllowlist");
        addDynamicPrefix(prefixes, "solonclaw.task.restartDrainTimeoutSeconds");
        addDynamicPrefix(prefixes, "solonclaw.terminal.shellInitFiles");
        addDynamicPrefix(prefixes, "solonclaw.terminal.autoSourceBashrc");
        addDynamicPrefix(prefixes, "solonclaw.terminal.processWaitTimeoutSeconds");
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

    /**
     * 追加DynamicPrefix。
     *
     * @param prefixes prefixes 参数。
     * @param prefix prefix 参数。
     */
    private static void addDynamicPrefix(List<String> prefixes, String prefix) {
        if (StrUtil.isBlank(prefix) || prefixes.contains(prefix)) {
            return;
        }
        prefixes.add(prefix);
    }

    /**
     * 执行生效路径相关逻辑。
     *
     * @param rawKey 原始键标识或键值。
     * @return 返回生效路径。
     */
    private static String effectivePath(String rawKey) {
        if (StrUtil.isBlank(rawKey) || isStartupPathKey(rawKey)) {
            return null;
        }
        String mapped = KEY_PATHS.get(rawKey);
        if (StrUtil.isNotBlank(mapped)) {
            return appConfigPath(mapped);
        }
        return appConfigPath(rawKey);
    }

    /**
     * 执行应用配置路径相关逻辑。
     *
     * @param configKey 配置键标识或键值。
     * @return 返回app配置路径。
     */
    private static String appConfigPath(String configKey) {
        if (StrUtil.isBlank(configKey)) {
            return null;
        }
        if (configKey.startsWith("solonclaw.")) {
            return configKey.substring("solonclaw.".length());
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

    /**
     * 执行same配置值相关逻辑。
     *
     * @param rawValue 原始值参数。
     * @param effectiveValue effective值参数。
     * @return 返回same配置Value结果。
     */
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
            } catch (Exception e) {
                logConfigFallback("compare-number", "diagnostics.effective_diffs", e);
                return normalizeValueText(rawValue).equals(normalizeValueText(effectiveValue));
            }
        }
        if (rawValue instanceof Boolean || effectiveValue instanceof Boolean) {
            return Boolean.valueOf(parseBoolean(rawValue))
                    .equals(Boolean.valueOf(parseBoolean(effectiveValue)));
        }
        return normalizeValueText(rawValue).equals(normalizeValueText(effectiveValue));
    }

    /**
     * 解析Boolean。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回解析后的Boolean。
     */
    private static boolean parseBoolean(Object value) {
        String text = StrUtil.nullToEmpty(String.valueOf(value)).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    /**
     * 规范化Value Text。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回Value Text结果。
     */
    private static String normalizeValueText(Object value) {
        if (value instanceof List || value instanceof Map) {
            return ONode.serialize(value);
        }
        return StrUtil.nullToEmpty(String.valueOf(value)).trim();
    }

    /**
     * 执行flatten应用配置相关逻辑。
     *
     * @param appConfig 应用运行配置。
     * @return 返回flatten App配置。
     */
    private static Map<String, Object> flattenAppConfig(AppConfig appConfig) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        flattenBean("", appConfig, result, new HashSet<Object>());
        return result;
    }

    /**
     * 执行flattenBean相关逻辑。
     *
     * @param prefix prefix 参数。
     * @param bean bean 参数。
     * @param output 命令执行输出文本。
     * @param visited visited 参数。
     */
    private static void flattenBean(
            String prefix, Object bean, Map<String, Object> output, Set<Object> visited) {
        if (bean == null || visited.contains(bean)) {
            return;
        }
        visited.add(bean);
        try {
            for (PropertyDescriptor descriptor :
                    Introspector.getBeanInfo(bean.getClass()).getPropertyDescriptors()) {
                Method getter = descriptor.getReadMethod();
                String name = descriptor.getName();
                if (getter == null || "class".equals(name)) {
                    continue;
                }
                Object value = getter.invoke(bean);
                String key = prefix.length() == 0 ? name : prefix + "." + name;
                flattenValue(key, value, output, visited);
            }
        } catch (Exception e) {
            logConfigFallback("flatten-bean", StrUtil.blankToDefault(prefix, "root"), e);
        }
    }

    /**
     * 记录配置诊断中的可恢复失败，仅输出阶段、配置键和异常类型，避免配置值或凭据进入日志。
     *
     * @param stage 解析或诊断阶段。
     * @param key 配置键或诊断路径。
     * @param error 捕获到的异常。
     */
    private static void logConfigFallback(String stage, String key, Throwable error) {
        log.debug(
                "Runtime config resolver fallback: stage={}, key={}, errorType={}",
                StrUtil.blankToDefault(stage, "unknown"),
                StrUtil.blankToDefault(key, "unknown"),
                exceptionType(error));
    }

    /**
     * 提取低敏异常类型，禁止把异常消息或堆栈写入工作区配置解析日志。
     *
     * @param error 捕获到的异常。
     * @return 异常类型名称。
     */
    private static String exceptionType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    /**
     * 执行flatten值相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @param output 命令执行输出文本。
     * @param visited visited 参数。
     */
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

    /**
     * 判断是否Scalar。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Scalar满足条件则返回 true，否则返回 false。
     */
    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum();
    }

    /**
     * 解析路径。
     *
     * @param key 配置键或映射键。
     * @return 返回解析后的路径。
     */
    private String resolvePath(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        String mapped = KEY_PATHS.get(key);
        if (StrUtil.isNotBlank(mapped)) {
            return mapped;
        }
        if (isStartupPathKey(key)) {
            return null;
        }
        if ("fallbackProviders".equals(key)) {
            return key;
        }
        if (isKnownConfigKey(key, knownDynamicPrefixes(null))) {
            return key;
        }
        return null;
    }

    /**
     * 执行stringify相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回stringify结果。
     */
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

    /**
     * 写入Nested Value。
     *
     * @param root root 参数。
     * @param path 文件或目录路径。
     * @param value 待规范化或校验的原始值。
     */
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

    /**
     * 移除Nested Value。
     *
     * @param root root 参数。
     * @param path 文件或目录路径。
     * @return 返回Nested Value结果。
     */
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

    /**
     * 解析工作区主目录。
     *
     * @param workspaceHome 工作区目录参数。
     * @return 返回解析后的工作区目录。
     */
    private static File resolveWorkspaceHome(String workspaceHome) {
        String raw = StrUtil.blankToDefault(workspaceHome, RuntimePathConstants.DEFAULT_WORKSPACE);
        File file = new File(raw);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(System.getProperty("user.dir"), raw);
    }

    /**
     * 构建键Paths。
     *
     * @return 返回创建好的键Paths。
     */
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
                "solonclaw.llm.contextFallbackTokens",
                "solonclaw.llm.modelsDevRefreshEnabled",
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
                "solonclaw.display.toolPreviewLength",
                "solonclaw.display.progressThrottleMs",
                "solonclaw.display.metadataFooter.enabled",
                "solonclaw.display.metadataFooter.fields",
                "solonclaw.gateway.allowedUsers",
                "solonclaw.gateway.allowAllUsers",
                "solonclaw.gateway.injectionSecret",
                "solonclaw.gateway.injectionMaxBodyBytes",
                "solonclaw.gateway.injectionReplayWindowSeconds",
                "solonclaw.gateway.processingReactionsEnabled",
                "solonclaw.dashboard.accessToken",
                "solonclaw.agent.heartbeat.intervalMinutes",
                "solonclaw.proactive.enabled",
                "solonclaw.proactive.intervalMinutes",
                "solonclaw.proactive.initialDelaySeconds",
                "solonclaw.proactive.dailyMaxContacts",
                "solonclaw.proactive.cooldownMinutes",
                "solonclaw.proactive.quietStartHour",
                "solonclaw.proactive.quietEndHour",
                "solonclaw.proactive.minConfidenceToContact",
                "solonclaw.proactive.llmDecisionEnabled",
                "solonclaw.proactive.llmPolishEnabled",
                "solonclaw.proactive.maxCandidatesPerTick",
                "solonclaw.proactive.maxContactsPerTick",
                "solonclaw.proactive.candidateTtlHours",
                "solonclaw.proactive.repositoryCheckEnabled",
                "solonclaw.proactive.repositoryCheckIntervalMinutes",
                "solonclaw.proactive.sessionLookbackDays",
                "solonclaw.proactive.runLookbackDays",
                "solonclaw.proactive.cronLookbackDays",
                "solonclaw.proactive.careCheckinEnabled",
                "solonclaw.proactive.careCheckinAfterIdleHours",
                "solonclaw.proactive.deliveryPreviewPrefix",
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
                "solonclaw.task.mediaCacheTtlHours",
                "solonclaw.browser.rewriteLoopbackUrls",
                "solonclaw.browser.loopbackHostAlias",
                "security.allowPrivateUrls",
                "security.websiteBlocklist.enabled",
                "security.websiteBlocklist.domains",
                "security.websiteBlocklist.sharedFiles",
                "security.tirithEnabled",
                "security.tirithPath",
                "security.tirithTimeoutSeconds",
                "security.tirithFailOpen",
                "security.fileGuardrailMode",
                "security.urlGuardrailMode",
                "security.guardrailMode",
                "security.guardrailCronMode",
                "security.guardrailCronScope",
                "security.hardlineAllowlist",
                "solonclaw.web.searchBackend",
                "solonclaw.web.braveSearchApiKey",
                "approvals.subagentAutoApprove",
                "approvals.timeoutSeconds",
                "approvals.gatewayTimeoutSeconds",
                "approvals.mcpReloadConfirm",
                "solonclaw.terminal.credentialFiles",
                "solonclaw.terminal.envPassthrough",
                "solonclaw.terminal.sudoPassword",
                "solonclaw.terminal.shellInitFiles",
                "solonclaw.terminal.autoSourceBashrc",
                "solonclaw.terminal.processWaitTimeoutSeconds",
                "solonclaw.terminal.maxForegroundTimeoutSeconds",
                "solonclaw.terminal.foregroundMaxRetries",
                "solonclaw.terminal.foregroundRetryBaseDelaySeconds",
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
        addChannelMappings(
                mappings,
                "feishu",
                "appId",
                "appSecret",
                "domain",
                "websocketUrl",
                "botOpenId",
                "botUserId",
                "botName",
                "toolProgress");
        addAll(
                mappings,
                "solonclaw.channels.feishu.comment.enabled",
                "solonclaw.channels.feishu.comment.pairingFile",
                "solonclaw.display.platforms.feishu.metadataFooter.enabled");
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
                "solonclaw.display.platforms.dingtalk.metadataFooter.enabled");
        addChannelMappings(mappings, "wecom", "botId", "secret", "websocketUrl", "toolProgress");
        addAll(
                mappings,
                "solonclaw.channels.wecom.groupMemberAllowedUsers",
                "solonclaw.display.platforms.wecom.metadataFooter.enabled");
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
        add(mappings, "solonclaw.display.platforms.weixin.metadataFooter.enabled");
        addChannelMappings(
                mappings,
                "qqbot",
                "appId",
                "clientSecret",
                "apiDomain",
                "websocketUrl",
                "markdownSupport",
                "toolProgress");
        add(mappings, "solonclaw.display.platforms.qqbot.metadataFooter.enabled");
        addChannelMappings(
                mappings,
                "yuanbao",
                "appId",
                "appSecret",
                "botId",
                "apiDomain",
                "websocketUrl",
                "toolProgress");
        add(mappings, "solonclaw.display.platforms.yuanbao.metadataFooter.enabled");
        return mappings;
    }

    /**
     * 追加渠道映射pings。
     *
     * @param mappings mappings 参数。
     * @param channelName 渠道名称参数。
     * @param extraFields extraFields 参数。
     */
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
                base + "allowedChats",
                base + "requireMention",
                base + "freeResponseChats");
        for (String field : extraFields) {
            add(mappings, base + field);
        }
    }

    /**
     * 追加全部。
     *
     * @param mappings mappings 参数。
     * @param paths 文件或目录路径参数。
     */
    private static void addAll(Map<String, String> mappings, String... paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            add(mappings, path);
        }
    }

    /**
     * 执行add相关逻辑。
     *
     * @param mappings mappings 参数。
     * @param path 文件或目录路径。
     */
    private static void add(Map<String, String> mappings, String path) {
        mappings.put(path, path);
    }
}
