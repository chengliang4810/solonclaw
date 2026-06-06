package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 封装终端剪贴板辅助逻辑，降低主流程中的重复实现。 */
public final class TerminalClipboardSupport {
    /** 最大COPYCHARS的统一常量值。 */
    private static final int MAX_COPY_CHARS = 200_000;

    /** OSC52PREFIX的统一常量值。 */
    private static final String OSC52_PREFIX = "\u001B]52;c;";

    /** OSC52SUFFIX的统一常量值。 */
    private static final String OSC52_SUFFIX = "\u0007";

    /** 创建终端剪贴板辅助实例。 */
    private TerminalClipboardSupport() {}

    /**
     * 执行copy相关逻辑。
     *
     * @param writer writer 参数。
     * @param text 待处理文本。
     * @return 返回copy结果。
     */
    public static boolean copy(PrintWriter writer, String text) {
        String value = StrUtil.nullToEmpty(text);
        if (writer == null || value.length() == 0) {
            return false;
        }
        String clipped =
                value.length() > MAX_COPY_CHARS ? value.substring(0, MAX_COPY_CHARS) : value;
        writer.print(osc52(clipped));
        writer.flush();
        return true;
    }

    /**
     * 执行osc52相关逻辑。
     *
     * @param text 待处理文本。
     * @return 返回osc52结果。
     */
    static String osc52(String text) {
        String base64 =
                Base64.getEncoder()
                        .encodeToString(StrUtil.nullToEmpty(text).getBytes(StandardCharsets.UTF_8));
        return OSC52_PREFIX + base64 + OSC52_SUFFIX;
    }
}
