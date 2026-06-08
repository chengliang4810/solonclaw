package com.jimuqu.solon.claw.gateway.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 承载QQ机器人审批键盘相关状态和辅助逻辑。 */
public class QQBotApprovalKeyboard {
    /**
     * 构建审批Keyboard。
     *
     * @param approvalId 审批标识。
     * @param toolName 工具名称。
     * @return 返回创建好的审批Keyboard。
     */
    public static Map<String, Object> buildApprovalKeyboard(String approvalId, String toolName) {
        Map<String, Object> keyboard = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        List<Map<String, Object>> buttons = new ArrayList<Map<String, Object>>();
        buttons.add(button("approve_" + approvalId, "✓ 批准 " + toolName, 2));
        buttons.add(button("deny_" + approvalId, "✗ 拒绝", 2));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("buttons", buttons);
        rows.add(row);

        List<Map<String, Object>> secondRow = new ArrayList<Map<String, Object>>();
        secondRow.add(button("approve_all_" + approvalId, "批准全部", 2));
        secondRow.add(button("deny_all_" + approvalId, "拒绝全部", 2));
        Map<String, Object> row2 = new LinkedHashMap<String, Object>();
        row2.put("buttons", secondRow);
        rows.add(row2);

        keyboard.put("rows", rows);
        return keyboard;
    }

    /**
     * 构建Confirm Keyboard。
     *
     * @param actionId action标识。
     * @param label label 参数。
     * @return 返回创建好的Confirm Keyboard。
     */
    public static Map<String, Object> buildConfirmKeyboard(String actionId, String label) {
        Map<String, Object> keyboard = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

        List<Map<String, Object>> buttons = new ArrayList<Map<String, Object>>();
        buttons.add(button("confirm_" + actionId, "确认 " + label, 2));
        buttons.add(button("cancel_" + actionId, "取消", 2));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("buttons", buttons);
        rows.add(row);

        keyboard.put("rows", rows);
        return keyboard;
    }

    /**
     * 判断是否审批Callback。
     *
     * @param data 数据参数。
     * @return 如果审批Callback满足条件则返回 true，否则返回 false。
     */
    public static boolean isApprovalCallback(String data) {
        return data != null && (data.startsWith("approve_") || data.startsWith("deny_"));
    }

    /**
     * 提取审批标识。
     *
     * @param data 数据参数。
     * @return 返回审批标识。
     */
    public static String extractApprovalId(String data) {
        if (data == null) {
            return null;
        }
        if (data.startsWith("approve_all_")) {
            return data.substring("approve_all_".length());
        }
        if (data.startsWith("deny_all_")) {
            return data.substring("deny_all_".length());
        }
        if (data.startsWith("approve_")) {
            return data.substring("approve_".length());
        }
        if (data.startsWith("deny_")) {
            return data.substring("deny_".length());
        }
        return null;
    }

    /**
     * 判断是否Approved。
     *
     * @param data 数据参数。
     * @return 如果Approved满足条件则返回 true，否则返回 false。
     */
    public static boolean isApproved(String data) {
        return data != null && data.startsWith("approve");
    }

    /**
     * 执行button相关逻辑。
     *
     * @param id 标识。
     * @param label label 参数。
     * @param style style 参数。
     * @return 返回button结果。
     */
    private static Map<String, Object> button(String id, String label, int style) {
        Map<String, Object> btn = new LinkedHashMap<String, Object>();
        btn.put("id", id);
        btn.put("render_data", renderData(label, style));
        btn.put("action", actionData(id));
        return btn;
    }

    /**
     * 渲染数据。
     *
     * @param label label 参数。
     * @param style style 参数。
     * @return 返回render Data结果。
     */
    private static Map<String, Object> renderData(String label, int style) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("label", label);
        data.put("style", Integer.valueOf(style));
        return data;
    }

    /**
     * 执行action数据相关逻辑。
     *
     * @param id 标识。
     * @return 返回action Data结果。
     */
    private static Map<String, Object> actionData(String id) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("type", Integer.valueOf(2));
        data.put("data", id);
        return data;
    }
}
