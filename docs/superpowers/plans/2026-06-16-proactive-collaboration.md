# Proactive Collaboration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a complete proactive collaboration system that can observe prior work, known user context, repository updates, cron/run state, and home-channel availability, then contact the user with high-signal collaboration opportunities.

**Architecture:** Replace the narrow `HEARTBEAT.md` checklist behavior with a domain service pipeline: collect structured observations, generate typed proactive candidates, deduplicate/rate-limit them, optionally ask the LLM for final judgment and phrasing, then deliver through existing home-channel delivery. Keep execution passive by default: proactive contact may ask, suggest, summarize, or offer next actions, but must not modify code, run risky tools, or create external side effects without an explicit user reply.

**Tech Stack:** Java, Solon, Solon AI via existing `LlmGateway`/orchestrator patterns, Hutool utilities, Snack4 JSON, SQLite repositories, existing `DeliveryService`, `GatewayPolicyRepository`, `SessionRepository`, `AgentRunRepository`, `CronJobRepository`, `MemoryService`, `SessionSearchService`, and current dashboard/config services.

---

## Scope And Non-Negotiable Constraints

- Keep project naming as `solonclaw`; do not write old project names or copied upstream names into code, tests, docs, release notes, or prompt text.
- Main implementation language is Java. Prefer Solon/Solon AI, Hutool, and Snack4. Do not introduce Spring, Jackson, Fastjson, Gson, LangChain4j, or Spring AI.
- Domestic-channel scope only. Delivery must reuse existing home-channel and `DeliveryService` paths for Feishu, DingTalk, WeCom, Weixin, QQ bot, and Yuanbao as currently supported by the project.
- This feature is not a medical reminder or a hard-coded habit system. It is a general proactive collaboration layer: work continuation, knowledge-based check-ins, external update opportunities, cron/run follow-up, and gentle user-care prompts.
- Dashboard-first setup and doctor. Do not add a full CLI wizard.
- Default behavior should be enabled only when safe configuration and home-channel prerequisites are present. Missing prerequisites must be visible in diagnostics.
- Proactive contacts ask or offer. They do not execute work, edit files, push code, send external messages, or run dangerous tools without a later explicit user turn and existing approval policies.
- Every generated or modified Java class, field, and method must have concrete Chinese Javadoc or comments that explain purpose, responsibility, or key constraints.

## Target Product Behavior

The system should be able to proactively contact the user for these categories:

- `work_continuation`: prior sessions, active goals, unfinished runs, queued work, pending confirmations, failed verification, or resumable tasks indicate useful next action.
- `knowledge_followup`: long-term memory, user profile, project context, or previous requests imply a relevant check-in, such as asking whether there are current tasks needing help.
- `project_update_opportunity`: a repository, branch, release, dependency, or similar project that the user previously worked with has changed in a way that may matter to an earlier task.
- `cron_followup`: scheduled work produced results, failures, silence, or repeated skipped delivery that deserves a human-readable follow-up.
- `care_checkin`: low-frequency, non-specific contact that asks what work needs help, only when no higher-value candidate exists and quiet policy allows it.

Each candidate must include:

- `candidateId`
- `sourceType`
- `sourceRef`
- `sourceKey`
- `subjectType`
- `subjectRef`
- `topic`
- `title`
- `summary`
- `reason`
- `actionOffer`
- `evidenceJson`
- `confidence`
- `priority`
- `dedupKey`
- `stateHash`
- `createdAt`
- `expiresAt`

Each sent or skipped decision must be auditable:

- whether the scheduler tick ran,
- what observations were collected,
- which candidates were generated,
- why candidates were suppressed,
- whether LLM decision said send/skip,
- what text was sent,
- which home channel received it,
- delivery result or error.

## File Structure

Create the following focused package:

- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveScheduler.java`
  - Fixed-delay scheduler and tick boundary. It owns no business logic.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveObservationService.java`
  - Coordinates all observation collectors.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveObservationCollector.java`
  - Interface for independent observation collectors.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveCandidateService.java`
  - Converts observations into candidates and stores them.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDecisionService.java`
  - Applies hard gates, dedup, rate limits, confidence rules, and LLM send/skip judgment.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveMessageComposer.java`
  - Composes final outbound text using deterministic template fallback and optional LLM polish.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDispatchService.java`
  - Sends approved messages through home-channel delivery and records result.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDiagnosticsService.java`
  - Produces dashboard/doctor summaries.
