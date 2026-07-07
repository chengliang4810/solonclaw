package com.jimuqu.solon.claw.gateway.platform.base;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.ChannelStatus;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.service.ChannelAdapter;
import com.jimuqu.solon.claw.core.service.InboundMessageHandler;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.SecretValueGuard;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.support.constants.GatewayCommandConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 可配置渠道适配器基类，负责处理启用状态、连接状态和基础日志。 */
public abstract class AbstractConfigurableChannelAdapter implements ChannelAdapter {
    /** 渠道日志器。 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 当前适配器对应的平台。 */
    private final PlatformType platformType;

    /** 动态渠道配置引用。 */
    private final AppConfig.ChannelConfig channelConfig;

    /** 当前连接状态。 */
    private boolean connected;

    /** 当前详情描述。 */
    private String detail;

    /** 渠道 setup 状态。 */
    private String setupState;

    /** 渠道连接模式。 */
    private String connectionMode;

    /** 缺失配置路径。 */
    private final List<String> missingConfig = new ArrayList<String>();

    /** 功能标签。 */
    private final List<String> features = new ArrayList<String>();

    /** 最近一次错误码。 */
    private String lastErrorCode;

    /** 最近一次错误消息。 */
    private String lastErrorMessage;

    /** 入站消息处理器。 */
    private InboundMessageHandler inboundMessageHandler;

    /**
     * 控制命令专用并发执行器（懒加载）。
     *
     * <p>国内各渠道的入站消息走单线程串行执行器，整个 Agent 运行（多轮 ReAct 循环）会独占该线程。
     * 如果用户在运行过程中发送 {@code /stop} 等控制命令，会被排在该串行队列的任务之后，导致取消逻辑
     * 来不及触发（等任务跑完时 runningRuns 已为空）。这里用一个独立的并发线程池专门投递控制命令，
     * 使其不被运行中的任务阻塞。其余普通消息仍由各渠道自己的串行执行器处理，保持原有顺序与互斥语义。
     */
    private volatile ExecutorService controlExecutor;

    /** 构造基础适配器。 */
    protected AbstractConfigurableChannelAdapter(
            PlatformType platformType, AppConfig.ChannelConfig config) {
        this.platformType = platformType;
        this.channelConfig = config;
        this.detail = isEnabled() ? "configured" : "disabled";
        this.setupState = isEnabled() ? "configured" : "disabled";
        this.connectionMode = "custom";
    }

    /** 返回所属平台。 */
    @Override
    public PlatformType platform() {
        return platformType;
    }

    /** 返回是否启用。 */
    @Override
    public boolean isEnabled() {
        return channelConfig != null && channelConfig.isEnabled();
    }

    /** 建立基础连接状态。 */
    @Override
    public boolean connect() {
        if (!isEnabled()) {
            detail = "disabled";
            return false;
        }

        connected = true;
        detail = "adapter scaffold ready";
        return true;
    }

    /** 断开连接。 */
    @Override
    public void disconnect() {
        connected = false;
    }

    /** 返回当前是否已连接。 */
    @Override
    public boolean isConnected() {
        return connected;
    }

    /** 返回当前详情。 */
    @Override
    public String detail() {
        return detail;
    }

    /** 默认发送实现仅打日志，具体渠道可覆盖。 */
    @Override
    public void send(DeliveryRequest request) throws Exception {
        log.info("[{}:{}] {}", request.getPlatform(), request.getChatId(), request.getText());
    }

    /** 返回 dashboard 和诊断接口使用的渠道状态快照。 */
    @Override
    public ChannelStatus statusSnapshot() {
        ChannelStatus status = new ChannelStatus(platformType, isEnabled(), connected, detail);
        status.setSetupState(setupState);
        status.setConnectionMode(connectionMode);
        status.setMissingConfig(new ArrayList<String>(missingConfig));
        status.setFeatures(new ArrayList<String>(features));
        status.setLastErrorCode(lastErrorCode);
        status.setLastErrorMessage(lastErrorMessage);
        return status;
    }

    /** 注册入站消息处理器。 */
    @Override
    public void setInboundMessageHandler(InboundMessageHandler inboundMessageHandler) {
        this.inboundMessageHandler = inboundMessageHandler;
    }

    /** 供子类读取当前入站处理器。 */
    protected InboundMessageHandler inboundMessageHandler() {
        return inboundMessageHandler;
    }

