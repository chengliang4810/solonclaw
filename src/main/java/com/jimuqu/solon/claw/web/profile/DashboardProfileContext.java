package com.jimuqu.solon.claw.web.profile;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.llm.LlmProviderSupport;
import com.jimuqu.solon.claw.profile.ProfileGatewayStatus;
import com.jimuqu.solon.claw.profile.ProfileManager;
import com.jimuqu.solon.claw.support.LlmProviderService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.noear.solon.core.handle.Context;

/** 解析 Dashboard 请求选择的 Profile，并提供不触碰 JVM 全局状态的配置快照。 */
public final class DashboardProfileContext {
    /** 机器级 Profile 管理器。 */
    private final ProfileManager profileManager;

    /** 当前 Dashboard JVM 实际使用的配置单例。 */
    private final AppConfig currentConfig;

    /**
     * 创建 Dashboard Profile 上下文。
     *
     * @param profileManager Profile 管理器。
     * @param currentConfig 当前 Dashboard JVM 配置。
     */
    public DashboardProfileContext(ProfileManager profileManager, AppConfig currentConfig) {
        if (profileManager == null || currentConfig == null) {
            throw new IllegalArgumentException("Profile manager and current config are required.");
        }
        this.profileManager = profileManager;
        this.currentConfig = currentConfig;
    }

    /**
     * 解析请求 Profile；空值和 current 保持当前 JVM 行为。
     *
     * @param requestedProfile query 或 body 中的 Profile 名。
     * @return 已校验的请求作用域。
     */
    public Scope resolve(String requestedProfile) {
        String requested = StrUtil.nullToEmpty(requestedProfile).trim();
        if (requested.length() == 0 || "current".equalsIgnoreCase(requested)) {
            return currentScope();
        }
        String name = requested.toLowerCase(Locale.ROOT);
        Path home;
        try {
            home = profileManager.requireProfileHome(name).toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new DashboardProfileNotFoundException(
                    "Profile '" + name + "' does not exist.", e);
        }
        Path currentHome = currentHome();
        if (home.equals(currentHome)) {
            return new Scope(name, home, currentConfig, true);
        }
        DashboardProfileConfigFile file =
                new DashboardProfileConfigFile(home.resolve("config.yml"));
        return new Scope(name, home, file.loadAppConfig(home), false);
    }

    /**
     * 返回指定 Scope 的独立网关状态。
     *
     * @param scope 已解析请求作用域。
     * @return 目标 Profile 的 PID 与状态文件视图。
     */
    public ProfileGatewayStatus gatewayStatus(Scope scope) {
        try {
            return profileManager.gatewayStatus(scope.getName());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read gateway status for profile '" + scope.getName() + "'.", e);
        }
    }

    /**
     * @return Dashboard 各资源作用域共用的机器级 Profile 管理器。
     */
    public ProfileManager profileManager() {
        return profileManager;
    }

    /**
     * 从 query 参数读取 Profile。
     *
     * @param context 当前 HTTP 请求。
     * @return profile query 值。
     */
    public static String requestedProfile(Context context) {
        return context == null ? null : context.param("profile");
    }

    /**
     * body.profile 优先于 query.profile，保持 Dashboard 写接口的一致选择语义。
     *
     * @param context 当前 HTTP 请求。
     * @param body 已解析 JSON 对象。
     * @return 最终 Profile 选择。
     */
    public static String requestedProfile(Context context, Map<String, Object> body) {
        Object bodyProfile = body == null ? null : body.get("profile");
        String requested = bodyProfile == null ? null : String.valueOf(bodyProfile).trim();
        return StrUtil.isNotBlank(requested) ? requested : requestedProfile(context);
    }

    /**
     * 创建只读取给定 AppConfig 的 Provider 解析器，禁止回落到全局 RuntimeConfigResolver。
     *
     * @param config Profile 配置快照。
     * @return Profile 独立 Provider 解析服务。
     */
    public static LlmProviderService snapshotProviderService(AppConfig config) {
        return new SnapshotLlmProviderService(config);
    }

    /** 创建当前 JVM Scope。 */
    private Scope currentScope() {
        return new Scope(currentProfileName(), currentHome(), currentConfig, true);
    }

    /** 返回当前 JVM 工作区。 */
    private Path currentHome() {
        return Paths.get(currentConfig.getRuntime().getHome()).toAbsolutePath().normalize();
    }

