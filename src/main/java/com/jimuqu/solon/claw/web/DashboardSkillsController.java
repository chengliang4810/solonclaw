package com.jimuqu.solon.claw.web;

import java.util.List;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 技能接口。 */
@Controller
public class DashboardSkillsController {
    private final DashboardSkillsService skillsService;

    public DashboardSkillsController(DashboardSkillsService skillsService) {
        this.skillsService = skillsService;
    }

    @Mapping(value = "/api/skills", method = MethodType.GET)
    public List<Map<String, Object>> skills() throws Exception {
        return skillsService.getSkills();
    }

    @Mapping(value = "/api/skills/view", method = MethodType.GET)
    public Map<String, Object> view(Context context) throws Exception {
        return skillsService.viewSkill(context.param("name"), context.param("filePath"));
    }

    @Mapping(value = "/api/skills/files", method = MethodType.GET)
    public List<Map<String, Object>> files(Context context) throws Exception {
        return skillsService.getSkillFiles(context.param("name"));
    }

    @Mapping(value = "/api/skills/toggle", method = MethodType.PUT)
    public Map<String, Object> toggle(Context context) {
        try {
            ONode body = body(context);
            return skillsService.toggleSkill(
                    body.get("name").getString(), body.get("enabled").getBoolean());
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("SKILLS_BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            context.status(500);
            return DashboardResponse.error("SKILLS_FAILED", e.getMessage());
        }
    }

    @Mapping(value = "/api/tools/toolsets", method = MethodType.GET)
    public List<Map<String, Object>> toolsets() {
        return skillsService.getToolsets();
    }

    private ONode body(Context context) {
        String raw;
        try {
            raw = context.body();
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体读取失败 / Request body read failed");
        }
        if (raw == null || raw.trim().length() == 0) {
            return new ONode();
        }
        try {
            ONode node = ONode.ofJson(raw);
            if (node.toData() instanceof Map) {
                return node;
            }
            throw new IllegalArgumentException("请求体必须是 JSON 对象 / Request body must be a JSON object");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体 JSON 解析失败 / Request body JSON parse failed");
        }
    }
}
