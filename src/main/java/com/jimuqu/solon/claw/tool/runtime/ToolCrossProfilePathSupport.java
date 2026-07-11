package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** 识别文件写入是否落到其他 Profile 的用户可维护区域。 */
final class ToolCrossProfilePathSupport {
    /** 允许通过 cross_profile 显式放行的 Profile 局部目录。 */
    private static final Set<String> GUARDED_AREAS =
            new LinkedHashSet<String>(
                    Arrays.asList("skills", "plugins", "cron", "memory", "memories"));

    /** 工具辅助类不允许创建实例。 */
    private ToolCrossProfilePathSupport() {}

    /**
     * 解析跨 Profile 写入目标。
     *
     * @param workRoot 当前工具工作区根目录。
     * @param rawPath 模型传入的原始路径。
     * @return 命中其他 Profile 用户目录时返回目标信息，否则返回 null。
     */
    static CrossProfileTarget classify(Path workRoot, String rawPath) {
        if (workRoot == null || StrUtil.isBlank(rawPath)) {
            return null;
        }
        try {
            Path target = resolveTarget(workRoot, rawPath);
            ProfileManager manager = ProfileManager.current();
            Path profileRoot = manager.root().toAbsolutePath().normalize();
            if (!target.startsWith(profileRoot)) {
                return null;
            }
            Path relative = profileRoot.relativize(target);
            if (relative.getNameCount() == 0) {
                return null;
            }

            String targetProfile;
            String area;
            Path profileHome;
            if (GUARDED_AREAS.contains(relative.getName(0).toString())) {
                targetProfile = "default";
                area = relative.getName(0).toString();
                profileHome = profileRoot;
            } else if (relative.getNameCount() >= 3
                    && "profiles".equals(relative.getName(0).toString())
                    && GUARDED_AREAS.contains(relative.getName(2).toString())) {
                targetProfile = relative.getName(1).toString();
                area = relative.getName(2).toString();
                profileHome = profileRoot.resolve("profiles").resolve(targetProfile).normalize();
                if (!Files.isDirectory(profileHome)) {
                    return null;
                }
            } else {
                return null;
            }

            String activeProfile = activeProfile(profileRoot, workRoot);
            if (targetProfile.equals(activeProfile)) {
                return null;
            }
            return new CrossProfileTarget(target, profileHome, activeProfile, targetProfile, area);
        } catch (Exception e) {
            return null;
        }
    }

    /** 展开用户主目录前缀，并按工具工作区解析普通相对路径。 */
    private static Path resolveTarget(Path workRoot, String rawPath) {
        String value = StrUtil.nullToEmpty(rawPath).trim();
        if ("~".equals(value)) {
            return Paths.get(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home", "."))
                    .resolve(value.substring(2))
                    .toAbsolutePath()
                    .normalize();
        }
        return workRoot.resolve(value).toAbsolutePath().normalize();
    }

    /**
     * 生成模型可消费的跨 Profile 软拒绝提示。
     *
     * @param target 跨 Profile 目标。
     * @return 脱敏后的拒绝提示。
     */
    static String warning(CrossProfileTarget target) {
        if (target == null) {
            return null;
        }
        return "跨 Profile 写入已被软保护阻止：目标属于 Profile '"
                + target.targetProfile
                + "' 的 "
                + target.area
                + " 目录，当前 Profile 为 '"
                + target.activeProfile
                + "'。仅在用户明确要求修改其他 Profile 后，重试并设置 cross_profile=true。目标："
                + SecretRedactor.redactSensitivePaths(
                        SecretRedactor.redact(target.target.toString(), 500));
    }

    /** 根据当前 Profile 作用域、工具工作区或进程属性解析 Profile 名称。 */
    private static String activeProfile(Path profileRoot, Path workRoot) {
        ProfileRuntimeScope.Context scoped = ProfileRuntimeScope.current();
        if (scoped != null && StrUtil.isNotBlank(scoped.getProfile())) {
            return scoped.getProfile();
        }
        Path normalizedWorkRoot = workRoot.toAbsolutePath().normalize();
        Path profilesRoot = profileRoot.resolve("profiles");
        if (normalizedWorkRoot.startsWith(profilesRoot)) {
            Path relative = profilesRoot.relativize(normalizedWorkRoot);
            if (relative.getNameCount() > 0) {
                return relative.getName(0).toString();
            }
        }
        String configured =
                StrUtil.nullToEmpty(System.getProperty("solonclaw.profile.name")).trim();
        if (StrUtil.isNotBlank(configured)) {
            return configured;
        }
        return "default";
    }

    /** 跨 Profile 写入目标的规范化路径与归属信息。 */
    static final class CrossProfileTarget {
        /** 规范化目标路径。 */
        private final Path target;

        /** 目标 Profile 根目录。 */
        private final Path profileHome;

        /** 当前活动 Profile 名称。 */
        private final String activeProfile;

        /** 目标 Profile 名称。 */
        private final String targetProfile;

        /** 目标 Profile 内的受保护区域。 */
        private final String area;

        /** 创建跨 Profile 目标信息。 */
        private CrossProfileTarget(
                Path target,
                Path profileHome,
                String activeProfile,
                String targetProfile,
                String area) {
            this.target = target;
            this.profileHome = profileHome;
            this.activeProfile = activeProfile;
            this.targetProfile = targetProfile;
            this.area = area;
        }

        /** 返回规范化目标路径。 */
        Path target() {
            return target;
        }

        /** 返回目标 Profile 根目录。 */
        Path profileHome() {
            return profileHome;
        }
    }
}
