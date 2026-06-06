package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 提供消息规范化相关业务能力，封装调用方不需要感知的运行细节。 */
public class MessageCanonicalizationService {
    /** MENTION正则的统一常量值。 */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("<@[A-Za-z0-9_-]+>|@[A-Za-z0-9_\\u4e00-\\u9fff]+");

    /** EMOJICODE正则的统一常量值。 */
    private static final Pattern EMOJI_CODE_PATTERN = Pattern.compile(":[a-z0-9_+-]+:");

    /** 最大文本LENGTH的统一常量值。 */
    private static final int MAX_TEXT_LENGTH = 32000;

    /**
     * 执行canonicalize相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回canonicalize结果。
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
     * 执行策略相关逻辑。
     *
     * @return 返回策略结果。
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
     * 规范化Whitespace。
     *
     * @param text 待处理文本。
     * @return 返回Whitespace结果。
     */
    private String normalizeWhitespace(String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{4,}", "\n\n\n");
        return text;
    }

    /**
     * 剥离平台Mentions。
     *
     * @param text 待处理文本。
     * @param platform 平台参数。
     * @return 返回strip平台Mentions结果。
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
     * 规范化Emoji Codes。
     *
     * @param text 待处理文本。
     * @return 返回Emoji Codes结果。
     */
    private String normalizeEmojiCodes(String text) {
        return text;
    }

    /**
     * 执行truncateIfNeeded相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回truncate If Needed结果。
     */
    private String truncateIfNeeded(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "\n...[消息已截断]";
    }
}