    /**
     * 判断文本是否为需要并发投递的控制命令（{@code /stop}、{@code /cancel}）。
     *
     * <p>取首个空白分隔的 token 做精确匹配（大小写不敏感），避免误匹配 {@code /stopwatch}、
     * {@code /canceled} 等同样以 {@code /stop} 或 {@code /cancel} 开头的普通文本。
     *
     * @param text 入站消息文本，可能为 null。
     * @return 若首个 token 为 {@code /stop} 或 {@code /cancel} 则返回 true。
     */
    protected boolean isControlCommand(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String trimmed = text.trim();
        String firstToken = trimmed.split("\\s+", 2)[0];
        return GatewayCommandConstants.SLASH_STOP.equalsIgnoreCase(firstToken)
                || GatewayCommandConstants.SLASH_CANCEL.equalsIgnoreCase(firstToken);
    }

    /**
     * 返回（必要时创建）控制命令专用并发执行器。
     *
     * @return 控制命令并发执行器。
     */
    protected ExecutorService controlExecutor() {
        ExecutorService existing = controlExecutor;
        if (existing != null && !existing.isShutdown()) {
            return existing;
        }
        synchronized (this) {
            if (controlExecutor == null || controlExecutor.isShutdown()) {
                controlExecutor = newControlExecutor(platform());
            }
            return controlExecutor;
        }
    }

