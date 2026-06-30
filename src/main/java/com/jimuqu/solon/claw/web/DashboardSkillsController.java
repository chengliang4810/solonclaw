package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
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
    /** 注入技能服务，用于调用对应业务能力。 */
    private final DashboardSkillsService skillsService;

    /**
     * 创建控制台技能控制器实例，并注入运行所需依赖。
     *
     * @param skillsService 技能服务依赖。
     */
    public DashboardSkillsController(DashboardSkillsService skillsService) {
        this.skillsService = skillsService;
    }

    /**
     * 执行技能相关逻辑。
     *
     * @return 返回技能结果。
     */
    @Mapping(value = "/api/skills", method = MethodType.GET)
    public List<Map<String, Object>> skills() throws Exception {
        return skillsService.getSkills();
    }

    /**
     * 执行视图相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回视图。
     */
    @Mapping(value = "/api/skills/view", method = MethodType.GET)
    public Map<String, Object> view(Context context) throws Exception {
        return skillsService.viewSkill(context.param("name"), context.param("filePath"));
    }

    /**
     * 执行files相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回files结果。
     */
    @Mapping(value = "/api/skills/files", method = MethodType.GET)
    public List<Map<String, Object>> files(Context context) throws Exception {
        return skillsService.getSkillFiles(context.param("name"));
    }

    /**
     * 执行toggle相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回toggle结果。
     */
    @Mapping(value = "/api/skills/toggle", method = MethodType.PUT)
    public Map<String, Object> toggle(Context context) {
        try {
            ONode body = DashboardRequestBodies.jsonObject(context);
            return skillsService.toggleSkill(
                    body.get("name").getString(), body.get("enabled").getBoolean());
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "SKILLS_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 500, "SKILLS_FAILED", e);
        }
    }

    /**
     * 执行toolsets相关逻辑。
     *
     * @return 返回toolsets结果。
     */
    @Mapping(value = "/api/tools/toolsets", method = MethodType.GET)
    public List<Map<String, Object>> toolsets() {
        return skillsService.getToolsets();
    }
}
