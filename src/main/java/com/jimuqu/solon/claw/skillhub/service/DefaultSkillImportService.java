package com.jimuqu.solon.claw.skillhub.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.service.SkillGuardService;
import com.jimuqu.solon.claw.core.service.SkillImportService;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.InstallDecision;
import com.jimuqu.solon.claw.skillhub.model.SkillBundle;
import com.jimuqu.solon.claw.skillhub.model.SkillImportResult;
import com.jimuqu.solon.claw.skillhub.source.ClawHubSkillSource;
import com.jimuqu.solon.claw.skillhub.source.LobeHubSkillSource;
import com.jimuqu.solon.claw.skillhub.support.SkillBundlePathSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillFrontmatterSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubContentSupport;
import com.jimuqu.solon.claw.skillhub.support.SkillHubStateStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.noear.snack4.ONode;

/** 默认技能导入服务。 */
public class DefaultSkillImportService implements SkillImportService {
    /** 记录默认技能导入中的技能目录。 */
    private final File skillsDir;

    /** 注入技能保护服务，用于调用对应业务能力。 */
    private final SkillGuardService skillGuardService;

    /** 记录默认技能导入中的状态Store。 */
    private final SkillHubStateStore stateStore;

    /**
     * 创建默认技能导入服务实例，并注入运行所需依赖。
     *
     * @param skillsDir 文件或目录路径参数。
     * @param skillGuardService 技能防护服务依赖。
     * @param stateStore 状态Store参数。
     */
    public DefaultSkillImportService(
            File skillsDir, SkillGuardService skillGuardService, SkillHubStateStore stateStore) {
        this.skillsDir = skillsDir;
        this.skillGuardService = skillGuardService;
        this.stateStore = stateStore;
    }

