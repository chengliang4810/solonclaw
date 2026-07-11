package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;

/** 图片生成工具允许的宽高比枚举，枚举名直接对应模型工具 JSON 值。 */
public enum ImageAspectRatio {
    /** 16:9 横向图片。 */
    landscape,

    /** 1:1 方形图片。 */
    square,

    /** 9:16 纵向图片。 */
    portrait;

    /**
     * 解析宽高比；空值使用 landscape，非法值返回 null 供调用方明确报错。
     *
     * @param value 模型工具传入的宽高比文本。
     * @return 合法枚举、默认 landscape 或 null。
     */
    public static ImageAspectRatio resolve(String value) {
        if (StrUtil.isBlank(value)) {
            return landscape;
        }
        try {
            return ImageAspectRatio.valueOf(value.trim().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
