package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jimuqu.solon.claw.core.service.MemoryManager;
import com.jimuqu.solon.claw.engine.DefaultConversationOrchestrator;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

public class DefaultConversationOrchestratorLoggingTest {
    @Test
    void shouldRedactMemorySyncFailureLogs() throws Exception {
        String leakedToken = "sk-orchestrator-memory12345";
        DefaultConversationOrchestrator orchestrator =
                new DefaultConversationOrchestrator(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new FailingMemoryManager(leakedToken));
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultConversationOrchestrator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            Method method =
                    DefaultConversationOrchestrator.class.getDeclaredMethod(
                            "syncMemory", String.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(orchestrator, "MEMORY:room:user", "hello", "reply");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("Memory sync failed"))
                .anyMatch(message -> message.contains("token=***"))
                .noneMatch(message -> message.contains(leakedToken));
    }

    private static class FailingMemoryManager implements MemoryManager {
        private final String leakedToken;

        private FailingMemoryManager(String leakedToken) {
            this.leakedToken = leakedToken;
        }

        @Override
        public String buildSystemPrompt(String sourceKey) {
            return "";
        }

        @Override
        public String prefetch(String sourceKey, String userMessage) {
            return "";
        }

        @Override
        public void syncTurn(String sourceKey, String userMessage, String assistantMessage) {
            throw new IllegalStateException("memory failed token=" + leakedToken);
        }
    }
}
