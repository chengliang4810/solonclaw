package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;
import com.jimuqu.solon.claw.support.logging.WatchedRollingFileAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 校验运行日志在外部轮转后能恢复写入当前日志文件。 */
public class WatchedRollingFileAppenderTest {
    @TempDir Path tempDir;
    private LoggerContext loggerContext;

    @Test
    void shouldRecreateLiveFileAfterExternalRename() throws Exception {
        Path logPath = tempDir.resolve("gateway.log");
        Path rotatedPath = tempDir.resolve("gateway.log.1");
        WatchedRollingFileAppender<ILoggingEvent> appender =
                new WatchedRollingFileAppender<ILoggingEvent>();
        configure(appender, logPath);
        try {
            assertThat(appender.isStarted()).isTrue();
            assertThat(appender.getOutputStream()).isNotNull();
            assertThat(appender.getStatusManager().getCopyOfStatusList())
                    .extracting(Status::getMessage)
                    .doesNotContain("IO failure in appender");
            append(appender, "before rotation");
            assertThat(appender.getStatusManager().getCopyOfStatusList())
                    .extracting(Status::getMessage)
                    .doesNotContain("IO failure in appender");
            assertThat(Files.readAllLines(logPath, StandardCharsets.UTF_8))
                    .containsExactly("before rotation");

            Files.move(logPath, rotatedPath);
            assertThat(logPath).doesNotExist();

            append(appender, "after rotation");

            assertThat(logPath).exists();
            assertThat(Files.readAllLines(logPath, StandardCharsets.UTF_8))
                    .containsExactly("after rotation");
            assertThat(Files.readAllLines(rotatedPath, StandardCharsets.UTF_8))
                    .containsExactly("before rotation");
        } finally {
            appender.stop();
        }
    }

    @Test
    void shouldRecreateLiveFileAfterExternalDelete() throws Exception {
        Path logPath = tempDir.resolve("agent.log");
        WatchedRollingFileAppender<ILoggingEvent> appender =
                new WatchedRollingFileAppender<ILoggingEvent>();
        configure(appender, logPath);
        try {
            append(appender, "before delete");
            assertThat(Files.readAllLines(logPath, StandardCharsets.UTF_8))
                    .containsExactly("before delete");

            Files.delete(logPath);
            assertThat(logPath).doesNotExist();

            append(appender, "after delete");

            assertThat(logPath).exists();
            assertThat(Files.readAllLines(logPath, StandardCharsets.UTF_8))
                    .containsExactly("after delete");
        } finally {
            appender.stop();
        }
    }

    private void configure(WatchedRollingFileAppender<ILoggingEvent> appender, Path logPath) {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        loggerContext = context;

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(appender);
        rollingPolicy.setFileNamePattern(logPath.toString() + ".%i");
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(2);

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy =
                new SizeBasedTriggeringPolicy<ILoggingEvent>();
        triggeringPolicy.setContext(context);
        triggeringPolicy.setMaxFileSize(new FileSize(1024L * 1024L));
        triggeringPolicy.setCheckIncrement(Duration.buildByMilliseconds(0));

        appender.setContext(context);
        appender.setName("test-gateway");
        appender.setFile(logPath.toString());
        appender.setEncoder(encoder);
        appender.setRollingPolicy(rollingPolicy);
        appender.setTriggeringPolicy(triggeringPolicy);
        rollingPolicy.start();
        triggeringPolicy.start();
        appender.start();
    }

    private void append(WatchedRollingFileAppender<ILoggingEvent> appender, String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.jimuqu.solon.claw.gateway.test");
        event.setLoggerContext(loggerContext);
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        appender.doAppend(event);
    }
}
