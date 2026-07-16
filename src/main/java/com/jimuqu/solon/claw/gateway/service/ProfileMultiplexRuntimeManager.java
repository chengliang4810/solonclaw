package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.DeliveryService;
import com.jimuqu.solon.claw.gateway.authorization.GatewayOpenPolicyStartupGuard;
import com.jimuqu.solon.claw.profile.ProfileEnvironmentLoader;
import com.jimuqu.solon.claw.profile.ProfileMultiplexProfiles;
import com.jimuqu.solon.claw.profile.ProfileRuntimeScope;
import com.jimuqu.solon.claw.support.ErrorTextSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 管理 default 网关进程内的命名 Profile 子运行时，并保证路由、凭据和生命周期相互隔离。 */
public class ProfileMultiplexRuntimeManager implements ProfileMessageRouter, AutoCloseable {
    /** Profile 复用运行时日志；只记录 Profile、平台和脱敏错误，不记录凭据。 */
    private static final Logger log = LoggerFactory.getLogger(ProfileMultiplexRuntimeManager.class);

    /** 合法 Profile 名格式。 */
    private static final String PROFILE_PATTERN = "[a-z0-9][a-z0-9_-]{0,63}";

    /** default Profile 的应用配置。 */
    private final AppConfig defaultAppConfig;

    /** default Profile 根目录。 */
    private final Path root;

    /** default Profile 的网关服务，用于安装命名 Profile 路由器。 */
    private final DefaultGatewayService defaultGatewayService;

    /** default Profile 已创建的渠道适配器，用于预占正在消费的渠道凭据。 */
    private final Map<PlatformType, ChannelAdapter> defaultAdapters;

    /** 命名 Profile 子运行时工厂。 */
    private final ProfileRuntimeBundleFactory bundleFactory;

    /** 运行时重建与消息处理之间的读写锁，避免消息落到正在关闭的子容器。 */
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

    /** 当前已经成功启动的命名 Profile 子运行时。 */
    private volatile Map<String, ProfileRuntimeBundle> bundles = Collections.emptyMap();

    /** 当前实际承载的 Profile，default 固定在首位。 */
    private volatile List<String> servedProfiles = Collections.singletonList("default");

    /** 最近一次启动失败的 Profile 与脱敏错误摘要。 */
    private volatile Map<String, String> failures = Collections.emptyMap();

    /** 最近一次因重复凭据被禁用的平台及其凭据所有者。 */
    private volatile Map<String, Map<PlatformType, String>> credentialConflicts =
            Collections.emptyMap();

    /** 防止关闭后被配置刷新重新启动。 */
    private volatile boolean closed;

    /** 主进程状态文件写入器；Solon 完成启动后再绑定。 */
    private volatile GatewayRuntimeStatusService runtimeStatusService;

    /**
     * 创建 Profile 复用运行时管理器。
     *
     * @param defaultAppConfig default Profile 配置。
     * @param rootContext 主 Solon Bean 容器，仅用于复制构建元数据。
     * @param defaultGatewayService default Profile 网关服务。
     * @param defaultAdapters default Profile 渠道适配器。
     */
    public ProfileMultiplexRuntimeManager(
            AppConfig defaultAppConfig,
            AppContext rootContext,
            DefaultGatewayService defaultGatewayService,
            Map<PlatformType, ChannelAdapter> defaultAdapters) {
        this(
                defaultAppConfig,
                defaultGatewayService,
                defaultAdapters,
                new ProfileRuntimeBundleFactory(rootContext));
    }

    /** 创建可注入子运行时工厂的管理器，供定向测试验证失败和凭据冲突路径。 */
    ProfileMultiplexRuntimeManager(
            AppConfig defaultAppConfig,
            DefaultGatewayService defaultGatewayService,
            Map<PlatformType, ChannelAdapter> defaultAdapters,
            ProfileRuntimeBundleFactory bundleFactory) {
        if (defaultAppConfig == null || defaultGatewayService == null || bundleFactory == null) {
            throw new IllegalArgumentException(
                    "Default config, gateway service and Profile runtime factory are required.");
        }
        this.defaultAppConfig = defaultAppConfig;
        this.root = Paths.get(defaultAppConfig.getRuntime().getHome()).toAbsolutePath().normalize();
        this.defaultGatewayService = defaultGatewayService;
        this.defaultAdapters =
                defaultAdapters == null
                        ? Collections.<PlatformType, ChannelAdapter>emptyMap()
                        : defaultAdapters;
        this.bundleFactory = bundleFactory;
    }

