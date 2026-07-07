package com.jimuqu.solon.claw.gateway.platform.weixin;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.DeliveryRequest;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.core.repository.ChannelStateRepository;
import com.jimuqu.solon.claw.gateway.platform.base.AbstractConfigurableChannelAdapter;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BaseUrlSupport;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import com.jimuqu.solon.claw.support.HttpRedirectSupport;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import com.jimuqu.solon.claw.support.MessageAttachmentSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import com.jimuqu.solon.claw.support.ThreadInterruptSupport;
import com.jimuqu.solon.claw.support.UrlOriginSupport;
import com.jimuqu.solon.claw.support.constants.GatewayBehaviorConstants;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.noear.snack4.ONode;

/** WeiXinChannelAdapter 实现。 */
public class WeiXinChannelAdapter extends AbstractConfigurableChannelAdapter {
    /** 默认基础URL的统一常量值。 */
    private static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    /** 默认CDN基础URL的统一常量值。 */
    private static final String DEFAULT_CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";

    /** SENDENDPO整型的统一常量值。 */
    private static final String SEND_ENDPOINT = "ilink/bot/sendmessage";

    /** GETUPDATESENDPO整型的统一常量值。 */
    private static final String GET_UPDATES_ENDPOINT = "ilink/bot/getupdates";

    /** SENDTYPINGENDPO整型的统一常量值。 */
    private static final String SEND_TYPING_ENDPOINT = "ilink/bot/sendtyping";

    /** GET配置ENDPO整型的统一常量值。 */
    private static final String GET_CONFIG_ENDPOINT = "ilink/bot/getconfig";

    /** GET上传URLENDPO整型的统一常量值。 */
    private static final String GET_UPLOAD_URL_ENDPOINT = "ilink/bot/getuploadurl";

    /** 上下文token键的统一常量值。 */
    private static final String CONTEXT_TOKEN_KEY = "context_token";

    /** 同步BUF键的统一常量值。 */
    private static final String SYNC_BUF_KEY = "sync_buf";

    /** LONGPOLLTIMEOUTMS的统一常量值。 */
    private static final int LONG_POLL_TIMEOUT_MS = 35_000;

    /** 配置TIMEOUTMS的统一常量值。 */
    private static final int CONFIG_TIMEOUT_MS = 10_000;

    /** 消息DEDUPTTLMILLIS的统一常量值。 */
    private static final int MESSAGE_DEDUP_TTL_MILLIS = 5 * 60 * 1000;

    /** 消息DEDUP最大ENTRIES的统一常量值。 */
    private static final int MESSAGE_DEDUP_MAX_ENTRIES = 512;

    /** 最大HTTPREDIRECTS的统一常量值。 */
    private static final int MAX_HTTP_REDIRECTS = 5;

    /** MSG类型机器人的统一常量值。 */
    private static final int MSG_TYPE_BOT = 2;

    /** MSG状态FINISH的统一常量值。 */
    private static final int MSG_STATE_FINISH = 2;

    /** ITEM文本的统一常量值。 */
    private static final int ITEM_TEXT = 1;

    /** ITEM图片的统一常量值。 */
    private static final int ITEM_IMAGE = 2;

    /** ITEM文件的统一常量值。 */
    private static final int ITEM_FILE = 4;

    /** ITEMVIDEO的统一常量值。 */
    private static final int ITEM_VIDEO = 5;

    /** 媒体图片的统一常量值。 */
    private static final int MEDIA_IMAGE = 1;

    /** 媒体VIDEO的统一常量值。 */
    private static final int MEDIA_VIDEO = 2;

    /** 媒体文件的统一常量值。 */
    private static final int MEDIA_FILE = 3;

    /** TYPINGSTART的统一常量值。 */
    private static final int TYPING_START = 1;

    /** TYPINGSTOP的统一常量值。 */
    private static final int TYPING_STOP = 2;

    /** 最大文本分片LENGTH的统一常量值。 */
    private static final int MAX_TEXT_CHUNK_LENGTH = 2000;

    /** 微信文本普通长行的复制友好折行宽度。 */
    private static final int WEIXIN_COPY_LINE_WIDTH = 120;

    /** 为多分片序号预留的长度，避免序号追加后超过平台限制。 */
    private static final int CHUNK_INDICATOR_RESERVE = 10;

    /** 入站文本SPLITTHRESHOLD的统一常量值。 */
    private static final int INBOUND_TEXT_SPLIT_THRESHOLD = 1800;
    private static final String FENCE_CLOSE = "\n```";
    private static final Pattern FENCE_PATTERN = Pattern.compile("^```([^\\n`]*)\\s*$");
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern TABLE_RULE_PATTERN =
            Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
    private static final Pattern BOLD_ONLY_PATTERN = Pattern.compile("^\\*\\*[^*]+\\*\\*$");
    private static final Pattern NUMBERED_LINE_PATTERN = Pattern.compile("^\\d+\\.\\s.*");

    /** 记录WeiXin渠道中的配置。 */
    private final AppConfig.ChannelConfig config;

    /** 保存渠道状态仓储依赖，用于访问持久化数据。 */
    private final ChannelStateRepository channelStateRepository;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /** 注入安全策略服务，用于调用对应业务能力。 */
    private final SecurityPolicyService securityPolicyService;

    /** 保存recent消息标识映射，便于按键快速查询。 */
    private final ConcurrentMap<String, Long> recentMessageIds =
            new ConcurrentHashMap<String, Long>();

    /** 保存typingTickets映射，便于按键快速查询。 */
    private final ConcurrentMap<String, TypingTicketState> typingTickets =
            new ConcurrentHashMap<String, TypingTicketState>();

    /** 保存待恢复文本Batches映射，便于按键快速查询。 */
    private final ConcurrentMap<String, PendingTextBatch> pendingTextBatches =
            new ConcurrentHashMap<String, PendingTextBatch>();

    /** 保存待恢复文本BatchTasks映射，便于按键快速查询。 */
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingTextBatchTasks =
            new ConcurrentHashMap<String, ScheduledFuture<?>>();

    /** 保存poll执行器执行组件，负责调度异步或定时任务。 */
    private volatile ExecutorService pollExecutor;

    /** 保存入站执行器执行组件，负责调度异步或定时任务。 */
    private volatile ExecutorService inboundExecutor;

    /** 保存text Batch执行器执行组件，负责调度异步或定时任务。 */
    private volatile ScheduledExecutorService textBatchExecutor;

    /** 是否启用polling。 */
    private volatile boolean polling;

    /**
     * 创建Wei Xin渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param channelStateRepository 渠道状态仓储依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public WeiXinChannelAdapter(
            AppConfig.ChannelConfig config,
            ChannelStateRepository channelStateRepository,
            AttachmentCacheService attachmentCacheService) {
        this(config, channelStateRepository, attachmentCacheService, null);
    }

    /**
     * 创建Wei Xin渠道适配器实例，并注入运行所需依赖。
     *
     * @param config 当前模块使用的配置对象。
     * @param channelStateRepository 渠道状态仓储依赖。
     * @param attachmentCacheService 附件缓存服务依赖。
     * @param securityPolicyService 安全策略服务依赖。
     */
    public WeiXinChannelAdapter(
            AppConfig.ChannelConfig config,
            ChannelStateRepository channelStateRepository,
            AttachmentCacheService attachmentCacheService,
            SecurityPolicyService securityPolicyService) {
        super(PlatformType.WEIXIN, config);
        this.config = config;
        this.channelStateRepository = channelStateRepository;
        this.attachmentCacheService = attachmentCacheService;
        this.securityPolicyService = securityPolicyService;
        setConnectionMode("long-poll");
        setFeatures("text", "attachments", "quoted-media", "typing", "qr-login");
        setSetupState(config != null && config.isEnabled() ? "configured" : "disabled");
    }

    /**
     * 建立当前组件需要的连接。
     *
     * @return 返回connect结果。
     */
    @Override
    public boolean connect() {
        if (!isEnabled()) {
            setSetupState("disabled");
            setDetail("disabled");
            return false;
        }
        if (rejectWeakCredentials(
                "weixin_weak_credentials",
                credentialField("solonclaw.channels.weixin.token", config.getToken()),
                credentialField("solonclaw.channels.weixin.accountId", config.getAccountId()))) {
            return false;
        }
        java.util.ArrayList<String> missing = new java.util.ArrayList<String>();
        if (StrUtil.isBlank(config.getToken())) {
            missing.add("solonclaw.channels.weixin.token");
        }
        if (StrUtil.isBlank(config.getAccountId())) {
            missing.add("solonclaw.channels.weixin.accountId");
        }
        if (!missing.isEmpty()) {
            setConnected(false);
            setSetupState("missing_config");
            setMissingConfig(missing);
            setLastError("weixin_missing_credentials", "missing token/accountId");
            setDetail("missing token/accountId");
            log.warn("[WEIXIN] Missing token/accountId");
            return false;
        }
        setMissingConfig(new String[0]);
        clearLastError();
        setConnected(true);
        setSetupState("connected");
        setDetail("long-poll configured");
        ensureInboundExecutor();
        startPolling();
        return true;
    }

