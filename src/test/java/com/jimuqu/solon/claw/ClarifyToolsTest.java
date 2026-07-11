package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.AgentRunContext;
import com.jimuqu.solon.claw.tool.runtime.ClarifyRequestCoordinator;
import com.jimuqu.solon.claw.tool.runtime.ClarifyTools;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;

/** 验证 clarify 工具 schema 与交互响应结果对齐。 */
class ClarifyToolsTest {
    @AfterEach
    void clearRunContext() {
        AgentRunContext.setCurrent(null);
    }

    @Test
    void schemaRequiresQuestionAndExposesOnlyOptionalChoices() {
        FunctionTool tool = onlyTool(new ClarifyTools());

        assertThat(tool.inputSchema())
                .contains("\"required\":[\"question\"]")
                .contains("\"choices\"")
                .contains("\"maxItems\":4")
                .doesNotContain("\"options\"");
    }

    @Test
    void toolWaitsForResponseAndTrimsChoicesToFour() throws Exception {
        ClarifyRequestCoordinator coordinator = new ClarifyRequestCoordinator(2_000L);
        ClarifyTools tools = new ClarifyTools(coordinator);
        Object owner = new Object();
        AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> emitted =
                new AtomicReference<ClarifyRequestCoordinator.ClarifyRequest>();
        coordinator.bindSession("session-tool", owner, emitted::set);
        AgentRunContext.setCurrent(
                new AgentRunContext(
                        null, "run-tool", "session-tool", "MEMORY:terminal-ui:session-tool"));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("question", " Which target? ");
        args.put("choices", Arrays.asList("dev", "main", "staging", "preview", "extra"));

        CompletableFuture<Object> result =
                CompletableFuture.supplyAsync(
                        () -> {
                            AgentRunContext.setCurrent(
                                    new AgentRunContext(
                                            null,
                                            "run-tool-async",
                                            "session-tool",
                                            "MEMORY:terminal-ui:session-tool"));
                            try {
                                return onlyTool(tools).handle(args);
                            } catch (Throwable e) {
                                throw new IllegalStateException(e);
                            } finally {
                                AgentRunContext.setCurrent(null);
                            }
                        });
        ClarifyRequestCoordinator.ClarifyRequest request = waitForRequest(emitted);
        coordinator.respond("session-tool", request.getRequestId(), "main", owner);

        assertThat(request.getChoices()).containsExactly("dev", "main", "staging", "preview");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) result.get(1, TimeUnit.SECONDS);
        assertThat(response)
                .containsEntry("question", "Which target?")
                .containsEntry("user_response", "main");
        assertThat(response.get("choices_offered"))
                .isEqualTo(Arrays.asList("dev", "main", "staging", "preview"));
    }

    @Test
    void openEndedQuestionEmitsNullChoices() throws Exception {
        ClarifyRequestCoordinator coordinator = new ClarifyRequestCoordinator(2_000L);
        ClarifyTools tools = new ClarifyTools(coordinator);
        Object owner = new Object();
        AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> emitted =
                new AtomicReference<ClarifyRequestCoordinator.ClarifyRequest>();
        coordinator.bindSession("session-open", owner, emitted::set);
        AgentRunContext.setCurrent(
                new AgentRunContext(
                        null, "run-open", "session-open", "MEMORY:terminal-ui:session-open"));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("question", "What should change?");

        CompletableFuture<Object> result =
                CompletableFuture.supplyAsync(
                        () -> {
                            AgentRunContext.setCurrent(
                                    new AgentRunContext(
                                            null,
                                            "run-open-async",
                                            "session-open",
                                            "MEMORY:terminal-ui:session-open"));
                            try {
                                return onlyTool(tools).handle(args);
                            } catch (Throwable e) {
                                throw new IllegalStateException(e);
                            } finally {
                                AgentRunContext.setCurrent(null);
                            }
                        });
        ClarifyRequestCoordinator.ClarifyRequest request = waitForRequest(emitted);
        coordinator.respond("session-open", request.getRequestId(), "Use the current API", owner);

        assertThat(request.getChoices()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) result.get(1, TimeUnit.SECONDS);
        assertThat(response).containsEntry("user_response", "Use the current API");
        assertThat(response.get("choices_offered")).isNull();
    }

    @Test
    void nonListChoicesAreRejectedInsteadOfBeingSilentlyCoerced() throws Throwable {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("question", "Which target?");
        args.put("choices", "dev");

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                (Map<String, Object>) onlyTool(new ClarifyTools()).handle(args);

        assertThat(response).containsEntry("error", "choices must be a list of strings.");
    }

    /** 读取 clarify 工具提供器的唯一函数。 */
    private FunctionTool onlyTool(ClarifyTools tools) {
        Collection<FunctionTool> functions = tools.getTools();
        assertThat(functions).hasSize(1);
        return functions.iterator().next();
    }

    /** 等待工具线程完成事件发送，避免测试依赖固定 sleep。 */
    private ClarifyRequestCoordinator.ClarifyRequest waitForRequest(
            AtomicReference<ClarifyRequestCoordinator.ClarifyRequest> request) throws Exception {
        long deadline = System.currentTimeMillis() + 1_000L;
        while (request.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(request.get()).isNotNull();
        return request.get();
    }
}
