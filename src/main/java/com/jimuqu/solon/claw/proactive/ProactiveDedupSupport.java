package com.jimuqu.solon.claw.proactive;

import cn.hutool.core.util.StrUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** 主动协作去重辅助，负责把观测证据转换为稳定的去重键和状态哈希。 */
public final class ProactiveDedupSupport {
    /** 创建主动协作去重辅助实例。 */
    private ProactiveDedupSupport() {}

    /**
     * 生成运行状态候选的稳定去重键。
     *
     * @param runId 运行标识。
     * @param status 运行状态。
     * @return 返回 run 维度去重键。
     */
    public static String runKey(String runId, String status) {
        return "run:" + stablePart(runId) + ":" + stablePart(status);
    }

    /**
     * 生成定时任务候选的稳定去重键。
     *
     * @param jobId 定时任务标识。
     * @param lastStatus 最近执行或投递状态。
     * @param lastRunAt 最近运行时间。
     * @return 返回 cron 维度去重键。
     */
    public static String cronKey(String jobId, String lastStatus, long lastRunAt) {
        return "cron:" + stablePart(jobId) + ":" + stablePart(lastStatus) + ":" + lastRunAt;
    }

    /**
     * 生成仓库更新候选的稳定去重键。
     *
     * @param repositoryRef 仓库引用。
     * @param branch 分支或远程 HEAD 名称。
     * @param stateHash 仓库状态哈希。
     * @return 返回 repo 维度去重键。
     */
    public static String repositoryKey(String repositoryRef, String branch, String stateHash) {
        return "repo:"
                + normalizeRepositoryRef(repositoryRef)
                + ":"
                + stablePart(branch)
                + ":"
                + stablePart(stateHash);
    }

    /**
     * 生成会话续接候选的稳定去重键。
     *
     * @param sessionId 会话标识。
     * @param updatedAt 会话更新时间。
     * @return 返回 session 维度去重键。
     */
    public static String sessionKey(String sessionId, long updatedAt) {
        return "session:" + stablePart(sessionId) + ":" + updatedAt;
    }

    /**
     * 生成记忆跟进候选的稳定去重键。
     *
     * @param evidence 记忆观测证据。
     * @return 返回 memory 维度去重键。
     */
    public static String memoryKey(Map<String, Object> evidence) {
        return "memory:" + evidenceHash(evidence);
    }

    /**
     * 根据证据内容生成稳定哈希。
     *
     * @param evidence 结构化证据。
     * @return 返回 SHA-256 十六进制摘要。
     */
    public static String evidenceHash(Object evidence) {
        return sha256Hex(stableJson(evidence));
    }

    /**
     * 将结构化证据序列化为稳定 JSON，Map 键按字典序排序，列表保持原顺序。
     *
     * @param value 原始结构化值。
     * @return 返回稳定 JSON 文本。
     */
    public static String stableJson(Object value) {
        return ONode.serialize(canonicalValue(value));
    }

    /**
     * 对仓库引用做轻量规范化，避免同一引用因大小写或尾部斜杠产生不同去重键。
     *
     * @param repositoryRef 原始仓库引用。
     * @return 返回规范化仓库引用。
     */
    public static String normalizeRepositoryRef(String repositoryRef) {
        String value = StrUtil.nullToEmpty(repositoryRef).trim().replace('\\', '/');
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 待计算文本。
     * @return 返回十六进制摘要。
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder buffer = new StringBuilder();
            for (byte item : bytes) {
                buffer.append(String.format("%02x", item & 0xff));
            }
            return buffer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("主动协作去重哈希计算失败", e);
        }
    }

    /**
     * 把去重键片段规范化为非空短文本。
     *
     * @param value 原始片段。
     * @return 返回去重键片段。
     */
    private static String stablePart(String value) {
        return StrUtil.blankToDefault(StrUtil.nullToEmpty(value).trim(), "unknown");
    }

    /**
     * 递归规范化任意结构化值，确保 JSON 序列化稳定。
     *
     * @param value 原始值。
     * @return 返回规范化后的值。
     */
    private static Object canonicalValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> source = (Map<?, ?>) value;
            List<String> keys = new ArrayList<String>();
            for (Object key : source.keySet()) {
                keys.add(String.valueOf(key));
            }
            Collections.sort(keys);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (String key : keys) {
                result.put(key, canonicalValue(source.get(key)));
            }
            return result;
        }
        if (value instanceof Iterable<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                result.add(canonicalValue(item));
            }
            return result;
        }
        return String.valueOf(value);
    }
}