    /** 返回当前 JVM Profile 名。 */
    private String currentProfileName() {
        return StrUtil.blankToDefault(System.getProperty("solonclaw.profile.name"), "default")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /** 承载单次 Dashboard 请求解析后的 Profile 配置和路径。 */
    public static final class Scope {
        /** Profile 名。 */
        private final String name;

        /** Profile 工作区。 */
        private final Path home;

        /** 独立配置快照；当前 Profile 使用 JVM 单例。 */
        private final AppConfig config;

        /** 是否为当前 Dashboard JVM 正在运行的 Profile。 */
        private final boolean current;

        /** 创建请求作用域。 */
        private Scope(String name, Path home, AppConfig config, boolean current) {
            this.name = name;
            this.home = home;
            this.config = config;
            this.current = current;
        }

        /**
         * @return Profile 名。
         */
        public String getName() {
            return name;
        }

        /**
         * @return Profile 工作区。
         */
        public Path getHome() {
            return home;
        }

        /**
         * @return Profile 配置快照。
         */
        public AppConfig getConfig() {
            return config;
        }

        /**
         * @return 当前 Dashboard JVM Profile 时返回 true。
         */
        public boolean isCurrent() {
            return current;
        }

        /**
         * @return Profile 独立 config.yml 访问器。
         */
        public DashboardProfileConfigFile configFile() {
            return new DashboardProfileConfigFile(home.resolve("config.yml"));
        }
    }

    /** 只使用内存配置快照的 Provider 解析器。 */
    private static final class SnapshotLlmProviderService extends LlmProviderService {
        /** Profile 配置快照。 */
        private final AppConfig config;

        /** 创建快照 Provider 解析器。 */
        private SnapshotLlmProviderService(AppConfig config) {
            super(config);
            this.config = config;
        }

        /** {@inheritDoc} */
        @Override
        public ResolvedProvider resolveEffectiveProvider(SessionRecord session) {
            return resolveEffectiveProvider(session, null);
        }

        /** {@inheritDoc} */
        @Override
        public ResolvedProvider resolveEffectiveProvider(
                SessionRecord session, String agentDefaultModel) {
            String providerKey = StrUtil.nullToEmpty(config.getModel().getProviderKey()).trim();
            String model = StrUtil.nullToEmpty(agentDefaultModel).trim();
            if (session != null && StrUtil.isNotBlank(session.getTransientProviderOverride())) {
                providerKey = session.getTransientProviderOverride().trim();
            }
            if (session != null && StrUtil.isNotBlank(session.getTransientModelOverride())) {
                model = session.getTransientModelOverride().trim();
            }
            if (StrUtil.isBlank(model)) {
                model = StrUtil.nullToEmpty(config.getModel().getDefault()).trim();
            }
            return resolveProvider(providerKey, model);
        }

        /** {@inheritDoc} */
        @Override
        public ResolvedProvider resolveProvider(String providerKey, String explicitModel) {
            String key = StrUtil.nullToEmpty(providerKey).trim();
            AppConfig.ProviderConfig provider = config.getProviders().get(key);
            if (provider == null) {
                throw new IllegalStateException("未找到 provider：" + key);
            }
            String dialect = LlmProviderSupport.normalizeDialect(provider.getDialect());
            String model = StrUtil.blankToDefault(explicitModel, provider.getDefaultModel());
            ResolvedProvider result = new ResolvedProvider();
            result.setProviderKey(key);
            result.setLabel(StrUtil.blankToDefault(provider.getName(), key));
            result.setDialect(dialect);
            result.setBaseUrl(StrUtil.nullToEmpty(provider.getBaseUrl()).trim());
            result.setApiUrl(LlmProviderSupport.buildApiUrl(provider.getBaseUrl(), dialect));
            result.setApiKey(StrUtil.nullToEmpty(provider.getApiKey()).trim());
            result.setModel(StrUtil.nullToEmpty(model).trim());
            return result;
        }

        /** {@inheritDoc} */
        @Override
        public List<ResolvedProvider> resolveFallbackProviders() {
            if (config.getFallbackProviders() == null) {
                return Collections.emptyList();
            }
            List<ResolvedProvider> result = new ArrayList<ResolvedProvider>();
            for (AppConfig.FallbackProviderConfig fallback : config.getFallbackProviders()) {
                if (fallback != null && StrUtil.isNotBlank(fallback.getProvider())) {
                    result.add(resolveProvider(fallback.getProvider(), fallback.getModel()));
                }
            }
            return result;
        }
    }
}