    /** 断开当前组件持有的连接。 */
    @Override
    public void disconnect() {
        polling = false;
        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
        if (inboundExecutor != null) {
            inboundExecutor.shutdown();
            inboundExecutor = null;
        }
        if (textBatchExecutor != null) {
            textBatchExecutor.shutdownNow();
            textBatchExecutor = null;
        }
        pendingTextBatches.clear();
        pendingTextBatchTasks.clear();
        // 关闭控制命令并发执行器，避免断开连接后线程泄漏
        shutdownControlExecutor();
        setConnected(false);
        setDetail("disconnected");
    }

    /**
     * 发送当前请求对应的消息。
     *
     * @param request 当前请求对象。
     */
    @Override
    public void send(DeliveryRequest request) {
        if (StrUtil.isBlank(request.getChatId())) {
            throw new IllegalArgumentException("Weixin chatId is required");
        }

        if (StrUtil.isNotBlank(request.getText())) {
            sendText(request.getChatId(), request.getText());
        }
        List<MessageAttachment> attachments = request.getAttachments();
        if (attachments != null) {
            for (MessageAttachment attachment : attachments) {
                sendAttachment(request.getChatId(), attachment);
            }
        }
    }

    /**
     * 发送Text。
     *
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     */
    private void sendText(String chatId, String text) {
        List<String> chunks = splitTextForDelivery(text);
        for (int i = 0; i < chunks.size(); i++) {
            sendTextChunk(chatId, chunks.get(i));
            if (i + 1 < chunks.size()) {
                sleepQuietlyMillis((long) (config.getSendChunkDelaySeconds() * 1000L));
            }
        }
    }