- `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveRepository.java`
  - Repository interface for SQLite persistence.
- `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteProactiveRepository.java`
  - SQLite implementation.
- `src/main/java/com/jimuqu/solon/claw/proactive/collector/*.java`
  - Separate collectors for sessions, runs, cron, memory, repository updates, and quiet context.
- `src/main/java/com/jimuqu/solon/claw/core/model/Proactive*.java`
  - Model records and enums used by services and repository.

Modify existing files:

- `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java`
  - Add `ProactiveConfig`.
- `src/main/java/com/jimuqu/solon/claw/config/RuntimeConfigResolver.java`
  - Add runtime override keys.
- `config.example.yml`
  - Add documented proactive config block.
- `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteDatabase.java`
  - Add tables and indexes.
- `src/main/java/com/jimuqu/solon/claw/bootstrap/SchedulerConfiguration.java`
  - Wire scheduler and services.
- `src/main/java/com/jimuqu/solon/claw/bootstrap/ContextConfiguration.java`
  - Wire repository/service beans if that package remains the best fit.
- `src/main/java/com/jimuqu/solon/claw/bootstrap/DashboardConfiguration.java`
  - Wire dashboard service.
- `src/main/java/com/jimuqu/solon/claw/web/DashboardStatusService.java`
  - Add proactive status summary.
