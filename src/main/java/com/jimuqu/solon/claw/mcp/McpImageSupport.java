package com.jimuqu.solon.claw.mcp;

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

    /** 创建MCP图片辅助实例。 */
    private McpImageSupport() {}

    /**
     * 提取图片。
     *
     * @param toolResult 工具结果响应或执行结果。
     * @return 返回图片结果。
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
            if ("image".equals(type)) {
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
     * 判断是否存在图片。
     *
     * @param toolResult 工具结果响应或执行结果。
     * @return 如果图片满足条件则返回 true，否则返回 false。
     */
    public static boolean hasImages(Object toolResult) {
        return !extractImages(toolResult).isEmpty();
    }

    /**
     * 转换为Data URI。
     *
     * @param image描述符 图片描述符参数。
     * @return 返回转换后的Data URI。
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
     * 提取图片块。
     *
     * @param block 阻断参数。
     * @return 返回图片块结果。
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
            mimeType = "image/png";
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
     * 剥离数据URI。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回strip Data URI结果。
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
     * 将输入对象转换为去除首尾空白的字符串。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回string Value结果。
     */
    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