    /**
     * 执行待恢复Imports相关逻辑。
     *
     * @param force force 参数。
     * @return 返回Pending Imports结果。
     */
    @Override
    public SkillImportResult processPendingImports(boolean force) throws Exception {
        SkillImportResult result = new SkillImportResult();
        File[] children = skillsDir.listFiles();
        if (children == null) {
            return result;
        }

        for (File child : children) {
            if (".hub".equals(child.getName())) {
                continue;
            }
            if (child.isDirectory()
                    && (isCanonicalSkillDir(child) || isCanonicalCategoryDir(child))) {
                continue;
            }
            try {
                List<SkillBundle> bundles =
                        child.isFile()
                                ? detectBundlesFromFile(child)
                                : detectBundlesFromDirectory(child);
                if (bundles.isEmpty()) {
                    continue;
                }
                for (SkillBundle bundle : bundles) {
                    installBundle(bundle, null, force, child);
                    result.setInstalledCount(result.getInstalledCount() + 1);
                    result.getMessages()
                            .add("Imported " + bundle.getName() + " from " + child.getName());
                }
                result.setArchivedCount(result.getArchivedCount() + 1);
            } catch (Exception e) {
                result.setBlockedCount(result.getBlockedCount() + 1);
                result.getMessages()
                        .add("Blocked import " + child.getName() + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 安装包。
     *
     * @param bundle bundle 参数。
     * @param category 分类参数。
     * @param force force 参数。
     * @param sourceArtifact 来源Artifact参数。
     * @return 返回install包结果。
     */
    @Override
    public HubInstallRecord installBundle(
            SkillBundle bundle, String category, boolean force, File sourceArtifact)
            throws Exception {
        String skillName = SkillBundlePathSupport.normalizeSkillName(bundle.getName());
        File quarantineRoot =
                SkillBundlePathSupport.requireCanonicalUnderRoot(
                        skillsDir, stateStore.quarantineDir(), "quarantine path");
        File quarantineDir =
                SkillBundlePathSupport.requireCanonicalUnderRoot(
                        skillsDir,
                        FileUtil.file(quarantineRoot, skillName + "-" + System.nanoTime()),
                        "quarantine path");
        SkillHubContentSupport.writeBundle(quarantineDir, bundle);

        com.jimuqu.solon.claw.skillhub.model.ScanResult scanResult =
                skillGuardService.scanSkill(quarantineDir, bundle.getSource());
        InstallDecision decision = skillGuardService.shouldAllowInstall(scanResult, force);
        if (!decision.isAllowed()) {
            stateStore.appendAuditLog(
                    "BLOCKED",
                    skillName,
                    bundle.getSource(),
                    scanResult.getTrustLevel(),
                    scanResult.getVerdict(),
                    decision.getReason());
            archiveOriginal(sourceArtifact, "blocked-" + skillName);
            throw new IllegalStateException(decision.getReason());
        }

        String resolvedCategory = resolveCategory(category, bundle);
        String installPath =
                StrUtil.isBlank(resolvedCategory) ? skillName : resolvedCategory + "/" + skillName;
        File installDir = SkillBundlePathSupport.resolveUnderRoot(skillsDir, installPath);

        HubInstallRecord record = new HubInstallRecord();
        record.setName(skillName);
        record.setSource(bundle.getSource());
        record.setIdentifier(bundle.getIdentifier());
        record.setTrustLevel(scanResult.getTrustLevel());
        record.setScanVerdict(scanResult.getVerdict());
        record.setContentHash(SkillHubContentSupport.contentHash(quarantineDir));
        record.setInstallPath(installPath);
        record.setFiles(new ArrayList<String>(bundle.getFiles().keySet()));
        record.setMetadata(new LinkedHashMap<String, Object>(bundle.getMetadata()));

        File backupDir = null;
        try {
            if (installDir.exists()) {
                backupDir =
                        SkillBundlePathSupport.requireCanonicalUnderRoot(
                                skillsDir,
                                FileUtil.file(
                                        quarantineRoot, skillName + "-backup-" + System.nanoTime()),
                                "backup path");
                FileUtil.move(installDir, backupDir, true);
            }
            FileUtil.mkParentDirs(installDir);
            FileUtil.move(quarantineDir, installDir, true);
            stateStore.recordInstall(record);
        } catch (Exception e) {
            rollbackInstall(installDir, backupDir);
            throw e;
        }
        if (backupDir != null && backupDir.exists()) {
            FileUtil.del(backupDir);
        }
        stateStore.appendAuditLog(
                "INSTALL",
                skillName,
                bundle.getSource(),
                scanResult.getTrustLevel(),
                scanResult.getVerdict(),
                record.getContentHash());

        archiveOriginal(sourceArtifact, "imported-" + skillName);
        return record;
    }

    /**
     * 执行detectBundlesFrom文件相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回detect Bundles From文件结果。
     */
    private List<SkillBundle> detectBundlesFromFile(File file) throws Exception {
        if ("zip".equalsIgnoreCase(FileUtil.extName(file))) {
            SkillBundle bundle = bundleFromZip(file);
            return bundle == null
                    ? Collections.<SkillBundle>emptyList()
                    : java.util.Collections.singletonList(bundle);
        }
        if ("json".equalsIgnoreCase(FileUtil.extName(file))) {
            SkillBundle bundle = bundleFromJsonFile(file);
            return bundle == null
                    ? Collections.<SkillBundle>emptyList()
                    : java.util.Collections.singletonList(bundle);
        }
        return Collections.emptyList();
    }

    /**
     * 执行detectBundlesFrom目录相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @return 返回detect Bundles From Directory结果。
     */
    private List<SkillBundle> detectBundlesFromDirectory(File dir) throws Exception {
        List<SkillBundle> bundles = new ArrayList<SkillBundle>();
        File marketplace = FileUtil.file(dir, ".claude-plugin", "marketplace.json");
        if (marketplace.exists()) {
            bundles.addAll(bundlesFromMarketplaceRepo(dir, marketplace));
        }

        File wellKnownIndex = FileUtil.file(dir, ".well-known", "skills", "index.json");
        if (wellKnownIndex.exists()) {
            bundles.addAll(
                    bundlesFromWellKnownIndex(
                            FileUtil.file(dir, ".well-known", "skills"), wellKnownIndex));
        }
        File indexFile = FileUtil.file(dir, "index.json");
        if (indexFile.exists()) {
            bundles.addAll(bundlesFromWellKnownIndex(dir, indexFile));
        }

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && "json".equalsIgnoreCase(FileUtil.extName(child))) {
                    SkillBundle bundle = bundleFromJsonFile(child);
                    if (bundle != null) {
                        bundles.add(bundle);
                    }
                }
                if (child.isFile() && "zip".equalsIgnoreCase(FileUtil.extName(child))) {
                    SkillBundle bundle = bundleFromZip(child);
                    if (bundle != null) {
                        bundles.add(bundle);
                    }
                }
            }
        }

        if (bundles.isEmpty()) {
            SkillBundle nested = bundleFromNestedSkill(dir);
            if (nested != null) {
                bundles.add(nested);
            }
        }
        return bundles;
    }

    /**
     * 执行包FromZip相关逻辑。
     *
     * @param zipFile 文件或目录路径参数。
     * @return 返回包From Zip结果。
     */
    private SkillBundle bundleFromZip(File zipFile) throws Exception {
        byte[] bytes = FileUtil.readBytes(zipFile);
        ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bytes));
        try {
            Map<String, String> files = new LinkedHashMap<String, String>();
            String commonPrefix = null;
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash > 0) {
                    String prefix = name.substring(0, slash + 1);
                    commonPrefix =
                            commonPrefix == null
                                    ? prefix
                                    : commonPrefix.equals(prefix) ? commonPrefix : "";
                } else {
                    commonPrefix = "";
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = zipInputStream.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                }
                files.put(name, new String(out.toByteArray(), "UTF-8"));
            }

            Map<String, String> normalized = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entrySet : files.entrySet()) {
                String path = entrySet.getKey();
                if (StrUtil.isNotBlank(commonPrefix) && path.startsWith(commonPrefix)) {
                    path = path.substring(commonPrefix.length());
                }
                normalized.put(
                        SkillBundlePathSupport.normalizeBundlePath(path), entrySet.getValue());
            }
            if (!normalized.containsKey("SKILL.md")) {
                return null;
            }
            SkillBundle bundle = new SkillBundle();
            bundle.setName(FileUtil.mainName(zipFile));
            bundle.setFiles(normalized);
            bundle.setSource("clawhub");
            bundle.setIdentifier(FileUtil.mainName(zipFile));
            bundle.setTrustLevel("community");
            return bundle;
        } finally {
            zipInputStream.close();
        }
    }

    /**
     * 执行包FromJSON文件相关逻辑。
     *
     * @param jsonFile 文件或目录路径参数。
     * @return 返回包From JSON文件结果。
     */
    private SkillBundle bundleFromJsonFile(File jsonFile) {
        ONode node = ONode.ofJson(FileUtil.readUtf8String(jsonFile));
        if (looksLikeLobeHub(node)) {
            SkillBundle bundle = new SkillBundle();
            bundle.setName(FileUtil.mainName(jsonFile));
            bundle.getFiles().put("SKILL.md", LobeHubSkillSource.convertToSkillMd(node));
            bundle.setSource("lobehub");
            bundle.setIdentifier("lobehub/" + FileUtil.mainName(jsonFile));
            bundle.setTrustLevel("community");
            return bundle;
        }

        try {
            Map<String, String> files = ClawHubSkillSource.extractFiles(node, null);
            if (!files.containsKey("SKILL.md")) {
                return null;
            }
            SkillBundle bundle = new SkillBundle();
            bundle.setName(FileUtil.mainName(jsonFile));
            bundle.setFiles(files);
            bundle.setSource("clawhub");
            bundle.setIdentifier(FileUtil.mainName(jsonFile));
            bundle.setTrustLevel("community");
            return bundle;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行bundlesFrom技能市场Repo相关逻辑。
     *
     * @param repoDir 文件或目录路径参数。
     * @param marketplaceFile 文件或目录路径参数。
     * @return 返回bundles From技能市场Repo结果。
     */
    private List<SkillBundle> bundlesFromMarketplaceRepo(File repoDir, File marketplaceFile) {
        List<SkillBundle> bundles = new ArrayList<SkillBundle>();
        ONode node = ONode.ofJson(FileUtil.readUtf8String(marketplaceFile));
        ONode plugins = node.get("plugins");
        for (int i = 0; i < plugins.size(); i++) {
            ONode plugin = plugins.get(i);
            String source = plugin.get("source").getString();
            if (StrUtil.isBlank(source)) {
                continue;
            }
            if (source.startsWith("./")) {
                source = source.substring(2);
            }
            File pluginDir = FileUtil.file(repoDir, source);
            SkillBundle bundle =
                    bundleFromNestedDirectory(
                            pluginDir, "claude-marketplace", repoDir.getName() + "/" + source);
            if (bundle != null) {
                bundles.add(bundle);
            }
        }
        return bundles;
    }

    /**
     * 执行bundlesFromWellKnown索引相关逻辑。
     *
     * @param baseDir 文件或目录路径参数。
     * @param indexFile 文件或目录路径参数。
     * @return 返回bundles From Well Known Index结果。
     */
    private List<SkillBundle> bundlesFromWellKnownIndex(File baseDir, File indexFile) {
        List<SkillBundle> bundles = new ArrayList<SkillBundle>();
        ONode index = ONode.ofJson(FileUtil.readUtf8String(indexFile));
        ONode skills = index.get("skills");
        for (int i = 0; i < skills.size(); i++) {
            ONode skill = skills.get(i);
            String skillName = skill.get("name").getString();
            if (StrUtil.isBlank(skillName)) {
                continue;
            }
            File skillDir = FileUtil.file(baseDir, skillName);
            if (!skillDir.exists()) {
                continue;
            }
            SkillBundle bundle =
                    bundleFromNestedDirectory(skillDir, "well-known", "well-known:" + skillName);
            if (bundle != null) {
                bundles.add(bundle);
            }
        }
        return bundles;
    }

    /**
     * 执行包FromNested技能相关逻辑。
     *
     * @param dir 文件或目录路径参数。
     * @return 返回包From Nested技能结果。
     */
    private SkillBundle bundleFromNestedSkill(File dir) {
        File[] children = dir.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) {
            return null;
        }
        return bundleFromNestedDirectory(children[0], "community", dir.getName());
    }

    /**
     * 执行包FromNested目录相关逻辑。
     *
     * @param skillDir 文件或目录路径参数。
     * @param source 来源参数。
     * @param identifier identifier标识或键值。
     * @return 返回包From Nested Directory结果。
     */
    private SkillBundle bundleFromNestedDirectory(File skillDir, String source, String identifier) {
        File skillFile = FileUtil.file(skillDir, "SKILL.md");
        if (!skillFile.exists()) {
            return null;
        }
        SkillBundle bundle = new SkillBundle();
        bundle.setName(skillDir.getName());
        bundle.setSource(source);
        bundle.setIdentifier(identifier);
        bundle.setTrustLevel("official".equals(source) ? "builtin" : "community");
        if (StrUtil.isNotBlank(source) && source.startsWith("agent-created")) {
            bundle.setTrustLevel("agent-created");
        }
        for (File file : FileUtil.loopFiles(skillDir)) {
            if (!file.isDirectory()) {
                String relative =
                        file.getAbsolutePath()
                                .substring(skillDir.getAbsolutePath().length() + 1)
                                .replace(File.separatorChar, '/');
                bundle.getFiles().put(relative, FileUtil.readUtf8String(file));
            }
        }
        return bundle;
    }

    /**
     * 判断是否规范技能Dir。
     *
     * @param dir 文件或目录路径参数。
     * @return 如果规范技能Dir满足条件则返回 true，否则返回 false。
     */
    private boolean isCanonicalSkillDir(File dir) {
        return FileUtil.file(dir, "SKILL.md").exists();
    }

    /**
     * 判断是否规范Category Dir。
     *
     * @param dir 文件或目录路径参数。
     * @return 如果规范Category Dir满足条件则返回 true，否则返回 false。
     */
    private boolean isCanonicalCategoryDir(File dir) {
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return false;
        }
        boolean hasSkillChildren = false;
        for (File child : children) {
            if (child.isDirectory() && FileUtil.file(child, "SKILL.md").exists()) {
                hasSkillChildren = true;
                continue;
            }
            if (child.isDirectory()) {
                continue;
            }
            return false;
        }
        return hasSkillChildren;
    }

    /**
     * 执行回滚Install相关逻辑。
     *
     * @param installDir 文件或目录路径参数。
     * @param backupDir 文件或目录路径参数。
     */
    private void rollbackInstall(File installDir, File backupDir) {
        if (installDir != null && installDir.exists()) {
            FileUtil.del(installDir);
        }
        if (backupDir != null && backupDir.exists()) {
            FileUtil.mkParentDirs(installDir);
            FileUtil.move(backupDir, installDir, true);
        }
    }

    /**
     * 执行archiveOriginal相关逻辑。
     *
     * @param sourceArtifact 来源Artifact参数。
     * @param prefix prefix 参数。
     */
    private void archiveOriginal(File sourceArtifact, String prefix) {
        if (sourceArtifact == null || !sourceArtifact.exists()) {
            return;
        }
        File importedRoot =
                SkillBundlePathSupport.requireCanonicalUnderRoot(
                        skillsDir, stateStore.importedDir(), "imported path");
        String safeSourceName = SkillBundlePathSupport.normalizeSkillName(sourceArtifact.getName());
        File target =
                SkillBundlePathSupport.requireCanonicalUnderRoot(
                        skillsDir,
                        FileUtil.file(
                                importedRoot,
                                prefix + "-" + safeSourceName + "-" + System.nanoTime()),
                        "imported path");
        FileUtil.mkParentDirs(target);
        FileUtil.move(sourceArtifact, target, true);
    }

    /**
     * 解析Category。
     *
     * @param explicitCategory explicitCategory 参数。
     * @param bundle bundle 参数。
     * @return 返回解析后的Category。
     */
    private String resolveCategory(String explicitCategory, SkillBundle bundle) {
        if (StrUtil.isNotBlank(explicitCategory)) {
            return SkillBundlePathSupport.normalizeCategoryName(explicitCategory);
        }
        Object metadataCategory = bundle.getMetadata().get("category");
        if (metadataCategory instanceof String && StrUtil.isNotBlank((String) metadataCategory)) {
            return SkillBundlePathSupport.normalizeCategoryName((String) metadataCategory);
        }
        String skillMd = bundle.getFiles().get("SKILL.md");
        if (StrUtil.isBlank(skillMd)) {
            return null;
        }
        java.util.Map<String, Object> frontmatter =
                SkillFrontmatterSupport.parseFrontmatter(skillMd);
        Object category = frontmatter.get("category");
        if (category instanceof String && StrUtil.isNotBlank((String) category)) {
            return SkillBundlePathSupport.normalizeCategoryName((String) category);
        }
        return null;
    }

    /**
     * 判断是否具有Lobe中心特征。
     *
     * @param node 节点参数。
     * @return 返回looks Like Lobe中心结果。
     */
    private boolean looksLikeLobeHub(ONode node) {
        return node.get("config").get("systemRole").isValue()
                || node.get("meta").get("title").isValue()
                || node.get("meta").get("description").isValue();
    }
}
