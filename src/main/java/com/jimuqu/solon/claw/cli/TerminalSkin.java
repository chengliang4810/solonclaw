package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.util.Locale;

/** 承载终端Skin相关状态和辅助逻辑。 */
public final class TerminalSkin {
    /** RESET的统一常量值。 */
    private static final String RESET = "\u001B[0m";

    /** 记录终端Skin中的名称。 */
    private final String name;

    /** 记录终端Skin中的bold。 */
    private final String bold;

    /** 记录终端Skin中的dim。 */
    private final String dim;

    /** 记录终端Skin中的accent。 */
    private final String accent;

    /** 记录终端Skin中的border。 */
    private final String border;

    /**
     * 创建终端Skin实例，并注入运行所需依赖。
     *
     * @param name 名称参数。
     * @param bold bold 参数。
     * @param dim dim 参数。
     * @param accent accent 参数。
     * @param border border 参数。
     */
    private TerminalSkin(String name, String bold, String dim, String accent, String border) {
        this.name = name;
        this.bold = bold;
        this.dim = dim;
        this.accent = accent;
        this.border = border;
    }

    /**
     * 从输入转换Environment。
     *
     * @return 返回Environment结果。
     */
    public static TerminalSkin fromEnvironment() {
        return resolve(System.getenv("SOLONCLAW_TERMINAL_SKIN"));
    }

    /**
     * 解析运行时需要的目标对象。
     *
     * @param value 待规范化或校验的原始值。
     * @return 返回resolve结果。
     */
    public static TerminalSkin resolve(String value) {
        String normalized =
                StrUtil.blankToDefault(value, "classic").trim().toLowerCase(Locale.ROOT);
        if ("mono".equals(normalized) || "plain".equals(normalized)) {
            return new TerminalSkin(
                    "mono",
                    "",
                    "",
                    "",
                    "------------------------------------------------------------");
        }
        if ("contrast".equals(normalized) || "high-contrast".equals(normalized)) {
            return new TerminalSkin(
                    "contrast",
                    "\u001B[1m",
                    "\u001B[37m",
                    "\u001B[96m",
                    "============================================================");
        }
        return new TerminalSkin(
                "classic",
                "\u001B[1m",
                "\u001B[2m",
                "\u001B[36m",
                "────────────────────────────────────────────────────────");
    }

    /**
     * 判断是否Skin命令。
     *
     * @param input 输入参数。
     * @return 如果Skin命令满足条件则返回 true，否则返回 false。
     */
    public static boolean isSkinCommand(String input) {
        String value = StrUtil.nullToEmpty(input).trim().toLowerCase(Locale.ROOT);
        return "/skin".equals(value) || value.startsWith("/skin ");
    }

    /**
     * 渲染Help。
     *
     * @return 返回render Help结果。
     */
    public String renderHelp() {
        return "当前皮肤：" + name + "\n可选：classic、mono、contrast\n使用：/skin <名称>";
    }

    /**
     * 执行名称相关逻辑。
     *
     * @return 返回名称结果。
     */
    public String name() {
        return name;
    }

    /**
     * 执行bold相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回bold结果。
     */
    public String bold(String text) {
        return wrap(bold, text);
    }

    /**
     * 执行dim相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回dim结果。
     */
    public String dim(String text) {
        return wrap(dim, text);
    }

    /**
     * 执行accent相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回accent结果。
     */
    public String accent(String text) {
        return wrap(accent, text);
    }

    /**
     * 执行border相关逻辑。
     *
     * @return 返回border结果。
     */
    public String border() {
        return border;
    }

    /**
     * 执行提示词相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回提示词结果。
     */
    public String prompt(String text) {
        return bold(accent(text)) + " > ";
    }

    /**
     * 执行wrap相关逻辑。
     *
     * @param code code 参数。
     * @param text 待处理文本。
     * @return 返回wrap结果。
     */
    private String wrap(String code, String text) {
        String value = StrUtil.nullToEmpty(text);
        return StrUtil.isBlank(code) ? value : code + value + RESET;
    }
}
