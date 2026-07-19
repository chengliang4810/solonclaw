package com.jimuqu.solon.claw.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 统一附件模型。 */
@Getter
@Setter
@NoArgsConstructor
public class MessageAttachment {
    /** 附件类型：image / file / video / voice。 */
    private String kind;

    /** 附件缓存后的本地绝对路径。 */
    private String localPath;

    /** 原始文件名。 */
    private String originalName;

    /** MIME 类型。 */
    private String mimeType;

    /** 附件体积，未知时为 0。 */
    private long sizeBytes;

    /** 内联 base64 数据，例如 image/resource blob。 */
    private String data;

    /** 远程或 data: URL，例如直接 image block。 */
    private String url;

    /** 是否来自引用消息。 */
    private boolean fromQuote;

    /** 平台原生提供的转写文本。 */
    private String transcribedText;

    /** 入站准入阶段保存的平台原始附件引用，例如下载码、资源键或远程 URL。 */
    private String sourceReference;

    /** 解析原始附件引用所需的平台上下文，例如消息 ID 或备用加密键。 */
    private String sourceContext;

    /** 下载后解密附件所需的平台原始密钥。 */
    private String sourceEncryptionKey;

    /** 平台原始资源类型或引用类型，用于恢复时选择正确下载协议。 */
    private String sourceResourceType;
}
