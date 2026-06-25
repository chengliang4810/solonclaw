# Terminal Setup Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Java/Solon terminal setup commands so `solonclaw model`, `solonclaw setup`, `solonclaw setup model`, `solonclaw setup gateway`, and `solonclaw config` follow the external reference behavior through one shared configuration layer.

**Architecture:** Keep terminal UI rendering separate from configuration services. Top-level command parsing maps user commands into local terminal slash commands, and CLI/TUI both call one shared terminal setup service before routing to the agent runtime.

**Tech Stack:** Java, Solon, Hutool, Snack4/YAML workspace config support, JUnit 5, AssertJ.

---

### Task 1: Top-Level Command Parsing

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/CliModeParser.java`
- Test: `src/test/java/com/jimuqu/solon/claw/CliModeParserTest.java`

- [ ] **Step 1: Write failing parser tests**

Add tests that verify:
- `model` parses as console mode with input `/setup model`
- `setup model` parses as console mode with input `/setup model`
- `setup gateway` parses as console mode with input `/setup gateway`
- `gateway setup` parses as console mode with input `/setup gateway`
- `config path` parses as console mode with input `/config path`

- [ ] **Step 2: Run parser tests and confirm failure**

Run: `mvn -Dtest=CliModeParserTest test`

Expected: parser assertions fail because bare top-level commands are still treated as server mode.

- [ ] **Step 3: Implement parser mapping**

Add a small top-level command mapper in `CliModeParser` that activates only when no explicit `--cli`, `cli`, `--tui`, `tui`, or completion mode was requested.

- [ ] **Step 4: Verify parser tests**

Run: `mvn -Dtest=CliModeParserTest test`

Expected: tests pass.

### Task 2: Shared Terminal Setup Service

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/cli/TerminalSetupCommands.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/CliRunner.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/CliShell.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/TuiShell.java`
- Test: `src/test/java/com/jimuqu/solon/claw/TerminalSetupCommandsTest.java`

- [ ] **Step 1: Write failing setup service tests**

Add tests for:
- `/setup` renders a section list and routes users to `/setup model` and `/setup gateway`
- `/setup model` shows active provider, active model, API URL, key status, and config set examples
- `/setup gateway` lists only domestic channels: `feishu`, `dingtalk`, `wecom`, `weixin`, `qqbot`, `yuanbao`
- `/setup gateway` does not mention unsupported channels such as `sms` or `webhook`
- `/config path`, `/config show`, and `/config check` render usable terminal output

- [ ] **Step 2: Run setup service tests and confirm failure**

Run: `mvn -Dtest=TerminalSetupCommandsTest test`

Expected: test compilation or assertions fail because the service does not exist yet.

- [ ] **Step 3: Implement setup service**

Create `TerminalSetupCommands` with Chinese Javadocs. The service should read `AppConfig`, `RuntimeConfigResolver`, and `TerminalModelPicker`, render non-interactive setup guidance, and keep all outputs under `solonclaw` naming.

- [ ] **Step 4: Wire service into CLI/TUI**

Inject the service in `CliRunner`, then call it before agent runtime routing in both `CliShell` and `TuiShell`.

- [ ] **Step 5: Verify setup service tests**

Run: `mvn -Dtest=TerminalSetupCommandsTest test`

Expected: tests pass.

### Task 3: Command Registry and Help Alignment

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/command/CommandRegistry.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/LocalTerminalHelp.java`
- Test: `src/test/java/com/jimuqu/solon/claw/TerminalCommandCatalogTest.java`
- Test: `src/test/java/com/jimuqu/solon/claw/LocalTerminalHelpTest.java`

- [ ] **Step 1: Write failing registry/help tests**

Add assertions that `/setup` and `/config` are present in the terminal catalog and local help mentions setup/config usage.

- [ ] **Step 2: Run tests and confirm failure**

Run: `mvn -Dtest=TerminalCommandCatalogTest,LocalTerminalHelpTest test`

Expected: assertions fail because `/setup` is not registered and help lacks setup/config guidance.

- [ ] **Step 3: Register commands and update help**

Register `setup` and ensure `config` is visible in local terminal command discovery. Add concise help lines for setup/model/gateway/config.

- [ ] **Step 4: Verify registry/help tests**

Run: `mvn -Dtest=TerminalCommandCatalogTest,LocalTerminalHelpTest test`

Expected: tests pass.

### Task 4: Real Command Verification

**Files:**
- No source file changes unless runtime verification exposes issues.

- [ ] **Step 1: Run focused unit tests**

Run: `mvn -Dtest=CliModeParserTest,TerminalSetupCommandsTest,TerminalCommandCatalogTest,LocalTerminalHelpTest test`

- [ ] **Step 2: Build runnable jar**

Run: `mvn -DskipTests package`

- [ ] **Step 3: Execute real user commands**

Run:
- `java -jar target/solonclaw-0.0.1.jar model`
- `java -jar target/solonclaw-0.0.1.jar setup`
- `java -jar target/solonclaw-0.0.1.jar setup model`
- `java -jar target/solonclaw-0.0.1.jar setup gateway`
- `java -jar target/solonclaw-0.0.1.jar config path`
- `java -jar target/solonclaw-0.0.1.jar config check`
- `java -jar target/solonclaw-0.0.1.jar --cli -p /setup model`
- `java -jar target/solonclaw-0.0.1.jar --tui -p /setup gateway`

Expected: each command exits without routing setup/config text to the LLM.

- [ ] **Step 4: Fix verification issues**

For each failure, reproduce with the smallest command, identify root cause, add or adjust a regression test, then patch the implementation.