- `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
  - Add proactive doctor coverage.
- `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigService.java`
  - Expose safe config fields.
- `src/main/java/com/jimuqu/solon/claw/web/DashboardLogsService.java`
  - Add proactive log filter.
- `README.md`
  - Document behavior, safety, config, and diagnostics.

Add tests:

- `src/test/java/com/jimuqu/solon/claw/ProactiveConfigTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveRepositoryTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveObservationServiceTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveCandidateServiceTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveDecisionServiceTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveDispatchServiceTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveSchedulerTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveDashboardDiagnosticTest.java`
- `src/test/java/com/jimuqu/solon/claw/ProactiveNamingGuardTest.java`

## Task 1: Config Model And Runtime Keys

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/config/RuntimeConfigResolver.java`
- Modify: `config.example.yml`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveConfigTest.java`

- [ ] Add `AppConfig.ProactiveConfig` under top-level config, not under `HeartbeatConfig`.
- [ ] Fields and defaults:
  - `enabled = true`
  - `intervalMinutes = 30`
  - `initialDelaySeconds = 60`
  - `dailyMaxContacts = 3`
  - `cooldownMinutes = 120`
  - `quietStartHour = 23`
  - `quietEndHour = 8`
  - `minConfidenceToContact = 0.65`
  - `llmDecisionEnabled = true`
  - `llmPolishEnabled = true`
  - `maxCandidatesPerTick = 20`
  - `maxContactsPerTick = 1`
  - `candidateTtlHours = 72`
  - `repositoryCheckEnabled = true`
  - `repositoryCheckIntervalMinutes = 360`
  - `sessionLookbackDays = 30`
  - `runLookbackDays = 14`
  - `cronLookbackDays = 14`
  - `careCheckinEnabled = true`
  - `careCheckinAfterIdleHours = 48`
  - `deliveryPreviewPrefix = "主动协作"`
- [ ] Add Chinese comments for every field.
- [ ] Add runtime override keys under `solonclaw.proactive.*`.
- [ ] Add `config.example.yml` comments explaining that proactive contact asks before acting and uses existing home-channel delivery.
- [ ] Write tests that default config loads with expected values and YAML overrides work.
- [ ] Run `mvn -q -Dtest=ProactiveConfigTest test`.

## Task 2: Persistence Schema And Repository

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteDatabase.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveCandidateRecord.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveDecisionRecord.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveObservationRecord.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveSourceSnapshotRecord.java`
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveRepository.java`
- Create: `src/main/java/com/jimuqu/solon/claw/storage/repository/SqliteProactiveRepository.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveRepositoryTest.java`

- [ ] Add table `proactive_observations`:
  - `observation_id text primary key`
  - `tick_id text not null`
  - `collector text not null`
  - `source_key text`
  - `summary text`
  - `payload_json text`
  - `status text not null`
  - `error text`
  - `created_at integer not null`
- [ ] Add table `proactive_candidates`:
  - fields listed in target behavior,
  - `status text not null default 'PENDING'`,
  - `last_decision_id text`,
  - `updated_at integer not null`.
- [ ] Add table `proactive_decisions`:
  - `decision_id text primary key`
  - `tick_id text not null`
  - `candidate_id text`
  - `source_key text`
  - `decision text not null`
  - `reason text`
  - `message text`
  - `delivery_platform text`
  - `delivery_chat_id text`
  - `delivery_thread_id text`
  - `delivery_status text`
  - `delivery_error text`
  - `metadata_json text`
  - `created_at integer not null`.
- [ ] Add table `proactive_source_snapshots`:
  - `source_type text not null`
  - `source_ref text not null`
  - `state_hash text not null`
  - `payload_json text`
  - `checked_at integer not null`
  - primary key `(source_type, source_ref)`.
- [ ] Add indexes:
  - candidates by `status, priority desc, created_at`,
  - candidates by `dedup_key, state_hash`,
  - decisions by `created_at`,
  - decisions by `source_key, created_at`.
- [ ] Implement repository methods:
  - `saveObservation`
  - `saveCandidate`
  - `findRecentCandidateByDedup`
  - `listPendingCandidates`
  - `markCandidateStatus`
  - `saveDecision`
  - `countSentSince`
  - `findLastSentAt`
  - `findSnapshot`
  - `saveSnapshot`
  - `listRecentDecisions`
- [ ] Use Snack4 for JSON payload serialization where model payloads need maps.
- [ ] Tests must verify schema creation, candidate upsert/dedup lookup, decision logging, sent count window, and source snapshot replacement.
- [ ] Run `mvn -q -Dtest=ProactiveRepositoryTest test`.

## Task 3: Observation Model And Collector SPI

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveObservationCollector.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveObservation.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveTickContext.java`
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveObservationService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveObservationServiceTest.java`

- [ ] Define collector interface:
  - `String name()`
  - `boolean enabled(AppConfig config)`
  - `List<ProactiveObservation> collect(ProactiveTickContext context) throws Exception`
- [ ] `ProactiveTickContext` includes tick id, now millis, config snapshot, home channels, and last decision summaries.
- [ ] `ProactiveObservation` includes collector, sourceKey, summary, payload map, status, and error.
- [ ] `ProactiveObservationService.collectAll` must catch collector failures, save failed observations, and continue.
- [ ] Observations must be limited to a small payload and redact secrets through existing `SecretRedactor`.
- [ ] Tests must verify one failing collector does not stop others and failures become observation records.
- [ ] Run `mvn -q -Dtest=ProactiveObservationServiceTest test`.

## Task 4: Session And Goal Continuation Collector

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/collector/SessionContinuationCollector.java`
- Modify if needed: `src/main/java/com/jimuqu/solon/claw/core/repository/SessionRepository.java`
- Test: `src/test/java/com/jimuqu/solon/claw/SessionContinuationCollectorTest.java`

- [ ] Collect recent sessions within `sessionLookbackDays`.
- [ ] Detect:
  - sessions with active or blocked goal metadata,
  - sessions whose final assistant message ended with unresolved ask,
  - sessions containing keywords for verification, deploy, merge, push, review, follow-up, continue, or waiting user confirmation,
  - sessions with recent work but no final completion signal.
- [ ] Do not rely only on keyword match: include session title, branch, source key, last update time, and final reply preview in structured payload.
- [ ] Emit observation type `session_continuation`.
- [ ] Tests must include one completed session that does not produce a high-value observation and one resumable session that does.
- [ ] Run `mvn -q -Dtest=SessionContinuationCollectorTest test`.

