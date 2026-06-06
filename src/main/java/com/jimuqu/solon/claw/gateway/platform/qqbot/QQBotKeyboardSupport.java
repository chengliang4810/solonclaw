package com.jimuqu.solon.claw.gateway.platform.qqbot;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.tool.runtime.DangerousCommandApprovalService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.noear.snack4.ONode;

/** 封装QQ机器人Keyboard辅助逻辑，降低主流程中的重复实现。 */
final class QQBotKeyboardSupport {
    /** 审批数据正则的统一常量值。 */
    private static final Pattern APPROVAL_DATA_PATTERN =
            Pattern.compile("^approve:(.*):(allow-once|allow-session|allow-always|deny)$");

    /** 更新提示词数据正则的统一常量值。 */
    private static final Pattern UPDATE_PROMPT_DATA_PATTERN =
            Pattern.compile("^update_prompt:([yn])$");

    /** 创建QQ机器人Keyboard辅助实例。 */
    private QQBotKeyboardSupport() {}

    /**
     * 构建审批Keyboard。
     *
     * @param approvalId 审批标识。
     * @return 返回创建好的审批Keyboard。
     */
    static ONode buildApprovalKeyboard(String approvalId) {
        return buildApprovalKeyboard(approvalId, true);
    }

    /**
     * 构建审批Keyboard。
     *
     * @param approvalId 审批标识。
     * @param allowAlways allowAlways开关值。
     * @return 返回创建好的审批Keyboard。
     */
    static ONode buildApprovalKeyboard(String approvalId, boolean allowAlways) {
        String safeApprovalId =
                DangerousCommandApprovalService.safeApprovalSelectorToken(approvalId);
        if (safeApprovalId == null) {
            safeApprovalId = "";
        }
        List<Object> buttons = new ArrayList<Object>();
        buttons.add(
                button(
                        "allow",
                        "✅ 允许一次",
                        "已允许",
                        "approve:" + safeApprovalId + ":allow-once",
                        1,
                        "approval"));
        buttons.add(
                button(
                        "session",
                        "✅ 本会话允许",
                        "已允许本会话",
                        "approve:" + safeApprovalId + ":allow-session",
                        1,
                        "approval"));
        if (allowAlways) {
            buttons.add(
                    button(
                            "always",
                            "⭐ 始终允许",
                            "已始终允许",
                            "approve:" + safeApprovalId + ":allow-always",
                            1,
                            "approval"));
        }
        buttons.add(
                button(
                        "deny",
                        "❌ 拒绝",
                        "已拒绝",
                        "approve:" + safeApprovalId + ":deny",
                        0,
                        "approval"));

        List<Object> rows = new ArrayList<Object>();
        rows.add(new ONode().set("buttons", buttons).toData());
        ONode root = new ONode();
        root.getOrNew("content").set("rows", rows);
        return root;
    }

    /**
     * 执行命令FromButton数据相关逻辑。
     *
     * @param buttonData button数据参数。
     * @return 返回命令From Button Data结果。
     */
    static String commandFromButtonData(String buttonData) {
        Matcher matcher = APPROVAL_DATA_PATTERN.matcher(StrUtil.nullToEmpty(buttonData).trim());
        if (!matcher.matches()) {
            return null;
        }
        String approvalId =
                DangerousCommandApprovalService.safeApprovalSelectorToken(matcher.group(1));
        if (approvalId == null) {
            return null;
        }
        String decision = matcher.group(2);
        if ("deny".equals(decision)) {
            return StrUtil.isBlank(approvalId) ? "/deny" : "/deny " + approvalId;
        }
        if ("allow-always".equals(decision)) {
            return "/approve " + approvalId + " always";
        }
        if ("allow-session".equals(decision)) {
            return "/approve " + approvalId + " session";
        }
        return StrUtil.isBlank(approvalId) ? "/approve" : "/approve " + approvalId;
    }

    /**
     * 构建更新提示词Keyboard。
     *
     * @return 返回创建好的更新提示词Keyboard。
     */
    static ONode buildUpdatePromptKeyboard() {
        List<Object> buttons = new ArrayList<Object>();
        buttons.add(button("yes", "✅ 是", "已选择是", "update_prompt:y", 1, "update"));
        buttons.add(button("no", "❌ 否", "已选择否", "update_prompt:n", 0, "update"));

        List<Object> rows = new ArrayList<Object>();
        rows.add(new ONode().set("buttons", buttons).toData());
        ONode root = new ONode();
        root.getOrNew("content").set("rows", rows);
        return root;
    }

    /**
     * 更新提示词Answer From Button Data。
     *
     * @param buttonData button数据参数。
     * @return 返回提示词Answer From Button Data结果。
     */
    static String updatePromptAnswerFromButtonData(String buttonData) {
        Matcher matcher =
                UPDATE_PROMPT_DATA_PATTERN.matcher(StrUtil.nullToEmpty(buttonData).trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * 执行button相关逻辑。
     *
     * @param id 标识。
     * @param label label 参数。
     * @param visitedLabel visitedLabel 参数。
     * @param data 数据参数。
     * @param style style 参数。
     * @param groupId group标识。
     * @return 返回button结果。
     */
    private static Object button(
            String id, String label, String visitedLabel, String data, int style, String groupId) {
        ONode root = new ONode();
        root.set("id", id);
        root.getOrNew("render_data")
                .set("label", label)
                .set("visited_label", visitedLabel)
                .set("style", Integer.valueOf(style));
        ONode action = root.getOrNew("action");
        action.set("type", Integer.valueOf(1));
        action.set("data", data);
        action.getOrNew("permission").set("type", Integer.valueOf(2));
        action.set("click_limit", Integer.valueOf(1));
        root.set("group_id", groupId);
        return root.toData();
    }
}
