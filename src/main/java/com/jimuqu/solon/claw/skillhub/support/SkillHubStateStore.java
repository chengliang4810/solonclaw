package com.jimuqu.solon.claw.skillhub.support;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.skillhub.model.HubAuditEntry;
import com.jimuqu.solon.claw.skillhub.model.HubInstallRecord;
import com.jimuqu.solon.claw.skillhub.model.TapRecord;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.noear.snack4.ONode;

/** Skills Hub 状态存储。 */
public class SkillHubStateStore {
    /** 按状态文件规范路径共享的进程内锁，覆盖多个 Store 实例。 */
    private static final ConcurrentHashMap<String, Object> FILE_LOCKS =
            new ConcurrentHashMap<String, Object>();

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
        File lockFile = lockFile();
        synchronized (lockFor(lockFile)) {
            return new ArrayList<HubInstallRecord>(loadLock(lockFile).values());
        }
    }

    /**
     * 读取Installed。
     *
     * @param name 名称参数。
     * @return 返回读取到的Installed。
     */
    public HubInstallRecord getInstalled(String name) {
        File lockFile = lockFile();
        synchronized (lockFor(lockFile)) {
            return loadLock(lockFile).get(SkillBundlePathSupport.normalizeSkillName(name));
        }
    }

    /**
     * 记录Install。
     *
     * @param record 记录参数。
     */
    public void recordInstall(HubInstallRecord record) {
        validateRecord(record);
        File lockFile = lockFile();
        synchronized (lockFor(lockFile)) {
            Map<String, HubInstallRecord> installed = loadLock(lockFile);
            installed.put(record.getName(), record);
            saveLock(lockFile, installed);
        }
    }

    /**
     * 记录Uninstall。
     *
     * @param name 名称参数。
     */
    public void recordUninstall(String name) {
        File lockFile = lockFile();
        synchronized (lockFor(lockFile)) {
            Map<String, HubInstallRecord> installed = loadLock(lockFile);
            installed.remove(SkillBundlePathSupport.normalizeSkillName(name));
            saveLock(lockFile, installed);
        }
    }

    /**
     * 列出Taps。
     *
     * @return 返回Taps列表。
     */
    public List<TapRecord> listTaps() {
        File tapsFile = hubFile(SkillHubPathSupport.tapsFile(skillsDir), "taps path");
        synchronized (lockFor(tapsFile)) {
            if (!tapsFile.exists()) {
                return Collections.emptyList();
            }
            try {
                TapContainer container =
                        ONode.deserialize(FileUtil.readUtf8String(tapsFile), TapContainer.class);
                return container == null || container.getTaps() == null
                        ? Collections.<TapRecord>emptyList()
                        : new ArrayList<TapRecord>(container.getTaps());
            } catch (Exception ignored) {
                return Collections.emptyList();
            }
        }
    }

    /**
     * 保存Taps。
     *
     * @param taps taps 参数。
     */
    public void saveTaps(List<TapRecord> taps) {
        TapContainer container = new TapContainer();
        container.setTaps(
                taps == null ? new ArrayList<TapRecord>() : new ArrayList<TapRecord>(taps));
        File tapsFile = hubFile(SkillHubPathSupport.tapsFile(skillsDir), "taps path");
        synchronized (lockFor(tapsFile)) {
            writeAtomically(tapsFile, ONode.serialize(container));
        }
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
        File auditFile = hubFile(SkillHubPathSupport.auditLog(skillsDir), "audit path");
        synchronized (lockFor(auditFile)) {
            FileUtil.appendUtf8String(line, auditFile);
        }
    }

    /**
     * 读取Cached Index。
     *
     * @param key 配置键或映射键。
     * @return 返回读取到的Cached Index。
     */
    public String readCachedIndex(String key) {
        File target = cacheFile(key);
        synchronized (lockFor(target)) {
            if (!target.exists()) {
                return null;
            }
            return FileUtil.readUtf8String(target);
        }
    }

    /**
     * 写入Cached Index。
     *
     * @param key 配置键或映射键。
     * @param content 待处理内容。
     */
    public void writeCachedIndex(String key, String content) {
        File target = cacheFile(key);
        synchronized (lockFor(target)) {
            writeAtomically(target, StrUtil.nullToEmpty(content));
        }
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
    private Map<String, HubInstallRecord> loadLock(File lockFile) {
        if (!lockFile.exists()) {
            return new LinkedHashMap<String, HubInstallRecord>();
        }
        LockContainer container;
        try {
            container = ONode.deserialize(FileUtil.readUtf8String(lockFile), LockContainer.class);
        } catch (Exception ignored) {
            return new LinkedHashMap<String, HubInstallRecord>();
        }
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
    private void saveLock(File lockFile, Map<String, HubInstallRecord> installed) {
        LockContainer container = new LockContainer();
        container.setVersion(1);
        container.setInstalled(new LinkedHashMap<String, HubInstallRecord>(installed));
        writeAtomically(lockFile, ONode.serialize(container));
    }

    /** 返回安装记录状态文件。 */
    private File lockFile() {
        return hubFile(SkillHubPathSupport.lockFile(skillsDir), "lock path");
    }

    /** 按规范路径取得跨 Store 实例共享的文件锁。 */
    private Object lockFor(File file) {
        return FILE_LOCKS.computeIfAbsent(lockKey(file), ignored -> new Object());
    }

    /** 使用同目录临时文件和原子替换保存状态，避免中断后留下半份 JSON。 */
    private void writeAtomically(File file, String content) {
        Path target = file.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        try {
            if (parent == null) {
                throw new IOException("Skill Hub state parent is required");
            }
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".skillhub-state-", ".tmp");
            boolean moved = false;
            try {
                Files.write(temp, StrUtil.nullToEmpty(content).getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(
                            temp,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save Skills Hub state", e);
        }
    }

    /** 为同一状态文件生成跨实例稳定的锁键。 */
    private String lockKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
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
