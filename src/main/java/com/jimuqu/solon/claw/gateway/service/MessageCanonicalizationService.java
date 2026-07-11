package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 提供消息规范化相关业务能力，封装调用方不需要感知的运行细节。 */
public class MessageCanonicalizationService {
    /** 通用 @ 提及格式预留正则，保留给后续渠道差异化规则复用。 */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("<@[A-Za-z0-9_-]+>|@[A-Za-z0-9_\\u4e00-\\u9fff]+");

    /** emoji 短码格式预留正则，保留现有规范化策略的扩展入口。 */
    private static final Pattern EMOJI_CODE_PATTERN = Pattern.compile(":[a-z0-9_+-]+:");

    /** 单条入站文本进入 Agent 主循环前允许保留的最大字符数。 */
    private static final int MAX_TEXT_LENGTH = 32000;

    /**
     * 对入站消息执行跨平台文本规范化。
     *
     * @param message 国内渠道收到的原始消息。
     * @return 已原地更新文本字段的消息对象；入参为空时仍返回空。
     */
    public GatewayMessage canonicalize(GatewayMessage message) {
        if (message == null) {
            return message;
        }
        String text = StrUtil.nullToEmpty(message.getText());
        text = normalizeWhitespace(text);
        text = stripPlatformMentions(text, message.getPlatform());
        text = normalizeEmojiCodes(text);
        text = truncateIfNeeded(text);
        message.setText(text.trim());
        return message;
    }

    /**
     * 输出当前消息规范化策略，供 dashboard 诊断页展示。
     *
     * @return 包含长度、提及和空白处理开关的策略快照。
     */
    public Map<String, Object> policy() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("maxTextLength", Integer.valueOf(MAX_TEXT_LENGTH));
        result.put("mentionStripping", Boolean.TRUE);
        result.put("whitespaceNormalization", Boolean.TRUE);
        result.put("emojiCodeNormalization", Boolean.TRUE);
        return result;
    }

    /**
     * 统一不同平台换行和行尾空白，避免渠道格式差异进入会话历史。
     *
     * @param text 待规范化的消息文本。
     * @return 统一换行并压缩超长空行后的文本。
     */
    private String normalizeWhitespace(String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{4,}", "\n\n\n");
        return text;
    }

    /**
     * 按平台剥离机器人提及文本，保留用户真实输入。
     *
     * @param text 待处理文本。
     * @param platform 消息来源平台。
     * @return 去掉平台提及标记后的文本。
     */
    private String stripPlatformMentions(String text, PlatformType platform) {
        if (platform == null) {
            return text;
        }
        switch (platform) {
            case FEISHU:
                return text.replaceAll("<at user_id=\"[^\"]*\">[^<]*</at>", "").trim();
            case DINGTALK:
                return text.replaceAll("@[A-Za-z0-9_\\u4e00-\\u9fff]+\\s*", "").trim();
            case WECOM:
                return text.replaceAll("@[A-Za-z0-9_\\u4e00-\\u9fff]+\\s*", "").trim();
            case QQBOT:
                return text.replaceAll("<@![0-9]+>", "").trim();
            default:
                return text;
        }
    }

    /**
     * 保留 emoji 短码规范化入口；当前行为是不改写原文本。
     *
     * @param text 待处理文本。
     * @return 与入参相同的文本。
     */
    private String normalizeEmojiCodes(String text) {
        return text;
    }

    /**
     * 在进入会话前截断过长消息，避免单条渠道输入撑爆上下文。
     *
     * @param text 待处理文本。
     * @return 未超过限制时返回原文，否则追加截断提示。
     */
    private String truncateIfNeeded(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "\n...[消息已截断]";
    }
}