## Task 5: Agent Run And Tool-State Collector

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/collector/RunStateCollector.java`
- Test: `src/test/java/com/jimuqu/solon/claw/RunStateCollectorTest.java`

- [ ] Use `AgentRunRepository.searchRuns` and related list methods to inspect recent failed, recoverable, stale, interrupted, or verification-failed runs.
- [ ] Include tool calls that ended with error or repeated failure when available.
- [ ] Produce observations for:
  - `run_failed_needs_followup`
  - `run_recoverable`
  - `verification_failed`
  - `queued_work_waiting`
- [ ] Exclude transient busy heartbeat records that were already resolved.
- [ ] Tests verify recoverable runs produce observations and finished successful runs do not.
- [ ] Run `mvn -q -Dtest=RunStateCollectorTest test`.

## Task 6: Cron Follow-Up Collector

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/collector/CronFollowupCollector.java`
- Test: `src/test/java/com/jimuqu/solon/claw/CronFollowupCollectorTest.java`

- [ ] Inspect `CronJobRepository.listAll(true)` and recent runs.
- [ ] Produce observations for:
  - active cron repeatedly failing,
  - active cron with `last_delivery_error`,
  - cron output that appears actionable,
  - cron jobs that have not run despite due `nextRunAt`,
  - paused jobs with a user-visible reason.
- [ ] Respect existing `[SILENT]` semantics: silent output alone is not a candidate.
- [ ] Include job id, name, source key, last status, last error, delivery error, and next run time.
- [ ] Tests verify silent output does not generate a follow-up, while delivery error does.
- [ ] Run `mvn -q -Dtest=CronFollowupCollectorTest test`.

## Task 7: Memory And Knowledge Follow-Up Collector

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/collector/MemoryFollowupCollector.java`
- Test: `src/test/java/com/jimuqu/solon/claw/MemoryFollowupCollectorTest.java`

- [ ] Read `MemoryService` snapshot and today memory.
- [ ] Extract candidate hints from user/project memory lines that explicitly indicate ongoing interest, watched repositories, recurring responsibilities, or preferred follow-up behavior.
- [ ] Do not treat every memory as a reason to contact. Only lines with explicit project/work intent should become observations.
- [ ] Emit low-priority `knowledge_followup` observations with evidence lines and confidence hints.
- [ ] Tests verify generic user preference memory is ignored and project follow-up memory is included.
- [ ] Run `mvn -q -Dtest=MemoryFollowupCollectorTest test`.

## Task 8: Repository Update Collector

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/collector/RepositoryUpdateCollector.java`
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/RepositoryReferenceExtractor.java`
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/RepositoryProbeService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/RepositoryUpdateCollectorTest.java`

- [ ] Extract repository references from recent sessions, memory, cron prompts, and known workspace paths:
  - local absolute paths under allowed runtime/workspace context,
  - Git remote URLs already present in local git config,
  - GitHub/Gitee/GitLab URLs explicitly mentioned by user.
- [ ] Use non-destructive probes only:
  - local `git rev-parse`, `git remote -v`, `git ls-remote`,
  - HTTP release metadata only for explicit public repository URLs if configured and safe.
- [ ] Never run `git pull`, `git fetch`, checkout, or write to repositories in this collector.
- [ ] Store source snapshot by repo and branch/tag with `stateHash`.
- [ ] Emit observation only when snapshot changed since last check or a new relevant reference is first found.
- [ ] Include commit hash or release id, previous hash, repo display name, and related session/memory evidence.
- [ ] Respect `repositoryCheckEnabled` and `repositoryCheckIntervalMinutes`.
- [ ] Tests use a fake `RepositoryProbeService` and verify unchanged snapshots do not emit repeated observations.
- [ ] Run `mvn -q -Dtest=RepositoryUpdateCollectorTest test`.

