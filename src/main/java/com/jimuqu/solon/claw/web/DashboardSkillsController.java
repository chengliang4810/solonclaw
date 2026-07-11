package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.support.DashboardRequestBodies;
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
    public Object skills(Context context) throws Exception {
        try {
            return skillsService.getSkills(context.param("profile"));
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "SKILLS_PROFILE_NOT_FOUND", e);
        }
    }

    /** 搜索 Skills Hub，供 Profile Builder 选择待安装技能。 */
    @Mapping(value = "/api/skills/hub/search", method = MethodType.GET)
    public Map<String, Object> searchHub(Context context) {
        try {
            int limit = parseLimit(context.param("limit"));
            return skillsService.searchHub(
                    context.param("q"), context.param("source"), limit, context.param("profile"));
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "SKILLS_PROFILE_NOT_FOUND", e);
        } catch (IllegalArgumentException e) {
            return DashboardResponse.error(context, 400, "SKILLS_HUB_BAD_REQUEST", e);
        } catch (Exception e) {
            return DashboardResponse.error(context, 502, "SKILLS_HUB_SEARCH_FAILED", e);
        }
    }

    /**
     * 执行视图相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回视图。
     */
    @Mapping(value = "/api/skills/view", method = MethodType.GET)
    public Map<String, Object> view(Context context) throws Exception {
        try {
            return skillsService.viewSkill(
                    context.param("profile"), context.param("name"), context.param("filePath"));
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "SKILLS_PROFILE_NOT_FOUND", e);
        }
    }

    /**
     * 执行files相关逻辑。
     *
     * @param context 当前请求或运行上下文。
     * @return 返回files结果。
     */
    @Mapping(value = "/api/skills/files", method = MethodType.GET)
    public Object files(Context context) throws Exception {
        try {
            return skillsService.getSkillFiles(context.param("profile"), context.param("name"));
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "SKILLS_PROFILE_NOT_FOUND", e);
        }
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
                    profile(context, body),
                    body.get("name").getString(),
                    body.get("enabled").getBoolean());
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "SKILLS_PROFILE_NOT_FOUND", e);
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
    public Object toolsets(Context context) {
        try {
            return skillsService.getToolsets(context.param("profile"));
        } catch (DashboardProfileScope.ProfileNotFoundException e) {
            return DashboardResponse.error(context, 404, "SKILLS_PROFILE_NOT_FOUND", e);
        }
    }

    /** 写请求中请求体 profile 优先，未提供时使用查询参数。 */
    private String profile(Context context, ONode body) {
        String bodyProfile = body == null ? null : body.get("profile").getString();
        return bodyProfile == null || bodyProfile.trim().length() == 0
                ? context.param("profile")
                : bodyProfile.trim();
    }

    /** 解析搜索条数，缺失时使用 20，最终边界由服务统一收敛。 */
    private int parseLimit(String value) {
        if (value == null || value.trim().length() == 0) {
            return 20;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Skills Hub limit must be an integer.", e);
        }
    }
}
