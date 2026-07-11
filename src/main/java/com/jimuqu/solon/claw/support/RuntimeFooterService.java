package com.jimuqu.solon.claw.support;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.AgentRunOutcome;
import java.io.File;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 最终回复运行态 footer 渲染。 */
@RequiredArgsConstructor
public class RuntimeFooterService {
    /** 记录 footer 路径压缩失败的低敏诊断日志，不输出完整工作目录。 */
    private static final Logger log = LoggerFactory.getLogger(RuntimeFooterService.class);

    /** SEPARATOR的统一常量值。 */
    private static final String SEPARATOR = " · ";

    /** 注入应用配置，用于运行时Footer。 */
    private final AppConfig appConfig;

    /**
     * 追加Footer。
     *
     * @param reply 回复参数。
     * @param platform 平台参数。
     * @param outcome outcome 参数。
     * @return 返回Footer结果。
     */
    public String appendFooter(String reply, PlatformType platform, AgentRunOutcome outcome) {
        String footer = buildFooter(platform, outcome);
        if (StrUtil.isBlank(footer)) {
            return StrUtil.nullToEmpty(reply);
        }
        String base = StrUtil.nullToEmpty(reply).trim();
        if (base.length() == 0) {
            return footer;
        }
        return base + "\n\n" + footer;
    }

    /**
     * 构建Footer。
     *
     * @param platform 平台参数。
     * @param outcome outcome 参数。
     * @return 返回创建好的Footer。
     */
    public String buildFooter(PlatformType platform, AgentRunOutcome outcome) {
        if (outcome == null || !isEnabled(platform)) {
            return "";
        }
        List<String> fields = appConfig.getDisplay().getRuntimeFooter().getFields();
        StringBuilder buffer = new StringBuilder();
        for (String field : fields) {
            String value = resolveField(field, outcome);
            if (StrUtil.isBlank(value)) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(SEPARATOR);
            }
            buffer.append(value);
        }
        if (buffer.length() == 0) {
            return "";
        }
        return "—— " + buffer.toString();
    }

    /**
     * 判断是否启用。
     *
     * @param platform 平台参数。
     * @return 如果启用满足条件则返回 true，否则返回 false。
     */
    private boolean isEnabled(PlatformType platform) {
        Boolean platformOverride = platformOverride(platform);
        if (platformOverride != null) {
            return platformOverride.booleanValue();
        }
        return appConfig.getDisplay().getRuntimeFooter().isEnabled();
    }

    /**
     * 执行平台Override相关逻辑。
     *
     * @param platform 平台参数。
     * @return 返回平台Override结果。
     */
    private Boolean platformOverride(PlatformType platform) {
        if (platform == null) {
            return null;
        }
        if (platform == PlatformType.FEISHU) {
            return appConfig.getChannels().getFeishu().getRuntimeFooterEnabled();
        }
        if (platform == PlatformType.DINGTALK) {
            return appConfig.getChannels().getDingtalk().getRuntimeFooterEnabled();
        }
        if (platform == PlatformType.WECOM) {
            return appConfig.getChannels().getWecom().getRuntimeFooterEnabled();
        }
        if (platform == PlatformType.WEIXIN) {
            return appConfig.getChannels().getWeixin().getRuntimeFooterEnabled();
        }
        if (platform == PlatformType.QQBOT) {
            return appConfig.getChannels().getQqbot().getRuntimeFooterEnabled();
        }
        if (platform == PlatformType.YUANBAO) {
            return appConfig.getChannels().getYuanbao().getRuntimeFooterEnabled();
        }
        return null;
    }

    /**
     * 解析Field。
     *
     * @param rawField 原始Field参数。
     * @param outcome outcome 参数。
     * @return 返回解析后的Field。
     */
    private String resolveField(String rawField, AgentRunOutcome outcome) {
        String field = StrUtil.nullToEmpty(rawField).trim();
        if ("model".equals(field)) {
            return shortModel(outcome.getModel());
        }
        if ("provider".equals(field)) {
            return StrUtil.nullToEmpty(outcome.getProvider()).trim();
        }
        if ("context_pct".equals(field)) {
            if (outcome.getContextWindowTokens() <= 0 || outcome.getContextEstimateTokens() < 0) {
                return "";
            }
            int percent =
                    Math.max(
                            0,
                            Math.min(
                                    100,
                                    (int)
                                            Math.round(
                                                    (outcome.getContextEstimateTokens() * 100.0D)
                                                            / outcome.getContextWindowTokens())));
            return percent + "%";
        }
        if ("cwd".equals(field)) {
            return compactCwd(outcome.getCwd());
        }
        if ("tokens".equals(field)
                && outcome.getResult() != null
                && outcome.getResult().getTotalTokens() > 0) {
            return outcome.getResult().getTotalTokens() + " tokens";
        }
        return "";
    }

    /**
     * 执行短模型相关逻辑。
     *
     * @param model 模型名称。
     * @return 返回short模型结果。
     */
    private String shortModel(String model) {
        String value = StrUtil.nullToEmpty(model).trim();
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }

    /**
     * 执行紧凑工作目录相关逻辑。
     *
     * @param cwd 工作目录参数。
     * @return 返回compact Cwd结果。
     */
    private String compactCwd(String cwd) {
        String value = StrUtil.nullToEmpty(cwd).trim();
        if (value.length() == 0) {
            return "";
        }
        try {
            String home = System.getProperty("user.home");
            String absolute = new File(value).getAbsolutePath();
            if (StrUtil.isNotBlank(home) && absolute.startsWith(home)) {
                return "~" + absolute.substring(home.length());
            }
            return absolute;
        } catch (Exception e) {
            log.debug(
                    "压缩工作区路径展示失败，使用原始脱敏前输入长度 error={} length={}",
                    e.getClass().getSimpleName(),
                    Integer.valueOf(value.length()));
            return value;
        }
    }
}
