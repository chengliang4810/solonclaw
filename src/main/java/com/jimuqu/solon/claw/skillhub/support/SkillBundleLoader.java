package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.noear.snack4.ONode;
import org.yaml.snakeyaml.Yaml;

/** 负责读取、保存和缓存本地 Skills Hub 技能包清单。 */
public class SkillBundleLoader {
    /** 技能包清单加载器的低敏日志记录器。 */
    private static final Logger LOG = Logger.getLogger(SkillBundleLoader.class.getName());

    /** 技能包清单目录，位于运行时 skills 目录下的 bundles 子目录。 */
    private final File bundlesDir;

    /** 最近一次加载成功的技能包清单缓存。 */
    private volatile List<SkillBundle> cachedBundles;

    /** 缓存对应的目录最新修改时间，用于避免重复解析清单文件。 */
    private volatile long cachedLastTouchedAt = Long.MIN_VALUE;

    /**
     * 创建技能包Loader实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public SkillBundleLoader(AppConfig appConfig) {
        this.bundlesDir = new File(appConfig.getRuntime().getSkillsDir(), "bundles");
    }

    /**
     * 列出当前本地缓存中的技能包。
     *
     * @return 技能包清单副本，调用方修改不会污染缓存。
     */
    public List<SkillBundle> listBundles() {
        long lastTouchedAt = lastTouchedAt();
        List<SkillBundle> cached = cachedBundles;
        if (cached != null && cachedLastTouchedAt == lastTouchedAt) {
            return new ArrayList<SkillBundle>(cached);
        }
        return reload();
    }

    /**
     * 重新扫描并解析 bundles 目录。
     *
     * @return 解析后的技能包清单副本。
     */
    public synchronized List<SkillBundle> reload() {
        List<SkillBundle> bundles = new ArrayList<SkillBundle>();
        Map<String, SkillBundle> bySlug = new LinkedHashMap<String, SkillBundle>();
        File[] files = bundleFiles();
        if (files != null) {
            for (File file : files) {
                SkillBundle bundle = loadBundle(file);
                if (bundle == null || StrUtil.isBlank(bundle.getName())) {
                    continue;
                }
                String slug = slugify(bundle.getName());
                if (StrUtil.isBlank(slug) || bySlug.containsKey(slug)) {
                    continue;
                }
                bySlug.put(slug, bundle);
                bundles.add(bundle);
            }
        }
        cachedBundles = bundles;
        cachedLastTouchedAt = lastTouchedAt(files);
        return new ArrayList<SkillBundle>(bundles);
    }

    /**
     * 按名称读取技能包。
     *
     * @return 名称规范化后匹配的技能包；不存在时返回 null。
     */
    public SkillBundle getBundle(String name) {
        String slug = slugify(name);
        if (StrUtil.isBlank(slug)) {
            return null;
        }
        for (SkillBundle bundle : listBundles()) {
            if (slug.equals(slugify(bundle.getName()))) {
                return bundle;
            }
        }
        return null;
    }

    /** 保存技能包清单文件。 */
    public synchronized void saveBundle(SkillBundle bundle) {
        FileUtil.mkdir(bundlesDir);
        String slug = slugify(bundle == null ? null : bundle.getName());
        if (StrUtil.isBlank(slug)) {
            throw new IllegalArgumentException("Bundle name is required");
        }
        File file = new File(bundlesDir, slug + ".json");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("name", bundle.getName());
        data.put("files", bundle.getFiles());
        if (StrUtil.isNotBlank(bundle.getSource())) {
            data.put("source", bundle.getSource());
        }
        if (CollUtil.isNotEmpty(bundle.getMetadata())) {
            data.put("metadata", bundle.getMetadata());
        }
        String json = ONode.serialize(data);
        FileUtil.writeString(json, file, StandardCharsets.UTF_8);
        invalidateCache();
    }

    /**
     * 删除本地技能包清单。
     *
     * @param name 技能包名称或名称 slug。
     * @return 找到并删除清单文件时返回 true。
     */
    public synchronized boolean deleteBundle(String name) {
        File file = findBundleFile(name);
        if (file != null && file.isFile()) {
            file.delete();
            invalidateCache();
            return true;
        }
        return false;
    }

