package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
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
import org.noear.snack4.ONode;
import org.yaml.snakeyaml.Yaml;

/** 承载技能包Loader相关状态和辅助逻辑。 */
public class SkillBundleLoader {
    /** 记录技能包Loader中的bundles目录。 */
    private final File bundlesDir;

    /** 保存cachedBundles集合，维持调用顺序或去重语义。 */
    private volatile List<SkillBundle> cachedBundles;

    /** 记录技能包Loader中的cached最近一次Touched时间。 */
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
     * 列出Bundles。
     *
     * @return 返回Bundles列表。
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
     * 重新加载目标服务端配置与工具清单。
     *
     * @return 返回reload结果。
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
     * 读取包。
     *
     * @param name 名称参数。
     * @return 返回读取到的包。
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

    /**
     * 保存包。
     *
     * @param bundle bundle 参数。
     */
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
        if (bundle.getMetadata() != null && !bundle.getMetadata().isEmpty()) {
            data.put("metadata", bundle.getMetadata());
        }
        String json = ONode.serialize(data);
        FileUtil.writeString(json, file, StandardCharsets.UTF_8);
        invalidateCache();
    }

    /**
     * 删除包。
     *
     * @param name 名称参数。
     * @return 返回包结果。
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
     * 执行摘要相关逻辑。
     *
     * @return 返回summary结果。
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
     * 查找包文件。
     *
     * @param name 名称参数。
     * @return 返回包文件结果。
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
     * 加载包。
     *
     * @param file 文件或目录路径参数。
     * @return 返回包结果。
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
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 执行包Files相关逻辑。
     *
     * @return 返回包Files结果。
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
                         * @return 返回compare结果。
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
     * 判断是否包文件名称。
     *
     * @param name 名称参数。
     * @return 如果包文件名称满足条件则返回 true，否则返回 false。
     */
    private boolean isBundleFileName(String name) {
        return name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /**
     * 解析包Content。
     *
     * @param file 文件或目录路径参数。
     * @param content 待处理内容。
     * @return 返回解析后的包Content。
     */
    private Object parseBundleContent(File file, String content) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return new Yaml().load(content);
        }
        return ONode.deserialize(content, Object.class);
    }

    /**
     * 执行lastTouched时间相关逻辑。
     *
     * @return 返回last Touched时间结果。
     */
    private long lastTouchedAt() {
        return lastTouchedAt(bundleFiles());
    }

    /**
     * 执行lastTouched时间相关逻辑。
     *
     * @param files 文件或目录路径参数。
     * @return 返回last Touched时间结果。
     */
    private long lastTouchedAt(File[] files) {
        long latest = bundlesDir.exists() ? bundlesDir.lastModified() : 0L;
        if (files == null || files.length == 0) {
            return latest;
        }
        for (File file : files) {
            latest = Math.max(latest, file.lastModified());
        }
        return latest;
    }

    /**
     * 执行slugify相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回slugify结果。
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
     * @return 返回string Value结果。
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 执行invalidate缓存相关逻辑。 */
    private void invalidateCache() {
        cachedBundles = null;
        cachedLastTouchedAt = Long.MIN_VALUE;
    }
}
