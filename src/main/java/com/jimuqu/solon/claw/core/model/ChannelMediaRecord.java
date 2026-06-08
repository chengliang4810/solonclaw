package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 国内渠道媒体缓存索引。 */
@Getter
@Setter
@NoArgsConstructor
public class ChannelMediaRecord {
    /** 记录渠道媒体中的媒体标识。 */
    private String mediaId;

    /** 记录渠道媒体中的平台。 */
    private String platform;

    /** 记录渠道媒体中的聊天标识。 */
    private String chatId;

    /** 记录渠道媒体中的消息标识。 */
    private String messageId;

    /** 记录渠道媒体中的kind。 */
    private String kind;

    /** 记录渠道媒体中的original名称。 */
    private String originalName;

    /** 记录渠道媒体中的MIME 类型。 */
    private String mimeType;

    /** 记录渠道媒体中的本地路径。 */
    private String localPath;

    /** 记录渠道媒体中的remote标识。 */
    private String remoteId;

    /** 记录渠道媒体中的状态。 */
    private String status;

    /** 记录渠道媒体中的错误。 */
    private String error;

    /** 记录渠道媒体中的大小字节。 */
    private long sizeBytes;

    /** 记录渠道媒体中的创建时间。 */
    private long createdAt;

    /** 记录渠道媒体中的更新时间。 */
    private long updatedAt;

    /** 记录渠道媒体中的expires时间。 */
    private long expiresAt;
}