## Task 9: Quiet Context And Home Channel Collector

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/collector/QuietContextCollector.java`
- Test: `src/test/java/com/jimuqu/solon/claw/QuietContextCollectorTest.java`

- [ ] Collect whether any domestic channel has a configured home channel.
- [ ] Collect last successful proactive delivery time.
- [ ] Collect quiet-hour status.
- [ ] Collect whether active runs are currently running for each source key.
- [ ] Emit observations that later decision service can use as gates, not user-facing candidates.
- [ ] Tests verify missing home channel creates a diagnostic observation and quiet-hour flag is correct across midnight.
- [ ] Run `mvn -q -Dtest=QuietContextCollectorTest test`.

## Task 10: Candidate Generation

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveCandidateService.java`
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDedupSupport.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveCandidateServiceTest.java`

- [ ] Convert observations to candidates with deterministic rules.
- [ ] Define priority:
  - cron delivery failure and recoverable failed run: high,
  - repository update tied to recent user work: medium-high,
  - session continuation: medium,
  - knowledge follow-up: medium-low,
  - care check-in: low.
- [ ] Create stable dedup keys:
  - `run:<runId>:<status>`
  - `cron:<jobId>:<lastStatus>:<lastRunAt>`
  - `repo:<normalizedRepo>:<branch>:<stateHash>`
  - `session:<sessionId>:<updatedAt>`
  - `memory:<hashOfEvidence>`
- [ ] Create `stateHash` from source state, not from generated message text.
- [ ] Suppress duplicate candidate when same `dedupKey + stateHash` already exists and is not expired.
- [ ] Persist all new candidates.
- [ ] Tests verify duplicate state does not create a second candidate, but changed state does.
- [ ] Run `mvn -q -Dtest=ProactiveCandidateServiceTest test`.

## Task 11: Hard Gates, Rate Limits, And Decision Policy

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDecisionService.java`
- Create: `src/main/java/com/jimuqu/solon/claw/core/model/ProactiveDecision.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveDecisionServiceTest.java`

- [ ] Hard gates:
  - proactive disabled -> skip,
  - no home channel -> skip,
  - quiet hours -> skip except high-priority failures if config later allows,
  - daily sent count reached -> skip,
  - cooldown active -> skip,
  - candidate expired -> skip,
  - confidence below threshold -> skip,
  - active foreground run for target source -> skip unless candidate is run recovery for that source.
- [ ] One contact per tick by default.
- [ ] Rank candidates by priority, confidence, age, and source freshness.
- [ ] Save skip decisions with explicit reasons.
- [ ] If `llmDecisionEnabled` is false, use deterministic allow/skip result.
- [ ] If `llmDecisionEnabled` is true, call an LLM decision prompt that returns JSON:
  - `send: boolean`
  - `reason: string`
  - `message_intent: string`
  - `sensitivity: low|normal|high`
- [ ] LLM decision must not be allowed to override hard gates.
- [ ] Tests cover all gates and ranking.
- [ ] Run `mvn -q -Dtest=ProactiveDecisionServiceTest test`.

## Task 12: Message Composition

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveMessageComposer.java`
- Create: `src/main/resources/prompts/proactive_decision.md` if project uses classpath prompts; otherwise follow existing prompt storage pattern.
- Create: `src/main/resources/prompts/proactive_compose.md` if project uses classpath prompts; otherwise follow existing prompt storage pattern.
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveMessageComposerTest.java`

- [ ] Deterministic fallback format:
  - prefix from config,
  - one-line title,
  - concise reason,
  - one offered next action as a question.
- [ ] Example shape:
  - `主动协作：我发现「bzaqweb 风险看板」相关仓库有新提交，和你之前让我对齐的页面有关。要不要我帮你看一下差异并给出处理建议？`
- [ ] LLM polish may shorten and naturalize, but must preserve:
  - no claim of completed action,
  - no old project keyword,
  - no command execution promise,
  - asks for permission.
- [ ] Scrub memory context tags and secrets from outbound content.
- [ ] Tests verify fallback output, permission wording, and no execution claims.
- [ ] Run `mvn -q -Dtest=ProactiveMessageComposerTest test`.

## Task 13: Dispatch Through Home Channel

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDispatchService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveDispatchServiceTest.java`

- [ ] Resolve delivery target using `GatewayPolicyRepository.getHomeChannel(platform)` for enabled domestic platforms.
- [ ] Prefer the source key’s platform when candidate has a source key and that home channel exists.
- [ ] Otherwise select the first configured home channel in deterministic order: Weixin, WeCom, Feishu, DingTalk, QQ bot, Yuanbao, adjusted to available enum names.
- [ ] Use existing `DeliveryService.deliver`.
- [ ] Record delivery success or failure in `proactive_decisions`.
- [ ] Do not use `ConversationOrchestrator.runScheduled` for final outbound proactive message; the decision/composition services already generated the text and this avoids recursive agent behavior.
- [ ] Tests verify platform selection, thread id preservation, success logging, and failure logging.
- [ ] Run `mvn -q -Dtest=ProactiveDispatchServiceTest test`.

## Task 14: Scheduler Integration

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveScheduler.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/bootstrap/SchedulerConfiguration.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveSchedulerTest.java`

