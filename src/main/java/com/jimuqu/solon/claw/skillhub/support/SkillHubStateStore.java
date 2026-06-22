package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.HubAuditEntry;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Skills Hub 状态存储。 */
public class SkillHubStateStore {
    /** 记录技能中心状态Store中的技能目录。 */
    private final File skillsDir;

    /**
     * 创建技能中心状态Store实例，并注入运行所需依赖。
     *
     * @param skillsDir 文件或目录路径参数。
     */
    public SkillHubStateStore(File skillsDir) {
        this.skillsDir = skillsDir;
        SkillHubPathSupport.ensureHubDirs(skillsDir);
    }

    /**
     * 列出Installed。
     *
     * @return 返回Installed列表。
     */
    public List<HubInstallRecord> listInstalled() {
        return new ArrayList<HubInstallRecord>(loadLock().values());
    }

    /**
     * 读取Installed。
     *
     * @param name 名称参数。
     * @return 返回读取到的Installed。
     */
    public HubInstallRecord getInstalled(String name) {
        return loadLock().get(SkillBundlePathSupport.normalizeSkillName(name));
    }

    /**
     * 记录Install。
     *
     * @param record 记录参数。
     */
    public void recordInstall(HubInstallRecord record) {
        validateRecord(record);
        Map<String, HubInstallRecord> installed = loadLock();
        installed.put(record.getName(), record);
        saveLock(installed);
    }

    /**
     * 记录Uninstall。
     *
     * @param name 名称参数。
     */
    public void recordUninstall(String name) {
        Map<String, HubInstallRecord> installed = loadLock();
        installed.remove(SkillBundlePathSupport.normalizeSkillName(name));
        saveLock(installed);
    }

    /**
     * 列出Taps。
     *
     * @return 返回Taps列表。
     */
    public List<TapRecord> listTaps() {
        File tapsFile = hubFile(SkillHubPathSupport.tapsFile(skillsDir), "taps path");
        if (!tapsFile.exists()) {
            return Collections.emptyList();
        }
        TapContainer container =
                ONode.deserialize(FileUtil.readUtf8String(tapsFile), TapContainer.class);
        return container == null || container.getTaps() == null
                ? Collections.<TapRecord>emptyList()
                : container.getTaps();
    }

    /**
     * 保存Taps。
     *
     * @param taps taps 参数。
     */
    public void saveTaps(List<TapRecord> taps) {
        TapContainer container = new TapContainer();
        container.setTaps(new ArrayList<TapRecord>(taps));
        FileUtil.writeUtf8String(
                ONode.serialize(container),
                hubFile(SkillHubPathSupport.tapsFile(skillsDir), "taps path"));
    }

    /**
     * 追加审计日志。
     *
     * @param action 操作参数。
     * @param skillName 技能名称参数。
     * @param source 来源参数。
     * @param trustLevel trustLevel 参数。
     * @param verdict 判定参数。
     * @param extra extra 参数。
     */
    public void appendAuditLog(
            String action,
            String skillName,
            String source,
            String trustLevel,
            String verdict,
            String extra) {
        HubAuditEntry entry = new HubAuditEntry();
        entry.setTimestamp(String.valueOf(new Date().getTime()));
        entry.setAction(action);
        entry.setSkillName(skillName);
        entry.setSource(source);
        entry.setTrustLevel(trustLevel);
        entry.setVerdict(verdict);
        entry.setExtra(StrUtil.nullToEmpty(extra));
        String line = ONode.serialize(entry) + System.lineSeparator();
        FileUtil.appendUtf8String(
                line, hubFile(SkillHubPathSupport.auditLog(skillsDir), "audit path"));
    }

    /**
     * 读取Cached Index。
     *
     * @param key 配置键或映射键。
     * @return 返回读取到的Cached Index。
     */
    public String readCachedIndex(String key) {
        File target = cacheFile(key);
        if (!target.exists()) {
            return null;
        }
        return FileUtil.readUtf8String(target);
    }

    /**
     * 写入Cached Index。
     *
     * @param key 配置键或映射键。
     * @param content 待处理内容。
     */
    public void writeCachedIndex(String key, String content) {
        FileUtil.writeUtf8String(StrUtil.nullToEmpty(content), cacheFile(key));
    }

    /**
     * 执行quarantine目录相关逻辑。
     *
     * @return 返回quarantine Dir结果。
     */
    public File quarantineDir() {
        return SkillHubPathSupport.quarantineDir(skillsDir);
    }

    /**
     * 执行imported目录相关逻辑。
     *
     * @return 返回imported Dir结果。
     */
    public File importedDir() {
        return SkillHubPathSupport.importedDir(skillsDir);
    }

