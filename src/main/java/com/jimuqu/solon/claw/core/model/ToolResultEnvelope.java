package com.jimuqu.solon.claw.core.model;

import cn.hutool.core.util.StrUtil;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;

/** 统一工具返回 envelope，兼容旧版 success 字段。 */
public class ToolResultEnvelope {
    /** 保存数据映射，便于按键快速查询。 */
    private final Map<String, Object> data = new LinkedHashMap<String, Object>();

    /** 保存元数据映射，便于按键快速查询。 */
    private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

    /** 记录工具结果Envelope中的状态。 */
    private String status;

    /** 记录工具结果Envelope中的摘要。 */
    private String summary;

    /** 记录工具结果Envelope中的预览。 */
    private String preview;

    /** 记录工具结果Envelope中的结果Ref。 */
    private String resultRef;

    /** 记录工具结果Envelope中的错误。 */
    private String error;

    /** 记录工具结果Envelope中的大小。 */
    private long size;

    /** 是否启用truncated。 */
    private boolean truncated;

    /**
     * 构造成功结果。
     *
     * @param summary 摘要参数。
     * @return 返回ok结果。
     */
    public static ToolResultEnvelope ok(String summary) {
        ToolResultEnvelope envelope = new ToolResultEnvelope();
        envelope.status = "success";
        envelope.summary = summary;
        return envelope;
    }

    /**
     * 执行错误相关逻辑。
     *
     * @param message 平台消息或错误消息。
     * @return 返回error结果。
     */
    public static ToolResultEnvelope error(String message) {
        ToolResultEnvelope envelope = new ToolResultEnvelope();
        envelope.status = "error";
        envelope.error = StrUtil.blankToDefault(message, "Tool execution failed");
        envelope.summary = envelope.error;
        return envelope;
    }

    /**
     * 执行数据相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回data结果。
     */
    public ToolResultEnvelope data(String key, Object value) {
        data.put(key, value);
        return this;
    }

    /**
     * 执行数据IfNot空值相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回data If Not Null结果。
     */
    public ToolResultEnvelope dataIfNotNull(String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
        return this;
    }

    /**
     * 执行元数据相关逻辑。
     *
     * @param key 配置键或映射键。
     * @param value 待规范化或校验的原始值。
     * @return 返回元数据结果。
     */
    public ToolResultEnvelope metadata(String key, Object value) {
        metadata.put(key, value);
        return this;
    }

    /**
     * 执行预览相关逻辑。
     *
     * @param preview 预览参数。
     * @return 返回preview结果。
     */
    public ToolResultEnvelope preview(String preview) {
        this.preview = preview;
        this.size = StrUtil.nullToEmpty(preview).getBytes(StandardCharsets.UTF_8).length;
        return this;
    }

    /**
     * 执行结果Ref相关逻辑。
     *
     * @param resultRef 结果Ref响应或执行结果。
     * @return 返回结果Ref结果。
     */
    public ToolResultEnvelope resultRef(String resultRef) {
        this.resultRef = resultRef;
        return this;
    }

    /**
     * 执行大小相关逻辑。
     *
     * @param size size 参数。
     * @return 返回大小结果。
     */
    public ToolResultEnvelope size(long size) {
        this.size = size;
        return this;
    }

    /**
     * 执行truncated相关逻辑。
     *
     * @param truncated truncated 参数。
     * @return 返回truncated结果。
     */
    public ToolResultEnvelope truncated(boolean truncated) {
        this.truncated = truncated;
        return this;
    }

    /**
     * 转换为Map。
     *
     * @return 返回转换后的Map。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("success", Boolean.valueOf("success".equals(status)));
        map.put("summary", summary);
        if (StrUtil.isNotBlank(preview)) {
            map.put("preview", preview);
        }
        if (StrUtil.isNotBlank(resultRef)) {
            map.put("result_ref", resultRef);
        }
        if (StrUtil.isNotBlank(error)) {
            map.put("error", error);
        }
        map.put("size", Long.valueOf(size));
        map.put("truncated", Boolean.valueOf(truncated));
        if (!metadata.isEmpty()) {
            map.put("metadata", metadata);
        }
        map.putAll(data);
        return map;
    }

    /**
     * 转换为JSON。
     *
     * @return 返回转换后的JSON。
     */
    public String toJson() {
        return ONode.serialize(toMap());
    }
}
