package com.jimuqu.solon.claw.context;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hermes-style validation for skill-declared credential files. */
public class SkillCredentialFileService {
    private static final String DEFAULT_CONTAINER_BASE = "/root/.jimuqu-agent";

    private final AppConfig appConfig;
    private final File runtimeHome;

    public SkillCredentialFileService(AppConfig appConfig) {
        this.appConfig = appConfig;
        String home =
                appConfig == null || appConfig.getRuntime() == null
                        ? "runtime"
                        : StrUtil.blankToDefault(appConfig.getRuntime().getHome(), "runtime");
        this.runtimeHome = FileUtil.file(home).getAbsoluteFile();
    }

    public CredentialFilePlan plan(Object rawEntries) {
        return plan(rawEntries, DEFAULT_CONTAINER_BASE);
    }

    public CredentialFilePlan configPlan() {
        return configPlan(DEFAULT_CONTAINER_BASE);
    }

    public CredentialFilePlan configPlan(String containerBase) {
        List<String> credentialFiles =
                appConfig == null || appConfig.getTerminal() == null
                        ? Collections.<String>emptyList()
                        : appConfig.getTerminal().getCredentialFiles();
        return plan(new ArrayList<Object>(credentialFiles), containerBase);
    }

    public CredentialFilePlan plan(Object rawEntries, String containerBase) {
        List<Object> entries = normalizeEntries(rawEntries);
        CredentialFilePlan plan = new CredentialFilePlan();
        if (entries.isEmpty()) {
            return plan;
        }

        String base = StrUtil.blankToDefault(containerBase, DEFAULT_CONTAINER_BASE);
        for (Object entry : entries) {
            String relativePath = relativePath(entry);
            if (StrUtil.isBlank(relativePath)) {
                continue;
            }
            CredentialFileMount mount = resolve(relativePath, base);
            if (mount.isRegistered()) {
                plan.getMounts().add(mount);
            } else if ("missing".equals(mount.getStatus())) {
                plan.getMissing().add(mount.getRelativePath());
            } else {
                plan.getRejected().add(mount);
            }
        }
        return plan;
    }

    private CredentialFileMount resolve(String rawPath, String containerBase) {
        String relativePath = normalizeRelativeText(rawPath);
        if (StrUtil.isBlank(relativePath)) {
            return CredentialFileMount.rejected(rawPath, "empty path");
        }
        String trimmedRawPath = StrUtil.nullToEmpty(rawPath).trim();
        if (isAbsolutePath(trimmedRawPath)) {
            return CredentialFileMount.rejected(rawPath, "absolute path is not allowed");
        }
        if (hasTraversal(relativePath)) {
            return CredentialFileMount.rejected(rawPath, "path traversal is not allowed");
        }
        try {
            File candidate = FileUtil.file(runtimeHome, relativePath).getCanonicalFile();
            File home = runtimeHome.getCanonicalFile();
            if (!isInside(candidate, home)) {
                return CredentialFileMount.rejected(rawPath, "path escapes runtime home");
            }
            if (!candidate.isFile()) {
                return CredentialFileMount.missing(relativePath);
            }
            String containerPath = containerBase.replace('\\', '/').replaceAll("/+$", "")
                    + "/"
                    + relativePath.replace('\\', '/');
            return CredentialFileMount.registered(relativePath, candidate.getAbsolutePath(), containerPath);
        } catch (Exception e) {
            return CredentialFileMount.rejected(rawPath, "invalid credential file path");
        }
    }

    private boolean isInside(File child, File parent) {
        String childPath = child.getAbsolutePath();
        String parentPath = parent.getAbsolutePath();
        if (childPath.equals(parentPath)) {
            return true;
        }
        if (!parentPath.endsWith(File.separator)) {
            parentPath = parentPath + File.separator;
        }
        return childPath.startsWith(parentPath);
    }

    private boolean hasTraversal(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.endsWith("/..")
                || normalized.contains("/../");
    }

    private boolean isAbsolutePath(String rawPath) {
        return new File(rawPath).isAbsolute()
                || rawPath.startsWith("/")
                || rawPath.startsWith("\\");
    }

    private String normalizeRelativeText(String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        while (value.startsWith("/") || value.startsWith("\\")) {
            value = value.substring(1);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeEntries(Object rawEntries) {
        if (rawEntries == null) {
            return Collections.emptyList();
        }
        if (rawEntries instanceof List) {
            return (List<Object>) rawEntries;
        }
        List<Object> result = new ArrayList<Object>();
        result.add(rawEntries);
        return result;
    }

    private String relativePath(Object entry) {
        if (entry == null) {
            return "";
        }
        if (entry instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) entry;
            Object value = map.containsKey("path") ? map.get("path") : map.get("name");
            return value == null ? "" : String.valueOf(value).trim();
        }
        return String.valueOf(entry).trim();
    }

    public static class CredentialFilePlan {
        private final List<CredentialFileMount> mounts = new ArrayList<CredentialFileMount>();
        private final List<String> missing = new ArrayList<String>();
        private final List<CredentialFileMount> rejected = new ArrayList<CredentialFileMount>();

        public List<CredentialFileMount> getMounts() {
            return mounts;
        }

        public List<String> getMissing() {
            return missing;
        }

        public List<CredentialFileMount> getRejected() {
            return rejected;
        }

        public Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> mountMaps = new ArrayList<Map<String, Object>>();
            for (CredentialFileMount mount : mounts) {
                mountMaps.add(mount.toMetadata());
            }
            List<Map<String, Object>> rejectedMaps = new ArrayList<Map<String, Object>>();
            for (CredentialFileMount mount : rejected) {
                rejectedMaps.add(mount.toMetadata());
            }
            map.put("mounts", mountMaps);
            map.put("missing", new ArrayList<String>(missing));
            map.put("rejected", rejectedMaps);
            return map;
        }
    }

    public static class CredentialFileMount {
        private String relativePath;
        private String hostPath;
        private String containerPath;
        private String status;
        private String reason;

        private static CredentialFileMount registered(
                String relativePath, String hostPath, String containerPath) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.hostPath = hostPath;
            mount.containerPath = containerPath;
            mount.status = "registered";
            return mount;
        }

        private static CredentialFileMount missing(String relativePath) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.status = "missing";
            mount.reason = "file not found";
            return mount;
        }

        private static CredentialFileMount rejected(String relativePath, String reason) {
            CredentialFileMount mount = new CredentialFileMount();
            mount.relativePath = relativePath;
            mount.status = "rejected";
            mount.reason = reason;
            return mount;
        }

        public boolean isRegistered() {
            return "registered".equals(status);
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getHostPath() {
            return hostPath;
        }

        public String getContainerPath() {
            return containerPath;
        }

        public String getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        private Map<String, Object> toMetadata() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("path", relativePath);
            map.put("host_path", hostPath);
            map.put("container_path", containerPath);
            map.put("status", status);
            map.put("reason", reason);
            return map;
        }
    }
}
