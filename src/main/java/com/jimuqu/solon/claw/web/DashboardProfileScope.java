package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.web.profile.DashboardProfileContext;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.noear.solon.Solon;
import org.noear.solon.core.Props;

/** Dashboard 跨 Profile 请求的统一解析器，保证请求作用域不依赖可变全局属性。 */
public final class DashboardProfileScope {
    /** Profile 管理器，用于名称校验和独立工作区定位。 */
    private final ProfileManager profileManager;

    /** 当前 Dashboard 进程实际加载的 Profile 名。 */
    private final String currentProfile;

    /** 当前 Dashboard 进程实际使用的配置单例；未注入时按请求工作区加载。 */
    private final AppConfig currentConfig;

    /** 使用当前进程启动属性创建 Dashboard Profile 解析器。 */
    public DashboardProfileScope() {
        this(ProfileManager.current(), null, null);
    }

    /**
     * 使用可注入的 Profile 管理器创建解析器，便于隔离测试和嵌入式运行。
     *
     * @param profileManager Profile 路径管理器。
     * @param currentProfile 当前 Dashboard 进程 Profile；为空时从启动属性解析。
     */
    public DashboardProfileScope(ProfileManager profileManager, String currentProfile) {
        this(profileManager, currentProfile, null);
    }

    /**
     * 复用 Dashboard 其他管理页的机器级 Profile 上下文。
     *
     * @param profileContext 已注入的统一 Profile 上下文。
     */
    public DashboardProfileScope(DashboardProfileContext profileContext) {
        if (profileContext == null) {
            throw new IllegalArgumentException("Dashboard profile context is required.");
        }
        this.profileManager = profileContext.profileManager();
        this.currentProfile = profileContext.resolve(null).getName();
        this.currentConfig = profileContext.resolve(null).getConfig();
    }

    /**
     * 使用显式 Profile 管理器和当前配置创建解析器。
     *
     * @param profileManager 机器级 Profile 管理器。
     * @param currentProfile 当前 Profile 名。
     * @param currentConfig 当前 Profile 配置单例。
     */
    public DashboardProfileScope(
            ProfileManager profileManager, String currentProfile, AppConfig currentConfig) {
        if (profileManager == null) {
            throw new IllegalArgumentException("Profile manager is required.");
        }
        this.profileManager = profileManager;
        this.currentProfile = normalizeCurrentProfile(currentProfile);
        this.currentConfig = currentConfig;
    }

    /**
     * 解析查询参数和请求体中的 Profile，写请求以请求体显式值优先。
     *
     * @param queryProfile 查询参数中的 Profile。
     * @param body 请求体；允许包含 profile 字段。
     * @return 显式 Profile 或当前 Dashboard Profile。
     */
    public String requestedProfile(String queryProfile, Map<String, Object> body) {
        String bodyProfile = body == null ? null : text(body.get("profile"));
        return bodyProfile == null ? text(queryProfile) : bodyProfile;
    }

    /**
     * 解析并校验目标 Profile；未知 Profile 明确抛出可映射为 HTTP 404 的异常。
     *
     * @param requestedProfile 可选 Profile 名。
     * @return 目标 Profile 名和独立工作区。
     */
    public Resolved resolve(String requestedProfile) {
        String selected = text(requestedProfile);
        if (selected == null || "current".equalsIgnoreCase(selected)) {
            selected = currentProfile;
        }
        try {
            Path home = profileManager.requireProfileHome(selected);
            String name =
                    home.equals(profileManager.root()) ? "default" : home.getFileName().toString();
            return new Resolved(name, home, name.equals(currentProfile));
        } catch (IOException e) {
            throw new ProfileNotFoundException(e.getMessage(), e);
        }
    }

    /**
     * 加载目标 Profile 的独立配置快照，不修改当前进程的 system properties 或全局配置解析器。
     *
     * @param resolved 已校验的 Profile 作用域。
     * @return 目标 Profile 配置。
     */
    public AppConfig loadConfig(Resolved resolved) {
        if (resolved == null) {
            throw new IllegalArgumentException("Resolved profile is required.");
        }
        if (resolved.isCurrent() && currentConfig != null) {
            return currentConfig;
        }
        Props props = Solon.cfg() == null ? new Props() : new Props(Solon.cfg());
        props.put("solonclaw.workspace", resolved.getHome().toString());
        return AppConfig.loadDetached(props);
    }

    /** 返回当前 Dashboard 进程实际加载的 Profile 名。 */
    public String currentProfile() {
        return currentProfile;
    }

    /** 从显式参数或启动路径推导当前 Profile，并回退到 default。 */
    private String normalizeCurrentProfile(String explicit) {
        String selected = text(explicit);
        if (selected == null) {
            selected = text(System.getProperty("solonclaw.profile.name"));
        }
        if (selected != null) {
            return canonicalName(selected);
        }
        String workspace = text(System.getProperty("solonclaw.workspace"));
        if (workspace != null) {
            Path current = Paths.get(workspace).toAbsolutePath().normalize();
            if (current.equals(profileManager.root())) {
                return "default";
            }
            Path parent = current.getParent();
            if (parent != null && parent.equals(profileManager.root().resolve("profiles"))) {
                return canonicalName(current.getFileName().toString());
            }
        }
        return "default";
    }

    /** 通过 ProfileManager 的路径规则获得规范名称，同时复用其名称校验。 */
    private String canonicalName(String raw) {
        Path home = profileManager.profileHome(raw);
        return home.equals(profileManager.root()) ? "default" : home.getFileName().toString();
    }

    /** 读取非空文本。 */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.length() == 0 ? null : text;
    }

    /** 已校验的 Profile 作用域。 */
    public static final class Resolved {
        /** 规范化 Profile 名。 */
        private final String name;

        /** Profile 独立工作区。 */
        private final Path home;

        /** 是否为当前 Dashboard 进程实际加载的 Profile。 */
        private final boolean current;

        /** 创建不可变 Profile 作用域。 */
        private Resolved(String name, Path home, boolean current) {
            this.name = name;
            this.home = home;
            this.current = current;
        }

        /** 返回规范化 Profile 名。 */
        public String getName() {
            return name;
        }

        /** 返回 Profile 独立工作区。 */
        public Path getHome() {
            return home;
        }

        /** 返回是否为当前 Dashboard 进程 Profile。 */
        public boolean isCurrent() {
            return current;
        }
    }

    /** 未知 Profile 异常，控制器应映射为 HTTP 404。 */
    public static final class ProfileNotFoundException extends IllegalStateException {
        /** 创建未知 Profile 异常。 */
        public ProfileNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
