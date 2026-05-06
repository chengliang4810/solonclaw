package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.repository.GlobalSettingRepository;
import com.jimuqu.solon.claw.gateway.command.SlashConfirmService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SlashConfirmServiceTest {
    @Test
    void shouldReturnPendingCopyAndKeepStoredCommandImmutable() {
        SlashConfirmService service = new SlashConfirmService(new MemorySettings());
        SlashConfirmService.PendingConfirm registered =
                service.register("session-a", "reload-mcp", "reload?");

        SlashConfirmService.PendingConfirm pending = service.getPending("session-a");
        pending.setCommand("mutated");

        SlashConfirmService.PendingConfirm current = service.getPending("session-a");
        assertThat(current.getConfirmId()).isEqualTo(registered.getConfirmId());
        assertThat(current.getCommand()).isEqualTo("reload-mcp");
        assertThat(current.isAllowAlways()).isTrue();
    }

    @Test
    void shouldPreserveNonAlwaysConfirmFlagAcrossCopies() {
        SlashConfirmService service = new SlashConfirmService(new MemorySettings());
        SlashConfirmService.PendingConfirm registered =
                service.register("session-a", "rollback", "clear checkpoints?", false);

        SlashConfirmService.PendingConfirm pending = service.getPending("session-a");
        SlashConfirmService.PendingConfirm resolved =
                service.resolve("session-a", registered.getConfirmId());

        assertThat(pending.isAllowAlways()).isFalse();
        assertThat(resolved.isAllowAlways()).isFalse();
    }

    @Test
    void shouldSupersedePriorPendingConfirmForSameSource() {
        SlashConfirmService service = new SlashConfirmService(new MemorySettings());
        SlashConfirmService.PendingConfirm first =
                service.register("session-a", "reload-mcp", "first");
        SlashConfirmService.PendingConfirm second =
                service.register("session-a", "reload-mcp", "second");

        SlashConfirmService.PendingConfirm pending = service.getPending("session-a");

        assertThat(pending.getConfirmId()).isEqualTo(second.getConfirmId());
        assertThat(pending.getConfirmId()).isNotEqualTo(first.getConfirmId());
        assertThat(pending.getPrompt()).isEqualTo("second");
    }

    @Test
    void shouldKeepSlashConfirmIsolatedBySource() {
        SlashConfirmService service = new SlashConfirmService(new MemorySettings());
        SlashConfirmService.PendingConfirm a =
                service.register("source-a", "reload-mcp", "A");
        SlashConfirmService.PendingConfirm b =
                service.register("source-b", "reload-mcp", "B");

        SlashConfirmService.PendingConfirm resolvedA =
                service.resolve("source-a", a.getConfirmId());

        assertThat(resolvedA.getPrompt()).isEqualTo("A");
        assertThat(service.getPending("source-a")).isNull();
        assertThat(service.getPending("source-b").getConfirmId()).isEqualTo(b.getConfirmId());
    }

    @Test
    void shouldNotResolveConfirmIdMismatchAndKeepPendingEntry() {
        SlashConfirmService service = new SlashConfirmService(new MemorySettings());
        SlashConfirmService.PendingConfirm registered =
                service.register("session-a", "reload-mcp", "reload?");

        SlashConfirmService.PendingConfirm mismatch =
                service.resolve("session-a", "wrong-confirm-id");

        assertThat(mismatch).isNull();
        assertThat(service.getPending("session-a").getConfirmId())
                .isEqualTo(registered.getConfirmId());
    }

    @Test
    void shouldClearStalePendingConfirm() {
        SlashConfirmService service = new SlashConfirmService(new MemorySettings());
        SlashConfirmService.PendingConfirm pending =
                service.register("session-a", "reload-mcp", "reload?");
        pending.setCreatedAt(System.currentTimeMillis() - 10000L);

        assertThat(service.clearIfStale("session-a", 1L)).isTrue();
        assertThat(service.getPending("session-a")).isNull();
    }

    @Test
    void shouldPersistAlwaysConfirmedCommandsNormalized() throws Exception {
        MemorySettings settings = new MemorySettings();
        SlashConfirmService service = new SlashConfirmService(settings);

        service.addAlwaysConfirmed("/reload-mcp");

        assertThat(service.isAlwaysConfirmed("reload-mcp")).isTrue();
        assertThat(service.isAlwaysConfirmed("/RELOAD-MCP")).isTrue();
    }

    private static class MemorySettings implements GlobalSettingRepository {
        private final Map<String, String> values = new LinkedHashMap<String, String>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