    /**
     * 将控制命令投递到专用并发执行器，绕过各渠道串行入站执行器，确保 {@code /stop} 等控制命令
     * 不会被运行中的任务阻塞而错过取消时机。
     *
     * @param message 已构造好的网关消息（其文本应为控制命令）。
     */
    protected void dispatchInboundControl(final GatewayMessage message) {
        final InboundMessageHandler handler = inboundMessageHandler();
        if (handler == null || message == null) {
            return;
        }
        try {
            controlExecutor()
                    .submit(
                            new Runnable() {
                                /** 执行控制命令投递主体。 */
                                @Override
                                public void run() {
                                    try {
                                        handler.handle(message);
                                    } catch (Exception e) {
                                        log.warn(
                                                "[{}] control dispatch failed: errorType={}, error={}",
                                                platform(),
                                                errorType(e),
                                                safeError(e));
                                    }
                                }
                            });
        } catch (Exception e) {
            log.warn(
                    "[{}] control submit failed: errorType={}, error={}",
                    platform(),
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 关闭控制命令执行器。供各渠道在 {@link #disconnect()} 中调用，避免线程泄漏。
     */
    protected void shutdownControlExecutor() {
        ExecutorService existing = controlExecutor;
        if (existing != null) {
            existing.shutdownNow();
            controlExecutor = null;
        }
    }

    /**
     * 创建控制命令并发执行器（守护线程、带渠道前缀命名，便于诊断）。
     *
     * @param platformType 所属平台，用于线程命名。
     * @return 缓存线程池执行器。
     */
    private static ExecutorService newControlExecutor(final PlatformType platformType) {
        final String prefix =
                "channel-control-"
                        + (platformType == null ? "adapter" : platformType.name().toLowerCase());
        ThreadFactory factory =
                new ThreadFactory() {
                    /** 控制命令执行器线程序号。 */
                    private final AtomicInteger sequence = new AtomicInteger(1);

                    /**
                     * 创建守护线程。
                     *
                     * @param runnable runnable 参数。
                     * @return 守护线程实例。
                     */
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    }
                };
        return Executors.newCachedThreadPool(factory);
    }

    /** 更新连接状态。 */
    protected void setConnected(boolean connected) {
        this.connected = connected;
    }

    /** 更新详情。 */
    protected void setDetail(String detail) {
        this.detail = safeStatusText(detail);
    }

    /** 标记渠道连接模式。 */
    protected void setConnectionMode(String connectionMode) {
        this.connectionMode = connectionMode == null ? "custom" : connectionMode;
    }

    /** 标记渠道 setup 状态。 */
    protected void setSetupState(String setupState) {
        this.setupState = setupState == null ? GatewayBehaviorConstants.NONE : setupState;
    }

    /** 覆盖缺失配置项。 */
    protected void setMissingConfig(String... values) {
        missingConfig.clear();
        if (ArrayUtil.isNotEmpty(values)) {
            appendMissingConfig(CollUtil.newArrayList(values));
        }
    }

    /** 覆盖缺失配置项。 */
    protected void setMissingConfig(List<String> values) {
        missingConfig.clear();
        if (CollUtil.isNotEmpty(values)) {
            appendMissingConfig(values);
        }
    }

    /** 设置功能标签。 */
    protected void setFeatures(String... values) {
        features.clear();
        if (ArrayUtil.isNotEmpty(values)) {
            Collections.addAll(features, values);
        }
    }

    /** 返回功能标签快照。 */
    protected List<String> features() {
        return Collections.unmodifiableList(features);
    }

    /** 清理最近一次错误。 */
    protected void clearLastError() {
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    /** 记录最近一次错误。 */
    protected void setLastError(String code, String message) {
        this.lastErrorCode = code;
        this.lastErrorMessage = safeStatusText(message);
    }

    /** 标记渠道 websocket 已完成连接，统一 dashboard 状态语义。 */
    protected void markWebSocketConnected() {
        setConnected(true);
        setSetupState("connected");
        setDetail("websocket connected");
    }

    /**
     * 标记渠道 websocket 失败，统一连接状态与最近错误记录。
     *
     * @param errorCode 渠道专属错误码。
     * @param throwable 连接失败异常。
     */
    protected void markWebSocketFailure(String errorCode, Throwable throwable) {
        setConnected(false);
        setSetupState("error");
        setLastError(errorCode, safeError(throwable));
        setDetail("websocket disconnected");
    }

    /**
     * 标记渠道 websocket 已关闭，统一关闭状态描述。
     *
     * @param code websocket 关闭码。
     * @param reason websocket 关闭原因。
     */
    protected void markWebSocketClosed(int code, String reason) {
        setConnected(false);
        setSetupState("disconnected");
        setDetail("websocket closed: " + code + " " + reason);
    }

    /**
     * 追加缺失配置项，统一过滤空白并去掉两端空格。
     *
     * @param values 渠道配置路径或 dashboard 诊断字段。
     */
    private void appendMissingConfig(Iterable<String> values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                missingConfig.add(value.trim());
            }
        }
    }

    /**
     * 生成可展示的状态文本，并在进入 dashboard 前脱敏。
     *
     * @param value 渠道连接详情或错误消息。
     * @return 脱敏后的状态文本，入参为空时保持为空。
     */
    private String safeStatusText(String value) {
        return value == null ? null : SecretRedactor.redact(value, 1000);
    }

    /**
     * 将异常转换为可展示且不泄漏敏感信息的错误文本。
     *
     * @param throwable 捕获到的异常。
     * @return 可写入渠道状态和日志的脱敏错误文本。
     */
    protected String safeError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (StrUtil.isBlank(message)) {
            message = throwable.getClass().getSimpleName();
        }
        return safeStatusText(message);
    }

    /**
     * 提取异常类型名称，用作渠道最近错误码的兜底信息。
     *
     * @param throwable 捕获到的异常。
     * @return 异常类名；异常为空时返回通用 Throwable。
     */
    protected String errorType(Throwable throwable) {
        return throwable == null ? "Throwable" : throwable.getClass().getSimpleName();
    }

    /** 拦截示例/占位凭据，避免已启用渠道带弱凭据反复连接外部平台。 */
    protected boolean rejectWeakCredentials(String errorCode, CredentialField... fields) {
        if (!isEnabled() || fields == null) {
            return false;
        }
        List<String> weakFields = new ArrayList<String>();
        for (CredentialField field : fields) {
            if (field != null && isWeakCredentialPlaceholder(field.value)) {
                weakFields.add(field.path);
            }
        }
        if (CollUtil.isEmpty(weakFields)) {
            return false;
        }
        channelConfig.setEnabled(false);
        setConnected(false);
        setSetupState("weak_credentials");
        setMissingConfig(new String[0]);
        setLastError(
                StrUtil.blankToDefault(errorCode, "channel_weak_credentials"),
                "placeholder credential: " + weakFields);
        setDetail("disabled weak placeholder credentials: " + weakFields);
        log.error(
                "[{}] disabled because placeholder credentials were configured: {}",
                platformType,
                weakFields);
        return true;
    }

    /**
     * 构造弱凭据检测使用的配置字段描述。
     *
     * @param path 配置路径，用于诊断输出。
     * @param value 配置值，用于判断是否为示例或占位凭据。
     * @return 凭据字段描述对象。
     */
    protected CredentialField credentialField(String path, String value) {
        return new CredentialField(path, value);
    }

    /**
     * 判断是否Weak凭据Placeholder。
     *
     * @param value 待规范化或校验的原始值。
     * @return 如果Weak凭据Placeholder满足条件则返回 true，否则返回 false。
     */
    protected static boolean isWeakCredentialPlaceholder(String value) {
        return SecretValueGuard.isPlaceholderSecret(value);
    }

    /** 承载单个渠道凭据字段，避免弱凭据检测调用处重复传递 path/value 对。 */
    protected static class CredentialField {
        /** 配置路径，用于错误详情和 dashboard 诊断。 */
        private final String path;

        /** 配置值，仅用于占位凭据判断，不进入用户可见输出。 */
        private final String value;

        /**
         * 创建凭据字段描述。
         *
         * @param path 配置路径。
         * @param value 配置值。
         */
        private CredentialField(String path, String value) {
            this.path = path == null ? "" : path.trim();
            this.value = value;
        }
    }
}