    /**
     * 加载Lock。
     *
     * @return 返回Lock结果。
     */
    private Map<String, HubInstallRecord> loadLock() {
        File lockFile = hubFile(SkillHubPathSupport.lockFile(skillsDir), "lock path");
        if (!lockFile.exists()) {
            return new LinkedHashMap<String, HubInstallRecord>();
        }
        LockContainer container =
                ONode.deserialize(FileUtil.readUtf8String(lockFile), LockContainer.class);
        if (container == null || container.getInstalled() == null) {
            return new LinkedHashMap<String, HubInstallRecord>();
        }
        Map<String, HubInstallRecord> safeInstalled = new LinkedHashMap<String, HubInstallRecord>();
        for (HubInstallRecord record : container.getInstalled().values()) {
            try {
                validateRecord(record);
                safeInstalled.put(record.getName(), record);
            } catch (IllegalStateException e) {
                // 忽略手工编辑过的脏记录：单条状态异常不应拖垮整个 Hub 状态。
            }
        }
        return safeInstalled;
    }

    /**
     * 保存Lock。
     *
     * @param installed installed 参数。
     */
    private void saveLock(Map<String, HubInstallRecord> installed) {
        LockContainer container = new LockContainer();
        container.setVersion(1);
        container.setInstalled(new LinkedHashMap<String, HubInstallRecord>(installed));
        FileUtil.writeUtf8String(
                ONode.serialize(container),
                hubFile(SkillHubPathSupport.lockFile(skillsDir), "lock path"));
    }

    /**
     * 执行缓存文件相关逻辑。
     *
     * @param key 配置键或映射键。
     * @return 返回缓存文件结果。
     */
    private File cacheFile(String key) {
        String safeKey = SkillBundlePathSupport.normalizeSkillName(key);
        File indexCacheDir = hubFile(SkillHubPathSupport.indexCacheDir(skillsDir), "cache path");
        File target = FileUtil.file(indexCacheDir, safeKey + ".json");
        return SkillBundlePathSupport.requireCanonicalUnderRoot(indexCacheDir, target, "cache key");
    }

    /**
     * 执行中心文件相关逻辑。
     *
     * @param target target 参数。
     * @param fieldName field名称参数。
     * @return 返回中心文件结果。
     */
    private File hubFile(File target, String fieldName) {
        return SkillBundlePathSupport.requireCanonicalUnderRoot(skillsDir, target, fieldName);
    }

    /**
     * 校验记录。
     *
     * @param record 记录参数。
     */
    private void validateRecord(HubInstallRecord record) {
        if (record == null) {
            throw new IllegalStateException("Unsafe install record: empty path");
        }
        String safeName = SkillBundlePathSupport.normalizeSkillName(record.getName());
        String safeInstallPath =
                SkillBundlePathSupport.normalizeBundlePath(record.getInstallPath());
        if (!safeInstallPath.equals(safeName) && !safeInstallPath.endsWith("/" + safeName)) {
            throw new IllegalStateException("Unsafe install path: does not match skill name");
        }
        SkillBundlePathSupport.resolveUnderRoot(skillsDir, safeInstallPath);
        record.setName(safeName);
        record.setInstallPath(safeInstallPath);
    }

    /** 承载LockContainer相关状态和辅助逻辑。 */
    public static class LockContainer {
        /** 记录LockContainer中的版本。 */
        private int version;

        /** 保存installed映射，便于按键快速查询。 */
        private Map<String, HubInstallRecord> installed =
                new LinkedHashMap<String, HubInstallRecord>();

        /**
         * 读取版本。
         *
         * @return 返回读取到的版本。
         */
        public int getVersion() {
            return version;
        }

        /**
         * 写入版本。
         *
         * @param version 版本参数。
         */
        public void setVersion(int version) {
            this.version = version;
        }

        /**
         * 读取Installed。
         *
         * @return 返回读取到的Installed。
         */
        public Map<String, HubInstallRecord> getInstalled() {
            return installed;
        }

        /**
         * 写入Installed。
         *
         * @param installed installed 参数。
         */
        public void setInstalled(Map<String, HubInstallRecord> installed) {
            this.installed = installed;
        }
    }

    /** 承载来源库Container相关状态和辅助逻辑。 */
    public static class TapContainer {
        /** 保存taps集合，维持调用顺序或去重语义。 */
        private List<TapRecord> taps = new ArrayList<TapRecord>();

        /**
         * 读取Taps。
         *
         * @return 返回读取到的Taps。
         */
        public List<TapRecord> getTaps() {
            return taps;
        }

        /**
         * 写入Taps。
         *
         * @param taps taps 参数。
         */
        public void setTaps(List<TapRecord> taps) {
            this.taps = taps;
        }
    }
}
