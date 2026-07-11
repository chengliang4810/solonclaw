package com.jimuqu.solon.claw.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import org.noear.snack4.ONode;

/**
 * 模型上下文长度持久化缓存，按 {@code model@baseUrl} 键存储在线探测或解析得到的具体值。
 *
 * <p>对齐外部对标仓库的 {@code context_length_cache.yaml} 机制：同一模型在不同提供方下可能有不同的上下文上限， 因此缓存键同时绑定模型名和
 * baseUrl。只在内存维护一份读写索引，首次访问时懒加载磁盘文件，写入时原子替换。
 */
public class ModelContextCacheStore {
    /** 日志记录器。 */
    private static final Log log = LogFactory.get();

    /** 合法上下文长度的最小值，低于该值的探测结果不落盘，避免把异常小值固化为缓存。 */
    private static final int MINIMUM_CACHEABLE_CONTEXT_LENGTH = 64000;

    /** 缓存文件名，存放在工作区 cache 目录下。 */
    private static final String CACHE_FILE_NAME = "context-length-cache.json";

    /** 缓存目录，通常为 workspace/cache。 */
    private final File cacheDir;

    /** 内存中的缓存条目，懒加载。 */
    private Map<String, Integer> entries;

    /** 是否已完成首次磁盘加载。 */
    private volatile boolean loaded;

    /**
     * 创建模型上下文长度持久化缓存。
     *
     * @param cacheDir 工作区缓存目录，为空时回退到用户目录下的默认缓存位置。
     */
    public ModelContextCacheStore(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * 读取缓存中指定模型和提供方的上下文长度。
     *
     * @param model 已规范化的模型名。
     * @param baseUrl 提供方 baseUrl，用于区分同一模型在不同提供方下的差异。
     * @return 命中时返回上下文长度，未命中返回空。
     */
    public OptionalInt get(String model, String baseUrl) {
        if (StrUtil.isBlank(model)) {
            return OptionalInt.empty();
        }
        ensureLoaded();
        Integer value = entries.get(cacheKey(model, baseUrl));
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    /**
     * 写入上下文长度缓存，仅在值合法时落盘。
     *
     * @param model 已规范化的模型名。
     * @param baseUrl 提供方 baseUrl。
     * @param length 探测到的上下文长度。
     */
    public void save(String model, String baseUrl, int length) {
        if (StrUtil.isBlank(model) || length < MINIMUM_CACHEABLE_CONTEXT_LENGTH) {
            return;
        }
        ensureLoaded();
        String key = cacheKey(model, baseUrl);
        Integer existing = entries.get(key);
        if (existing != null && existing == length) {
            return;
        }
        entries.put(key, length);
        persist();
    }

    /**
     * 删除指定模型和提供方的缓存条目。
     *
     * @param model 已规范化的模型名。
     * @param baseUrl 提供方 baseUrl。
     */
    public void invalidate(String model, String baseUrl) {
        if (StrUtil.isBlank(model)) {
            return;
        }
        ensureLoaded();
        String key = cacheKey(model, baseUrl);
        if (entries.remove(key) != null) {
            persist();
        }
    }

    /**
     * 生成缓存键，对齐外部对标仓库的 {@code model@baseUrl} 格式。
     *
     * @param model 模型名。
     * @param baseUrl 提供方 baseUrl。
     * @return 返回缓存键。
     */
    private String cacheKey(String model, String baseUrl) {
        String normalizedBase = StrUtil.nullToEmpty(baseUrl).trim();
        if (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        return normalizedBase.length() == 0 ? model : model + "@" + normalizedBase;
    }

    /** 懒加载磁盘缓存到内存，文件不存在或解析失败时使用空 Map。 */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            Map<String, Integer> disk = loadFromDisk();
            entries = disk != null ? new ConcurrentHashMap<>(disk) : new ConcurrentHashMap<>();
            loaded = true;
        }
    }

    /**
     * 从磁盘加载缓存条目。
     *
     * @return 返回磁盘缓存条目，文件不存在或损坏时返回 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> loadFromDisk() {
        File file = cacheFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            String json = FileUtil.readUtf8String(file);
            Object parsed = ONode.deserialize(json, Object.class);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Object entriesObj = ((Map<String, Object>) parsed).get("entries");
            if (!(entriesObj instanceof Map)) {
                return null;
            }
            Map<String, Integer> result = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) entriesObj).entrySet()) {
                if (entry.getValue() instanceof Number) {
                    int value = ((Number) entry.getValue()).intValue();
                    if (value > 0) {
                        result.put(entry.getKey(), value);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("读取模型上下文长度缓存失败，已忽略损坏文件: {}", e.getMessage());
            return null;
        }
    }

    /** 将内存缓存原子写入磁盘，先写临时文件再 rename，避免写中断导致文件损坏。 */
    private void persist() {
        File file = cacheFile();
        if (file == null) {
            return;
        }
        try {
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("entries", entries);
            String json = ONode.serialize(root);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.debug("写入模型上下文长度缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 解析缓存文件句柄，缓存目录未配置时返回 null。
     *
     * @return 返回缓存文件。
     */
    private File cacheFile() {
        if (cacheDir == null) {
            return null;
        }
        return new File(cacheDir, CACHE_FILE_NAME);
    }
}
