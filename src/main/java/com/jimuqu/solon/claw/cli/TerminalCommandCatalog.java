package com.jimuqu.solon.claw.cli;

import com.jimuqu.solon.claw.command.CommandRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 承载终端命令Catalog相关状态和辅助逻辑。 */
public final class TerminalCommandCatalog {
    /** 斜杠命令COMMANDS的统一常量值。 */
    public static final String[] SLASH_COMMANDS = buildSlashCommands();

    /** 斜杠命令命令列表的统一常量值。 */
    private static final List<String> SLASH_COMMAND_LIST =
            Collections.unmodifiableList(asList(SLASH_COMMANDS));

    /** 创建终端命令Catalog实例。 */
    private TerminalCommandCatalog() {}

    /**
     * 执行斜杠命令Commands相关逻辑。
     *
     * @return 返回slash Commands结果。
     */
    public static List<String> slashCommands() {
        return SLASH_COMMAND_LIST;
    }

    /**
     * 构建Slash Commands。
     *
     * @return 返回创建好的Slash Commands。
     */
    private static String[] buildSlashCommands() {
        List<String> commands = new ArrayList<String>(CommandRegistry.slashCommands());
        return commands.toArray(new String[commands.size()]);
    }

    /**
     * 将输入对象归一为列表视图。
     *
     * @param commands commands 参数。
     * @return 返回as List结果。
     */
    private static List<String> asList(String[] commands) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, commands);
        return list;
    }
}
