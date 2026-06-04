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

/**
 * Skill bundle loader and manager. Bundles are YAML/JSON files that define a set of skills to load
 * together.
 */
public class SkillBundleLoader {
    private final File bundlesDir;
    private volatile List<SkillBundle> cachedBundles;
    private volatile long cachedLastTouchedAt = Long.MIN_VALUE;

    public SkillBundleLoader(AppConfig appConfig) {
        this.bundlesDir = new File(appConfig.getRuntime().getSkillsDir(), "bundles");
    }

    public List<SkillBundle> listBundles() {
        long lastTouchedAt = lastTouchedAt();
        List<SkillBundle> cached = cachedBundles;
        if (cached != null && cachedLastTouchedAt == lastTouchedAt) {
            return new ArrayList<SkillBundle>(cached);
        }
        return reload();
    }

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

    public synchronized boolean deleteBundle(String name) {
        File file = findBundleFile(name);
        if (file != null && file.isFile()) {
            file.delete();
            invalidateCache();
            return true;
        }
        return false;
    }

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

    private boolean isBundleFileName(String name) {
        return name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private Object parseBundleContent(File file, String content) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return new Yaml().load(content);
        }
        return ONode.deserialize(content, Object.class);
    }

    private long lastTouchedAt() {
        return lastTouchedAt(bundleFiles());
    }

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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void invalidateCache() {
        cachedBundles = null;
        cachedLastTouchedAt = Long.MIN_VALUE;
    }
}
