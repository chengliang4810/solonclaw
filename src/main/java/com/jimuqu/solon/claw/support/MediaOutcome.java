package com.jimuqu.solon.claw.support;

import com.jimuqu.solon.claw.core.model.MessageAttachment;
import java.util.Map;

/**
 * 媒体操作结果基类，提供图片生成、语音合成等媒体操作的通用结果结构。
 *
 * <p>子类只需添加对应的静态工厂方法（如 ok、fail），其余字段和访问器已在此基类实现。
 */
public abstract class MediaOutcome {
    /** 本次媒体操作是否成功。 */
    private final boolean success;

    /** 成功时缓存得到的附件。 */
    private final MessageAttachment attachment;

    /** 成功时可回填到会话或工具结果的媒体引用。 */
    private final String mediaReference;

    /** 实际执行的内置提供方名称。 */
    private final String provider;

    /** 失败时经过脱敏处理的错误。 */
    private final String error;

    /** 保存媒体用量映射，便于按键快速查询。 */
    private final Map<String, Object> mediaUsage;

    /**
     * 创建媒体操作结果实例。
     *
     * @param success 是否成功。
     * @param attachment 成功时的附件。
     * @param mediaReference 媒体引用。
     * @param provider 提供方名称。
     * @param error 错误信息。
     * @param mediaUsage 媒体用量。
     */
    protected MediaOutcome(
            boolean success,
            MessageAttachment attachment,
            String mediaReference,
            String provider,
            String error,
            Map<String, Object> mediaUsage) {
        this.success = success;
        this.attachment = attachment;
        this.mediaReference = mediaReference;
        this.provider = provider;
        this.error = error;
        this.mediaUsage = BasicValueSupport.mutableLinkedMap(mediaUsage);
    }

    /**
     * 判断操作是否成功。
     *
     * @return 成功返回 true，否则返回 false。
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 读取缓存附件。
     *
     * @return 附件对象，失败时为 null。
     */
    public MessageAttachment getAttachment() {
        return attachment;
    }

    /**
     * 读取可被模型或前端引用的媒体地址。
     *
     * @return 媒体引用字符串，失败时为 null。
     */
    public String getMediaReference() {
        return mediaReference;
    }

    /**
     * 读取实际使用的内置提供方名称。
     *
     * @return 提供方名称，失败时为 null。
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 读取失败错误文本。
     *
     * @return 错误信息，成功时为 null。
     */
    public String getError() {
        return error;
    }

    /**
     * 读取媒体用量映射。
     *
     * @return 媒体用量映射，失败时为 null。
     */
    public Map<String, Object> getMediaUsage() {
        return mediaUsage;
    }
}
