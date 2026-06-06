package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.RuntimePathGuard;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 提供控制台媒体相关业务能力，封装调用方不需要感知的运行细节。 */
public class DashboardMediaService {
    /** 记录控制台媒体中的数据库。 */
    private final SqliteDatabase database;

    /** 记录控制台媒体中的路径保护。 */
    private final RuntimePathGuard pathGuard;

    /** 注入附件缓存服务，用于调用对应业务能力。 */
    private final AttachmentCacheService attachmentCacheService;

    /**
     * 创建控制台媒体服务实例，并注入运行所需依赖。
     *
     * @param database database 参数。
     * @param pathGuard 文件或目录路径参数。
     * @param attachmentCacheService 附件缓存服务依赖。
     */
    public DashboardMediaService(
            SqliteDatabase database,
            RuntimePathGuard pathGuard,
            AttachmentCacheService attachmentCacheService) {
        this.database = database;
        this.pathGuard = pathGuard;
        this.attachmentCacheService = attachmentCacheService;
    }

    /**
     * 执行列表相关逻辑。
     *
     * @param platform 平台参数。
     * @param limit 最大返回数量。
     * @return 返回list结果。
     */
    public Map<String, Object> list(String platform, int limit) throws Exception {
        List<Map<String, Object>> media = new ArrayList<Map<String, Object>>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement;
            if (StrUtil.isBlank(platform)) {
                statement =
                        connection.prepareStatement(
                                "select * from channel_media order by updated_at desc limit ?");
                statement.setInt(1, Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200)));
            } else {
                statement =
                        connection.prepareStatement(
                                "select * from channel_media where platform = ? order by updated_at desc limit ?");
                statement.setString(1, platform);
                statement.setInt(2, Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200)));
            }
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    media.add(map(resultSet));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        return Collections.singletonMap("media", media);
    }

    /**
     * 执行索引本地相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @return 返回index本地结果。
     */
    public Map<String, Object> indexLocal(Map<String, Object> body) throws Exception {
        String localPath = read(body, "localPath");
        File file = pathGuard.requireUnderMedia(FileUtil.file(localPath));
        long now = System.currentTimeMillis();
        String mediaId = StrUtil.blankToDefault(read(body, "mediaId"), IdSupport.newId());
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert or replace into channel_media (media_id, platform, chat_id, message_id, kind, original_name, mime_type, local_path, remote_id, status, error, size_bytes, created_at, updated_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce((select created_at from channel_media where media_id = ?), ?), ?, ?)");
            statement.setString(1, mediaId);
            statement.setString(2, StrUtil.blankToDefault(read(body, "platform"), "MEMORY"));
            statement.setString(3, read(body, "chatId"));
            statement.setString(4, read(body, "messageId"));
            statement.setString(5, read(body, "kind"));
            statement.setString(
                    6,
                    SecretRedactor.redact(
                            StrUtil.blankToDefault(read(body, "originalName"), file.getName()),
                            400));
            statement.setString(7, read(body, "mimeType"));
            statement.setString(8, file.getAbsolutePath());
            statement.setString(9, SecretRedactor.redact(read(body, "remoteId"), 400));
            statement.setString(10, "cached");
            statement.setString(11, null);
            statement.setLong(12, file.length());
            statement.setString(13, mediaId);
            statement.setLong(14, now);
            statement.setLong(15, now);
            statement.setLong(16, asLong(body.get("expiresAt")));
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        return Collections.singletonMap("media_id", mediaId);
    }

    /**
     * 执行详情相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回detail结果。
     */
    public Map<String, Object> detail(String mediaId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from channel_media where media_id = ?");
            statement.setString(1, mediaId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? map(resultSet) : new LinkedHashMap<String, Object>();
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 执行刷新相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回刷新结果。
     */
    public Map<String, Object> refresh(String mediaId) throws Exception {
        return updateStatus(mediaId, "refresh_requested", null);
    }

    /**
     * 执行download相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回download结果。
     */
    public Map<String, Object> download(String mediaId) throws Exception {
        Map<String, Object> detail = requireRawDetail(mediaId);
        File file = FileUtil.file(String.valueOf(detail.get("local_path")));
        if (!file.isFile()) {
            return updateStatus(mediaId, "download_missing", "local file not found");
        }
        Map<String, Object> result = updateStatus(mediaId, "download_ready", null);
        result.put("local_path", mediaReference(file));
        result.put("reference", mediaReference(file));
        result.put("size_bytes", file.length());
        return result;
    }

    /**
     * 执行引用相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回reference结果。
     */
    public Map<String, Object> reference(String mediaId) throws Exception {
        Map<String, Object> detail = requireRawDetail(mediaId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("media_id", mediaId);
        result.put(
                "reference",
                mediaReference(FileUtil.file(String.valueOf(detail.get("local_path")))));
        result.put("status", detail.get("status"));
        result.put("kind", detail.get("kind"));
        result.put("local_path", result.get("reference"));
        return result;
    }

    /**
     * 更新状态。
     *
     * @param mediaId 媒体标识。
     * @param status 状态参数。
     * @param error 错误参数。
     * @return 返回状态。
     */
    private Map<String, Object> updateStatus(String mediaId, String status, String error)
            throws Exception {
        long now = System.currentTimeMillis();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "update channel_media set status = ?, error = ?, updated_at = ? where media_id = ?");
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setLong(3, now);
            statement.setString(4, mediaId);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("media_id", mediaId);
        result.put("status", status);
        result.put("error", SecretRedactor.redact(error, 1000));
        return result;
    }

    /**
     * 执行map相关逻辑。
     *
     * @param resultSet 结果Set响应或执行结果。
     * @return 返回map结果。
     */
    private Map<String, Object> map(ResultSet resultSet) throws Exception {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("media_id", resultSet.getString("media_id"));
        map.put("platform", resultSet.getString("platform"));
        map.put("chat_id", resultSet.getString("chat_id"));
        map.put("message_id", resultSet.getString("message_id"));
        map.put("kind", resultSet.getString("kind"));
        map.put("original_name", resultSet.getString("original_name"));
        map.put("mime_type", resultSet.getString("mime_type"));
        map.put("local_path", mediaReference(FileUtil.file(resultSet.getString("local_path"))));
        map.put("reference", map.get("local_path"));
        map.put("remote_id", resultSet.getString("remote_id"));
        map.put("status", resultSet.getString("status"));
        map.put("error", SecretRedactor.redact(resultSet.getString("error"), 1000));
        map.put("size_bytes", resultSet.getLong("size_bytes"));
        map.put("created_at", resultSet.getLong("created_at"));
        map.put("updated_at", resultSet.getLong("updated_at"));
        map.put("expires_at", resultSet.getLong("expires_at"));
        return map;
    }

    /**
     * 执行原始详情相关逻辑。
     *
     * @param mediaId 媒体标识。
     * @return 返回原始Detail结果。
     */
    private Map<String, Object> rawDetail(String mediaId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from channel_media where media_id = ?");
            statement.setString(1, mediaId);
            ResultSet resultSet = statement.executeQuery();
            try {
                if (!resultSet.next()) {
                    return new LinkedHashMap<String, Object>();
                }
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("media_id", resultSet.getString("media_id"));
                map.put("kind", resultSet.getString("kind"));
                map.put("local_path", resultSet.getString("local_path"));
                map.put("status", resultSet.getString("status"));
                return map;
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 要求原始Detail。
     *
     * @param mediaId 媒体标识。
     * @return 返回原始Detail结果。
     */
    private Map<String, Object> requireRawDetail(String mediaId) throws Exception {
        Map<String, Object> detail = rawDetail(mediaId);
        if (detail.isEmpty()) {
            throw new IllegalArgumentException("Media not found: " + mediaId);
        }
        return detail;
    }

    /**
     * 执行媒体引用相关逻辑。
     *
     * @param file 文件或目录路径参数。
     * @return 返回媒体Reference结果。
     */
    private String mediaReference(File file) {
        try {
            return attachmentCacheService.mediaReference(file);
        } catch (Exception e) {
            String name = file == null ? "" : file.getName();
            if (StrUtil.isBlank(name)) {
                name = "unknown";
            }
            return "media://unavailable/" + SecretRedactor.redact(name, 200);
        }
    }

    /**
     * 执行read相关逻辑。
     *
     * @param body 请求体或消息正文内容。
     * @param key 配置键或映射键。
     * @return 返回read结果。
     */
    private String read(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 执行as长整型相关逻辑。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回as Long结果。
     */
    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }
}
