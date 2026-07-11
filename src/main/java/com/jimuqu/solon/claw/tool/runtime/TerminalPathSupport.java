package com.jimuqu.solon.claw.tool.runtime;

import cn.hutool.core.util.StrUtil;
import java.io.File;
import java.util.Locale;

/** 封装终端路径辅助逻辑，降低主流程中的重复实现。 */
final class TerminalPathSupport {
    /** 创建终端路径辅助实例。 */
    private TerminalPathSupport() {}

    /**
     * 转换为进程Cwd。
     *
     * @param cwd 工作目录参数。
     * @return 返回转换后的进程Cwd。
     */
    static String toProcessCwd(String cwd) {
        return toProcessCwd(cwd, isWindows());
    }

    /**
     * 转换为进程Cwd。
     *
     * @param cwd 工作目录参数。
     * @param windows Windows参数。
     * @return 返回转换后的进程Cwd。
     */
    static String toProcessCwd(String cwd, boolean windows) {
        String value = StrUtil.nullToEmpty(cwd);
        if (!windows || value.length() < 2) {
            return value;
        }
        if (value.charAt(0) != '/' || !isAsciiLetter(value.charAt(1))) {
            return value;
        }
        if (value.length() > 2 && value.charAt(2) != '/') {
            return value;
        }
        StringBuilder converted = new StringBuilder();
        converted.append(Character.toUpperCase(value.charAt(1))).append(':');
        if (value.length() == 2) {
            converted.append('\\');
            return converted.toString();
        }
        for (int i = 2; i < value.length(); i++) {
            char ch = value.charAt(i);
            converted.append(ch == '/' ? '\\' : ch);
        }
        return converted.toString();
    }

    /**
     * 解析Safe Cwd。
     *
     * @param cwd 工作目录参数。
     * @param fallback 兜底参数。
     * @return 返回解析后的Safe Cwd。
     */
    static File resolveSafeCwd(String cwd, File fallback) {
        return resolveSafeCwd(cwd, fallback, isWindows());
    }

    /**
     * 解析Safe Cwd。
     *
     * @param cwd 工作目录参数。
     * @param fallback 兜底参数。
     * @param windows Windows参数。
     * @return 返回解析后的Safe Cwd。
     */
    static File resolveSafeCwd(String cwd, File fallback, boolean windows) {
        File fallbackDir = fallback == null ? new File(".") : fallback;
        if (StrUtil.isBlank(cwd)) {
            return fallbackDir.getAbsoluteFile();
        }
        // 非 Windows 环境无法访问 UNC 路径，直接回退到默认工作目录。
        if (!windows && cwd.startsWith("\\\\")) {
            return fallbackDir.getAbsoluteFile();
        }
        File candidate = new File(toProcessCwd(cwd, windows)).getAbsoluteFile();
        if (candidate.isDirectory()) {
            return candidate;
        }
        File parent = candidate.getParentFile();
        while (parent != null) {
            if (parent.isDirectory()) {
                return parent.getAbsoluteFile();
            }
            File next = parent.getParentFile();
            if (next == null || next.equals(parent)) {
                break;
            }
            parent = next;
        }
        return fallbackDir.getAbsoluteFile();
    }

    /**
     * 校验工具工作目录文本，阻断带 shell 元字符的伪路径。
     *
     * @param workDir 命令执行工作目录。
     * @return 原始工作目录文本。
     */
    static String checkedWorkDir(String workDir) {
        SecurityPolicyService.FileVerdict verdict = SecurityPolicyService.checkWorkdirText(workDir);
        if (!verdict.isAllowed()) {
            throw new IllegalArgumentException(
                    "Invalid workdir: "
                            + verdict.getMessage()
                            + ". Use a simple filesystem path without shell metacharacters.");
        }
        return workDir;
    }

    /**
     * 判断是否Windows。
     *
     * @return 如果Windows满足条件则返回 true，否则返回 false。
     */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 判断是否Ascii Letter。
     *
     * @param ch ch 参数。
     * @return 如果Ascii Letter满足条件则返回 true，否则返回 false。
     */
    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }
}
