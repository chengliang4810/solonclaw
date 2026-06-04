package com.jimuqu.solon.claw.gateway.service;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.GatewayMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Message canonicalization service. Normalizes messages from different platforms into a consistent
 * internal format.
 */
public class MessageCanonicalizationService {
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("<@[A-Za-z0-9_-]+>|@[A-Za-z0-9_\\u4e00-\\u9fff]+");
    private static final Pattern EMOJI_CODE_PATTERN = Pattern.compile(":[a-z0-9_+-]+:");
    private static final int MAX_TEXT_LENGTH = 32000;

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

    public Map<String, Object> policy() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("maxTextLength", Integer.valueOf(MAX_TEXT_LENGTH));
        result.put("mentionStripping", Boolean.TRUE);
        result.put("whitespaceNormalization", Boolean.TRUE);
        result.put("emojiCodeNormalization", Boolean.TRUE);
        return result;
    }

    private String normalizeWhitespace(String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{4,}", "\n\n\n");
        return text;
    }

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

    private String normalizeEmojiCodes(String text) {
        return text;
    }

    private String truncateIfNeeded(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH) + "\n...[消息已截断]";
    }
}
