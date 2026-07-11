package com.jimuqu.solon.claw.profile;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** 根据工作区路径解析当前逻辑运行时所属 Profile，避免依赖进程级可变属性。 */
public final class ProfileRuntimeIdentity {
    /** 合法 Profile 名格式。 */
    private static final String PROFILE_PATTERN = "[a-z0-9][a-z0-9_-]{0,63}";

    /** 工具类不保存实例状态。 */
    private ProfileRuntimeIdentity() {}

    /**
     * 从 AppConfig 的工作区解析 Profile 名。
     *
     * <p>默认工作区返回 default；位于 root/profiles/name 的工作区返回 name。无法确认路径时才回退启动属性。
     *
     * @param appConfig 当前逻辑运行时配置。
     * @return default 或合法命名 Profile。
     */
    public static String resolve(AppConfig appConfig) {
        Path workspace = workspace(appConfig);
        Path root = root();
        if (workspace != null && workspace.getParent() != null) {
            Path parent = workspace.getParent();
            if (parent.getFileName() != null
                    && "profiles".equals(parent.getFileName().toString())
                    && workspace.getFileName() != null) {
                String candidate = normalize(workspace.getFileName().toString());
                if (candidate != null && !"default".equals(candidate)) {
                    return candidate;
                }
            }
        }
        if (workspace != null && root != null) {
            if (workspace.equals(root)) {
                return "default";
            }
            Path parent = workspace.getParent();
            if (parent != null
                    && parent.equals(root.resolve("profiles"))
                    && workspace.getFileName() != null) {
                String candidate = normalize(workspace.getFileName().toString());
                if (candidate != null && !"default".equals(candidate)) {
                    return candidate;
                }
            }
        }
        String configured = normalize(System.getProperty("solonclaw.profile.name"));
        return configured == null ? "default" : configured;
    }

    /** 返回机器级 default Profile 根目录。 */
    public static Path root() {
        String configured =
                StrUtil.nullToEmpty(System.getProperty("solonclaw.profile.root")).trim();
        if (configured.length() > 0) {
            return normalizedPath(configured);
        }
        try {
            return ProfileManager.current().root().toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回配置中的规范化工作区路径。 */
    private static Path workspace(AppConfig appConfig) {
        if (appConfig == null || appConfig.getRuntime() == null) {
            return null;
        }
        String value = StrUtil.nullToEmpty(appConfig.getRuntime().getHome()).trim();
        return value.length() == 0 ? null : normalizedPath(value);
    }

    /** 安全规范化路径，非法值返回 null。 */
    private static Path normalizedPath(String value) {
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** 规范化并校验 Profile 名。 */
    private static String normalize(String value) {
        String normalized = StrUtil.nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        return normalized.matches(PROFILE_PATTERN) ? normalized : null;
    }
}