    /**
     * 发送Text Chunk。
     *
     * @param chatId 聊天标识。
     * @param text 待处理文本。
     */
    private void sendTextChunk(String chatId, String text) {
        int attempts = Math.max(1, config.getSendChunkRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ONode message = baseMessage(chatId);
                String contextToken = loadContextToken(chatId);
                if (StrUtil.isNotBlank(contextToken)) {
                    message.set("context_token", contextToken);
                }
                ONode textItem = new ONode();
                textItem.set("text", normalizeOutboundTextForWeixin(text));
                ONode item = new ONode();
                item.set("type", ITEM_TEXT);
                item.set("text_item", textItem);
                message.get("item_list").add(item);
                ONode response = apiPost(SEND_ENDPOINT, new ONode().set("msg", message).asObject());
                if (response.get("errcode").getInt(0) == -14 && StrUtil.isNotBlank(contextToken)) {
                    message.remove("context_token");
                    response = apiPost(SEND_ENDPOINT, new ONode().set("msg", message).asObject());
                }
                ensureSuccess(response, "Weixin text send failed");
                clearLastError();
                return;
            } catch (Exception e) {
                setLastError("weixin_send_text_failed", safeMessage(e));
                if (attempt >= attempts) {
                    throw new IllegalStateException(
                            "Weixin text send failed after "
                                    + attempts
                                    + " attempt(s): "
                                    + safeMessage(e),
                            e);
                }
                sleepQuietlyMillis((long) (config.getSendChunkRetryDelaySeconds() * 1000L));
            }
        }
    }

    /** 将出站文本统一为 LF 换行，保持与微信 iLink 文本字段兼容。 */
    private static String normalizeOutboundTextForWeixin(String text) {
        return StrUtil.nullToEmpty(text).replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 先执行微信展示友好的文本格式化，再按微信消息长度和结构拆分。 */
    private List<String> splitTextForDelivery(String text) {
        String formatted = formatTextForDelivery(text);
        boolean splitPerLine = config != null && config.isSplitMultilineMessages();
        return splitFormattedTextForDelivery(formatted, MAX_TEXT_CHUNK_LENGTH, splitPerLine);
    }

    /** 对微信出站文本执行 Markdown 块清理和长行折行。 */
    private static String formatTextForDelivery(String text) {
        return wrapCopyFriendlyLinesForWeixin(normalizeMarkdownBlocks(text));
    }

    /** 压缩 Markdown 块之间的多余空行，同时保留代码块内部内容。 */
    private static String normalizeMarkdownBlocks(String text) {
        String[] lines = normalizeOutboundTextForWeixin(text).split("\n", -1);
        ArrayList<String> result = new ArrayList<String>();
        boolean inCodeBlock = false;
        int blankRun = 0;
        for (String rawLine : lines) {
            String line = rstrip(rawLine);
            if (FENCE_PATTERN.matcher(line.trim()).matches()) {
                inCodeBlock = !inCodeBlock;
                result.add(line);
                blankRun = 0;
                continue;
            }

            if (inCodeBlock) {
                result.add(line);
                continue;
            }

            if (line.trim().length() == 0) {
                blankRun++;
                if (blankRun <= 1) {
                    result.add("");
                }
                continue;
            }

            blankRun = 0;
            result.add(line);
        }
        return joinLines(result).trim();
    }

    /** 对普通长行做 120 字以内折行，避免微信客户端复制困难。 */
    private static String wrapCopyFriendlyLinesForWeixin(String content) {
        if (StrUtil.isBlank(content)) {
            return StrUtil.nullToEmpty(content);
        }
        ArrayList<String> wrapped = new ArrayList<String>();
        boolean inCodeBlock = false;
        String[] lines = content.split("\n", -1);
        for (String rawLine : lines) {
            String line = rstrip(rawLine);
            String stripped = line.trim();
            if (FENCE_PATTERN.matcher(stripped).matches()) {
                inCodeBlock = !inCodeBlock;
                wrapped.add(line);
                continue;
            }
            if (inCodeBlock
                    || codePointLength(line) <= WEIXIN_COPY_LINE_WIDTH
                    || stripped.length() == 0
                    || stripped.startsWith("|")
                    || TABLE_RULE_PATTERN.matcher(stripped).matches()) {
                wrapped.add(line);
                continue;
            }
            wrapped.addAll(wrapPlainLine(line, WEIXIN_COPY_LINE_WIDTH));
        }
        return joinLines(wrapped).trim();
    }

    /** 按空白折行普通文本，不拆分超长单词。 */
    private static List<String> wrapPlainLine(String line, int width) {
        ArrayList<String> wrapped = new ArrayList<String>();
        String trimmed = line.trim();
        if (trimmed.length() == 0) {
            return wrapped;
        }
        StringBuilder current = new StringBuilder();
        int index = 0;
        while (index < trimmed.length()) {
            int wordStart = index;
            while (wordStart < trimmed.length()
                    && Character.isWhitespace(trimmed.charAt(wordStart))) {
                wordStart++;
            }
            int wordEnd = wordStart;
            while (wordEnd < trimmed.length()
                    && !Character.isWhitespace(trimmed.charAt(wordEnd))) {
                wordEnd++;
            }
            String separator = trimmed.substring(index, wordStart);
            String word = trimmed.substring(wordStart, wordEnd);
            if (word.length() == 0) {
                break;
            }
            String candidate =
                    current.length() == 0 ? word : current.toString() + separator + word;
            if (current.length() > 0 && codePointLength(candidate) > width) {
                wrapped.add(rstrip(current.toString()));
                current.setLength(0);
                current.append(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
            index = wordEnd;
        }
        if (current.length() > 0) {
            wrapped.add(rstrip(current.toString()));
        }
        if (wrapped.isEmpty()) {
            wrapped.add(line);
        }
        return wrapped;
    }

    /** 按参考实现的紧凑模式或逐行模式拆分已格式化的微信文本。 */
    private List<String> splitFormattedTextForDelivery(
            String content, int maxLength, boolean splitPerLine) {
        ArrayList<String> chunks = new ArrayList<String>();
        String normalized = StrUtil.nullToEmpty(content);
        if (normalized.length() == 0) {
            return chunks;
        }
        if (splitPerLine) {
            if (codePointLength(normalized) <= maxLength && normalized.indexOf('\n') < 0) {
                chunks.add(normalized);
                return chunks;
            }
            for (String unit : splitDeliveryUnitsForWeixin(normalized)) {
                if (codePointLength(unit) <= maxLength) {
                    chunks.add(unit);
                } else {
                    chunks.addAll(packMarkdownBlocksForWeixin(unit, maxLength));
                }
            }
            return chunks.isEmpty() ? singleChunk(normalized) : chunks;
        }

        if (codePointLength(normalized) <= maxLength) {
            if (shouldSplitShortChatBlockForWeixin(normalized)) {
                chunks.addAll(splitDeliveryUnitsForWeixin(normalized));
                return chunks;
            }
            chunks.add(normalized);
            return chunks;
        }
        chunks.addAll(packMarkdownBlocksForWeixin(normalized, maxLength));
        return chunks.isEmpty() ? singleChunk(normalized) : chunks;
    }

    /** 将格式化文本切成 Markdown 顶层块，尽量保持代码块完整。 */
    private static List<String> splitMarkdownBlocks(String content) {
        ArrayList<String> blocks = new ArrayList<String>();
        String[] lines = StrUtil.nullToEmpty(content).split("\n", -1);
        ArrayList<String> current = new ArrayList<String>();
        boolean inCodeBlock = false;
        for (String rawLine : lines) {
            String line = rstrip(rawLine);
            if (FENCE_PATTERN.matcher(line.trim()).matches()) {
                if (!inCodeBlock && !current.isEmpty()) {
                    blocks.add(joinLines(current).trim());
                    current.clear();
                }
                current.add(line);
                inCodeBlock = !inCodeBlock;
                if (!inCodeBlock) {
                    blocks.add(joinLines(current).trim());
                    current.clear();
                }
                continue;
            }
            if (inCodeBlock) {
                current.add(line);
                continue;
            }
            if (line.trim().length() == 0) {
                if (!current.isEmpty()) {
                    blocks.add(joinLines(current).trim());
                    current.clear();
                }
                continue;
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            blocks.add(joinLines(current).trim());
        }
        ArrayList<String> nonBlank = new ArrayList<String>();
        for (String block : blocks) {
            if (StrUtil.isNotBlank(block)) {
                nonBlank.add(block);
            }
        }
        return nonBlank;
    }

    /** 将顶层文本块拆成微信气泡友好的投递单元。 */
    private static List<String> splitDeliveryUnitsForWeixin(String content) {
        ArrayList<String> units = new ArrayList<String>();
        for (String block : splitMarkdownBlocks(content)) {
            String[] blockLines = block.split("\n", -1);
            if (blockLines.length > 0 && FENCE_PATTERN.matcher(blockLines[0].trim()).matches()) {
                units.add(block);
                continue;
            }
            ArrayList<String> current = new ArrayList<String>();
            for (String rawLine : blockLines) {
                String line = rstrip(rawLine);
                if (line.trim().length() == 0) {
                    if (!current.isEmpty()) {
                        units.add(joinLines(current).trim());
                        current.clear();
                    }
                    continue;
                }
                boolean continuation =
                        !current.isEmpty()
                                && (rawLine.startsWith(" ") || rawLine.startsWith("\t"));
                if (continuation) {
                    current.add(line);
                    continue;
                }
                if (!current.isEmpty()) {
                    units.add(joinLines(current).trim());
                }
                current.clear();
                current.add(line);
            }
            if (!current.isEmpty()) {
                units.add(joinLines(current).trim());
            }
        }
        ArrayList<String> nonBlank = new ArrayList<String>();
        for (String unit : units) {
            if (StrUtil.isNotBlank(unit)) {
                nonBlank.add(unit);
            }
        }
        return nonBlank;
    }

    /** 判断短多行文本是否更适合按聊天气泡拆分。 */
    private static boolean shouldSplitShortChatBlockForWeixin(String block) {
        ArrayList<String> lines = new ArrayList<String>();
        for (String line : StrUtil.nullToEmpty(block).split("\n", -1)) {
            if (StrUtil.isNotBlank(line)) {
                lines.add(line);
            }
        }
        if (lines.size() < 2 || lines.size() > 6) {
            return false;
        }
        if (looksLikeHeadingLineForWeixin(lines.get(0))) {
            return false;
        }
        for (String line : lines) {
            if (!looksLikeChattyLineForWeixin(line)) {
                return false;
            }
        }
        return true;
    }

    /** 判断一行文本是否像独立的聊天短句。 */
    private static boolean looksLikeChattyLineForWeixin(String line) {
        String stripped = StrUtil.nullToEmpty(line).trim();
        if (stripped.length() == 0 || codePointLength(stripped) > 48) {
            return false;
        }
        if (line.startsWith(" ") || line.startsWith("\t")) {
            return false;
        }
        if (stripped.startsWith(">")
                || stripped.startsWith("-")
                || stripped.startsWith("*")
                || stripped.startsWith("【")
                || stripped.startsWith("#")
                || stripped.startsWith("|")) {
            return false;
        }
        return !TABLE_RULE_PATTERN.matcher(stripped).matches()
                && !BOLD_ONLY_PATTERN.matcher(stripped).matches()
                && !NUMBERED_LINE_PATTERN.matcher(stripped).matches();
    }

    /** 判断一行文本是否像标题，标题后续内容不拆成多个气泡。 */
    private static boolean looksLikeHeadingLineForWeixin(String line) {
        String stripped = StrUtil.nullToEmpty(line).trim();
        if (stripped.length() == 0) {
            return false;
        }
        return HEADER_PATTERN.matcher(stripped).matches()
                || (codePointLength(stripped) <= 24
                        && (stripped.endsWith(":") || stripped.endsWith("：")));
    }

    /** 按 Markdown 块打包超长文本，尽量不打断完整结构。 */
    private static List<String> packMarkdownBlocksForWeixin(String content, int maxLength) {
        ArrayList<String> packed = new ArrayList<String>();
        if (codePointLength(content) <= maxLength) {
            packed.add(content);
            return packed;
        }

        String current = "";
        for (String block : splitMarkdownBlocks(content)) {
            String candidate = current.length() == 0 ? block : current + "\n\n" + block;
            if (codePointLength(candidate) <= maxLength) {
                current = candidate;
                continue;
            }
            if (current.length() > 0) {
                packed.add(current);
                current = "";
            }
            if (codePointLength(block) <= maxLength) {
                current = block;
            } else {
                packed.addAll(truncateMessageForWeixin(block, maxLength));
            }
        }
        if (current.length() > 0) {
            packed.add(current);
        }
        return packed;
    }

    /** 按参考平台基类规则拆分超长块，保留代码围栏并追加分片序号。 */
    private static List<String> truncateMessageForWeixin(String content, int maxLength) {
        if (codePointLength(content) <= maxLength) {
            return singleChunk(content);
        }
        ArrayList<String> chunks = new ArrayList<String>();
        String remaining = content;
        String carryLanguage = null;
        while (remaining.length() > 0) {
            String prefix = carryLanguage == null ? "" : "```" + carryLanguage + "\n";
            int headroom =
                    maxLength
                            - CHUNK_INDICATOR_RESERVE
                            - codePointLength(prefix)
                            - codePointLength(FENCE_CLOSE);
            if (headroom < 1) {
                headroom = Math.max(1, maxLength / 2);
            }
            if (codePointLength(prefix) + codePointLength(remaining)
                    <= maxLength - CHUNK_INDICATOR_RESERVE) {
                chunks.add(prefix + remaining);
                break;
            }

            String region = leftCodePoints(remaining, headroom);
            int halfIndex = charIndexForCodePointOffset(region, Math.max(0, headroom / 2));
            int splitAt = region.lastIndexOf('\n');
            if (splitAt < halfIndex) {
                splitAt = region.lastIndexOf(' ');
            }
            if (splitAt < 1) {
                splitAt = region.length();
            }

            String candidate = remaining.substring(0, splitAt);
            int backtickCount = countOccurrences(candidate, "`") - countOccurrences(candidate, "\\`");
            if (backtickCount % 2 == 1) {
                int lastBacktick = candidate.lastIndexOf('`');
                while (lastBacktick > 0 && candidate.charAt(lastBacktick - 1) == '\\') {
                    lastBacktick = candidate.lastIndexOf('`', lastBacktick - 1);
                }
                if (lastBacktick > 0) {
                    int safeSplit = candidate.lastIndexOf(' ', lastBacktick);
                    int newlineSplit = candidate.lastIndexOf('\n', lastBacktick);
                    safeSplit = Math.max(safeSplit, newlineSplit);
                    if (safeSplit > halfIndex) {
                        splitAt = safeSplit;
                    }
                }
            }

            String chunkBody = remaining.substring(0, splitAt);
            remaining = lstrip(remaining.substring(splitAt));
            String fullChunk = prefix + chunkBody;

            boolean inCode = carryLanguage != null;
            String language = carryLanguage == null ? "" : carryLanguage;
            String[] lines = chunkBody.split("\n", -1);
            for (String line : lines) {
                String stripped = line.trim();
                if (stripped.startsWith("```")) {
                    if (inCode) {
                        inCode = false;
                        language = "";
                    } else {
                        inCode = true;
                        String tag = stripped.substring(3).trim();
                        language = firstToken(tag);
                    }
                }
            }
            if (inCode) {
                fullChunk += FENCE_CLOSE;
                carryLanguage = language;
            } else {
                carryLanguage = null;
            }
            chunks.add(fullChunk);
        }

        if (chunks.size() > 1) {
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                chunks.set(i, chunks.get(i) + " (" + (i + 1) + "/" + total + ")");
            }
        }
        return chunks;
    }

    /** 返回字符串的 Unicode code point 数，匹配参考实现的 Python len 行为。 */
    private static int codePointLength(String text) {
        String value = StrUtil.nullToEmpty(text);
        return value.codePointCount(0, value.length());
    }

    /** 按 code point 数截取左侧内容，避免把代理对字符切开。 */
    private static String leftCodePoints(String text, int count) {
        String value = StrUtil.nullToEmpty(text);
        int safeCount = Math.max(0, Math.min(count, codePointLength(value)));
        return value.substring(0, value.offsetByCodePoints(0, safeCount));
    }

    /** 将 code point 偏移转换为 Java 字符下标。 */
    private static int charIndexForCodePointOffset(String text, int count) {
        String value = StrUtil.nullToEmpty(text);
        int safeCount = Math.max(0, Math.min(count, codePointLength(value)));
        return value.offsetByCodePoints(0, safeCount);
    }

    /** 统计固定子串出现次数，按参考实现用于反引号保护的简单计数规则处理。 */
    private static int countOccurrences(String text, String needle) {
        if (StrUtil.isEmpty(needle)) {
            return 0;
        }
        int count = 0;
        int start = 0;
        while (start <= text.length()) {
            int index = text.indexOf(needle, start);
            if (index < 0) {
                break;
            }
            count++;
            start = index + needle.length();
        }
        return count;
    }

    /** 去掉左侧空白，模拟参考拆分后 remaining.lstrip() 的行为。 */
    private static String lstrip(String text) {
        String value = StrUtil.nullToEmpty(text);
        int start = 0;
        while (start < value.length() && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        return value.substring(start);
    }

    /** 提取代码围栏语言标签的首个 token。 */
    private static String firstToken(String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        if (value.length() == 0) {
            return "";
        }
        String[] tokens = value.split("\\s+", 2);
        return tokens[0];
    }

    /** 生成只有一个元素的列表，作为空拆分结果的兜底。 */
    private static List<String> singleChunk(String text) {
        ArrayList<String> chunks = new ArrayList<String>();
        chunks.add(text);
        return chunks;
    }

    /** 拼接行集合为 LF 文本。 */
    private static String joinLines(List<String> lines) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                buffer.append('\n');
            }
            buffer.append(lines.get(i));
        }
        return buffer.toString();
    }

    /** 去掉右侧空白，保留左侧缩进用于列表续行判断。 */
    private static String rstrip(String text) {
        String value = StrUtil.nullToEmpty(text);
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 生成安全展示用的消息。
     *
     * @param e 捕获到的异常。
     * @return 返回safe消息结果。
     */
    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return StrUtil.isBlank(message) ? e.getClass().getSimpleName() : message.trim();
    }

    /**
     * 发送附件。
     *
     * @param chatId 聊天标识。
     * @param attachment 附件参数。
     */
    private void sendAttachment(String chatId, MessageAttachment attachment) {
        File file = new File(attachment.getLocalPath());
        if (!file.isFile()) {
            throw new IllegalStateException(
                    MessageAttachmentSupport.fileNotFoundMessage("Weixin", attachment));
        }

        String kind =
                AttachmentCacheService.normalizeKind(
                        attachment.getKind(),
                        attachment.getOriginalName(),
                        attachment.getMimeType());
        int mediaType =
                "image".equals(kind)
                        ? MEDIA_IMAGE
                        : ("video".equals(kind) ? MEDIA_VIDEO : MEDIA_FILE);
        byte[] plaintext = FileUtil.readBytes(file);
        byte[] aesKey = RandomUtil.randomBytes(16);
        byte[] ciphertext = encryptAesEcb(plaintext, aesKey);
        String fileKey = IdUtil.fastSimpleUUID();

        ONode uploadInfo =
                apiPost(
                        GET_UPLOAD_URL_ENDPOINT,
                        new ONode()
                                .set("filekey", fileKey)
                                .set("media_type", mediaType)
                                .set("to_user_id", chatId)
                                .set("rawsize", plaintext.length)
                                .set("rawfilemd5", DigestUtil.md5Hex(plaintext))
                                .set("filesize", ciphertext.length)
                                .set("no_need_thumb", true)
                                .set("aeskey", HexUtil.encodeHexStr(aesKey))
                                .asObject());
        ensureSuccess(uploadInfo, "Weixin upload init failed");

        String uploadUrl = resolveUploadUrl(uploadInfo, fileKey);
        String encryptedParam = uploadCiphertext(uploadUrl, ciphertext);

        ONode message = baseMessage(chatId);
        String contextToken = loadContextToken(chatId);
        if (StrUtil.isNotBlank(contextToken)) {
            message.set("context_token", contextToken);
        }
        message.getOrNew("item_list")
                .asArray()
                .add(
                        buildMediaItem(
                                mediaType,
                                attachment,
                                plaintext.length,
                                ciphertext.length,
                                encryptedParam,
                                aesKey));
        ONode sendResponse = apiPost(SEND_ENDPOINT, new ONode().set("msg", message).asObject());
        ensureSuccess(sendResponse, "Weixin media send failed");
    }

    /**
     * 执行基础消息相关逻辑。
     *
     * @param chatId 聊天标识。
     * @return 返回base消息结果。
     */
    private ONode baseMessage(String chatId) {
        ONode message = new ONode();
        message.asObject();
        message.set("from_user_id", "");
        message.set("to_user_id", chatId);
        message.set("client_id", "jimuqu-weixin-" + UUID.randomUUID().toString().replace("-", ""));
        message.set("message_type", MSG_TYPE_BOT);
        message.set("message_state", MSG_STATE_FINISH);
        message.getOrNew("item_list").asArray();
        return message;
    }

    /**
     * 构建媒体Item。
     *
     * @param mediaType 媒体类型参数。
     * @param attachment 附件参数。
     * @param plaintextSize plaintextSize 参数。
     * @param ciphertextSize ciphertextSize 参数。
     * @param encryptedParam encryptedParam 参数。
     * @param aesKey aes键标识或键值。
     * @return 返回创建好的媒体Item。
     */
    private ONode buildMediaItem(
            int mediaType,
            MessageAttachment attachment,
            int plaintextSize,
            int ciphertextSize,
            String encryptedParam,
            byte[] aesKey) {
        String encodedAesKey =
                Base64.getEncoder()
                        .encodeToString(
                                HexUtil.encodeHexStr(aesKey).getBytes(StandardCharsets.UTF_8));
        if (mediaType == MEDIA_IMAGE) {
            ONode media = new ONode();
            media.set("encrypt_query_param", encryptedParam);
            media.set("aes_key", encodedAesKey);
            media.set("encrypt_type", 1);

            ONode imageItem = new ONode();
            imageItem.set("media", media);
            imageItem.set("mid_size", ciphertextSize);

            ONode item = new ONode();
            item.set("type", ITEM_IMAGE);
            item.set("image_item", imageItem);
            return item;
        }
        if (mediaType == MEDIA_VIDEO) {
            ONode media = new ONode();
            media.set("encrypt_query_param", encryptedParam);
            media.set("aes_key", encodedAesKey);
            media.set("encrypt_type", 1);

            ONode videoItem = new ONode();
            videoItem.set("media", media);
            videoItem.set("video_size", ciphertextSize);
            videoItem.set("play_length", 0);
            videoItem.set("video_md5", DigestUtil.md5Hex(new File(attachment.getLocalPath())));

            ONode item = new ONode();
            item.set("type", ITEM_VIDEO);
            item.set("video_item", videoItem);
            return item;
        }
        ONode media = new ONode();
        media.set("encrypt_query_param", encryptedParam);
        media.set("aes_key", encodedAesKey);
        media.set("encrypt_type", 1);

        ONode fileItem = new ONode();
        fileItem.set("media", media);
        fileItem.set("file_name", fileNameOf(attachment));
        fileItem.set("len", String.valueOf(plaintextSize));

        ONode item = new ONode();
        item.set("type", ITEM_FILE);
        item.set("file_item", fileItem);
        return item;
    }

    /**
     * 执行apiPost相关逻辑。
     *
     * @param endpoint endpoint 参数。
     * @param payload 待签名或解析的载荷内容。
     * @return 返回api Post结果。
     */
    private ONode apiPost(String endpoint, ONode payload) {
        return apiPost(endpoint, payload, LONG_POLL_TIMEOUT_MS + 5_000);
    }

    /**
     * 解析Upload URL。
     *
     * @param uploadInfo uploadInfo 参数。
     * @param fileKey 文件或目录路径参数。
     * @return 返回解析后的Upload URL。
     */
    private String resolveUploadUrl(ONode uploadInfo, String fileKey) {
        String uploadFullUrl = uploadInfo.get("upload_full_url").getString();
        if (StrUtil.isNotBlank(uploadFullUrl)) {
            return uploadFullUrl;
        }
        String uploadParam = uploadInfo.get("upload_param").getString();
        if (StrUtil.isBlank(uploadParam)) {
            throw new IllegalStateException(
                    "Weixin upload init missing upload url: " + safeJson(uploadInfo));
        }
        String cdnBaseUrl =
                StrUtil.blankToDefault(config.getCdnBaseUrl(), DEFAULT_CDN_BASE_URL)
                        .replaceAll("/+$", "");
        return cdnBaseUrl
                + "/upload?encrypted_query_param="
                + cn.hutool.core.net.URLEncodeUtil.encodeAll(uploadParam)
                + "&filekey="
                + cn.hutool.core.net.URLEncodeUtil.encodeAll(fileKey);
    }

    /**
     * 执行uploadCiphertext相关逻辑。
     *
     * @param uploadUrl 待校验或访问的地址参数。
     * @param ciphertext ciphertext 参数。
     * @return 返回upload Ciphertext结果。
     */
    private String uploadCiphertext(String uploadUrl, byte[] ciphertext) {
        HttpResponse response = executeBinaryPost(uploadUrl, ciphertext, uploadUrl, 0);
        try {
            if (response.getStatus() != 200) {
                throw new IllegalStateException(
                        HutoolHttpErrorFormatter.failure("Weixin CDN upload", response));
            }
            String encryptedParam = response.header("x-encrypted-param");
            if (StrUtil.isBlank(encryptedParam)) {
                throw new IllegalStateException(
                        "Weixin CDN upload missing x-encrypted-param header");
            }
            return encryptedParam;
        } finally {
            response.close();
        }
    }

    /**
     * 执行encryptAesEcb相关逻辑。
     *
     * @param plaintext plaintext 参数。
     * @param key 配置键或映射键。
     * @return 返回encrypt Aes Ecb结果。
     */
    private byte[] encryptAesEcb(byte[] plaintext, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("Weixin encrypt failed", e);
        }
    }

    /**
     * 确保Success。
     *
     * @param node 节点参数。
     * @param defaultMessage 默认消息参数。
     */
    private void ensureSuccess(ONode node, String defaultMessage) {
        int errCode = node.get("errcode").getInt(0);
        int ret = node.get("ret").getInt(0);
        if (errCode != 0 || ret != 0) {
            throw new IllegalStateException(defaultMessage + ": " + safeJson(node));
        }
    }

    /**
     * 加载上下文token。
     *
     * @param chatId 聊天标识。
     * @return 返回上下文token结果。
     */
    private String loadContextToken(String chatId) {
        try {
            return channelStateRepository.get(
                    PlatformType.WEIXIN, config.getAccountId() + ":" + chatId, CONTEXT_TOKEN_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行文件名称Of相关逻辑。
     *
     * @param attachment 附件参数。
     * @return 返回文件名称Of结果。
     */
    private String fileNameOf(MessageAttachment attachment) {
        return StrUtil.blankToDefault(
                attachment.getOriginalName(), new File(attachment.getLocalPath()).getName());
    }

    /** 启动Polling。 */
    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        pollExecutor = Executors.newSingleThreadExecutor();
        pollExecutor.submit(
                new Runnable() {
                    /** 执行异步任务主体。 */
                    @Override
                    public void run() {
                        pollLoop();
                    }
                });
    }

    /**
     * 确保入站执行器。
     *
     * @return 返回入站执行器结果。
     */
    private synchronized ExecutorService ensureInboundExecutor() {
        if (inboundExecutor == null
                || inboundExecutor.isShutdown()
                || inboundExecutor.isTerminated()) {
            inboundExecutor = Executors.newSingleThreadExecutor();
        }
        return inboundExecutor;
    }

    /** 执行poll循环相关逻辑。 */
    private void pollLoop() {
        String syncBuf = loadSyncBuf();
        while (polling && !Thread.currentThread().isInterrupted()) {
            try {
                pruneRecentMessageIds();
                ONode response =
                        apiPost(
                                GET_UPDATES_ENDPOINT,
                                new ONode().set("get_updates_buf", syncBuf).asObject());
                int errCode = response.get("errcode").getInt(0);
                int ret = response.get("ret").getInt(0);
                if (errCode != 0 || ret != 0) {
                    log.warn("[WEIXIN] getupdates failed: {}", safeJson(response));
                    if (errCode == -14 || ret == -14) {
                        setLastError("weixin_session_expired", safeJson(response));
                        setDetail("session expired");
                        sleepQuietly(10);
                        continue;
                    }
                    setLastError("weixin_poll_failed", safeJson(response));
                    setDetail("poll failed");
                    sleepQuietly(2);
                    continue;
                }
                clearLastError();
                setDetail("long-poll active");
                String nextSyncBuf = response.get("get_updates_buf").getString();
                ONode msgs = response.get("msgs");
                for (int i = 0; i < msgs.size(); i++) {
                    processInboundMessage(msgs.get(i));
                }
                if (StrUtil.isNotBlank(nextSyncBuf)) {
                    syncBuf = nextSyncBuf;
                    saveSyncBuf(syncBuf);
                }
            } catch (Exception e) {
                log.warn(
                        "[WEIXIN] poll failed: errorType={}, error={}", errorType(e), safeError(e));
                sleepQuietly(2);
            }
        }
    }

    /**
     * 执行入站消息相关逻辑。
     *
     * @param message 平台消息或错误消息。
     */
    private void processInboundMessage(ONode message) {
        String senderId = message.get("from_user_id").getString();
        if (StrUtil.isBlank(senderId) || senderId.equals(config.getAccountId())) {
            return;
        }
        String messageId = message.get("message_id").getString();
        if (isDuplicate(messageId)) {
            return;
        }

        ChatTarget chatTarget = guessChatTarget(message);
        if ("group".equals(chatTarget.chatType) && !allowGroup(chatTarget.chatId)) {
            return;
        }
        if ("dm".equals(chatTarget.chatType) && !allowDm(senderId)) {
            return;
        }

        String contextToken = message.get("context_token").getString();
        if (StrUtil.isNotBlank(contextToken)) {
            saveContextToken(chatTarget.chatId, contextToken);
        }

        ONode itemList = message.get("item_list");
        String text = extractInboundText(itemList);
        if (isDuplicateText(senderId, text)) {
            return;
        }
        java.util.ArrayList<MessageAttachment> attachments =
                new java.util.ArrayList<MessageAttachment>();
        for (int i = 0; i < itemList.size(); i++) {
            collectMedia(itemList.get(i), attachments, false);
            ONode refItem = itemList.get(i).get("ref_msg").get("message_item");
            if (refItem != null && refItem.isObject()) {
                collectMedia(refItem, attachments, true);
            }
        }
        if (StrUtil.isBlank(text) && attachments.isEmpty()) {
            return;
        }

        GatewayMessage gatewayMessage =
                new GatewayMessage(PlatformType.WEIXIN, chatTarget.chatId, senderId, text);
        gatewayMessage.setChatType(chatTarget.chatType);
        gatewayMessage.setChatName(chatTarget.chatId);
        gatewayMessage.setUserName(senderId);
        gatewayMessage.setAttachments(attachments);
        if (attachments.isEmpty() && StrUtil.isNotBlank(text)) {
            enqueueTextBatch(gatewayMessage, chatTarget.chatType, chatTarget.chatId, contextToken);
            return;
        }
        dispatchInboundMessage(
                gatewayMessage, chatTarget.chatType, chatTarget.chatId, contextToken);
    }

    /**
     * 执行enqueue文本Batch相关逻辑。
     *
     * @param gatewayMessage 网关消息参数。
     * @param chatType 聊天类型参数。
     * @param chatId 聊天标识。
     * @param contextToken 上下文token上下文。
     */
    private void enqueueTextBatch(
            GatewayMessage gatewayMessage, String chatType, String chatId, String contextToken) {
        final String key =
                String.valueOf(gatewayMessage.getPlatform())
                        + ":"
                        + StrUtil.nullToEmpty(gatewayMessage.getChatId())
                        + ":"
                        + StrUtil.nullToEmpty(gatewayMessage.getUserId());
        PendingTextBatch batch =
                pendingTextBatches.compute(
                        key,
                        (batchKey, existing) -> {
                            if (existing == null) {
                                return new PendingTextBatch(
                                        gatewayMessage, chatType, chatId, contextToken);
                            }
                            existing.append(gatewayMessage, chatType, chatId, contextToken);
                            return existing;
                        });
        ScheduledFuture<?> previous = pendingTextBatchTasks.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        long delayMillis = batch.delayMillis(config);
        ScheduledFuture<?> future =
                ensureTextBatchExecutor()
                        .schedule(
                                new Runnable() {
                                    /** 执行异步任务主体。 */
                                    @Override
                                    public void run() {
                                        flushTextBatch(key);
                                    }
                                },
                                delayMillis,
                                TimeUnit.MILLISECONDS);
        pendingTextBatchTasks.put(key, future);
    }

    /**
     * 确保Text Batch执行器。
     *
     * @return 返回Text Batch执行器结果。
     */
    private synchronized ScheduledExecutorService ensureTextBatchExecutor() {
        if (textBatchExecutor == null
                || textBatchExecutor.isShutdown()
                || textBatchExecutor.isTerminated()) {
            textBatchExecutor = BoundedExecutorFactory.scheduled("weixin-text-batch", 1);
        }
        return textBatchExecutor;
    }

    /**
     * 执行flush文本Batch相关逻辑。
     *
     * @param key 配置键或映射键。
     */
    private void flushTextBatch(String key) {
        pendingTextBatchTasks.remove(key);
        PendingTextBatch batch = pendingTextBatches.remove(key);
        if (batch == null) {
            return;
        }
        GatewayMessage message = batch.toGatewayMessage();
        dispatchInboundMessage(message, batch.chatType, batch.chatId, batch.contextToken);
    }

    /**
     * 分发入站消息。
     *
     * @param gatewayMessage 网关消息参数。
     * @param chatType 聊天类型参数。
     * @param chatId 聊天标识。
     * @param contextToken 上下文token上下文。
     */
    private void dispatchInboundMessage(
            final GatewayMessage gatewayMessage,
            final String chatType,
            final String chatId,
            final String contextToken) {
        // 控制命令（/stop、/cancel）走并发执行器，避免被串行入站队列中运行中的任务阻塞而错过取消时机
        if (isControlCommand(gatewayMessage.getText())) {
            dispatchInboundControl(gatewayMessage);
            return;
        }
        try {
            ensureInboundExecutor()
                    .submit(
                            new Runnable() {
                                /** 执行异步任务主体。 */
                                @Override
                                public void run() {
                                    try {
                                        if ("dm".equals(chatType)) {
                                            maybeFetchTypingTicket(chatId, contextToken);
                                            sendTyping(chatId, TYPING_START);
                                        }
                                        inboundMessageHandler().handle(gatewayMessage);
                                    } catch (Exception e) {
                                        log.warn(
                                                "[WEIXIN] inbound dispatch failed: errorType={}, error={}",
                                                errorType(e),
                                                safeError(e));
                                    } finally {
                                        if ("dm".equals(chatType)) {
                                            sendTyping(chatId, TYPING_STOP);
                                        }
                                    }
                                }
                            });
        } catch (Exception e) {
            log.warn(
                    "[WEIXIN] inbound submit failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 提取入站Text。
     *
     * @param itemList item列表参数。
     * @return 返回入站Text结果。
     */
    private String extractInboundText(ONode itemList) {
        for (int i = 0; i < itemList.size(); i++) {
            ONode item = itemList.get(i);
            if (item.get("type").getInt() == ITEM_TEXT) {
                String text = item.get("text_item").get("text").getString();
                if (StrUtil.isBlank(text)) {
                    text = item.get("text_item").get("content").getString();
                }
                if (StrUtil.isNotBlank(text)) {
                    return text.trim();
                }
            }
        }
        for (int i = 0; i < itemList.size(); i++) {
            ONode item = itemList.get(i);
            if (item.get("type").getInt() == 3) {
                String voiceText = item.get("voice_item").get("text").getString();
                if (StrUtil.isNotBlank(voiceText)) {
                    return voiceText.trim();
                }
            }
        }
        return "";
    }

    /**
     * 收集媒体。
     *
     * @param item item 参数。
     * @param attachments attachments 参数。
     * @param fromQuote fromQuote 参数。
     */
    private void collectMedia(ONode item, List<MessageAttachment> attachments, boolean fromQuote) {
        int type = item.get("type").getInt();
        try {
            if (type == ITEM_IMAGE) {
                MessageAttachment attachment =
                        downloadAttachment(
                                "image",
                                item.get("image_item"),
                                "image.jpg",
                                "image/jpeg",
                                fromQuote);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } else if (type == ITEM_VIDEO) {
                MessageAttachment attachment =
                        downloadAttachment(
                                "video",
                                item.get("video_item"),
                                "video.mp4",
                                "video/mp4",
                                fromQuote);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } else if (type == ITEM_FILE) {
                String originalName = item.get("file_item").get("file_name").getString();
                MessageAttachment attachment =
                        downloadAttachment(
                                "file",
                                item.get("file_item"),
                                StrUtil.blankToDefault(originalName, "document.bin"),
                                null,
                                fromQuote);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } else if (type == 3) {
                MessageAttachment attachment =
                        downloadAttachment(
                                "voice",
                                item.get("voice_item"),
                                "voice.silk",
                                "audio/silk",
                                fromQuote);
                if (attachment != null) {
                    String voiceText = item.get("voice_item").get("text").getString();
                    attachment.setTranscribedText(StrUtil.nullToEmpty(voiceText).trim());
                    attachments.add(attachment);
                }
            }
        } catch (Exception e) {
            log.warn(
                    "[WEIXIN] collect media failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 执行download附件相关逻辑。
     *
     * @param kind kind 参数。
     * @param payload 待签名或解析的载荷内容。
     * @param fallbackName 兜底名称参数。
     * @param fallbackMime 兜底MIME参数。
     * @param fromQuote fromQuote 参数。
     * @return 返回download附件结果。
     */
    private MessageAttachment downloadAttachment(
            String kind,
            ONode payload,
            String fallbackName,
            String fallbackMime,
            boolean fromQuote) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        ONode media = payload.get("media");
        String encryptedQuery = media.get("encrypt_query_param").getString();
        String fullUrl = media.get("full_url").getString();
        byte[] raw = downloadBytes(resolveInboundUrl(encryptedQuery, fullUrl));
        byte[] key =
                parseAesKey(payload.get("aeskey").getString(), media.get("aes_key").getString());
        if (key != null) {
            raw = decryptAesEcb(raw, key);
        }
        String originalName = fallbackName;
        if ("file".equals(kind)) {
            originalName =
                    StrUtil.blankToDefault(payload.get("file_name").getString(), fallbackName);
        }
        String mimeType = AttachmentCacheService.normalizeMimeType(fallbackMime, originalName);
        return attachmentCacheService.cacheBytes(
                PlatformType.WEIXIN,
                kind,
                originalName,
                mimeType,
                fromQuote,
                payload.get("text").getString(),
                raw);
    }

    /**
     * 解析入站URL。
     *
     * @param encryptedQuery encrypted查询参数。
     * @param fullUrl 待校验或访问的地址参数。
     * @return 返回解析后的入站URL。
     */
    private String resolveInboundUrl(String encryptedQuery, String fullUrl) {
        if (StrUtil.isNotBlank(encryptedQuery)) {
            String cdnBaseUrl =
                    StrUtil.blankToDefault(config.getCdnBaseUrl(), DEFAULT_CDN_BASE_URL)
                            .replaceAll("/+$", "");
            return cdnBaseUrl
                    + "/download?encrypted_query_param="
                    + cn.hutool.core.net.URLEncodeUtil.encodeAll(encryptedQuery);
        }
        if (StrUtil.isNotBlank(fullUrl)) {
            return fullUrl;
        }
        throw new IllegalStateException("Weixin media item missing download url");
    }

    /**
     * 执行download字节相关逻辑。
     *
     * @param url 待校验或访问的 URL。
     * @return 返回download Bytes结果。
     */
    private byte[] downloadBytes(String url) {
        return BoundedAttachmentIO.downloadHutool(
                url, 60000, BoundedAttachmentIO.DEFAULT_MAX_BYTES, securityPolicyService);
    }

    /**
     * 解析Aes键。
     *
     * @param hexAesKey hexAes键标识或键值。
     * @param encodedAesKey encodedAes键标识或键值。
     * @return 返回解析后的Aes键。
     */
    private byte[] parseAesKey(String hexAesKey, String encodedAesKey) {
        if (StrUtil.isNotBlank(hexAesKey)) {
            return HexUtil.decodeHex(hexAesKey);
        }
        if (StrUtil.isBlank(encodedAesKey)) {
            return null;
        }
        byte[] decoded = Base64.getDecoder().decode(encodedAesKey);
        if (decoded.length == 16) {
            return decoded;
        }
        String candidate = new String(decoded, StandardCharsets.UTF_8);
        if (candidate.matches("(?i)[0-9a-f]{32}")) {
            return HexUtil.decodeHex(candidate);
        }
        return null;
    }

    /**
     * 执行decryptAesEcb相关逻辑。
     *
     * @param ciphertext ciphertext 参数。
     * @param key 配置键或映射键。
     * @return 返回decrypt Aes Ecb结果。
     */
    private byte[] decryptAesEcb(byte[] ciphertext, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Weixin decrypt failed", e);
        }
    }

    /**
     * 执行guess聊天Target相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回guess Chat Target结果。
     */
    private ChatTarget guessChatTarget(ONode message) {
        String roomId = message.get("room_id").getString();
        if (StrUtil.isBlank(roomId)) {
            roomId = message.get("chat_room_id").getString();
        }
        String toUserId = message.get("to_user_id").getString();
        boolean isGroup =
                StrUtil.isNotBlank(roomId)
                        || (StrUtil.isNotBlank(toUserId)
                                && !toUserId.equals(config.getAccountId())
                                && message.get("msg_type").getInt() == 1);
        if (isGroup) {
            return new ChatTarget(
                    "group",
                    StrUtil.blankToDefault(
                            roomId,
                            StrUtil.blankToDefault(
                                    toUserId, message.get("from_user_id").getString())));
        }
        return new ChatTarget("dm", message.get("from_user_id").getString());
    }

    /**
     * 判断是否允许Dm。
     *
     * @param userId 用户标识。
     * @return 如果Dm满足条件则返回 true，否则返回 false。
     */
    private boolean allowDm(String userId) {
        if (config.isAllowAllUsers()) {
            return true;
        }
        String policy =
                StrUtil.blankToDefault(
                                config.getDmPolicy(), GatewayBehaviorConstants.DM_POLICY_OPEN)
                        .toLowerCase();
        if (GatewayBehaviorConstants.DM_POLICY_DISABLED.equals(policy)) {
            return false;
        }
        if (GatewayBehaviorConstants.DM_POLICY_ALLOWLIST.equals(policy)) {
            return contains(config.getAllowedUsers(), userId);
        }
        return true;
    }

    /**
     * 判断是否允许群组。
     *
     * @param chatId 聊天标识。
     * @return 如果群组满足条件则返回 true，否则返回 false。
     */
    private boolean allowGroup(String chatId) {
        if (config.isAllowAllUsers()) {
            return true;
        }
        String policy =
                StrUtil.blankToDefault(
                                config.getGroupPolicy(),
                                GatewayBehaviorConstants.GROUP_POLICY_DISABLED)
                        .toLowerCase();
        if (GatewayBehaviorConstants.GROUP_POLICY_DISABLED.equals(policy)) {
            return false;
        }
        if (GatewayBehaviorConstants.GROUP_POLICY_ALLOWLIST.equals(policy)) {
            return contains(config.getGroupAllowedUsers(), chatId);
        }
        return true;
    }

    /**
     * 执行contains相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @param target target 参数。
     * @return 返回contains结果。
     */
    private boolean contains(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        if (values.contains(GatewayBehaviorConstants.ALLOW_ALL_MARKER)) {
            return true;
        }
        return values.contains(target);
    }

    /**
     * 执行maybeFetchTypingTicket相关逻辑。
     *
     * @param userId 用户标识。
     * @param contextToken 上下文token上下文。
     */
    private void maybeFetchTypingTicket(String userId, String contextToken) {
        TypingTicketState existing = typingTickets.get(userId);
        if (existing != null && existing.isValid()) {
            return;
        }
        try {
            ONode response =
                    apiPost(
                            GET_CONFIG_ENDPOINT,
                            new ONode()
                                    .set("ilink_user_id", userId)
                                    .set("context_token", contextToken)
                                    .asObject(),
                            CONFIG_TIMEOUT_MS);
            String typingTicket = response.get("typing_ticket").getString();
            if (StrUtil.isNotBlank(typingTicket)) {
                typingTickets.put(
                        userId,
                        new TypingTicketState(
                                typingTicket, System.currentTimeMillis() + 10L * 60L * 1000L));
            }
        } catch (Exception e) {
            log.debug(
                    "[WEIXIN] fetch typing ticket failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 发送Typing。
     *
     * @param userId 用户标识。
     * @param status 状态参数。
     */
    private void sendTyping(String userId, int status) {
        TypingTicketState state = typingTickets.get(userId);
        if (state == null || !state.isValid()) {
            return;
        }
        try {
            apiPost(
                    SEND_TYPING_ENDPOINT,
                    new ONode()
                            .set("ilink_user_id", userId)
                            .set("typing_ticket", state.ticket)
                            .set("status", status)
                            .asObject(),
                    CONFIG_TIMEOUT_MS);
        } catch (Exception e) {
            log.debug(
                    "[WEIXIN] send typing failed: errorType={}, error={}",
                    errorType(e),
                    safeError(e));
        }
    }

    /**
     * 判断是否Duplicate。
     *
     * @param messageId 消息标识。
     * @return 如果Duplicate满足条件则返回 true，否则返回 false。
     */
    private boolean isDuplicate(String messageId) {
        if (StrUtil.isBlank(messageId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        pruneRecentMessageIds(now);
        Long previous = recentMessageIds.putIfAbsent(messageId, now);
        if (previous != null) {
            return true;
        }
        pruneRecentMessageIdsToMaxEntries();
        return false;
    }

    /**
     * 判断是否Duplicate Text。
     *
     * @param senderId sender标识。
     * @param text 待处理文本。
     * @return 如果Duplicate Text满足条件则返回 true，否则返回 false。
     */
    private boolean isDuplicateText(String senderId, String text) {
        if (StrUtil.isBlank(senderId) || StrUtil.isBlank(text)) {
            return false;
        }
        return isDuplicate("content:" + senderId + ":" + DigestUtil.md5Hex(text));
    }

    /** 执行pruneRecent消息标识相关逻辑。 */
    private void pruneRecentMessageIds() {
        pruneRecentMessageIds(System.currentTimeMillis());
    }

    /**
     * 执行pruneRecent消息标识相关逻辑。
     *
     * @param now 当前时间戳。
     */
    private void pruneRecentMessageIds(long now) {
        for (java.util.Map.Entry<String, Long> entry : recentMessageIds.entrySet()) {
            if (now - entry.getValue() >= MESSAGE_DEDUP_TTL_MILLIS) {
                recentMessageIds.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 执行pruneRecent消息标识ToMaxEntries相关逻辑。 */
    private void pruneRecentMessageIdsToMaxEntries() {
        while (recentMessageIds.size() > MESSAGE_DEDUP_MAX_ENTRIES) {
            java.util.Map.Entry<String, Long> oldest = null;
            for (java.util.Map.Entry<String, Long> entry : recentMessageIds.entrySet()) {
                if (oldest == null || entry.getValue() < oldest.getValue()) {
                    oldest = entry;
                }
            }
            if (oldest == null || !recentMessageIds.remove(oldest.getKey(), oldest.getValue())) {
                return;
            }
        }
    }

    /**
     * 保存Sync Buf。
     *
     * @param syncBuf 同步Buf参数。
     */
    private void saveSyncBuf(String syncBuf) {
        try {
            channelStateRepository.put(
                    PlatformType.WEIXIN, config.getAccountId(), SYNC_BUF_KEY, syncBuf);
        } catch (Exception e) {
            logRecoverableChannelFailure("save_sync_buf", e);
        }
    }

    /**
     * 加载Sync Buf。
     *
     * @return 返回Sync Buf结果。
     */
    private String loadSyncBuf() {
        try {
            return StrUtil.nullToEmpty(
                    channelStateRepository.get(
                            PlatformType.WEIXIN, config.getAccountId(), SYNC_BUF_KEY));
        } catch (Exception e) {
            logRecoverableChannelFailure("load_sync_buf", e);
            return "";
        }
    }

    /**
     * 保存上下文token。
     *
     * @param chatId 聊天标识。
     * @param contextToken 上下文token上下文。
     */
    private void saveContextToken(String chatId, String contextToken) {
        try {
            channelStateRepository.put(
                    PlatformType.WEIXIN,
                    config.getAccountId() + ":" + chatId,
                    CONTEXT_TOKEN_KEY,
                    contextToken);
        } catch (Exception e) {
            logRecoverableChannelFailure("save_context_token", e);
        }
    }

    /**
     * 记录微信渠道可降级失败，只输出平台、阶段和异常类型，避免泄露消息正文或凭据。
     *
     * @param stage 失败发生的内部阶段。
     * @param error 捕获到的异常。
     */
    private void logRecoverableChannelFailure(String stage, Exception error) {
        ThreadInterruptSupport.restoreIfCausedByInterrupted(error);
        log.debug(
                "[WEIXIN] recoverable channel failure: platform={}, stage={}, errorType={}",
                PlatformType.WEIXIN,
                stage,
                errorType(error));
    }

    /**
     * 执行sleepQuietly相关逻辑。
     *
     * @param seconds seconds 参数。
     */
    private void sleepQuietly(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行sleepQuietlyMillis相关逻辑。
     *
     * @param millis millis 参数。
     */
    private void sleepQuietlyMillis(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行apiPost相关逻辑。
     *
     * @param endpoint endpoint 参数。
     * @param payload 待签名或解析的载荷内容。
     * @param timeoutMs timeoutMs 参数。
     * @return 返回api Post结果。
     */
    private ONode apiPost(String endpoint, ONode payload, int timeoutMs) {
        String baseUrl = resolveBaseUrl(endpoint);
        payload.set("base_info", new ONode().set("channel_version", "2.2.0").asObject());
        String body = payload.toJson();
        String url = normalizeBaseUrl(baseUrl) + "/" + endpoint;
        HttpResponse response = executeApiPost(url, body, Math.max(20_000, timeoutMs), url, 0);
        try {
            return ONode.ofJson(
                    BoundedAttachmentIO.readHutoolText(
                            response, BoundedAttachmentIO.JSON_MAX_BYTES));
        } finally {
            response.close();
        }
    }

    /**
     * 解析Base URL。
     *
     * @param endpoint endpoint 参数。
     * @return 返回解析后的Base URL。
     */
    private String resolveBaseUrl(String endpoint) {
        String configured =
                BaseUrlSupport.stripTrailingSlashes(
                        StrUtil.blankToDefault(config.getBaseUrl(), DEFAULT_BASE_URL));
        if (GET_UPDATES_ENDPOINT.equals(endpoint) && StrUtil.isNotBlank(config.getLongPollUrl())) {
            String longPoll = config.getLongPollUrl().trim();
            if (longPoll.endsWith("/" + GET_UPDATES_ENDPOINT)) {
                return longPoll.substring(0, longPoll.length() - GET_UPDATES_ENDPOINT.length() - 1);
            }
            if (longPoll.startsWith("http://") || longPoll.startsWith("https://")) {
                return longPoll.replaceAll("/+$", "");
            }
        }
        return configured;
    }

    /**
     * 执行Api Post。
     *
     * @param url 待校验或访问的 URL。
     * @param body 请求体或消息正文内容。
     * @param timeoutMs timeoutMs 参数。
     * @param initialUrl 待校验或访问的地址参数。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回Api Post结果。
     */
    private HttpResponse executeApiPost(
            String url, String body, int timeoutMs, String initialUrl, int redirectCount) {
        HttpRequest request =
                HttpRequest.post(url)
                        .header("AuthorizationType", "ilink_bot_token")
                        .header("Authorization", "Bearer " + config.getToken())
                        .header("iLink-App-Id", "bot")
                        .header("iLink-App-ClientVersion", String.valueOf((2 << 16) | (2 << 8)))
                        .header(
                                "X-WECHAT-UIN",
                                Base64.getEncoder()
                                        .encodeToString(
                                                String.valueOf(Math.abs(RandomUtil.randomInt()))
                                                        .getBytes(StandardCharsets.UTF_8)))
                        .contentType(ContentType.JSON.toString())
                        .body(body)
                        .timeout(timeoutMs)
                        .setFollowRedirects(false);
        HttpResponse response = request.execute();
        if (!HttpRedirectSupport.isRedirectStatus(response.getStatus())) {
            return response;
        }
        try {
            String nextUrl = resolveRedirect(url, response, redirectCount, "Weixin API URL");
            if (!UrlOriginSupport.sameOrigin(initialUrl, nextUrl)) {
                throw new IllegalStateException(
                        "Weixin API redirect crosses origin: " + SecretRedactor.maskUrl(nextUrl));
            }
            response.close();
            return executeApiPost(nextUrl, body, timeoutMs, initialUrl, redirectCount + 1);
        } catch (RuntimeException e) {
            response.close();
            throw e;
        }
    }

    /**
     * 执行Binary Post。
     *
     * @param url 待校验或访问的 URL。
     * @param body 请求体或消息正文内容。
     * @param initialUrl 待校验或访问的地址参数。
     * @param redirectCount 文件或目录路径参数。
     * @return 返回Binary Post结果。
     */
    private HttpResponse executeBinaryPost(
            String url, byte[] body, String initialUrl, int redirectCount) {
        HttpRequest request =
                HttpRequest.post(url)
                        .body(body)
                        .header("Content-Type", "application/octet-stream")
                        .timeout(120000)
                        .setFollowRedirects(false);
        HttpResponse response = request.execute();
        if (!HttpRedirectSupport.isRedirectStatus(response.getStatus())) {
            return response;
        }
        try {
            String nextUrl = resolveRedirect(url, response, redirectCount, "Weixin CDN upload URL");
            if (!UrlOriginSupport.sameOrigin(initialUrl, nextUrl)) {
                throw new IllegalStateException(
                        "Weixin CDN upload redirect crosses origin: "
                                + SecretRedactor.maskUrl(nextUrl));
            }
            response.close();
            return executeBinaryPost(nextUrl, body, initialUrl, redirectCount + 1);
        } catch (RuntimeException e) {
            response.close();
            throw e;
        }
    }

    /**
     * 解析Redirect。
     *
     * @param url 待校验或访问的 URL。
     * @param response 当前响应对象。
     * @param redirectCount 文件或目录路径参数。
     * @param purpose purpose 参数。
     * @return 返回解析后的Redirect。
     */
    private String resolveRedirect(
            String url, HttpResponse response, int redirectCount, String purpose) {
        if (redirectCount >= MAX_HTTP_REDIRECTS) {
            throw new IllegalStateException(purpose + " redirect count exceeds limit");
        }
        String location = response.header("Location");
        if (StrUtil.isBlank(location)) {
            throw new IllegalStateException(purpose + " redirect missing Location");
        }
        String nextUrl =
                HttpRedirectSupport.resolveLocation(
                        url, location, purpose + " redirect URL is invalid");
        return nextUrl;
    }


    /**
     * 生成安全展示用的JSON。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回safe JSON结果。
     */
    private String safeJson(ONode value) {
        return SecretRedactor.redact(value == null ? "" : value.toJson(), 1000);
    }

    /** 规范化基础 URL，避免后续拼接路径时出现重复斜杠。 */
    private String normalizeBaseUrl(String baseUrl) {
        return BaseUrlSupport.stripTrailingSlashes(baseUrl);
    }

    /** 承载聊天Target相关状态和辅助逻辑。 */
    private static class ChatTarget {
        /** 记录聊天Target中的聊天类型。 */
        private final String chatType;

        /** 记录聊天Target中的聊天标识。 */
        private final String chatId;

        /**
         * 创建Chat Target实例，并注入运行所需依赖。
         *
         * @param chatType 聊天类型参数。
         * @param chatId 聊天标识。
         */
        private ChatTarget(String chatType, String chatId) {
            this.chatType = chatType;
            this.chatId = chatId;
        }
    }

    /** 表示TypingTicket数据，在服务、仓储和接口之间传递。 */
    private static class TypingTicketState {
        /** 记录TypingTicket中的ticket。 */
        private final String ticket;

        /** 记录TypingTicket中的expires时间。 */
        private final long expiresAt;

        /**
         * 创建Typing Ticket状态实例，并注入运行所需依赖。
         *
         * @param ticket ticket 参数。
         * @param expiresAt expiresAt 参数。
         */
        private TypingTicketState(String ticket, long expiresAt) {
            this.ticket = ticket;
            this.expiresAt = expiresAt;
        }

        /**
         * 判断是否Valid。
         *
         * @return 如果Valid满足条件则返回 true，否则返回 false。
         */
        private boolean isValid() {
            return ticket != null && ticket.length() > 0 && expiresAt > System.currentTimeMillis();
        }
    }

    /** 承载待恢复文本Batch相关状态和辅助逻辑。 */
    private static class PendingTextBatch {
        /** 记录待恢复文本Batch中的消息。 */
        private final GatewayMessage message;

        /** 记录待恢复文本Batch中的聊天类型。 */
        private String chatType;

        /** 记录待恢复文本Batch中的聊天标识。 */
        private String chatId;

        /** 记录待恢复文本Batch中的上下文token。 */
        private String contextToken;

        /** 记录待恢复文本Batch中的最近一次分片Length。 */
        private int lastChunkLength;

        /**
         * 创建Pending Text Batch实例，并注入运行所需依赖。
         *
         * @param message 平台消息或错误消息。
         * @param chatType 聊天类型参数。
         * @param chatId 聊天标识。
         * @param contextToken 上下文token上下文。
         */
        private PendingTextBatch(
                GatewayMessage message, String chatType, String chatId, String contextToken) {
            this.message = message;
            this.chatType = chatType;
            this.chatId = chatId;
            this.contextToken = contextToken;
            this.lastChunkLength = StrUtil.length(message.getText());
        }

        /**
         * 执行append相关逻辑。
         *
         * @param nextMessage next消息参数。
         * @param nextChatType next聊天类型参数。
         * @param nextChatId next聊天标识。
         * @param nextContextToken next上下文token上下文。
         */
        private void append(
                GatewayMessage nextMessage,
                String nextChatType,
                String nextChatId,
                String nextContextToken) {
            String existing = StrUtil.nullToEmpty(message.getText());
            String nextText = StrUtil.nullToEmpty(nextMessage.getText());
            if (StrUtil.isBlank(existing)) {
                message.setText(nextText);
            } else if (StrUtil.isNotBlank(nextText)) {
                message.setText(existing + "\n" + nextText);
            }
            message.setThreadId(nextMessage.getThreadId());
            message.setTimestamp(nextMessage.getTimestamp());
            this.chatType = nextChatType;
            this.chatId = nextChatId;
            this.contextToken = nextContextToken;
            this.lastChunkLength = StrUtil.length(nextText);
        }

        /**
         * 执行delayMillis相关逻辑。
         *
         * @param config 当前模块使用的配置对象。
         * @return 返回delay Millis结果。
         */
        private long delayMillis(AppConfig.ChannelConfig config) {
            double seconds =
                    lastChunkLength >= INBOUND_TEXT_SPLIT_THRESHOLD
                            ? config.getTextBatchSplitDelaySeconds()
                            : config.getTextBatchDelaySeconds();
            if (!Double.isFinite(seconds) || seconds < 0.0D) {
                seconds = 0.0D;
            }
            return Math.max(0L, Math.round(seconds * 1000D));
        }

        /**
         * 转换为消息网关消息。
         *
         * @return 返回转换后的消息网关消息。
         */
        private GatewayMessage toGatewayMessage() {
            return message;
        }
    }
}