    /**
     * 生成技能包缓存摘要。
     *
     * @return 包含数量、目录和每个包基础信息的摘要 Map。
     */
    public Map<String, Object> summary() {
        List<SkillBundle> bundles = listBundles();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", Integer.valueOf(bundles.size()));
        result.put("directory", bundlesDir.getAbsolutePath());
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (SkillBundle bundle : bundles) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", bundle.getName());
            item.put("skillCount", Integer.valueOf(bundle.getFiles().size()));
            item.put("source", StrUtil.blankToDefault(bundle.getSource(), "local"));
            items.add(item);
        }
        result.put("bundles", items);
        return result;
    }

    /**
     * 按名称查找技能包清单文件。
     *
     * @param name 技能包名称或名称 slug。
     * @return 文件名或清单内名称匹配的清单文件。
     */
    private File findBundleFile(String name) {
        String slug = slugify(name);
        if (StrUtil.isBlank(slug)) {
            return null;
        }
        File[] files = bundleFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String base = file.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            if (slug.equals(slugify(base))) {
                return file;
            }
            SkillBundle bundle = loadBundle(file);
            if (bundle != null && slug.equals(slugify(bundle.getName()))) {
                return file;
            }
        }
        return null;
    }

    /**
     * 从单个 JSON/YAML 文件中加载技能包。
     *
     * @return 解析成功的技能包；内容无效时返回 null。
     */
    @SuppressWarnings("unchecked")
    private SkillBundle loadBundle(File file) {
        try {
            String content = FileUtil.readString(file, StandardCharsets.UTF_8);
            if (StrUtil.isBlank(content)) {
                return null;
            }
            Object parsed = parseBundleContent(file, content);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<String, Object> data = (Map<String, Object>) parsed;
            SkillBundle bundle = new SkillBundle();
            bundle.setName(StrUtil.nullToEmpty(stringValue(data.get("name"))).trim());
            if (StrUtil.isBlank(bundle.getName())) {
                String fileName = file.getName();
                int dot = fileName.lastIndexOf('.');
                bundle.setName(dot > 0 ? fileName.substring(0, dot) : fileName);
            }
            Object filesObj = data.get("files");
            if (filesObj instanceof Map) {
                Map<String, String> files = new LinkedHashMap<String, String>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) filesObj).entrySet()) {
                    files.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                bundle.setFiles(files);
            }
            bundle.setSource(stringValue(data.get("source")));
            bundle.setIdentifier(stringValue(data.get("identifier")));
            bundle.setTrustLevel(stringValue(data.get("trustLevel")));
            Object meta = data.get("metadata");
            if (meta instanceof Map) {
                bundle.setMetadata(new LinkedHashMap<String, Object>((Map<String, Object>) meta));
            }
            return bundle;
        } catch (RuntimeException e) {
            LOG.fine(
                    "技能包清单解析失败，已跳过该清单：file="
                            + file.getName()
                            + ", errorType="
                            + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 读取并排序 bundles 目录下的清单文件。
     *
     * @return 支持的 JSON/YAML 清单文件数组，目录不存在时返回 null。
     */
    private File[] bundleFiles() {
        if (!bundlesDir.isDirectory()) {
            return null;
        }
        File[] files =
                bundlesDir.listFiles(
                        file -> file.isFile() && isBundleFileName(file.getName().toLowerCase()));
        if (files != null) {
            Arrays.sort(
                    files,
                    new Comparator<File>() {
                        /**
                         * 比较两个对象的排序位置。
                         *
                         * @param left 左侧比较对象。
                         * @param right 右侧比较对象。
                         * @return 按文件名忽略大小写排序后的比较结果。
                         */
                        @Override
                        public int compare(File left, File right) {
                            int compare = left.getName().compareToIgnoreCase(right.getName());
                            if (compare != 0) {
                                return compare;
                            }
                            return left.getAbsolutePath().compareTo(right.getAbsolutePath());
                        }
                    });
        }
        return files;
    }

    /**
     * 判断文件名是否为支持的技能包清单格式。
     *
     * @param name 小写后的文件名。
     * @return 文件名以 json、yml 或 yaml 结尾时返回 true。
     */
    private boolean isBundleFileName(String name) {
        return name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /**
     * 按文件扩展名解析技能包清单内容。
     *
     * @param content 待处理内容。
     * @return YAML 或 JSON 解析后的对象。
     */
    private Object parseBundleContent(File file, String content) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return new Yaml().load(content);
        }
        return ONode.deserialize(content, Object.class);
    }

    /**
     * 读取 bundles 目录和清单文件的最新修改时间。
     *
     * @return 用于缓存失效判断的时间戳。
     */
    private long lastTouchedAt() {
        return lastTouchedAt(bundleFiles());
    }

    /**
     * 基于给定文件数组计算最新修改时间。
     *
     * @param files 当前已发现的技能包清单文件数组。
     * @return 目录或清单文件的最大 lastModified 值。
     */
    private long lastTouchedAt(File[] files) {
        long latest = bundlesDir.exists() ? bundlesDir.lastModified() : 0L;
        if (ArrayUtil.isEmpty(files)) {
            return latest;
        }
        for (File file : files) {
            latest = Math.max(latest, file.lastModified());
        }
        return latest;
    }

    /**
     * 将技能包名称转换为稳定 slug。
     *
     * @param value 待规范化或校验的原始值。
     * @return 仅包含小写字母、数字和连字符的 slug。
     */
    private String slugify(String value) {
        String text = StrUtil.nullToEmpty(value).trim().toLowerCase().replace('_', '-');
        StringBuilder builder = new StringBuilder();
        boolean previousHyphen = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean hyphen = ch == '-' || Character.isWhitespace(ch);
            if (hyphen) {
                if (!previousHyphen && builder.length() > 0) {
                    builder.append('-');
                    previousHyphen = true;
                }
            } else if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
                builder.append(ch);
                previousHyphen = false;
            }
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '-') {
            builder.deleteCharAt(length - 1);
        }
        return builder.toString();
    }

    /**
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return null 保持为 null，其余值转为字符串。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 清空缓存，确保下一次读取重新扫描 bundles 目录。 */
    private void invalidateCache() {
        cachedBundles = null;
        cachedLastTouchedAt = Long.MIN_VALUE;
    }
}