    /** 启动或按最新磁盘配置完整重建全部命名 Profile 子运行时。 */
    public void start() {
        reload();
    }

    /**
     * 关闭旧子运行时并按稳定 Profile 顺序重新装配。
     *
     * <p>失败的 Profile 只记录错误并从 served_profiles 排除；同平台重复凭据只禁用后出现的重复适配器，不影响该 Profile 的其他渠道和 Agent 运行图。
     */
    public void reload() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            defaultGatewayService.setProfileMessageRouter(null);
            closeBundles(bundles);
            bundles = Collections.emptyMap();
            servedProfiles = Collections.singletonList("default");
            failures = Collections.emptyMap();
            credentialConflicts = Collections.emptyMap();

            if (!defaultAppConfig.getGateway().isMultiplexProfiles()) {
                syncRuntimeStatus(true);
                return;
            }

            LinkedHashMap<String, String> claimedCredentials = seedDefaultCredentialClaims();
            LinkedHashMap<String, ProfileRuntimeBundle> started =
                    new LinkedHashMap<String, ProfileRuntimeBundle>();
            LinkedHashMap<String, String> failed = new LinkedHashMap<String, String>();
            LinkedHashMap<String, Map<PlatformType, String>> conflicts =
                    new LinkedHashMap<String, Map<PlatformType, String>>();

            List<String> names;
            try {
                names = ProfileMultiplexProfiles.names(root);
            } catch (Exception e) {
                String error = ErrorTextSupport.safeError(e);
                failures = Collections.singletonMap("profiles", error);
                defaultGatewayService.setProfileMessageRouter(this);
                syncRuntimeStatus(true);
                log.error(
                        "Profile runtime enumeration failed: errorType={}, error={}",
                        e.getClass().getSimpleName(),
                        error);
                return;
            }
            for (String profile : names) {
                if ("default".equals(profile)) {
                    continue;
                }
                Path home = root.resolve("profiles").resolve(profile).normalize();
                try {
                    LoadedProfile loaded = loadProfile(profile, home);
                    GatewayOpenPolicyStartupGuard.requireAllowed(loaded.appConfig, profile);
                    LinkedHashMap<String, String> candidateClaims =
                            new LinkedHashMap<String, String>(claimedCredentials);
                    Map<PlatformType, String> profileConflicts =
                            disableDuplicateCredentials(profile, loaded.appConfig, candidateClaims);
                    if (!profileConflicts.isEmpty()) {
                        conflicts.put(profile, profileConflicts);
                    }
                    ProfileRuntimeBundle bundle =
                            bundleFactory.create(
                                    profile, home, loaded.environment, loaded.appConfig);
                    started.put(profile, bundle);
                    claimedCredentials = candidateClaims;
                } catch (GatewayOpenPolicyStartupGuard.ViolationException e) {
                    closeBundles(started);
                    bundles = Collections.emptyMap();
                    servedProfiles = Collections.singletonList("default");
                    failed.put(profile, ErrorTextSupport.safeError(e));
                    failures = Collections.unmodifiableMap(failed);
                    credentialConflicts = immutableConflicts(conflicts);
                    syncRuntimeStatus(true);
                    throw e;
                } catch (Throwable e) {
                    String error = ErrorTextSupport.safeError(e);
                    failed.put(profile, error);
                    log.error(
                            "Profile runtime startup failed: profile={}, errorType={}, error={}",
                            profile,
                            e.getClass().getSimpleName(),
                            error);
                }
            }

