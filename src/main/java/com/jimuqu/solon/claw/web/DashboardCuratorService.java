package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.context.SkillCuratorService;
import com.jimuqu.solon.claw.storage.repository.SqliteDatabase;
import com.jimuqu.solon.claw.support.IdSupport;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;

/** Curator dashboard report facade. */
public class DashboardCuratorService {
    private final SkillCuratorService skillCuratorService;
    private final SqliteDatabase database;

    public DashboardCuratorService(
            SkillCuratorService skillCuratorService, SqliteDatabase database) {
        this.skillCuratorService = skillCuratorService;
        this.database = database;
    }

    public Map<String, Object> run(boolean force) throws Exception {
        Map<String, Object> report = skillCuratorService.runOnce(force);
        saveReport(report);
        return sanitizeReport(report);
    }

    public Map<String, Object> list(int limit) throws Exception {
        List<Map<String, Object>> reports = new ArrayList<Map<String, Object>>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from curator_reports order by started_at desc limit ?");
            statement.setInt(1, Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100)));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    reports.add(map(resultSet, false));
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reports", reports);
        return result;
    }

    public Map<String, Object> detail(String reportId) throws Exception {
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement("select * from curator_reports where report_id = ?");
            statement.setString(1, reportId);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? map(resultSet, true) : new LinkedHashMap<String, Object>();
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    public Map<String, Object> apply(String skillName, String suggestion) {
        return skillCuratorService.applySuggestion(skillName, suggestion);
    }

    public Map<String, Object> ignore(String skillName, String suggestion) {
        return skillCuratorService.ignoreSuggestion(skillName, suggestion);
    }

    public Map<String, Object> improvements(int limit) throws Exception {
        List<Map<String, Object>> improvements = new ArrayList<Map<String, Object>>();
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "select * from skill_improvements order by created_at desc limit ?");
            statement.setInt(1, Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100)));
            ResultSet resultSet = statement.executeQuery();
            try {
                while (resultSet.next()) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("improvement_id", resultSet.getString("improvement_id"));
                    item.put("session_id", safe(resultSet.getString("session_id"), 200));
                    item.put("run_id", safe(resultSet.getString("run_id"), 200));
                    item.put("skill_name", safe(resultSet.getString("skill_name"), 400));
                    item.put("action", safe(resultSet.getString("action"), 200));
                    item.put("summary", safe(resultSet.getString("summary"), 2000));
                    item.put(
                            "changed_files",
                            sanitizeObject(parseJson(resultSet.getString("changed_files_json"))));
                    item.put(
                            "evidence",
                            sanitizeObject(parseJson(resultSet.getString("evidence_json"))));
                    item.put("needs_review", resultSet.getInt("needs_review") != 0);
                    item.put("created_at", resultSet.getLong("created_at"));
                    improvements.add(item);
                }
            } finally {
                resultSet.close();
                statement.close();
            }
        } finally {
            connection.close();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("improvements", improvements);
        return result;
    }

    private void saveReport(Map<String, Object> report) throws Exception {
        long startedAt = asLong(report.get("startedAt"));
        long finishedAt = asLong(report.get("finishedAt"));
        String status = StrUtil.blankToDefault(String.valueOf(report.get("status")), "unknown");
        Object items = report.get("items");
        int itemCount = items instanceof List ? ((List<?>) items).size() : 0;
        String summary = "items=" + itemCount + ", status=" + status;
        Connection connection = database.openConnection();
        try {
            PreparedStatement statement =
                    connection.prepareStatement(
                            "insert into curator_reports (report_id, status, summary, report_path, report_json, started_at, finished_at) values (?, ?, ?, ?, ?, ?, ?)");
            statement.setString(1, IdSupport.newId());
            statement.setString(2, status);
            statement.setString(3, summary);
            statement.setString(4, String.valueOf(report.get("stateFile")));
            statement.setString(5, ONode.serialize(report));
            statement.setLong(6, startedAt);
            statement.setLong(7, finishedAt);
            statement.executeUpdate();
            statement.close();
        } finally {
            connection.close();
        }
    }

    private Map<String, Object> map(ResultSet resultSet, boolean includeJson) throws Exception {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("report_id", resultSet.getString("report_id"));
        map.put("status", safe(resultSet.getString("status"), 200));
        map.put("summary", safe(resultSet.getString("summary"), 2000));
        map.put("report_path", curatorReference(resultSet.getString("report_path")));
        map.put("started_at", resultSet.getLong("started_at"));
        map.put("finished_at", resultSet.getLong("finished_at"));
        if (includeJson) {
            Object parsed = ONode.deserialize(resultSet.getString("report_json"), Object.class);
            map.put(
                    "report",
                    parsed instanceof Map
                            ? sanitizeReport((Map<String, Object>) parsed)
                            : sanitizeObject(parsed));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeReport(Map<String, Object> report) {
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
        if (report == null) {
            return sanitized;
        }
        for (Map.Entry<String, Object> entry : report.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeObject(entry.getValue()));
        }
        if (sanitized.containsKey("stateFile")) {
            sanitized.put("stateFile", curatorReference(String.valueOf(sanitized.get("stateFile"))));
        }
        Object items = sanitized.get("items");
        if (items instanceof List) {
            List<Object> sanitizedItems = new ArrayList<Object>();
            for (Object item : (List<?>) items) {
                if (item instanceof Map) {
                    Map<String, Object> copy = new LinkedHashMap<String, Object>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                        copy.put(String.valueOf(entry.getKey()), sanitizeObject(entry.getValue()));
                    }
                    if (copy.containsKey("path")) {
                        copy.put("path", skillReference(String.valueOf(copy.get("name"))));
                    }
                    sanitizedItems.add(copy);
                } else {
                    sanitizedItems.add(sanitizeObject(item));
                }
            }
            sanitized.put("items", sanitizedItems);
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeObject(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), sanitizeObject(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(sanitizeObject(item));
            }
            return result;
        }
        if (value instanceof String) {
            return safe((String) value, 4000);
        }
        return value;
    }

    private String curatorReference(String value) {
        return "curator://report";
    }

    private String skillReference(String name) {
        return "skill://" + safe(StrUtil.blankToDefault(name, "unknown"), 400);
    }

    private String safe(String value, int maxLength) {
        return SecretRedactor.redact(value, maxLength);
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private Object parseJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return ONode.deserialize(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
