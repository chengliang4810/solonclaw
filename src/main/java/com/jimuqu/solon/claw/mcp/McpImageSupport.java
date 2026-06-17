package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 封装MCP图片辅助逻辑，降低主流程中的重复实现。 */
public class McpImageSupport {
    /** 单张 MCP 图片允许进入附件链路的最大字节数。 */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** MCP 服务端未声明 MIME 类型时使用的默认图片类型。 */
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/png";

    /** MCP 图片类型块的固定标识。 */
    private static final String MCP_IMAGE_TYPE = "image";

    /** 创建MCP图片辅助实例。 */
    private McpImageSupport() {}

    /**
     * 从 MCP 工具结果中递归提取图片块，兼容 result envelope 的 content 数组。
     *
     * @param toolResult MCP 工具调用返回的原始对象，可能是 Map、List 或普通文本。
     * @return 已校验和规范化后的图片描述符列表，列表元素可直接进入媒体附件链路。
     */
    public static List<Map<String, Object>> extractImages(Object toolResult) {
        List<Map<String, Object>> images = new ArrayList<Map<String, Object>>();
        if (toolResult == null) {
            return images;
        }
        if (toolResult instanceof List) {
            for (Object item : (List<?>) toolResult) {
                images.addAll(extractImages(item));
            }
            return images;
        }
        if (toolResult instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) toolResult;
            String type = stringValue(map.get("type"));
            if (MCP_IMAGE_TYPE.equals(type)) {
                Map<String, Object> image = extractImageBlock(map);
                if (image != null) {
                    images.add(image);
                }
                return images;
            }
            // 递归解析 MCP result envelope 中的 content 数组。
            Object content = map.get("content");
            if (content instanceof List) {
                for (Object item : (List<?>) content) {
                    images.addAll(extractImages(item));
                }
            }
        }
        return images;
    }

    /**
     * 快速判断 MCP 工具结果中是否包含可用图片。
     *
     * @param toolResult MCP 工具调用返回的原始对象。
     * @return 存在可进入附件链路的图片时返回 true。
     */
    public static boolean hasImages(Object toolResult) {
        return CollUtil.isNotEmpty(extractImages(toolResult));
    }

    /**
     * 将规范化图片描述符转换为浏览器和多模态模型可识别的 Data URI。
     *
     * @param imageDescriptor {@link #extractImages(Object)} 输出的单个图片描述符。
     * @return MIME 类型或 base64 数据缺失时返回空字符串，否则返回 Data URI。
     */
    public static String toDataUri(Map<String, Object> imageDescriptor) {
        if (imageDescriptor == null) {
            return "";
        }
        String mimeType = stringValue(imageDescriptor.get("mime_type"));
        String data = stringValue(imageDescriptor.get("data"));
        if (StrUtil.isBlank(mimeType) || StrUtil.isBlank(data)) {
            return "";
        }
        return "data:" + mimeType + ";base64," + data;
    }

    /**
     * 从单个 MCP image block 中提取 MIME、base64 数据或来源 URL。
     *
     * @param block MCP 协议返回的 image 类型块。
     * @return 合法图片描述符；类型不安全、base64 非法或超过大小限制时返回 null。
     */
    private static Map<String, Object> extractImageBlock(Map<?, ?> block) {
        String source = stringValue(block.get("source"));
        String mimeType = "";
        String data = "";

        // 兼容 MCP 图片块：source 可承载 media_type、mimeType、data 或 url。
        Object sourceObj = block.get("source");
        if (sourceObj instanceof Map) {
            Map<?, ?> sourceMap = (Map<?, ?>) sourceObj;
            mimeType = stringValue(sourceMap.get("media_type"));
            if (StrUtil.isBlank(mimeType)) {
                mimeType = stringValue(sourceMap.get("mimeType"));
            }
            data = stringValue(sourceMap.get("data"));
            source = stringValue(sourceMap.get("url"));
        }

        // 兼容扁平图片块：data 和 mimeType 直接位于当前对象。
        if (StrUtil.isBlank(data)) {
            data = stringValue(block.get("data"));
        }
        if (StrUtil.isBlank(mimeType)) {
            mimeType = stringValue(block.get("mimeType"));
            if (StrUtil.isBlank(mimeType)) {
                mimeType = stringValue(block.get("mime_type"));
            }
        }
        if (StrUtil.isBlank(mimeType)) {
            mimeType = DEFAULT_IMAGE_MIME_TYPE;
        }
        if (!mimeType.startsWith("image/")) {
            return null;
        }

        // 校验 base64 图片数据，并阻断超出大小上限的结果。
        if (StrUtil.isNotBlank(data)) {
            try {
                byte[] bytes = Base64.getDecoder().decode(stripDataUri(data));
                if (bytes.length > MAX_IMAGE_BYTES) {
                    return null;
                }
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("mime_type", mimeType);
                result.put("data", Base64.getEncoder().encodeToString(bytes));
                result.put("size_bytes", Integer.valueOf(bytes.length));
                result.put("source", StrUtil.nullToEmpty(source));
                return result;
            } catch (Exception e) {
                return null;
            }
        }

        // 对仅提供 URL 的图片引用保留来源，不在此处下载远程内容。
        if (StrUtil.isNotBlank(source)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("mime_type", mimeType);
            result.put("data", "");
            result.put("size_bytes", Integer.valueOf(0));
            result.put("source", source);
            return result;
        }
        return null;
    }

    /**
     * 去掉 Data URI 前缀，仅保留 base64 主体，便于统一校验图片大小。
     *
     * @param value 可能带有 {@code data:*;base64,} 前缀的原始图片数据。
     * @return 可直接交给 Base64 解码器的文本。
     */
    private static String stripDataUri(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        int comma = text.indexOf(',');
        if (text.startsWith("data:") && comma >= 0) {
            return text.substring(comma + 1);
        }
        return text;
    }

    /**
     * 将 MCP 返回值安全转换为去除首尾空白的字符串。
     *
     * @param value 任意来源字段值。
     * @return null 转为空字符串，其余值使用 {@link String#valueOf(Object)} 后 trim。
     */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