- [ ] Scheduler flow:
  - sleep initial delay,
  - collect observations,
  - generate candidates,
  - decide approved candidate(s),
  - compose message,
  - dispatch,
  - log tick result.
- [ ] Use single-thread scheduled executor.
- [ ] Catch all exceptions per tick and persist a failed observation/decision when possible.
- [ ] Respect `enabled` and `intervalMinutes <= 0`.
- [ ] Ensure shutdown stops executor.
- [ ] Tests verify disabled scheduler does not call services and enabled tick executes services in order.
- [ ] Run `mvn -q -Dtest=ProactiveSchedulerTest test`.

## Task 15: Dashboard Status, Diagnostics, And Logs

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/proactive/ProactiveDiagnosticsService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardStatusService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardDiagnosticsService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardConfigService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/web/DashboardLogsService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveDashboardDiagnosticTest.java`

- [ ] Status must expose:
  - enabled,
  - interval,
  - last tick,
  - pending candidate count,
  - sent today,
  - last sent,
  - last skip reason,
  - home-channel readiness.
- [ ] Diagnostics must explicitly answer:
  - did the proactive scheduler run,
  - were candidates generated,
  - why none were sent,
  - whether home channel is missing,
  - whether quiet hours/cooldown/daily cap blocked contact,
  - whether delivery failed.
- [ ] Config UI must expose safe fields only; no prompt text editing is required.
- [ ] Logs filter `proactive` should include classes under `.proactive.`.
- [ ] Tests verify diagnostic output contains actionable skip reason.
- [ ] Run `mvn -q -Dtest=ProactiveDashboardDiagnosticTest test`.

## Task 16: Command Surface For User Feedback

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/ProactiveCommandTest.java`

- [ ] Add slash command support:
  - `/proactive status`
  - `/proactive pause`
  - `/proactive resume`
  - `/proactive why`
  - `/proactive less`
  - `/proactive more`
  - `/proactive ignore <candidateId>`
- [ ] Commands should update workspace config or candidate status through repository/settings, not edit YAML directly.
- [ ] `/proactive why` returns the latest decision and skip/send reason.
- [ ] `/proactive less` increases cooldown or lowers daily max within bounded values.
- [ ] `/proactive more` decreases cooldown or raises daily max within bounded values.
- [ ] Tests verify command routing and settings changes.
- [ ] Run `mvn -q -Dtest=ProactiveCommandTest test`.

## Task 17: Safety And Naming Guardrails

**Files:**
- Create: `src/test/java/com/jimuqu/solon/claw/ProactiveNamingGuardTest.java`
- Modify as needed: `scripts/check-project-naming.py` only if it must include new paths and already supports extension.

- [ ] Add tests or reuse naming script to verify proactive code/docs/prompts do not contain old project keywords.
- [ ] Verify proactive prompts contain explicit no-execution-without-user-confirmation instruction.
- [ ] Verify repository collector only uses read-only probes.
- [ ] Verify outbound message sanitizer removes memory context fences and secret-looking values.
- [ ] Run:
  - `mvn -q -Dtest=ProactiveNamingGuardTest test`
  - `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`

## Task 18: Documentation

**Files:**
- Modify: `README.md`
- Modify: `config.example.yml`
- Optional create: `docs/proactive-collaboration.md` if README section becomes too long.

- [ ] Document the feature as “主动协作” rather than reminders or habit tracking.
- [ ] Explain:
  - what the system may proactively contact the user about,
  - what it will not do without confirmation,
  - how home channel controls delivery,
  - how to tune frequency,
  - how to pause/resume,
  - how to debug “为什么没有联系我”.