            bundles = Collections.unmodifiableMap(started);
            failures = Collections.unmodifiableMap(failed);
            credentialConflicts = immutableConflicts(conflicts);
            List<String> served = new ArrayList<String>();
            served.add("default");
            served.addAll(started.keySet());
            servedProfiles = Collections.unmodifiableList(served);
            defaultGatewayService.setProfileMessageRouter(this);
            syncRuntimeStatus(true);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 把显式命名 Profile 消息路由到对应子容器，并在整轮结束前阻止该容器被刷新关闭。
     *
     * @param profile 已规范化 Profile 名。
     * @param message 待处理消息。
     * @return 目标 Profile 的处理结果；未承载时返回明确错误。
     */
    @Override
    public GatewayReply route(String profile, GatewayMessage message) throws Exception {
        String normalized = normalizeProfile(profile);
        lifecycleLock.readLock().lock();
        try {
            ProfileRuntimeBundle bundle = bundles.get(normalized);
            if (closed || bundle == null) {
                return GatewayReply.error("Profile '" + normalized + "' is not served here.");
            }
            return bundle.handle(message);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * @return default 加实际成功子运行时组成的稳定快照。
     */
    public List<String> servedProfiles() {
        return new ArrayList<String>(servedProfiles);
    }

    /**
     * @return 当前成功子运行时的只读快照，供状态和定向测试读取。
     */
    public Map<String, ProfileRuntimeBundle> runtimes() {
        return new LinkedHashMap<String, ProfileRuntimeBundle>(bundles);
    }

    /**
     * 查找已经启动的命名 Profile 运行时，不触发磁盘加载或后台资源创建。
     *
     * @param profile 命名 Profile。
     * @return 已启动子运行时；尚未启动时返回 null。
     */
    public ProfileRuntimeBundle findRuntime(String profile) {
        String normalized = normalizeProfile(profile);
        lifecycleLock.readLock().lock();
        try {
            return bundles.get(normalized);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * 读取命名 Profile 子运行时的真实渠道状态；未由 multiplex 承载时返回 null。
     *
     * @param profile 命名 Profile。
     * @return 子运行时渠道状态，或 null。
     */
    public List<ChannelStatus> deliveryStatuses(String profile) {
        String normalized = normalizeProfile(profile);
        lifecycleLock.readLock().lock();
        try {
            ProfileRuntimeBundle bundle = bundles.get(normalized);
            if (bundle == null) {
                return null;
            }
            DeliveryService deliveryService = bundle.appContext().getBean(DeliveryService.class);
            return deliveryService == null
                    ? Collections.<ChannelStatus>emptyList()
                    : deliveryService.statuses();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** 关闭并移除指定命名 Profile 子运行时，供删除和重命名前释放文件句柄。 */
    public void releaseRuntime(String profile) {
        String normalized = normalizeProfile(profile);
        lifecycleLock.writeLock().lock();
        try {
            ProfileRuntimeBundle bundle = bundles.get(normalized);
            if (bundle == null) {
                return;
            }
            LinkedHashMap<String, ProfileRuntimeBundle> next =
                    new LinkedHashMap<String, ProfileRuntimeBundle>(bundles);
            next.remove(normalized);
            bundles = Collections.unmodifiableMap(next);
            updateServedProfiles(next);
            updateConflictSnapshot(normalized, Collections.<PlatformType, String>emptyMap());
            updateFailureSnapshot(normalized, null);
            try {
                bundle.close();
            } finally {
                syncRuntimeStatus(true);
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * 返回或只启动指定命名 Profile，供 Dashboard 会话等显式跨 Profile 请求复用。
     *
     * <p>普通 CLI/TUI Bean 初始化不会调用此方法，因此仍不会批量启动所有 Profile；只有调用方明确请求的一个 Profile 会被装配。
     *
     * @param profile 命名 Profile。
     * @return 已启动的独立 Profile 运行时。
     */
    public ProfileRuntimeBundle requireRuntime(String profile) {
        String normalized = normalizeProfile(profile);
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("Profile runtime manager is closed.");
            }
            ProfileRuntimeBundle existing = bundles.get(normalized);
            if (existing != null) {
                return existing;
            }
            if (!defaultAppConfig.getGateway().isMultiplexProfiles()) {
                throw new IllegalStateException("Profile multiplex runtime is disabled.");
            }
            Path home = root.resolve("profiles").resolve(normalized).normalize();
            if (!Files.isDirectory(home)) {
                throw new IllegalArgumentException("Profile '" + normalized + "' does not exist.");
            }

            LinkedHashMap<String, String> claimed = currentCredentialClaims();
            LoadedProfile loaded = loadProfile(normalized, home);
            try {
                GatewayOpenPolicyStartupGuard.requireAllowed(loaded.appConfig, normalized);
            } catch (GatewayOpenPolicyStartupGuard.ViolationException e) {
                updateFailureSnapshot(normalized, ErrorTextSupport.safeError(e));
                syncRuntimeStatus(true);
                throw e;
            }
            Map<PlatformType, String> profileConflicts =
                    disableDuplicateCredentials(normalized, loaded.appConfig, claimed);
            try {
                ProfileRuntimeBundle bundle =
                        bundleFactory.create(
                                normalized, home, loaded.environment, loaded.appConfig);
                LinkedHashMap<String, ProfileRuntimeBundle> next =
                        new LinkedHashMap<String, ProfileRuntimeBundle>(bundles);
                next.put(normalized, bundle);
                bundles = Collections.unmodifiableMap(next);
                updateServedProfiles(next);
                updateConflictSnapshot(normalized, profileConflicts);
                updateFailureSnapshot(normalized, null);
                defaultGatewayService.setProfileMessageRouter(this);
                syncRuntimeStatus(true);
                return bundle;
            } catch (RuntimeException e) {
                updateFailureSnapshot(normalized, ErrorTextSupport.safeError(e));
                syncRuntimeStatus(true);
                throw e;
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * @return 最近一次启动失败摘要的只读快照。
     */
    public Map<String, String> failures() {
        return new LinkedHashMap<String, String>(failures);
    }

    /**
     * @return 最近一次重复凭据冲突的只读快照。
     */
    public Map<String, Map<PlatformType, String>> credentialConflicts() {
        LinkedHashMap<String, Map<PlatformType, String>> result =
                new LinkedHashMap<String, Map<PlatformType, String>>();
        for (Map.Entry<String, Map<PlatformType, String>> entry : credentialConflicts.entrySet()) {
            result.put(entry.getKey(), new EnumMap<PlatformType, String>(entry.getValue()));
        }
        return result;
    }

    /**
     * 绑定主进程运行状态写入器，并立即同步当前实际承载列表。
     *
     * @param service default Profile 的状态文件服务。
     */
    public void bindRuntimeStatusService(GatewayRuntimeStatusService service) {
        lifecycleLock.readLock().lock();
        try {
            this.runtimeStatusService = service;
            syncRuntimeStatus(false);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** 关闭全部命名 Profile 子容器，不关闭 default Solon 容器或全局应用。 */
    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            defaultGatewayService.setProfileMessageRouter(null);
            closeBundles(bundles);
            bundles = Collections.emptyMap();
            servedProfiles = Collections.singletonList("default");
            syncRuntimeStatus(true);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /** 读取命名 Profile 的独立配置和局部环境，并强制关闭递归 multiplex。 */
    private LoadedProfile loadProfile(String profile, Path home) {
        Props props = new Props();
        props.loadAddIfAbsent("app.yml");
        props.put("solonclaw.workspace", home.toAbsolutePath().normalize().toString());
        props.put("solonclaw.profile.name", profile);
        Map<String, String> environment = ProfileEnvironmentLoader.load(home);
        AppConfig config;
        try (ProfileRuntimeScope.Scope ignored =
                ProfileRuntimeScope.open(profile, home, environment, null)) {
            config = AppConfig.loadDetached(props);
        }
        ProfileEnvironmentLoader.apply(config, environment);
        config.getGateway().setMultiplexProfiles(false);
        return new LoadedProfile(config, environment);
    }

    /** 用 default 实际启用的适配器预占凭据，保持与主渠道启动结果一致。 */
    private LinkedHashMap<String, String> seedDefaultCredentialClaims() {
        LinkedHashMap<String, String> claimed = new LinkedHashMap<String, String>();
        for (Map.Entry<PlatformType, ChannelAdapter> entry : defaultAdapters.entrySet()) {
            ChannelAdapter adapter = entry.getValue();
            if (adapter == null || !adapter.isEnabled()) {
                continue;
            }
            String fingerprint = credentialFingerprint(defaultAppConfig, entry.getKey());
            if (fingerprint != null) {
                claimed.put(claimKey(entry.getKey(), fingerprint), "default");
            }
        }
        return claimed;
    }

    /** 汇总 default 和当前成功子运行时已经占用的渠道凭据。 */
    private LinkedHashMap<String, String> currentCredentialClaims() {
        LinkedHashMap<String, String> claimed = seedDefaultCredentialClaims();
        for (Map.Entry<String, ProfileRuntimeBundle> entry : bundles.entrySet()) {
            AppConfig config = entry.getValue().appConfig();
            for (PlatformType platform : PlatformType.values()) {
                AppConfig.ChannelConfig channel = channelConfig(config, platform);
                if (channel == null || !channel.isEnabled()) {
                    continue;
                }
                String fingerprint = credentialFingerprint(config, platform);
                if (fingerprint != null) {
                    claimed.put(claimKey(platform, fingerprint), entry.getKey());
                }
            }
        }
        return claimed;
    }

    /** 禁用与先前 Profile 使用完全相同平台凭据的重复渠道，并返回凭据所有者。 */
    private Map<PlatformType, String> disableDuplicateCredentials(
            String profile, AppConfig config, Map<String, String> claimed) {
        EnumMap<PlatformType, String> conflicts =
                new EnumMap<PlatformType, String>(PlatformType.class);
        for (PlatformType platform : PlatformType.values()) {
            AppConfig.ChannelConfig channel = channelConfig(config, platform);
            if (channel == null || !channel.isEnabled()) {
                continue;
            }
            String fingerprint = credentialFingerprint(config, platform);
            if (fingerprint == null) {
                continue;
            }
            String key = claimKey(platform, fingerprint);
            String owner = claimed.get(key);
            if (owner != null) {
                channel.setEnabled(false);
                conflicts.put(platform, owner);
                log.error(
                        "Duplicate Profile channel credential disabled: owner={}, duplicate={},"
                                + " platform={}",
                        owner,
                        profile,
                        platform);
            } else {
                claimed.put(key, profile);
            }
        }
        return conflicts;
    }

    /** 返回六个已确认国内渠道的配置，其他平台不参与 Profile 凭据碰撞判断。 */
    private AppConfig.ChannelConfig channelConfig(AppConfig config, PlatformType platform) {
        if (config == null || config.getChannels() == null || platform == null) {
            return null;
        }
        switch (platform) {
            case FEISHU:
                return config.getChannels().getFeishu();
            case DINGTALK:
                return config.getChannels().getDingtalk();
            case WECOM:
                return config.getChannels().getWecom();
            case WEIXIN:
                return config.getChannels().getWeixin();
            case QQBOT:
                return config.getChannels().getQqbot();
            case YUANBAO:
                return config.getChannels().getYuanbao();
            default:
                return null;
        }
    }

    /** 对平台主身份和密钥计算不可逆指纹；凭据不完整时不做猜测性冲突判断。 */
    private String credentialFingerprint(AppConfig config, PlatformType platform) {
        AppConfig.ChannelConfig channel = channelConfig(config, platform);
        if (channel == null) {
            return null;
        }
        String identity;
        String secret;
        switch (platform) {
            case FEISHU:
                identity = channel.getAppId();
                secret = channel.getAppSecret();
                break;
            case DINGTALK:
                identity = channel.getClientId();
                secret = channel.getClientSecret();
                break;
            case WECOM:
                identity = channel.getBotId();
                secret = channel.getSecret();
                break;
            case WEIXIN:
                identity = channel.getAccountId();
                secret = channel.getToken();
                break;
            case QQBOT:
                identity = channel.getAppId();
                secret = channel.getClientSecret();
                break;
            case YUANBAO:
                identity = channel.getAppId();
                secret = channel.getAppSecret();
                break;
            default:
                return null;
        }
        if (StrUtil.isBlank(identity) || StrUtil.isBlank(secret)) {
            return null;
        }
        String material =
                "solonclaw-profile-multiplex\u0000"
                        + platform.name()
                        + "\u0000"
                        + identity.trim()
                        + "\u0000"
                        + secret.trim();
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(value & 0xff)));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    /** 拼接平台与不可逆凭据指纹，避免不同平台碰撞。 */
    private String claimKey(PlatformType platform, String fingerprint) {
        return platform.name() + ":" + fingerprint;
    }

    /** 规范化并校验路由目标 Profile。 */
    private String normalizeProfile(String profile) {
        String normalized = StrUtil.nullToEmpty(profile).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(PROFILE_PATTERN) || "default".equals(normalized)) {
            throw new IllegalArgumentException("Invalid named Profile: " + normalized);
        }
        return normalized;
    }

    /** 逐个释放子容器；单个关闭失败不会阻断其他 Profile 清理。 */
    private void closeBundles(Map<String, ProfileRuntimeBundle> current) {
        if (current == null || current.isEmpty()) {
            return;
        }
        List<ProfileRuntimeBundle> reverse = new ArrayList<ProfileRuntimeBundle>(current.values());
        Collections.reverse(reverse);
        for (ProfileRuntimeBundle bundle : reverse) {
            try {
                bundle.close();
            } catch (Throwable e) {
                log.warn(
                        "Profile runtime shutdown failed: profile={}, errorType={}, error={}",
                        bundle.profile(),
                        e.getClass().getSimpleName(),
                        ErrorTextSupport.safeError(e));
            }
        }
    }

    /** 把 multiplex 开关和实际成功列表同步到主进程状态文件。 */
    private void syncRuntimeStatus(boolean persist) {
        GatewayRuntimeStatusService service = runtimeStatusService;
        if (service == null) {
            return;
        }
        if (!closed && defaultAppConfig.getGateway().isMultiplexProfiles()) {
            service.setServedProfiles(servedProfiles);
        } else {
            service.setServedProfiles(null);
        }
        if (persist) {
            service.writeState("running", "Profile gateway topology refreshed.");
        }
    }

    /** 根据成功子运行时 Map 重新生成 default 在首位的状态列表。 */
    private void updateServedProfiles(Map<String, ProfileRuntimeBundle> current) {
        List<String> served = new ArrayList<String>();
        served.add("default");
        served.addAll(current.keySet());
        servedProfiles = Collections.unmodifiableList(served);
    }

    /** 更新一个 Profile 的重复凭据冲突快照。 */
    private void updateConflictSnapshot(
            String profile, Map<PlatformType, String> profileConflicts) {
        LinkedHashMap<String, Map<PlatformType, String>> next =
                new LinkedHashMap<String, Map<PlatformType, String>>(credentialConflicts);
        if (profileConflicts == null || profileConflicts.isEmpty()) {
            next.remove(profile);
        } else {
            next.put(profile, new EnumMap<PlatformType, String>(profileConflicts));
        }
        credentialConflicts = immutableConflicts(next);
    }

    /** 更新一个 Profile 的启动失败快照；error 为空时删除旧失败。 */
    private void updateFailureSnapshot(String profile, String error) {
        LinkedHashMap<String, String> next = new LinkedHashMap<String, String>(failures);
        if (StrUtil.isBlank(error)) {
            next.remove(profile);
        } else {
            next.put(profile, error);
        }
        failures = Collections.unmodifiableMap(next);
    }

    /** 深复制并冻结凭据冲突结果，避免运行中被调用方修改。 */
    private Map<String, Map<PlatformType, String>> immutableConflicts(
            Map<String, Map<PlatformType, String>> source) {
        LinkedHashMap<String, Map<PlatformType, String>> result =
                new LinkedHashMap<String, Map<PlatformType, String>>();
        for (Map.Entry<String, Map<PlatformType, String>> entry : source.entrySet()) {
            result.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(
                            new EnumMap<PlatformType, String>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 保存一次命名 Profile 的已解析配置和局部环境。 */
    private static final class LoadedProfile {
        /** Profile 独立配置。 */
        private final AppConfig appConfig;

        /** Profile 局部环境快照。 */
        private final Map<String, String> environment;

        /** 创建不可变加载结果。 */
        private LoadedProfile(AppConfig appConfig, Map<String, String> environment) {
            this.appConfig = appConfig;
            this.environment = environment;
        }
    }
}
