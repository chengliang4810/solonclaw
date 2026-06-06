package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 承载终端补全文本Generator相关状态和辅助逻辑。 */
public class ShellCompletionGenerator {
    /** TOP级别的统一常量值。 */
    private static final List<String> TOP_LEVEL =
            Collections.unmodifiableList(Arrays.asList("cli", "tui", "completion"));

    /** 补全文本SHELLS的统一常量值。 */
    private static final List<String> COMPLETION_SHELLS =
            Collections.unmodifiableList(Arrays.asList("bash", "zsh", "fish"));

    /** 选项列表的统一常量值。 */
    private static final List<String> OPTIONS =
            Collections.unmodifiableList(
                    Arrays.asList("--cli", "--tui", "--session", "--ask", "-p"));

    /** 本地斜杠命令COMMANDS的统一常量值。 */
    private static final List<String> LOCAL_SLASH_COMMANDS = TerminalCommandCatalog.slashCommands();

    /**
     * 执行写入相关逻辑。
     *
     * @param shell 终端参数。
     * @param out out 参数。
     * @param err err 参数。
     * @return 返回write结果。
     */
    public int write(String shell, PrintStream out, PrintStream err) {
        String normalized = StrUtil.blankToDefault(shell, "bash").trim().toLowerCase();
        if ("bash".equals(normalized)) {
            out.print(bash());
            return 0;
        }
        if ("zsh".equals(normalized)) {
            out.print(zsh());
            return 0;
        }
        if ("fish".equals(normalized)) {
            out.print(fish());
            return 0;
        }
        err.println("Unsupported completion shell: " + shell);
        err.println("Supported shells: bash, zsh, fish");
        return 1;
    }

    /**
     * 执行bash相关逻辑。
     *
     * @return 返回bash结果。
     */
    String bash() {
        return "# Solon Claw bash completion\n"
                + "# Add to ~/.bashrc:\n"
                + "#   eval \"$(solon-claw completion bash)\"\n\n"
                + "_solon_claw_completion() {\n"
                + "    local cur prev\n"
                + "    COMPREPLY=()\n"
                + "    cur=\"${COMP_WORDS[COMP_CWORD]}\"\n"
                + "    prev=\"${COMP_WORDS[COMP_CWORD-1]}\"\n\n"
                + "    if [[ \"$prev\" == \"--session\" ]]; then\n"
                + "        COMPREPLY=()\n"
                + "        return\n"
                + "    fi\n\n"
                + "    if [[ $COMP_CWORD -eq 1 ]]; then\n"
                + "        COMPREPLY=($(compgen -W \""
                + words(TOP_LEVEL)
                + " "
                + words(OPTIONS)
                + "\" -- \"$cur\"))\n"
                + "        return\n"
                + "    fi\n\n"
                + "    case \"${COMP_WORDS[1]}\" in\n"
                + "        completion|--completion)\n"
                + "            COMPREPLY=($(compgen -W \""
                + words(COMPLETION_SHELLS)
                + "\" -- \"$cur\"))\n"
                + "            return\n"
                + "            ;;\n"
                + "        cli|--cli|tui|--tui)\n"
                + "            COMPREPLY=($(compgen -W \"--session --ask -p "
                + words(LOCAL_SLASH_COMMANDS)
                + "\" -- \"$cur\"))\n"
                + "            return\n"
                + "            ;;\n"
                + "    esac\n"
                + "}\n\n"
                + "complete -F _solon_claw_completion solon-claw\n";
    }

    /**
     * 执行zsh相关逻辑。
     *
     * @return 返回zsh结果。
     */
    String zsh() {
        return "#compdef solon-claw\n"
                + "# Solon Claw zsh completion\n"
                + "# Add to ~/.zshrc:\n"
                + "#   eval \"$(solon-claw completion zsh)\"\n\n"
                + "_solon_claw() {\n"
                + "    local context state line\n"
                + "    typeset -A opt_args\n\n"
                + "    _arguments -C \\\n"
                + "        '--cli[Run line-oriented CLI]' \\\n"
                + "        '--tui[Run terminal UI]' \\\n"
                + "        '--session[Session id]:session id:' \\\n"
                + "        '(-p --ask)'{-p,--ask}'[Send one prompt or local terminal command]:prompt:("
                + words(LOCAL_SLASH_COMMANDS)
                + ")' \\\n"
                + "        '1:command:->commands' \\\n"
                + "        '*::arg:->args'\n\n"
                + "    case $state in\n"
                + "        commands)\n"
                + "            local -a cmds\n"
                + "            cmds=(\n"
                + "                'cli:Run line-oriented CLI'\n"
                + "                'tui:Run terminal UI'\n"
                + "                'completion:Print shell completion script'\n"
                + "            )\n"
                + "            _describe 'solon-claw command' cmds\n"
                + "            ;;\n"
                + "        args)\n"
                + "            case ${line[1]} in\n"
                + "                completion)\n"
                + "                    local -a shells\n"
                + "                    shells=(bash zsh fish)\n"
                + "                    _describe 'shell' shells\n"
                + "                    ;;\n"
                + "            esac\n"
                + "            ;;\n"
                + "    esac\n"
                + "}\n\n"
                + "_solon_claw \"$@\"\n";
    }

    /**
     * 执行fish相关逻辑。
     *
     * @return 返回fish结果。
     */
    String fish() {
        return "# Solon Claw fish completion\n"
                + "# Add to your config:\n"
                + "#   solon-claw completion fish | source\n\n"
                + "complete -c solon-claw -f\n"
                + "complete -c solon-claw -f -l cli -d 'Run line-oriented CLI'\n"
                + "complete -c solon-claw -f -l tui -d 'Run terminal UI'\n"
                + "complete -c solon-claw -f -l session -d 'Session id'\n"
                + "complete -c solon-claw -f -s p -l ask -d 'Send one prompt'\n"
                + "complete -c solon-claw -f -n '__fish_seen_subcommand_from cli tui; and __fish_seen_argument -s p -l ask' -a '"
                + words(LOCAL_SLASH_COMMANDS)
                + "'\n"
                + "complete -c solon-claw -f -n 'not __fish_seen_subcommand_from "
                + words(TOP_LEVEL)
                + "' -a '"
                + words(TOP_LEVEL)
                + "'\n"
                + "complete -c solon-claw -f -n '__fish_seen_subcommand_from completion' -a '"
                + words(COMPLETION_SHELLS)
                + "'\n";
    }

    /**
     * 执行words相关逻辑。
     *
     * @param values 待规范化或校验的原始值集合。
     * @return 返回words结果。
     */
    private String words(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
