package com.jimuqu.solon.claw.web;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** 提供控制台审批Events相关业务能力，封装调用方不需要感知的运行细节。 */
public class DashboardApprovalEventsService {
    /** 注入应用配置，用于控制台审批Events。 */
    private final AppConfig appConfig;

    /** 保存events映射，便于按键快速查询。 */
    private final LinkedList<Map<String, Object>> events = new LinkedList<Map<String, Object>>();

    /** 最大EVENTS的统一常量值。 */
    private static final int MAX_EVENTS = 500;

    /**
     * 创建控制台审批Events服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public DashboardApprovalEventsService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 记录审批事件。
     *
     * @param toolName 工具名称。
     * @param decision 决策参数。
     * @param sourceKey 渠道来源键。
     * @param summary 摘要参数。
     * @param details details 参数。
     */
    public synchronized void recordApprovalEvent(
            String toolName,
            String decision,
            String sourceKey,
            String summary,
            Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("timestamp", Long.valueOf(System.currentTimeMillis()));
        event.put("toolName", toolName);
        event.put("decision", decision);
        event.put("sourceKey", sourceKey);
        event.put("summary", StrUtil.nullToEmpty(summary));
        if (details != null && !details.isEmpty()) {
            event.put("details", details);
        }
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    /**
     * 执行recentEvents相关逻辑。
     *
     * @param limit 最大返回数量。
     * @return 返回recent Events结果。
     */
    public synchronized List<Map<String, Object>> recentEvents(int limit) {
        int size = events.size();
        int from = Math.max(0, size - Math.min(limit, MAX_EVENTS));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (int i = size - 1; i >= from; i--) {
            result.add(events.get(i));
        }
        return result;
    }

    /**
     * 执行stats相关逻辑。
     *
     * @return 返回stats结果。
     */
    public synchronized Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("totalEvents", Integer.valueOf(events.size()));
        int approved = 0;
        int blocked = 0;
        int pending = 0;
        for (Map<String, Object> event : events) {
            String decision = (String) event.get("decision");
            if ("allow".equals(decision) || "approved".equals(decision)) {
                approved++;
            } else if ("block".equals(decision) || "denied".equals(decision)) {
                blocked++;
            } else {
                pending++;
            }
        }
        result.put("approved", Integer.valueOf(approved));
        result.put("blocked", Integer.valueOf(blocked));
        result.put("pending", Integer.valueOf(pending));
        return result;
    }

    /** 执行clear相关逻辑。 */
    public synchronized void clear() {
        events.clear();
    }
}
