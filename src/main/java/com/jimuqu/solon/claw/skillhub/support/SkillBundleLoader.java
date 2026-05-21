package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/**
 * Skill bundle loader and manager.
 * Bundles are YAML/JSON files that define a set of skills to load together.
 */
public class SkillBundleLoader {
    private final File bundlesDir;
    private volatile List<SkillBundle> cachedBundles;

    public SkillBundleLoader(AppConfig appConfig) {
        this.bundlesDir = new File(appConfig.getRuntime().getSkillsDir(), "bundles");
    }

    public List<SkillBundle> listBundles() {
        if (cachedBundles != null) {
            return new ArrayList<SkillBundle>(cachedBundles);
        }
        return reload();
    }

    public synchronized List<SkillBundle> reload() {
        List<SkillBundle> bundles = new ArrayList<SkillBundle>();
        if (!bundlesDir.isDirectory()) {
            cachedBundles = bundles;
            return bundles;
        }
        File[] files = bundlesDir.listFiles();
        if (files == null) {
            cachedBundles = bundles;
            return bundles;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase();
            if (!name.endsWith(".json") && !name.endsWith(".yml") && !name.endsWith(".yaml")) {
                continue;
            }
            SkillBundle bundle = loadBundle(file);
            if (bundle != null) {
                bundles.add(bundle);
            }
        }
        cachedBundles = bundles;
        return new ArrayList<SkillBundle>(bundles);
    }

    public SkillBundle getBundle(String name) {
        for (SkillBundle bundle : listBundles()) {
            if (name.equalsIgnoreCase(bundle.getName())) {
                return bundle;
            }
        }
        return null;
    }

    public synchronized void saveBundle(SkillBundle bundle) {
        FileUtil.mkdir(bundlesDir);
        String fileName = StrUtil.nullToEmpty(bundle.getName()).trim().toLowerCase() + ".json";
        File file = new File(bundlesDir, fileName);
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
        cachedBundles = null;
    }

    public synchronized boolean deleteBundle(String name) {
        File file = findBundleFile(name);
        if (file != null && file.isFile()) {
            file.delete();
            cachedBundles = null;
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
        if (!bundlesDir.isDirectory()) {
            return null;
        }
        String lower = StrUtil.nullToEmpty(name).trim().toLowerCase();
        File[] files = bundlesDir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String base = file.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            if (base.equalsIgnoreCase(lower)) {
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
            Object parsed = ONode.deserialize(content, Object.class);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<String, Object> data = (Map<String, Object>) parsed;
            SkillBundle bundle = new SkillBundle();
            bundle.setName(StrUtil.nullToEmpty((String) data.get("name")).trim());
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
            bundle.setSource((String) data.get("source"));
            bundle.setIdentifier((String) data.get("identifier"));
            bundle.setTrustLevel((String) data.get("trustLevel"));
            Object meta = data.get("metadata");
            if (meta instanceof Map) {
                bundle.setMetadata(new LinkedHashMap<String, Object>((Map<String, Object>) meta));
            }
            return bundle;
        } catch (Exception ignored) {
            return null;
        }
    }
}
