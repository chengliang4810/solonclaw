package com.jimuqu.solon.claw.web;

import com.jimuqu.solon.claw.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dashboard platform toolsets API service. */
public class DashboardPlatformToolsetsService {
    private final AppConfig appConfig;

    public DashboardPlatformToolsetsService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, AppConfig.PlatformConfig> platforms = appConfig.getGateway().getPlatforms();
        if (platforms != null) {
            Map<String, Object> platformSummary = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, AppConfig.PlatformConfig> entry : platforms.entrySet()) {
                Map<String, Object> config = new LinkedHashMap<String, Object>();
                config.put("enabledToolsets", entry.getValue().getEnabledToolsets());
                config.put("disabledToolsets", entry.getValue().getDisabledToolsets());
                config.put("approvalRequired", Boolean.valueOf(entry.getValue().isApprovalRequired()));
                platformSummary.put(entry.getKey(), config);
            }
            result.put("platforms", platformSummary);
        }
        return result;
    }
}
