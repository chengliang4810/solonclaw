package com.jimuqu.solon.claw.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Dashboard 日志读取服务。 */
public class DashboardLogsService {
    /** 注入应用配置，用于控制台Logs。 */
    private final AppConfig appConfig;

    /**
     * 创建控制台Logs服务实例，并注入运行所需依赖。
     *
     * @param appConfig 应用运行配置。
     */
    public DashboardLogsService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 执行read相关逻辑。
     *
     * @param fileName 文件或目录路径参数。
     * @param lineCount 行Count参数。
     * @param level level 参数。
     * @param component component 参数。
     * @return 返回read结果。
     */
    public List<String> read(String fileName, int lineCount, String level, String component) {
        File file = resolveFile(fileName);
        if (!file.exists()) {
            return Collections.emptyList();
        }

        List<String> all = FileUtil.readUtf8Lines(file);
        List<String> filtered = new ArrayList<String>();
        for (String line : all) {
            if (!matchesLevel(line, level)) {
                continue;
            }
            if (!matchesComponent(line, component)) {
                continue;
            }
            filtered.add(SecretRedactor.redact(line, 2000));
        }

        int safeLineCount = lineCount <= 0 ? 100 : Math.min(lineCount, 500);
        int start = Math.max(0, filtered.size() - safeLineCount);
        return new ArrayList<String>(filtered.subList(start, filtered.size()));
    }

    /**
     * 解析文件。
     *
     * @param fileName 文件或目录路径参数。
     * @return 返回解析后的文件。
     */
    private File resolveFile(String fileName) {
        String resolved = StrUtil.blankToDefault(fileName, "agent").toLowerCase(Locale.ROOT);
        if (!"gateway".equals(resolved) && !"errors".equals(resolved)) {
            resolved = "agent";
        }
        return FileUtil.file(appConfig.getRuntime().getLogsDir(), resolved + ".log");
    }

    /**
     * 判断是否匹配级别。
     *
     * @param line 行参数。
     * @param level level 参数。
     * @return 返回matches级别结果。
     */
    private boolean matchesLevel(String line, String level) {
        String normalized = StrUtil.blankToDefault(level, "ALL").toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return true;
        }
        return line.toUpperCase(Locale.ROOT).contains(" " + normalized + " ");
    }

    /**
     * 判断是否匹配Component。
     *
     * @param line 行参数。
     * @param component component 参数。
     * @return 返回matches Component结果。
     */
    private boolean matchesComponent(String line, String component) {
        String normalized = StrUtil.blankToDefault(component, "all").toLowerCase(Locale.ROOT);
        if ("all".equals(normalized)) {
            return true;
        }
        if ("gateway".equals(normalized)) {
            return line.contains(".gateway.");
        }
        if ("tools".equals(normalized)) {
            return line.contains(".tool.");
        }
        if ("cron".equals(normalized)) {
            return line.contains(".scheduler.");
        }
        if ("proactive".equals(normalized)) {
            return line.contains(".proactive.");
        }
        if ("agent".equals(normalized)) {
            return line.contains("com.jimuqu.solon.claw");
        }
        return line.toLowerCase(Locale.ROOT).contains(normalized);
    }
}
