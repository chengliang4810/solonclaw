package com.jimuqu.solon.claw.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.snack4.ONode;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;

/** Dashboard 配置接口。 */
@Controller
public class DashboardConfigController {
    private final DashboardConfigService configService;

    public DashboardConfigController(DashboardConfigService configService) {
        this.configService = configService;
    }

    @Mapping(value = "/api/config", method = MethodType.GET)
    public Map<String, Object> config() {
        return DashboardResponse.ok(configService.getConfig());
    }

    @Mapping(value = "/api/config/defaults", method = MethodType.GET)
    public Map<String, Object> defaults() {
        return DashboardResponse.ok(configService.getDefaults());
    }

    @Mapping(value = "/api/config/schema", method = MethodType.GET)
    public Map<String, Object> schema() {
        return DashboardResponse.ok(configService.getSchema());
    }

    @Mapping(value = "/api/config/raw", method = MethodType.GET)
    public Map<String, Object> raw() {
        return DashboardResponse.ok(configService.getRaw());
    }

    @Mapping(value = "/api/config", method = MethodType.PUT)
    public Map<String, Object> save(Context context) throws Exception {
        return safeConfig(
                context,
                new ConfigAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return configService.saveConfig(
                                ONode.deserialize(
                                        ONode.ofJson(context.body()).get("config").toJson(),
                                        LinkedHashMap.class));
                    }
                });
    }

    @Mapping(value = "/api/config/raw", method = MethodType.PUT)
    public Map<String, Object> saveRaw(Context context) throws Exception {
        return safeConfig(
                context,
                new ConfigAction() {
                    @Override
                    public Map<String, Object> run() throws Exception {
                        return configService.saveRaw(
                                ONode.ofJson(context.body()).get("yaml_text").getString());
                    }
                });
    }

    private Map<String, Object> safeConfig(Context context, ConfigAction action) {
        try {
            return DashboardResponse.ok(action.run());
        } catch (IllegalArgumentException e) {
            context.status(400);
            return DashboardResponse.error("CONFIG_BAD_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            context.status(400);
            return DashboardResponse.error("CONFIG_BAD_REQUEST", e.getMessage());
        } catch (Exception e) {
            context.status(400);
            return DashboardResponse.error("CONFIG_BAD_REQUEST", e.getMessage());
        }
    }

    private interface ConfigAction {
        Map<String, Object> run() throws Exception;
    }
}
