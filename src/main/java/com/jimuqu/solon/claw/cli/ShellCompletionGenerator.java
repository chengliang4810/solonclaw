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
            Collections.unmodifiableList(Arrays.asList("cli", "tui", "profile", "completion"));

    /** Profile 管理子命令列表。 */
    private static final List<String> PROFILE_COMMANDS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "list",
                            "use",
                            "create",
                            "describe",
                            "show",
                            "rename",
                            "delete",
                            "alias",
                            "export",
                            "import",
                            "install",
                            "update",
                            "info"));

    /** 补全文本SHELLS的统一常量值。 */
    private static final List<String> COMPLETION_SHELLS =
            Collections.unmodifiableList(Arrays.asList("bash", "zsh", "fish"));

    /** 选项列表的统一常量值。 */
    private static final List<String> OPTIONS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "--cli",
                            "--tui",
                            "--session",
                            "--ask",
                            "--profile",
                            "-p",
                            "--help",
                            "-h"));

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
                + "#   eval \"$(solonclaw completion bash)\"\n\n"
                + "_solon_claw_profiles() {\n"
                + "    { printf '%s\\n' default; solonclaw profile list 2>/dev/null | sed -e"
                + " 's/^[* ]*//' -e 's/[[:space:]].*$//'; } | awk 'NF && !seen[$0]++'\n"
                + "}\n\n"
                + "_solon_claw_completion() {\n"
                + "    local cur prev terminal_mode word\n"
                + "    COMPREPLY=()\n"
                + "    cur=\"${COMP_WORDS[COMP_CWORD]}\"\n"
                + "    prev=\"${COMP_WORDS[COMP_CWORD-1]}\"\n"
                + "    for word in \"${COMP_WORDS[@]:1:$((COMP_CWORD - 1))}\"; do\n"
                + "        case \"$word\" in cli|--cli|tui|--tui) terminal_mode=1; break ;; esac\n"
                + "    done\n\n"
                + "    if [[ \"$prev\" == \"--session\" ]]; then\n"
                + "        COMPREPLY=()\n"
                + "        return\n"
                + "    fi\n\n"
                + "    if [[ \"$prev\" == \"--profile\" || ( \"$prev\" == \"-p\" && -z \"$terminal_mode\" ) ]]; then\n"
                + "        COMPREPLY=($(compgen -W \"$(_solon_claw_profiles)\" -- \"$cur\"))\n"
                + "        return\n"
                + "    fi\n\n"
                + "    if [[ -n \"$terminal_mode\" ]]; then\n"
                + "        COMPREPLY=($(compgen -W \"--session --ask --profile -p "
                + words(LOCAL_SLASH_COMMANDS)
                + "\" -- \"$cur\"))\n"
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
                + "            COMPREPLY=($(compgen -W \"--session --ask --profile -p "
                + words(LOCAL_SLASH_COMMANDS)
                + "\" -- \"$cur\"))\n"
                + "            return\n"
                + "            ;;\n"
                + "        profile)\n"
                + "            if [[ $COMP_CWORD -eq 2 ]]; then\n"
                + "                COMPREPLY=($(compgen -W \""
                + words(PROFILE_COMMANDS)
                + " -h --help\" -- \"$cur\"))\n"
                + "            else\n"
                + "                local action=\"${COMP_WORDS[2]}\"\n"
                + "                local profiles=\"$(_solon_claw_profiles)\"\n"
                + "                case \"$action\" in\n"
                + "                    list)\n"
                + "                        COMPREPLY=($(compgen -W \"-h --help\" -- \"$cur\"))\n"
                + "                        ;;\n"
                + "                    use|show|rename|info)\n"
                + "                        COMPREPLY=($(compgen -W \"$profiles -h --help\" --"
                + " \"$cur\"))\n"
                + "                        ;;\n"
                + "                    create)\n"
                + "                        if [[ \"$prev\" == \"--clone-from\" ]]; then\n"
                + "                            COMPREPLY=($(compgen -W \"$profiles\" --"
                + " \"$cur\"))\n"
                + "                        elif [[ \"$prev\" != \"--description\" ]]; then\n"
                + "                            COMPREPLY=($(compgen -W \"--clone --clone-all"
                + " --clone-from --no-alias --no-skills --description -h --help\" -- \"$cur\"))\n"
                + "                        fi\n"
                + "                        ;;\n"
                + "                    describe)\n"
                + "                        if [[ \"$prev\" != \"--text\" ]]; then\n"
                + "                            COMPREPLY=($(compgen -W \"$profiles --text --auto"
                + " --overwrite --all -h --help\" -- \"$cur\"))\n"
                + "                        fi\n"
                + "                        ;;\n"
                + "                    delete)\n"
                + "                        COMPREPLY=($(compgen -W \"$profiles -y --yes -h --help\""
                + " -- \"$cur\"))\n"
                + "                        ;;\n"
                + "                    alias)\n"
                + "                        if [[ \"$prev\" != \"--name\" ]]; then\n"
                + "                            COMPREPLY=($(compgen -W \"$profiles --remove --name"
                + " -h --help\" -- \"$cur\"))\n"
                + "                        fi\n"
                + "                        ;;\n"
                + "                    export)\n"
                + "                        if [[ \"$prev\" == \"-o\" || \"$prev\" == \"--output\""
                + " ]]; then\n"
                + "                            COMPREPLY=($(compgen -f -- \"$cur\"))\n"
                + "                        else\n"
                + "                            COMPREPLY=($(compgen -W \"$profiles -o --output -h"
                + " --help\" -- \"$cur\"))\n"
                + "                        fi\n"
                + "                        ;;\n"
                + "                    import)\n"
                + "                        if [[ \"$prev\" != \"--name\" ]]; then\n"
                + "                            COMPREPLY=($(compgen -W \"--name -h --help\" --"
                + " \"$cur\") $(compgen -f -- \"$cur\"))\n"
                + "                        fi\n"
                + "                        ;;\n"
                + "                    install)\n"
                + "                        if [[ \"$prev\" != \"--name\" ]]; then\n"
                + "                            COMPREPLY=($(compgen -W \"--name --alias --force -y"
                + " --yes -h --help\" -- \"$cur\") $(compgen -f -- \"$cur\"))\n"
                + "                        fi\n"
                + "                        ;;\n"
                + "                    update)\n"
                + "                        COMPREPLY=($(compgen -W \"$profiles --force-config -y"
                + " --yes -h --help\" -- \"$cur\"))\n"
                + "                        ;;\n"
                + "                esac\n"
                + "            fi\n"
                + "            return\n"
                + "            ;;\n"
                + "    esac\n"
                + "}\n\n"
                + "complete -F _solon_claw_completion solonclaw\n";
    }

    /**
     * 执行zsh相关逻辑。
     *
     * @return 返回zsh结果。
     */
    String zsh() {
        return "#compdef solonclaw\n"
                + "# Solon Claw zsh completion\n"
                + "# Add to ~/.zshrc:\n"
                + "#   eval \"$(solonclaw completion zsh)\"\n\n"
                + "_solon_claw() {\n"
                + "    local context state line\n"
                + "    typeset -A opt_args\n\n"
                + "    _arguments -C \\\n"
                + "        '--cli[Run line-oriented CLI]' \\\n"
                + "        '--tui[Run terminal UI]' \\\n"
                + "        '--session[Session id]:session id:' \\\n"
                + "        '--profile[Run under a named profile]:profile:($(solonclaw profile"
                + " list 2>/dev/null | sed -e \"s/^[* ]*//\" -e \"s/[[:space:]].*$//\"))' \\\n"
                + "        '-p[Send one prompt after --cli or --tui]:prompt:("
                + words(LOCAL_SLASH_COMMANDS)
                + ")' \\\n"
                + "        '--ask[Send one prompt or local terminal command]:prompt:("
                + words(LOCAL_SLASH_COMMANDS)
                + ")' \\\n"
                + "        '(-h --help)'{-h,--help}'[Show help]' \\\n"
                + "        '1:command:->commands' \\\n"
                + "        '*::arg:->args'\n\n"
                + "    case $state in\n"
                + "        commands)\n"
                + "            local -a cmds\n"
                + "            cmds=(\n"
                + "                'cli:Run line-oriented CLI'\n"
                + "                'tui:Run terminal UI'\n"
                + "                'profile:Manage isolated profiles'\n"
                + "                'completion:Print shell completion script'\n"
                + "            )\n"
                + "            _describe 'solonclaw command' cmds\n"
                + "            ;;\n"
                + "        args)\n"
                + "            case ${line[1]} in\n"
                + "                completion)\n"
                + "                    local -a shells\n"
                + "                    shells=(bash zsh fish)\n"
                + "                    _describe 'shell' shells\n"
                + "                    ;;\n"
                + "                profile)\n"
                + "                    local action=${line[2]}\n"
                + "                    local profile_names=($(solonclaw profile list 2>/dev/null |"
                + " sed -e 's/^[* ]*//' -e 's/[[:space:]].*$//'))\n"
                + "                    case $action in\n"
                + "                        '')\n"
                + "                            local -a profile_cmds\n"
                + "                            profile_cmds=("
                + words(PROFILE_COMMANDS)
                + ")\n"
                + "                            _describe 'profile command' profile_cmds\n"
                + "                            ;;\n"
                + "                        list) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " ;;\n"
                + "                        use|show|info) _arguments '(-h --help)'{-h,--help}'[Show"
                + " help]' '1:profile:($profile_names)' ;;\n"
                + "                        rename) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '1:current profile:($profile_names)' '2:new profile name:' ;;\n"
                + "                        create) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '1:profile name:' '--clone' '--clone-all' '--clone-from[Source"
                + " profile]:profile:($profile_names)' '--no-alias' '--no-skills'"
                + " '--description[Profile description]:description:' ;;\n"
                + "                        describe) _arguments '(-h --help)'{-h,--help}'[Show"
                + " help]' '--text[Exact description]:description:' '--auto' '--overwrite' '--all'"
                + " '*:profile:($profile_names)' ;;\n"
                + "                        delete) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '(-y --yes)'{-y,--yes} '*:profile:($profile_names)' ;;\n"
                + "                        alias) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '--remove' '--name[Custom alias name]:alias:' '*:profile:($profile_names)' ;;\n"
                + "                        export) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '(-o --output)'{-o,--output}'[Output archive]:archive:_files'"
                + " '*:profile:($profile_names)' ;;\n"
                + "                        import) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '--name[Imported profile name]:profile name:' '1:archive:_files' ;;\n"
                + "                        install) _arguments '(-h --help)'{-h,--help}'[Show"
                + " help]' '--name[Installed profile name]:profile name:' '--alias' '--force' '(-y"
                + " --yes)'{-y,--yes} '1:source:_files' ;;\n"
                + "                        update) _arguments '(-h --help)'{-h,--help}'[Show help]'"
                + " '--force-config' '(-y --yes)'{-y,--yes} '*:profile:($profile_names)' ;;\n"
                + "                    esac\n"
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
                + "#   solonclaw completion fish | source\n\n"
                + "complete -c solonclaw -f\n"
                + "complete -c solonclaw -f -l cli -d 'Run line-oriented CLI'\n"
                + "complete -c solonclaw -f -l tui -d 'Run terminal UI'\n"
                + "complete -c solonclaw -f -l session -d 'Session id'\n"
                + "complete -c solonclaw -f -l profile -d 'Run under a named profile' -a"
                + " '(solonclaw profile list 2>/dev/null | string replace -r \"^[* ]*\" \"\" |"
                + " string split -f1 \" \")'\n"
                + "complete -c solonclaw -f -l ask -d 'Send one prompt'\n"
                + "complete -c solonclaw -f -s p -d 'Send one prompt after --cli or --tui' -n"
                + " '__fish_seen_subcommand_from cli tui; or contains -- --cli (commandline -opc); or contains -- --tui (commandline -opc)' -a '"
                + words(LOCAL_SLASH_COMMANDS)
                + "'\n"
                + "complete -c solonclaw -f -s h -l help -d 'Show help'\n"
                + "complete -c solonclaw -f -n '__fish_seen_subcommand_from cli tui; and"
                + " __fish_seen_argument -l ask' -a '"
                + words(LOCAL_SLASH_COMMANDS)
                + "'\n"
                + "complete -c solonclaw -f -n 'not __fish_seen_subcommand_from "
                + words(TOP_LEVEL)
                + "' -a '"
                + words(TOP_LEVEL)
                + "'\n"
                + "complete -c solonclaw -f -n '__fish_seen_subcommand_from profile; and not"
                + " __fish_seen_subcommand_from "
                + words(PROFILE_COMMANDS)
                + "' -a '"
                + words(PROFILE_COMMANDS)
                + "'\n"
                + profileFishOptions()
                + "complete -c solonclaw -f -n '__fish_seen_subcommand_from completion' -a '"
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

    /** 生成 Profile 子命令选项的 fish 补全，参数名与管理命令保持一致。 */
    private String profileFishOptions() {
        String prefix =
                "complete -c solonclaw -f -n '__fish_seen_subcommand_from profile; and"
                        + " __fish_seen_subcommand_from ";
        String filePrefix =
                "complete -c solonclaw -F -n '__fish_seen_subcommand_from profile; and"
                        + " __fish_seen_subcommand_from ";
        String profiles =
                "'(solonclaw profile list 2>/dev/null | string replace -r \"^[* ]*\" \"\" |"
                        + " string split -f1 \" \")'";
        return prefix
                + "use show describe delete alias rename export update info' -a "
                + profiles
                + "\n"
                + filePrefix
                + "import install'\n"
                + prefix
                + "create' -l clone\n"
                + prefix
                + "create' -l clone-all\n"
                + prefix
                + "create' -l clone-from -r -a '(solonclaw profile list 2>/dev/null | string"
                + " replace -r \"^[* ]*\" \"\" | string split -f1 \" \")'\n"
                + prefix
                + "create' -l no-alias\n"
                + prefix
                + "create' -l no-skills\n"
                + prefix
                + "create' -l description -r\n"
                + prefix
                + "describe' -l text -r\n"
                + prefix
                + "describe' -l auto\n"
                + prefix
                + "describe' -l overwrite\n"
                + prefix
                + "describe' -l all\n"
                + prefix
                + "delete' -s y -l yes\n"
                + prefix
                + "alias' -l remove\n"
                + prefix
                + "alias' -l name -r\n"
                + prefix
                + "export' -s o -l output -r\n"
                + prefix
                + "import' -l name -r\n"
                + prefix
                + "install' -l name -r\n"
                + prefix
                + "install' -l alias\n"
                + prefix
                + "install' -l force\n"
                + prefix
                + "install' -s y -l yes\n"
                + prefix
                + "update' -l force-config\n"
                + prefix
                + "update' -s y -l yes\n";
    }
}