- [ ] Keep language plain Chinese and operational.
- [ ] Include examples:
  - previous project work can be resumed,
  - related repository update found,
  - cron failed delivery needs attention,
  - quiet care check-in.
- [ ] Do not mention external project names.

## Task 19: Full Verification

**Files:**
- No new files unless failures require targeted tests.

- [ ] Run focused tests:
  - `mvn -q -Dtest=ProactiveConfigTest,ProactiveRepositoryTest,ProactiveObservationServiceTest,ProactiveCandidateServiceTest,ProactiveDecisionServiceTest,ProactiveDispatchServiceTest,ProactiveSchedulerTest,ProactiveDashboardDiagnosticTest test`
- [ ] Run broader relevant tests:
  - `mvn -q -Dtest=HeartbeatSchedulerTest,HeartbeatHomeChannelIntegrationTest,DashboardDiagnosticOutputTest test`
- [ ] Run naming guard:
  - `python3 scripts/check-project-naming.py --check-git-commit-subjects --check-git-object-text --check-current-branch-range`
- [ ] Run formatting/lint gate that matches repo conventions:
  - `mvn -q spotless:check`
  - If unrelated existing files fail, rerun with scoped `-DspotlessFiles=` for touched files and document the unrelated failures.
- [ ] Manually inspect diagnostic JSON or dashboard output to ensure proactive skip reasons are human-readable.

## Task 20: Commit Plan

- [ ] Commit storage and config changes:
  - `git add src/main/java/com/jimuqu/solon/claw/config src/main/java/com/jimuqu/solon/claw/storage src/main/java/com/jimuqu/solon/claw/core/model src/test/java/com/jimuqu/solon/claw/ProactiveConfigTest.java src/test/java/com/jimuqu/solon/claw/ProactiveRepositoryTest.java config.example.yml`
  - `git commit -m "feat: 增加主动协作配置和存储 / Add proactive collaboration config and storage"`
- [ ] Commit observation and candidate pipeline:
  - `git add src/main/java/com/jimuqu/solon/claw/proactive src/test/java/com/jimuqu/solon/claw/*CollectorTest.java src/test/java/com/jimuqu/solon/claw/ProactiveObservationServiceTest.java src/test/java/com/jimuqu/solon/claw/ProactiveCandidateServiceTest.java`
  - `git commit -m "feat: 建设主动协作观察和候选生成 / Build proactive observation and candidate pipeline"`
- [ ] Commit decision, composition, dispatch, and scheduler:
  - `git add src/main/java/com/jimuqu/solon/claw/proactive src/main/java/com/jimuqu/solon/claw/bootstrap src/test/java/com/jimuqu/solon/claw/ProactiveDecisionServiceTest.java src/test/java/com/jimuqu/solon/claw/ProactiveMessageComposerTest.java src/test/java/com/jimuqu/solon/claw/ProactiveDispatchServiceTest.java src/test/java/com/jimuqu/solon/claw/ProactiveSchedulerTest.java`
  - `git commit -m "feat: 接入主动协作决策和投递 / Add proactive decision and delivery flow"`
- [ ] Commit dashboard, commands, docs, guards:
  - `git add src/main/java/com/jimuqu/solon/claw/web src/main/java/com/jimuqu/solon/claw/gateway/command README.md docs src/test/java/com/jimuqu/solon/claw/ProactiveDashboardDiagnosticTest.java src/test/java/com/jimuqu/solon/claw/ProactiveCommandTest.java src/test/java/com/jimuqu/solon/claw/ProactiveNamingGuardTest.java`
  - `git commit -m "feat: 完善主动协作诊断和控制 / Add proactive diagnostics and controls"`

## Self-Review Checklist

- [ ] All product requirements from the user are covered:主动关心用户、询问工作协作、根据已有知识主动联系、发现相关项目更新、不是具体吃药类型。
- [ ] Existing project boundaries are respected: Java/Solon, domestic channels, dashboard-first, existing delivery and approval systems.
- [ ] The plan does not require destructive repository operations.
- [ ] The plan includes tests before implementation for every core behavior.
- [ ] The plan includes explicit skip reasons and diagnostics so “为什么没联系我” can be answered.
- [ ] The plan avoids old project names and uses neutral “外部对标仓库” only when needed.
