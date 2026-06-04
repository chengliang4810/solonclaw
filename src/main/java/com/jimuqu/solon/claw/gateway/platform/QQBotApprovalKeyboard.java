package com.jimuqu.solon.claw.gateway.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQBot approval keyboard builder. Generates inline keyboard markup for tool approval/denial in
 * QQBot messages.
 */
public class QQBotApprovalKeyboard {
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

    public static boolean isApprovalCallback(String data) {
        return data != null && (data.startsWith("approve_") || data.startsWith("deny_"));
    }

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

    public static boolean isApproved(String data) {
        return data != null && data.startsWith("approve");
    }

    private static Map<String, Object> button(String id, String label, int style) {
        Map<String, Object> btn = new LinkedHashMap<String, Object>();
        btn.put("id", id);
        btn.put("render_data", renderData(label, style));
        btn.put("action", actionData(id));
        return btn;
    }

    private static Map<String, Object> renderData(String label, int style) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("label", label);
        data.put("style", Integer.valueOf(style));
        return data;
    }

    private static Map<String, Object> actionData(String id) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("type", Integer.valueOf(2));
        data.put("data", id);
        return data;
    }
}
