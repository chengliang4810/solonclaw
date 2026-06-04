package com.jimuqu.solon.claw.mcp;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles MCP tool results that contain image content blocks. Converts image content to internal
 * attachment-friendly format.
 */
public class McpImageSupport {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 10 MB cap

    private McpImageSupport() {}

    /**
     * Extracts image attachments from an MCP tool result object. Returns a list of image descriptor
     * maps, each with keys: {@code mime_type}, {@code data} (base64), {@code size_bytes}, {@code
     * source}.
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
            // Recurse into content arrays (MCP result envelope)
            Object content = map.get("content");
            if (content instanceof List) {
                for (Object item : (List<?>) content) {
                    images.addAll(extractImages(item));
                }
            }
        }
        return images;
    }

    /** Returns true if the tool result contains at least one image content block. */
    public static boolean hasImages(Object toolResult) {
        return !extractImages(toolResult).isEmpty();
    }

    /**
     * Converts an image descriptor map (from {@link #extractImages}) to a base64 data URI string
     * suitable for embedding in HTML or markdown.
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

    private static Map<String, Object> extractImageBlock(Map<?, ?> block) {
        String source = stringValue(block.get("source"));
        String mimeType = "";
        String data = "";

        // MCP image block: { type: "image", source: { type: "base64", media_type: "...", data:
        // "..." } }
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

        // Fallback: flat image block with data/mimeType directly
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

        // Validate and size-check the data
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

        // URL-only image reference
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

    private static String stripDataUri(String value) {
        String text = StrUtil.nullToEmpty(value).trim();
        int comma = text.indexOf(',');
        if (text.startsWith("data:") && comma >= 0) {
            return text.substring(comma + 1);
        }
        return text;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
