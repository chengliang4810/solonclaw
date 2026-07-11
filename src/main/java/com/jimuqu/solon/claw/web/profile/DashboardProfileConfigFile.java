package com.jimuqu.solon.claw.web.profile;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.config.ConfigFlattenSupport;
import com.jimuqu.solon.claw.support.BasicValueSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 以单个 Profile 的 config.yml 为边界提供并发安全的读取、写入和配置快照。 */
public final class DashboardProfileConfigFile {
    /** 每个配置文件独立串行化写入，避免不同 Profile 互相阻塞或覆盖。 */
    private static final Map<Path, Object> FILE_LOCKS = new ConcurrentHashMap<Path, Object>();

    /** 当前 Profile 的规范化配置文件。 */
    private final Path configFile;

    /**
     * 创建 Profile 配置文件访问器。
     *
     * @param configFile 目标 Profile 的 config.yml。
     */
    public DashboardProfileConfigFile(Path configFile) {
        if (configFile == null) {
            throw new IllegalArgumentException("Profile config file is required.");
        }
        this.configFile = configFile.toAbsolutePath().normalize();
    }

    /**
     * @return 当前访问器绑定的 config.yml。
     */
    public Path path() {
        return configFile;
    }

    /**
     * 返回指定配置文件的共享锁，供已有 Dashboard 写入路径复用。
     *
     * @param path 配置文件路径。
     * @return 文件级共享锁。
     */
    public static Object lockFor(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return FILE_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
    }

    /**
     * @return 配置根映射的防御性副本。
     */
    public Map<String, Object> readRoot() {
        synchronized (lockFor(configFile)) {
            return readRootUnlocked();
        }
    }

    /**
     * @return 扁平化后的原始配置值。
     */
    public Map<String, Object> readFlat() {
        Map<String, Object> flat = new LinkedHashMap<String, Object>();
        ConfigFlattenSupport.flatten("", readRoot(), flat);
        return flat;
    }

    /**
     * 读取指定 YAML 路径并转换为 Dashboard 文本值。
     *
     * @param path 点分隔配置路径。
     * @return 未配置时返回 null。
     */
    public String get(String path) {
        Object value = readFlat().get(path);
        if (value == null) {
            return null;
        }
        if (value instanceof List || value instanceof Map) {
            return ONode.serialize(value);
        }
        return String.valueOf(value).trim();
    }

    /**
     * 写入指定 YAML 路径。
     *
     * @param path 点分隔配置路径。
     * @param value 待写入文本。
     */
    public void set(String path, String value) {
        synchronized (lockFor(configFile)) {
            Map<String, Object> root = readRootUnlocked();
            setNested(root, requirePath(path), StrUtil.nullToEmpty(value));
            writeRootUnlocked(root);
        }
    }

    /**
     * 删除指定 YAML 路径。
     *
     * @param path 点分隔配置路径。
     */
    public void remove(String path) {
        synchronized (lockFor(configFile)) {
            Map<String, Object> root = readRootUnlocked();
            removeNested(root, requirePath(path));
            writeRootUnlocked(root);
        }
    }

    /**
     * 原子替换整个配置根映射。
     *
     * @param root 已完成业务校验的配置根。
     */
    public void writeRoot(Map<String, Object> root) {
        synchronized (lockFor(configFile)) {
            writeRootUnlocked(
                    root == null
                            ? new LinkedHashMap<String, Object>()
                            : BasicValueSupport.sanitizeMap(root));
        }
    }

    /**
     * 把 Profile 配置加载为独立 AppConfig 快照，不修改全局解析器或 JVM 属性。
     *
     * @param home Profile 工作区根目录。
     * @return 只属于该 Profile 的配置快照。
     */
    public AppConfig loadAppConfig(Path home) {
        Props props = new Props();
        props.put("solonclaw.workspace", home.toAbsolutePath().normalize().toString());
        return AppConfig.loadDetached(props);
    }

    /**
     * 生成 Profile 独立配置的低敏诊断摘要。
     *
     * @return 与现有配置诊断字段兼容的摘要。
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> flat = readFlat();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("config_file", "workspace://config.yml");
        result.put("raw_key_count", Integer.valueOf(flat.size()));
        result.put("unknown_keys", Collections.emptyList());
        result.put("effective_diffs", Collections.emptyList());
        result.put("unknown_count", Integer.valueOf(0));
        result.put("effective_diff_count", Integer.valueOf(0));
        result.put("has_issues", Boolean.FALSE);
        return result;
    }

    /** 读取 YAML 根映射，调用方必须持有文件锁。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readRootUnlocked() {
        if (!Files.isRegularFile(configFile)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object parsed =
                    new Yaml(new SafeConstructor(options))
                            .load(
                                    new String(
                                            Files.readAllBytes(configFile),
                                            StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                return new LinkedHashMap<String, Object>();
            }
            return BasicValueSupport.sanitizeMap((Map<?, ?>) parsed);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read profile config file.", e);
        }
    }

    /** 原子写入 YAML，调用方必须持有文件锁。 */
    private void writeRootUnlocked(Map<String, Object> root) {
        try {
            Files.createDirectories(configFile.getParent());
            Path temp = configFile.resolveSibling(configFile.getFileName().toString() + ".tmp");
            Files.write(temp, yaml().dump(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                        temp,
                        configFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write profile config file.", e);
        }
    }

    /** 创建稳定的块状 YAML 输出器。 */
    private Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(1);
        return new Yaml(options);
    }

    /** 写入嵌套配置值。 */
    @SuppressWarnings("unchecked")
    private void setNested(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = cursor.get(parts[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], child);
            }
            cursor = (Map<String, Object>) child;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    /** 删除嵌套配置值并清理空父节点。 */
    @SuppressWarnings("unchecked")
    private boolean removeNested(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = cursor.get(parts[i]);
            if (!(child instanceof Map)) {
                return false;
            }
            cursor = (Map<String, Object>) child;
        }
        return cursor.remove(parts[parts.length - 1]) != null;
    }

    /** 校验点分隔路径，避免空路径写到配置根。 */
    private String requirePath(String path) {
        String normalized = StrUtil.nullToEmpty(path).trim();
        if (normalized.length() == 0
                || normalized.startsWith(".")
                || normalized.endsWith(".")
                || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid profile config path: " + path);
        }
        return normalized;
    }

    /** 判断配置键是否包含凭据语义，供诊断展示时使用。 */
    public static boolean isSecretKey(String key) {
        String normalized = StrUtil.nullToEmpty(key).toLowerCase(Locale.ROOT);
        return normalized.contains("apikey")
                || normalized.contains("api_key")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("private_key");
    }

    /** 返回低敏配置值，禁止诊断接口泄露 Profile 密钥。 */
    public static Object safeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        return isSecretKey(key) ? "***" : SecretRedactor.redact(String.valueOf(value), 800);
    }
}
