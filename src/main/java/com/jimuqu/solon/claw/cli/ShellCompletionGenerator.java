package com.jimuqu.solon.claw.cli;

import cn.hutool.core.util.StrUtil;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shell completion script generator for the Java CLI entrypoint. */
public class ShellCompletionGenerator {
    private static final List<String> TOP_LEVEL =
            Collections.unmodifiableList(Arrays.asList("cli", "tui", "acp", "completion"));
    private static final List<String> COMPLETION_SHELLS =
            Collections.unmodifiableList(Arrays.asList("bash", "zsh", "fish"));
    private static final List<String> OPTIONS =
            Collections.unmodifiableList(
                    Arrays.asList("--cli", "--tui", "--acp", "--session", "--ask", "-p"));
    private static final List<String> LOCAL_SLASH_COMMANDS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "/help",
                            "/models",
                            "/sessions",
                            "/session",
                            "/history",
                            "/title",
                            "/events",
                            "/tasks",
                            "/attachments",
                            "/tips",
                            "/queue",
                            "/steer",
                            "/skin",
                            "/copy",
                            "/exit",
                            "/quit",
                            "/exit!",
                            "/quit!"));

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

    String bash() {
        return "# Jimuqu Agent bash completion\n"
                + "# Add to ~/.bashrc:\n"
                + "#   eval \"$(jimuqu-agent completion bash)\"\n\n"
                + "_jimuqu_agent_completion() {\n"
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
                + "complete -F _jimuqu_agent_completion jimuqu-agent\n";
    }

    String zsh() {
        return "#compdef jimuqu-agent\n"
                + "# Jimuqu Agent zsh completion\n"
                + "# Add to ~/.zshrc:\n"
                + "#   eval \"$(jimuqu-agent completion zsh)\"\n\n"
                + "_jimuqu_agent() {\n"
                + "    local context state line\n"
                + "    typeset -A opt_args\n\n"
                + "    _arguments -C \\\n"
                + "        '--cli[Run line-oriented CLI]' \\\n"
                + "        '--tui[Run terminal UI]' \\\n"
                + "        '--acp[Run ACP stdio adapter]' \\\n"
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
                + "                'acp:Run ACP stdio adapter'\n"
                + "                'completion:Print shell completion script'\n"
                + "            )\n"
                + "            _describe 'jimuqu-agent command' cmds\n"
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
                + "_jimuqu_agent \"$@\"\n";
    }

    String fish() {
        return "# Jimuqu Agent fish completion\n"
                + "# Add to your config:\n"
                + "#   jimuqu-agent completion fish | source\n\n"
                + "complete -c jimuqu-agent -f\n"
                + "complete -c jimuqu-agent -f -l cli -d 'Run line-oriented CLI'\n"
                + "complete -c jimuqu-agent -f -l tui -d 'Run terminal UI'\n"
                + "complete -c jimuqu-agent -f -l acp -d 'Run ACP stdio adapter'\n"
                + "complete -c jimuqu-agent -f -l session -d 'Session id'\n"
                + "complete -c jimuqu-agent -f -s p -l ask -d 'Send one prompt'\n"
                + "complete -c jimuqu-agent -f -n '__fish_seen_subcommand_from cli tui; and __fish_seen_argument -s p -l ask' -a '"
                + words(LOCAL_SLASH_COMMANDS)
                + "'\n"
                + "complete -c jimuqu-agent -f -n 'not __fish_seen_subcommand_from "
                + words(TOP_LEVEL)
                + "' -a '"
                + words(TOP_LEVEL)
                + "'\n"
                + "complete -c jimuqu-agent -f -n '__fish_seen_subcommand_from completion' -a '"
                + words(COMPLETION_SHELLS)
                + "'\n";
    }

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
