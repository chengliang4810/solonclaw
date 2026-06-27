package com.jimuqu.solon.claw.cli;
import com.jimuqu.solon.claw.support.AttachmentPathResolver;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.GatewayReply;
import com.jimuqu.solon.claw.core.repository.SessionRepository;
import com.jimuqu.solon.claw.core.service.ConversationEventSink;
import com.jimuqu.solon.claw.support.LlmProviderService;

/** 驱动Cli运行流程，连接命令入口与业务服务。 */
public class CliRunner {
    /** 记录CLI中的CLI运行时。 */
    private final CliRuntime cliRuntime;

    /** 保存会话仓储依赖，用于访问持久化数据。 */
    private final SessionRepository sessionRepository;

    /** 注入应用配置，用于CLI。 */
    private final AppConfig appConfig;

    /** 记录CLI中的附件Resolver。 */
    private final AttachmentPathResolver attachmentResolver;

    /** 记录CLI中的模型Picker。 */
    private final TerminalModelPicker modelPicker;

    /** 记录CLI中的会话浏览器。 */
    private final TerminalSessionBrowser sessionBrowser;

    /** 记录CLI中的历史Viewer。 */
    private final TerminalHistoryViewer historyViewer;

    /** 记录CLI中的 setup/config 本地命令服务。 */
    private final TerminalSetupCommands setupCommands;

    /**
     * 创建Cli Runner实例，并注入运行所需依赖。
     *
     * @param cliRuntime CLI运行时参数。
     * @param sessionRepository 会话仓储依赖。
     * @param appConfig 应用运行配置。
     * @param attachmentResolver 附件解析器参数。
     * @param llmProviderService LLM提供方Service标识或键值。
     */
    public CliRunner(
            CliRuntime cliRuntime,
            SessionRepository sessionRepository,
            AppConfig appConfig,
            AttachmentPathResolver attachmentResolver,
            LlmProviderService llmProviderService) {
        this.cliRuntime = cliRuntime;
        this.sessionRepository = sessionRepository;
        this.appConfig = appConfig;
        this.attachmentResolver = attachmentResolver;
        this.modelPicker =
                appConfig == null || llmProviderService == null
                        ? null
                        : new TerminalModelPicker(appConfig, llmProviderService);
        this.setupCommands =
                appConfig == null ? null : new TerminalSetupCommands(appConfig, modelPicker);
        this.sessionBrowser =
                sessionRepository == null ? null : new TerminalSessionBrowser(sessionRepository);
        this.historyViewer =
                sessionRepository == null
                        ? null
                        : new TerminalHistoryViewer(sessionRepository, cliRuntime);
    }

    /**
     * 执行异步任务主体。
     *
     * @param mode 模式参数。
     * @return 返回运行结果。
     */
    public int run(CliMode mode) throws Exception {
        if (mode.getKind() == CliMode.Kind.COMPLETION) {
            return new ShellCompletionGenerator().write(mode.getInput(), System.out, System.err);
        }
        if (setupCommands != null && setupCommands.isSetupCommand(mode.getInput())) {
            System.out.println(setupCommands.render(mode.getInput()));
            return 0;
        }
        String local = localRender(mode);
        if (local != null) {
            System.out.println(local);
            return 0;
        }
        if (mode.getKind() == CliMode.Kind.CLI || mode.getKind() == CliMode.Kind.TUI) {
            return runPrompt(mode);
        }
        return 0;
    }

    /** 渲染不需要模型调用的本地终端命令，避免 TUI-only 命令误落入 Agent 对话。 */
    private String localRender(CliMode mode) {
        String input = mode == null ? "" : StrUtil.nullToEmpty(mode.getInput()).trim();
        if (StrUtil.isBlank(input)) {
            return null;
        }
        if (modelPicker != null && modelPicker.isPickerCommand(input)) {
            return modelPicker.render();
        }
        if (TerminalSecurityPolicyView.isSecurityCommand(input)) {
            return TerminalSecurityPolicyView.render(appConfig, input);
        }
        if (historyViewer != null && historyViewer.isHistoryCommand(input)) {
            return historyViewer.render(mode.getSessionId(), input);
        }
        if (TerminalTips.isTipsCommand(input)) {
            return TerminalTips.render();
        }
        if (TerminalSkin.isSkinCommand(input)) {
            return TerminalSkin.resolve(skinArgument(input)).renderHelp();
        }
        if (input.equals("/tasks") || input.startsWith("/tasks ")) {
            return "当前是一次性 CLI 模式，没有正在显示的 TUI 后台任务。请在交互式 TUI 中使用 /tasks。";
        }
        if (input.equals("/events") || input.startsWith("/events ")) {
            return "当前是一次性 CLI 模式，没有可展示的 TUI 事件流。请在交互式 TUI 中使用 /events。";
        }
        if (input.equals("/copy") || input.startsWith("/copy ")) {
            return "当前是一次性 CLI 模式，没有可复制的 TUI 选区或上一条可见回复。请在交互式 TUI 中使用 /copy。";
        }
        if (input.equals("/transcript") || input.startsWith("/transcript ")) {
            return "当前是一次性 CLI 模式，没有可展示的 TUI 虚拟历史。请在交互式 TUI 中使用 /transcript。";
        }
        if (input.equals("/yolo") || input.startsWith("/yolo ")) {
            return "本项目未提供 yolo 模式。请使用 /security status 查看安全策略，或用 /approve auto status 查看会话审批设置。";
        }
        if (input.equals("/attachments") || input.startsWith("/attachments ")) {
            String text =
                    input.length() <= "/attachments".length()
                            ? ""
                            : input.substring("/attachments".length()).trim();
            return attachmentResolver == null
                    ? "附件预检服务不可用。"
                    : attachmentResolver.renderPreview(text);
        }
        return null;
    }

    /** 读取 /skin 后的皮肤名，缺省时展示当前环境默认皮肤。 */
    private String skinArgument(String input) {
        String value = StrUtil.nullToEmpty(input).trim();
        return value.length() <= "/skin".length() ? "" : value.substring("/skin".length()).trim();
    }

    /** 执行一次性 CLI/TUI 输入并把后端回复输出到当前终端。 */
    private int runPrompt(CliMode mode) throws Exception {
        if (mode == null || mode.getInput() == null || mode.getInput().trim().isEmpty()) {
            System.err.println("缺少输入内容。示例：solonclaw --cli -p /help");
            return 1;
        }
        GatewayReply reply =
                cliRuntime.send(mode.getSessionId(), mode.getInput(), ConversationEventSink.noop());
        String content = reply == null ? "" : reply.getContent();
        if (content != null && !content.isEmpty()) {
            if (reply != null && reply.isError()) {
                System.err.println(content);
            } else {
                System.out.println(content);
            }
        }
        return reply != null && reply.isError() ? 1 : 0;
    }
}
