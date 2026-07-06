# `/goal` 全量复刻实现计划 / Goal Full Replication Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有最小骨架 `/goal`（9 字段状态 + 启发式 judge + 4 子命令 + 续轮调度）补齐到外部对标仓库的完整行为：LLM judge、`/subgoal`、GoalContract、wait 屏障、抢占、压缩迁移归档。

**Architecture:** 复用现有 `GoalService` + 续轮调度链（`handleGoal` → `safeScheduleGoalKickoff` → `runScheduled` → `applyGoalDecision` → `safeScheduleGoalContinuation`）。新增 LLM judge 走 `LlmGateway` 非流式 auxiliary 通道（镜像 `AsyncSkillLearningService.callAuxiliaryChat`）。续轮提示始终是普通 user 消息，绝不改写 system prompt / 工具集。judge 失败 fail-open。真实用户消息抢占续轮。

**Tech Stack:** Java, Solon, Hutool, Snack4 (`org.noear.snack4.ONode`), JUnit 5, AssertJ, Maven。已有测试基础设施：`TestEnvironment.withFakeLlm()` / `TestEnvironment.withLlm(LlmGateway)` / `FakeLlmGateway`。

## Global Constraints

- 主语言 Java；核心框架 Solon；JSON 用 `org.noear.snack4.ONode`；通用工具 Hutool（`cn.hutool.core.util.StrUtil`）。
- 所有新增 Java 类/字段/方法必须加**中文 Javadoc/注释**，说明业务含义（禁止空泛"定义字段"）。
- 配置项必须加中文注释。
- commit message 中英双语 `type: 中文 / English`。
- 不得引入历史兼容/别名/回退逻辑；命名统一 `solonclaw`。
- 不改写 system prompt、不替换工具集（核心不变量）。
- judge 失败 fail-open（视为 continue）；JSON 不可解析才累计 `consecutiveParseFailures`。
- `fromJson` 每个新字段带默认值，旧 9 字段 JSON 必须能正常加载。
- 工作区：`D:/projects/jimuqu-agent-goal-replication`，分支 `feat/goal-replication-20260706`。
- 每个 task 结束跑 `mvn -q test -Dtest=<TestClass>` 验证，最后跑全量 `mvn -q test`。

---

## File Structure

**新增**（`src/main/java/com/jimuqu/solon/claw/goal/`）：
- `GoalContract.java` — 5 字段完成契约 + render/parse/序列化
- `GoalPromptTemplates.java` — 续轮/judge/draft 提示模板常量集中地
- `GoalJudgeRequest.java` — judge 入参 DTO
- `GoalJudgeResult.java` — judge 出参 DTO（verdict + reason + wait 指令）
- `LlmGoalJudge.java` — LLM-backed judge（auxiliary 通道）
- `GoalContractDrafter.java` — aux LLM 起草 contract
- `GoalMigrationSupport.java` — 压缩轮转时父归档/子继承

**修改**（goal 包）：
- `GoalState.java` — +7 字段 + contract + 序列化
- `GoalVerdict.java` — +WAIT
- `GoalJudge.java` — 签名改 `judge(GoalJudgeRequest)`
- `HeuristicGoalJudge.java` — 适配新签名
- `GoalService.java` — subgoal/wait/unwait/contract/evaluateAfterTurn 重写

**修改**（命令/dispatch/网关/配置）：
- `command/CommandRegistry.java` — 注册 `subgoal`
- `support/constants/GatewayCommandConstants.java` — `COMMAND_SUBGOAL`/`SLASH_SUBGOAL`
- `gateway/command/DefaultCommandService.java` — `handleGoal` 扩展 + `handleSubgoal` + dispatch
- `gateway/command/CommandValueSupport.java` — goal 选项解析（如需）
- `gateway/command/SlashCommandHelpRenderer.java` — /goal /subgoal 帮助文本
- `core/model/GatewayMessage.java` — +`transient boolean goalContinuation` 标志
- `gateway/service/DefaultGatewayService.java` — `safeScheduleGoalContinuation` 加抢占检查
- `engine/DefaultConversationOrchestrator.java` — `applyGoalDecision` 透传 wait metadata
- `engine/DefaultContextCompressionService.java` — 调用 goal 迁移
- `bootstrap/GatewayConfiguration.java` — `goalService` bean 注入 LlmGateway + AppConfig
- `config/AppConfig.java` — +`GoalConfig` 内嵌类
- `config.example.yml` — `solonclaw.goal` 段
- `cli/LocalTerminalHelp.java` — /goal 帮助 + /subgoal
- `src/test/.../support/TestEnvironment.java` — `goalService` 构造改用新签名

**新增测试**（`src/test/java/com/jimuqu/solon/claw/goal/`）：
- `GoalContractTest.java`, `GoalStateSerializationTest.java`, `GoalJudgeResultTest.java`, `GoalMigrationSupportTest.java`, `GoalServiceSubgoalsTest.java`, `GoalServiceWaitBarrierTest.java`, `GoalContinuationPromptTest.java`, `GoalStatusLineTest.java`, `LlmGoalJudgeTest.java`, `GoalContractDrafterTest.java`

---

## Batch 1: 状态模型与契约 / Goal state model + contract (commit 1)

### Task 1: GoalContract 五字段契约

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalContract.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalContractTest.java`

**Interfaces:**
- Produces: `GoalContract`（Lombok `@Getter @Setter @NoArgsConstructor`，字段 `outcome/verification/constraints/boundaries/stopWhen`，全 String 默认 ""），方法 `boolean isEmpty()`、`String renderBlock()`、`Map<String,Object> toMap()`、`static GoalContract fromMap(Map)`、`static GoalContract fromJson(String)`、`String toJson()`。`renderBlock()` 把非空字段渲染成 `- <Label>: <value>` 换行拼接，Label 用 `_LABELS`：outcome→Outcome，verification→Verification，constraints→Constraints，boundaries→Boundaries，stopWhen→Stop when blocked。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalContractTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalContractTest {
    @Test
    void emptyContractRendersBlankAndIsEmpty() {
        GoalContract c = new GoalContract();
        assertThat(c.isEmpty()).isTrue();
        assertThat(c.renderBlock()).isEmpty();
    }

    @Test
    void rendersNonEmptyFieldsWithLabels() {
        GoalContract c = new GoalContract();
        c.setOutcome("测试通过");
        c.setVerification("运行 mvn test 全绿");
        c.setStopWhen("遇到阻塞");
        assertThat(c.isEmpty()).isFalse();
        assertThat(c.renderBlock())
                .contains("- Outcome: 测试通过")
                .contains("- Verification: 运行 mvn test 全绿")
                .contains("- Stop when blocked: 遇到阻塞")
                .doesNotContain("Constraints")
                .doesNotContain("Boundaries");
    }

    @Test
    void roundTripsThroughJson() {
        GoalContract c = new GoalContract();
        c.setOutcome("完成 A");
        c.setConstraints("不改 B");
        String json = c.toJson();
        GoalContract back = GoalContract.fromJson(json);
        assertThat(back.getOutcome()).isEqualTo("完成 A");
        assertThat(back.getConstraints()).isEqualTo("不改 B");
        assertThat(back.getVerification()).isEqualTo("");
    }

    @Test
    void fromJsonHandlesNullAndBlank() {
        assertThat(GoalContract.fromJson(null)).isNull();
        assertThat(GoalContract.fromJson("")).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalContractTest`
Expected: FAIL — `GoalContract` 类不存在，编译错误。

- [ ] **Step 3: 实现 GoalContract**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalContract.java
package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.noear.snack4.ONode;

/**
 * 目标完成契约：把"什么算完成"结构化为五个字段，供 judge 严格裁决、供续轮提示精确引导。
 * 对标外部对标仓库的 GoalContract，字段语义逐一对应。
 */
@Getter
@Setter
@NoArgsConstructor
public class GoalContract {
    /** 期望的最终产出，对应 Outcome。 */
    private String outcome = "";

    /** 完成的验证方式（命令输出、文件内容、测试结果等），对应 Verification。 */
    private String verification = "";

    /** 必须遵守的约束（不能破坏的东西），对应 Constraints。 */
    private String constraints = "";

    /** 作用范围边界（允许触碰的文件/模块等），对应 Boundaries。 */
    private String boundaries = "";

    /** 阻塞放弃条件，对应 Stop when blocked。 */
    private String stopWhen = "";

    /** renderBlock 显示用的字段标签，顺序固定。 */
    private static final String[] FIELD_KEYS = {"outcome", "verification", "constraints", "boundaries", "stopWhen"};

    /** renderBlock 显示用的字段中文/英文标签。 */
    private static final String[] FIELD_LABELS = {
        "Outcome", "Verification", "Constraints", "Boundaries", "Stop when blocked"
    };

    /**
     * 判断契约是否全空。
     *
     * @return 五字段全部为空时返回 true。
     */
    public boolean isEmpty() {
        return StrUtil.isBlank(outcome)
                && StrUtil.isBlank(verification)
                && StrUtil.isBlank(constraints)
                && StrUtil.isBlank(boundaries)
                && StrUtil.isBlank(stopWhen);
    }

    /**
     * 渲染非空字段为 "- Label: value" 的换行块，供续轮提示和 /goal show 使用。
     *
     * @return 渲染后的多行文本，全空时返回空串。
     */
    public String renderBlock() {
        String[] values = {outcome, verification, constraints, boundaries, stopWhen};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FIELD_KEYS.length; i++) {
            String v = StrUtil.nullToEmpty(values[i]).trim();
            if (StrUtil.isBlank(v)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- ").append(FIELD_LABELS[i]).append(": ").append(v);
        }
        return sb.toString();
    }

    /**
     * 转为有序 Map，供 GoalState 序列化嵌套使用。
     *
     * @return 字段键值映射，键为 snake_case。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", StrUtil.nullToEmpty(outcome));
        m.put("verification", StrUtil.nullToEmpty(verification));
        m.put("constraints", StrUtil.nullToEmpty(constraints));
        m.put("boundaries", StrUtil.nullToEmpty(boundaries));
        m.put("stop_when", StrUtil.nullToEmpty(stopWhen));
        return m;
    }

    /**
     * 从 Map 还原契约，缺字段按空串处理。
     *
     * @param data 数据映射。
     * @return 还原后的契约；data 为 null 时返回空契约。
     */
    public static GoalContract fromMap(Map<String, Object> data) {
        GoalContract c = new GoalContract();
        if (data == null) {
            return c;
        }
        c.setOutcome(text(data, "outcome"));
        c.setVerification(text(data, "verification"));
        c.setConstraints(text(data, "constraints"));
        c.setBoundaries(text(data, "boundaries"));
        c.setStopWhen(text(data, "stop_when"));
        return c;
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @return JSON 文本。
     */
    public String toJson() {
        return ONode.serialize(toMap());
    }

    /**
     * 从 JSON 还原契约。
     *
     * @param json JSON 文本。
     * @return 契约；空白输入返回 null。
     */
    @SuppressWarnings("unchecked")
    public static GoalContract fromJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        Map<String, Object> data = ONode.deserialize(json, LinkedHashMap.class);
        return fromMap(data);
    }

    /**
     * 安全读取 Map 中的字符串字段。
     *
     * @param data 数据映射。
     * @param key 字段键。
     * @return 字符串值，空安全。
     */
    private static String text(Map<String, Object> data, String key) {
        Object v = data == null ? null : data.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalContractTest`
Expected: PASS（4 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
cd /d/projects/jimuqu-agent-goal-replication
git add src/main/java/com/jimuqu/solon/claw/goal/GoalContract.java src/test/java/com/jimuqu/solon/claw/goal/GoalContractTest.java
git commit -m "feat: 新增目标完成契约模型与渲染 / Add goal completion contract model and rendering"
```

---

### Task 2: GoalContract inline 解析器

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalContractParser.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalContractParserTest.java`

**Interfaces:**
- Produces: `GoalContractParser.parse(String text)` 返回 `ParseResult`，含 `String headline`（非字段行的剩余文本）和 `GoalContract contract`。识别行首别名前缀（大小写不敏感）：outcome←{outcome,goal,done,"done when"}；verification←{verification,verify,"verified by",evidence,proof}；constraints←{constraints,constraint,preserve,"must not","do not change"}；boundaries←{boundaries,boundary,scope,allowed,files}；stopWhen←{"stop when",stop_when,blocked,"stop if blocked","give up when"}。非识别前缀的冒号（如 "Fix bug: parser"）**不动**，归入 headline。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalContractParserTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalContractParserTest {
    @Test
    void plainTextBecomesHeadlineEmptyContract() {
        GoalContractParser.ParseResult r = GoalContractParser.parse("补齐项目测试");
        assertThat(r.getHeadline()).isEqualTo("补齐项目测试");
        assertThat(r.getContract().isEmpty()).isTrue();
    }

    @Test
    void extractsOutcomeAndVerification() {
        String text = "补齐测试\noutcome: 全部测试通过\nverification: 运行 mvn test";
        GoalContractParser.ParseResult r = GoalContractParser.parse(text);
        assertThat(r.getHeadline()).isEqualTo("补齐测试");
        assertThat(r.getContract().getOutcome()).isEqualTo("全部测试通过");
        assertThat(r.getContract().getVerification()).isEqualTo("运行 mvn test");
    }

    @Test
    void incidentColonNotMangled() {
        // "Fix bug: the parser" 里的冒号不是已知前缀，整行归入 headline
        GoalContractParser.ParseResult r = GoalContractParser.parse("Fix bug: the parser here");
        assertThat(r.getHeadline()).contains("Fix bug: the parser here");
        assertThat(r.getContract().isEmpty()).isTrue();
    }

    @Test
    void recognizesStopWhenAlias() {
        GoalContractParser.ParseResult r =
                GoalContractParser.parse("do task\nstop when: 遇到阻塞");
        assertThat(r.getContract().getStopWhen()).isEqualTo("遇到阻塞");
    }

    @Test
    void nullInputReturnsEmpty() {
        GoalContractParser.ParseResult r = GoalContractParser.parse(null);
        assertThat(r.getHeadline()).isEmpty();
        assertThat(r.getContract().isEmpty()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalContractParserTest`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现 GoalContractParser**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalContractParser.java
package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Getter;

/**
 * 解析用户输入的 inline 完成契约：识别行首 "field: value" 形式，抽取到 GoalContract，
 * 其余行作为目标 headline 返回。对标仓库 parse_contract 的行为复刻。
 */
public final class GoalContractParser {

    private GoalContractParser() {}

    /** 解析结果：headline 为非字段行的剩余文本，contract 为抽取出的契约。 */
    @Getter
    public static final class ParseResult {
        /** 目标主标题（非字段行拼接）。 */
        private final String headline;
        /** 抽取出的完成契约。 */
        private final GoalContract contract;

        /** 构造解析结果。 */
        public ParseResult(String headline, GoalContract contract) {
            this.headline = StrUtil.nullToEmpty(headline);
            this.contract = contract == null ? new GoalContract() : contract;
        }
    }

    /** outcome 字段别名（小写）。 */
    private static final Set<String> ALIAS_OUTCOME = aliases("outcome", "goal", "done", "done when");
    /** verification 字段别名（小写）。 */
    private static final Set<String> ALIAS_VERIFICATION =
            aliases("verification", "verify", "verified by", "evidence", "proof");
    /** constraints 字段别名（小写）。 */
    private static final Set<String> ALIAS_CONSTRAINTS =
            aliases("constraints", "constraint", "preserve", "must not", "do not change");
    /** boundaries 字段别名（小写）。 */
    private static final Set<String> ALIAS_BOUNDARIES =
            aliases("boundaries", "boundary", "scope", "allowed", "files");
    /** stopWhen 字段别名（小写）。 */
    private static final Set<String> ALIAS_STOP_WHEN =
            aliases("stop when", "stop_when", "blocked", "stop if blocked", "give up when");

    /**
     * 解析用户文本为 headline + contract。
     *
     * @param text 用户输入文本。
     * @return 解析结果，绝不返回 null。
     */
    public static ParseResult parse(String text) {
        GoalContract contract = new GoalContract();
        if (StrUtil.isBlank(text)) {
            return new ParseResult("", contract);
        }
        // 按行扫描：识别到已知前缀的字段行抽进 contract，其余行累积为 headline
        Map<Set<String>, java.util.List<String>> buckets = new LinkedHashMap<>();
        buckets.put(ALIAS_OUTCOME, new java.util.ArrayList<>());
        buckets.put(ALIAS_VERIFICATION, new java.util.ArrayList<>());
        buckets.put(ALIAS_CONSTRAINTS, new java.util.ArrayList<>());
        buckets.put(ALIAS_BOUNDARIES, new java.util.ArrayList<>());
        buckets.put(ALIAS_STOP_WHEN, new java.util.ArrayList<>());

        java.util.List<String> headlineLines = new java.util.ArrayList<>();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (StrUtil.isBlank(line)) {
                continue;
            }
            int colon = indexOfFieldColon(line);
            if (colon <= 0) {
                headlineLines.add(rawLine);
                continue;
            }
            String prefix = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            Set<String> matched = matchAlias(prefix);
            if (matched == null) {
                headlineLines.add(rawLine);
                continue;
            }
            buckets.get(matched).add(value);
        }
        contract.setOutcome(join(buckets.get(ALIAS_OUTCOME)));
        contract.setVerification(join(buckets.get(ALIAS_VERIFICATION)));
        contract.setConstraints(join(buckets.get(ALIAS_CONSTRAINTS)));
        contract.setBoundaries(join(buckets.get(ALIAS_BOUNDARIES)));
        contract.setStopWhen(join(buckets.get(ALIAS_STOP_WHEN)));
        return new ParseResult(String.join("\n", headlineLines).trim(), contract);
    }

    /** 找到可作为字段分隔的冒号位置；若冒号前文本不是已知别名候选，返回 -1 让调用方按 headline 处理。 */
    private static int indexOfFieldColon(String line) {
        int idx = line.indexOf(':');
        return idx;
    }

    /** 判断前缀是否命中任一别名集合，返回命中的集合，否则 null。 */
    private static Set<String> matchAlias(String prefix) {
        if (ALIAS_OUTCOME.contains(prefix)) return ALIAS_OUTCOME;
        if (ALIAS_VERIFICATION.contains(prefix)) return ALIAS_VERIFICATION;
        if (ALIAS_CONSTRAINTS.contains(prefix)) return ALIAS_CONSTRAINTS;
        if (ALIAS_BOUNDARIES.contains(prefix)) return ALIAS_BOUNDARIES;
        if (ALIAS_STOP_WHEN.contains(prefix)) return ALIAS_STOP_WHEN;
        return null;
    }

    /** 把多行同字段值用 " ; " 连接。 */
    private static String join(java.util.List<String> values) {
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        for (String v : values) {
            if (StrUtil.isNotBlank(v)) {
                cleaned.add(v);
            }
        }
        return cleaned.isEmpty() ? "" : String.join(" ; ", cleaned);
    }

    /** 构造小写别名集合。 */
    private static Set<String> aliases(String... values) {
        Set<String> s = new LinkedHashSet<>();
        for (String v : values) {
            if (StrUtil.isNotBlank(v)) {
                s.add(v.toLowerCase());
            }
        }
        return s;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalContractParserTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/jimuqu/solon/claw/goal/GoalContractParser.java src/test/java/com/jimuqu/solon/claw/goal/GoalContractParserTest.java
git commit -m "feat: 新增目标契约 inline 解析器 / Add goal contract inline parser"
```

---

### Task 3: GoalState 扩展字段与序列化兼容

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalState.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalStateSerializationTest.java`

**Interfaces:**
- Consumes: `GoalContract`（Task 1）。
- Produces: `GoalState` 新增字段 `List<String> subgoals`、`int consecutiveParseFailures`、`Integer waitingOnPid`、`long waitingUntil`、`String waitingReason`、`long waitingSince`、`GoalContract contract`。`toJson`/`fromJson` 增加 snake_case 键 `subgoals/consecutive_parse_failures/waiting_on_pid/waiting_until/waiting_reason/waiting_since/contract`。新增方法 `boolean isActive()`、`boolean hasContract()`、`boolean isWaiting()`（pid 存活或未到 deadline）、`void clearWaitBarrier()`、`void addSubgoal(String)`、`boolean removeSubgoal(int)`（1-based）、`void clearSubgoals()`。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalStateSerializationTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GoalStateSerializationTest {
    @Test
    void oldNineFieldJsonLoadsCleanly() {
        // 旧版只有 9 字段的 JSON 必须能加载，新字段取默认值
        String oldJson =
                "{\"goal\":\"g\",\"status\":\"active\",\"turns_used\":1,\"max_turns\":5,"
                        + "\"created_at\":100,\"last_turn_at\":200,\"last_verdict\":\"continue\","
                        + "\"last_reason\":\"r\",\"paused_reason\":null}";
        GoalState s = GoalState.fromJson(oldJson);
        assertThat(s.getGoal()).isEqualTo("g");
        assertThat(s.getSubgoals()).isEmpty();
        assertThat(s.getConsecutiveParseFailures()).isZero();
        assertThat(s.getWaitingOnPid()).isNull();
        assertThat(s.getContract().isEmpty()).isTrue();
    }

    @Test
    void newFieldsRoundTrip() {
        GoalState s = new GoalState();
        s.setGoal("完成测试");
        s.addSubgoal("覆盖 goal 包");
        s.addSubgoal("覆盖工具");
        s.setConsecutiveParseFailures(2);
        s.setWaitingOnPid(1234);
        s.setWaitingReason("等编译");
        GoalContract c = new GoalContract();
        c.setOutcome("测试通过");
        s.setContract(c);
        String json = s.toJson();

        GoalState back = GoalState.fromJson(json);
        assertThat(back.getSubgoals()).containsExactly("覆盖 goal 包", "覆盖工具");
        assertThat(back.getConsecutiveParseFailures()).isEqualTo(2);
        assertThat(back.getWaitingOnPid()).isEqualTo(1234);
        assertThat(back.getWaitingReason()).isEqualTo("等编译");
        assertThat(back.getContract().getOutcome()).isEqualTo("测试通过");
    }

    @Test
    void subgoalAddRemoveClear() {
        GoalState s = new GoalState();
        s.addSubgoal("a");
        s.addSubgoal("b");
        s.addSubgoal("c");
        assertThat(s.removeSubgoal(2)).isTrue(); // 删 b
        assertThat(s.getSubgoals()).containsExactly("a", "c");
        assertThat(s.removeSubgoal(99)).isFalse(); // 越界
        s.clearSubgoals();
        assertThat(s.getSubgoals()).isEmpty();
    }

    @Test
    void isWaitingPidBarrierClearsWhenPidDead() {
        GoalState s = new GoalState();
        s.setWaitingOnPid(Integer.MAX_VALUE); // 几乎不可能存活的 pid
        assertThat(s.isWaiting()).isFalse(); // pid 不存活 → 不在等待
        s.clearWaitBarrier();
        assertThat(s.getWaitingOnPid()).isNull();
    }

    @Test
    void isWaitingUntilDeadline() {
        GoalState s = new GoalState();
        s.setWaitingUntil(System.currentTimeMillis() + 60_000);
        assertThat(s.isWaiting()).isTrue();
        s.setWaitingUntil(System.currentTimeMillis() - 1);
        assertThat(s.isWaiting()).isFalse();
    }

    @Test
    void isActiveReflectsStatus() {
        GoalState s = new GoalState();
        assertThat(s.isActive()).isTrue(); // 默认 active
        s.setStatus(GoalState.STATUS_PAUSED);
        assertThat(s.isActive()).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalStateSerializationTest`
Expected: FAIL — 新字段/方法不存在。

- [ ] **Step 3: 修改 GoalState.java**

在现有字段后追加（保留原有 9 字段不动），并扩展 `toJson`/`fromJson`：

```java
// 新增 import（文件顶部）
import java.util.ArrayList;
import java.util.List;

// 在 pausedReason 字段后追加新字段
    /** 连续 JSON 解析失败次数，达到上限自动暂停（对标 consecutive_parse_failures）。 */
    private int consecutiveParseFailures;

    /** 用户补充的子目标准则列表（对标 subgoals）。 */
    private List<String> subgoals = new ArrayList<>();

    /** 等待的进程 pid，非空表示 pid 屏障（对标 waiting_on_pid）。 */
    private Integer waitingOnPid;

    /** 等待截止时间戳，>0 表示时间屏障（对标 waiting_until）。 */
    private long waitingUntil;

    /** 等待原因（对标 waiting_reason）。 */
    private String waitingReason;

    /** 等待开始时间戳（对标 waiting_since）。 */
    private long waitingSince;

    /** 完成契约（对标 contract）。 */
    private GoalContract contract = new GoalContract();
```

替换 `toJson()` 方法体（在原 9 个 put 之后追加）：
```java
    public String toJson() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("goal", goal);
        data.put("status", status);
        data.put("turns_used", Integer.valueOf(turnsUsed));
        data.put("max_turns", Integer.valueOf(maxTurns));
        data.put("created_at", Long.valueOf(createdAt));
        data.put("last_turn_at", Long.valueOf(lastTurnAt));
        data.put("last_verdict", lastVerdict);
        data.put("last_reason", lastReason);
        data.put("paused_reason", pausedReason);
        data.put("consecutive_parse_failures", Integer.valueOf(consecutiveParseFailures));
        data.put("subgoals", subgoals == null ? new ArrayList<String>() : subgoals);
        data.put("waiting_on_pid", waitingOnPid);
        data.put("waiting_until", Long.valueOf(waitingUntil));
        data.put("waiting_reason", waitingReason);
        data.put("waiting_since", Long.valueOf(waitingSince));
        data.put("contract", contract == null ? new GoalContract().toMap() : contract.toMap());
        return ONode.serialize(data);
    }
```

替换 `fromJson()` 方法体（追加新字段读取，每个带默认值）：
```java
    @SuppressWarnings("unchecked")
    public static GoalState fromJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        Map<String, Object> data = ONode.deserialize(json, LinkedHashMap.class);
        GoalState state = new GoalState();
        state.setGoal(text(data, "goal"));
        state.setStatus(StrUtil.blankToDefault(text(data, "status"), STATUS_ACTIVE));
        state.setTurnsUsed(intValue(data, "turns_used", 0));
        state.setMaxTurns(intValue(data, "max_turns", DEFAULT_MAX_TURNS));
        state.setCreatedAt(longValue(data, "created_at", 0L));
        state.setLastTurnAt(longValue(data, "last_turn_at", 0L));
        state.setLastVerdict(text(data, "last_verdict"));
        state.setLastReason(text(data, "last_reason"));
        state.setPausedReason(text(data, "paused_reason"));
        state.setConsecutiveParseFailures(intValue(data, "consecutive_parse_failures", 0));
        // subgoals 向前兼容：旧 JSON 没有该键时取空列表
        List<String> sg = new ArrayList<>();
        Object raw = data.get("subgoals");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                String s = o == null ? "" : String.valueOf(o).trim();
                if (StrUtil.isNotBlank(s)) {
                    sg.add(s);
                }
            }
        }
        state.setSubgoals(sg);
        // waiting_on_pid 仅在非空且非 0 时设置
        Object pidRaw = data.get("waiting_on_pid");
        if (pidRaw != null && !"0".equals(String.valueOf(pidRaw))) {
            try {
                state.setWaitingOnPid(Integer.parseInt(String.valueOf(pidRaw)));
            } catch (Exception ignored) {
            }
        }
        state.setWaitingUntil(longValue(data, "waiting_until", 0L));
        state.setWaitingReason(text(data, "waiting_reason"));
        state.setWaitingSince(longValue(data, "waiting_since", 0L));
        // contract 向前兼容：旧 JSON 没有该键时为空契约
        Object contractRaw = data.get("contract");
        if (contractRaw instanceof Map) {
            state.setContract(GoalContract.fromMap((Map<String, Object>) contractRaw));
        }
        return state;
    }
```

在类末尾（`longValue` helper 之后）追加新方法：
```java
    /**
     * 判断目标是否处于 active 状态。
     *
     * @return status 为 active 时返回 true。
     */
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /**
     * 判断是否设置了完成契约。
     *
     * @return 契约非空时返回 true。
     */
    public boolean hasContract() {
        return contract != null && !contract.isEmpty();
    }

    /**
     * 判断是否仍处于等待屏障中（pid 仍存活或未到截止时间）。
     * pid 屏障惰性自清：pid 不存活时返回 false（视为屏障已解除）。
     *
     * @return 仍在等待时返回 true。
     */
    public boolean isWaiting() {
        if (waitingOnPid != null) {
            try {
                if (ProcessHandle.of(waitingOnPid).isPresent()) {
                    return true;
                }
            } catch (Exception ignored) {
                // 无法判定 pid 存活时保守视为仍在等待
                return true;
            }
        }
        if (waitingUntil > 0) {
            return System.currentTimeMillis() < waitingUntil;
        }
        return false;
    }

    /**
     * 清除所有等待屏障字段。
     */
    public void clearWaitBarrier() {
        this.waitingOnPid = null;
        this.waitingUntil = 0L;
        this.waitingReason = null;
        this.waitingSince = 0L;
    }

    /**
     * 追加一条子目标准则。
     *
     * @param subgoal 子目标文本。
     */
    public void addSubgoal(String subgoal) {
        if (subgoals == null) {
            subgoals = new ArrayList<>();
        }
        String t = StrUtil.nullToEmpty(subgoal).trim();
        if (StrUtil.isNotBlank(t)) {
            subgoals.add(t);
        }
    }

    /**
     * 删除第 n 条子目标（1-based）。
     *
     * @param oneBasedIndex 1 起的序号。
     * @return 删除成功返回 true。
     */
    public boolean removeSubgoal(int oneBasedIndex) {
        if (subgoals == null || oneBasedIndex < 1 || oneBasedIndex > subgoals.size()) {
            return false;
        }
        subgoals.remove(oneBasedIndex - 1);
        return true;
    }

    /**
     * 清空所有子目标。
     */
    public void clearSubgoals() {
        if (subgoals != null) {
            subgoals.clear();
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalStateSerializationTest`
Expected: PASS（6 个测试全绿）。

- [ ] **Step 5: 跑现有 goal 相关测试确认无回归**

Run: `mvn -q test -Dtest=CommandEnhancementTest#shouldSupportGoalCommandLifecycle`
Expected: PASS（现有骨架测试不应被破坏）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/jimuqu/solon/claw/goal/GoalState.java src/test/java/com/jimuqu/solon/claw/goal/GoalStateSerializationTest.java
git commit -m "feat: 目标状态扩展契约与等待屏障字段 / Extend goal state with contract and wait barrier fields"
```

---

## Batch 2: LLM judge 与辅助通道 / LLM judge + auxiliary channel (commit 2)

### Task 4: GoalVerdict 增加 WAIT，GoalJudge 接口签名升级

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalVerdict.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalJudge.java`
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeRequest.java`
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeResult.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/HeuristicGoalJudge.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalJudgeResultTest.java`

**Interfaces:**
- Produces: `GoalVerdict.WAIT = "wait"` 常量 + `GoalVerdict.waiting(String reason)` 工厂 + `isWait()`。`GoalJudgeRequest`（`@Getter @Setter @NoArgsConstructor @AllArgsConstructor`：`String goal`、`String lastResponse`、`List<String> subgoals`、`GoalContract contract`）。`GoalJudgeResult`（`@Getter @Setter @NoArgsConstructor @AllArgsConstructor`：`String verdict`、`String reason`、`Integer waitOnPid`、`Long waitForSeconds`；工厂 `done(reason)`/`continueGoal(reason)`/`waitPid(pid,reason)`/`waitSeconds(sec,reason)`；方法 `isDone()`/`isContinue()`/`isWait()`）。`GoalJudge.judge(GoalJudgeRequest)` 返回 `GoalJudgeResult`。`HeuristicGoalJudge` 适配新签名（逻辑不变，DONE/CONTINUE/SKIPPED 映射）。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalJudgeResultTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalJudgeResultTest {
    @Test
    void doneResult() {
        GoalJudgeResult r = GoalJudgeResult.done("完成");
        assertThat(r.isDone()).isTrue();
        assertThat(r.getReason()).isEqualTo("完成");
        assertThat(r.getWaitOnPid()).isNull();
    }

    @Test
    void continueResult() {
        GoalJudgeResult r = GoalJudgeResult.continueGoal("继续");
        assertThat(r.isContinue()).isTrue();
    }

    @Test
    void waitPidResult() {
        GoalJudgeResult r = GoalJudgeResult.waitPid(1234, "等编译");
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitOnPid()).isEqualTo(1234);
        assertThat(r.getWaitForSeconds()).isNull();
    }

    @Test
    void waitSecondsResult() {
        GoalJudgeResult r = GoalJudgeResult.waitSeconds(60L, "等 60 秒");
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitForSeconds()).isEqualTo(60L);
    }

    @Test
    void heuristicJudgeAdaptsToNewSignature() {
        HeuristicGoalJudge j = new HeuristicGoalJudge();
        GoalJudgeRequest req = new GoalJudgeRequest("g", "goal achieved", null, null);
        GoalJudgeResult r = j.judge(req);
        assertThat(r.isDone()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalJudgeResultTest`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现 GoalJudgeRequest / GoalJudgeResult**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeRequest.java
package com.jimuqu.solon.claw.goal;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Goal judge 的入参，携带目标、上轮回复、子目标和契约，供 judge 综合裁决。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoalJudgeRequest {
    /** 当前目标文本。 */
    private String goal;
    /** 上一轮 Agent 的回复内容。 */
    private String lastResponse;
    /** 用户补充的子目标准则（可空）。 */
    private List<String> subgoals;
    /** 完成契约（可空）。 */
    private GoalContract contract;
}
```

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeResult.java
package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Goal judge 的裁决结果：verdict（done/continue/wait）+ reason + 可选的 wait 指令。 */
@Getter
@Setter
@NoArgsConstructor
public class GoalJudgeResult {
    /** 裁决类型：done / continue / wait。 */
    private String verdict;
    /** 裁决原因。 */
    private String reason;
    /** wait 屏障：等待的进程 pid（仅 verdict=wait 时有意义）。 */
    private Integer waitOnPid;
    /** wait 屏障：等待秒数（仅 verdict=wait 时有意义）。 */
    private Long waitForSeconds;

    /** 构造裁决结果。 */
    public GoalJudgeResult(String verdict, String reason, Integer waitOnPid, Long waitForSeconds) {
        this.verdict = verdict;
        this.reason = StrUtil.blankToDefault(reason, "no reason provided");
        this.waitOnPid = waitOnPid;
        this.waitForSeconds = waitForSeconds;
    }

    /** 构造 done 裁决。 */
    public static GoalJudgeResult done(String reason) {
        return new GoalJudgeResult(GoalVerdict.DONE, reason, null, null);
    }

    /** 构造 continue 裁决。 */
    public static GoalJudgeResult continueGoal(String reason) {
        return new GoalJudgeResult(GoalVerdict.CONTINUE, reason, null, null);
    }

    /** 构造 wait-on-pid 裁决。 */
    public static GoalJudgeResult waitPid(int pid, String reason) {
        return new GoalJudgeResult(GoalVerdict.WAIT, reason, pid, null);
    }

    /** 构造 wait-for-seconds 裁决。 */
    public static GoalJudgeResult waitSeconds(long seconds, String reason) {
        return new GoalJudgeResult(GoalVerdict.WAIT, reason, null, seconds);
    }

    /** 是否 done。 */
    public boolean isDone() {
        return GoalVerdict.DONE.equals(verdict);
    }

    /** 是否 continue。 */
    public boolean isContinue() {
        return GoalVerdict.CONTINUE.equals(verdict);
    }

    /** 是否 wait。 */
    public boolean isWait() {
        return GoalVerdict.WAIT.equals(verdict);
    }
}
```

- [ ] **Step 4: 修改 GoalVerdict 加 WAIT**

在 `GoalVerdict.java` 的 `SKIPPED` 常量后追加：
```java
    /** WAIT 的统一常量值。 */
    public static final String WAIT = "wait";
```
在 `skipped` 工厂后追加：
```java
    /**
     * 执行waiting相关逻辑。
     *
     * @param reason 原因参数。
     * @return 返回waiting结果。
     */
    public static GoalVerdict waiting(String reason) {
        return new GoalVerdict(WAIT, reason);
    }

    /**
     * 判断是否Waiting。
     *
     * @return verdict 为 wait 时返回 true。
     */
    public boolean isWait() {
        return WAIT.equals(verdict);
    }
```

- [ ] **Step 5: 修改 GoalJudge 接口签名**

替换 `GoalJudge.java` 的 judge 方法签名为：
```java
package com.jimuqu.solon.claw.goal;

/** 定义 Goal Judge 的抽象契约，供不同运行时实现保持一致行为。 */
public interface GoalJudge {
    /**
     * 综合目标、上轮回复、子目标和契约，裁决目标是否完成、继续还是等待。
     *
     * @param request 裁决请求，含 goal/lastResponse/subgoals/contract。
     * @return 裁决结果。
     */
    GoalJudgeResult judge(GoalJudgeRequest request);
}
```

- [ ] **Step 6: 修改 HeuristicGoalJudge 适配新签名**

替换 `judge` 方法：
```java
    @Override
    public GoalJudgeResult judge(GoalJudgeRequest request) {
        String goal = request == null ? null : request.getGoal();
        String lastResponse = request == null ? null : request.getLastResponse();
        if (StrUtil.isBlank(goal)) {
            return GoalJudgeResult.done("empty goal");
        }
        String text = StrUtil.nullToEmpty(lastResponse).trim();
        if (StrUtil.isBlank(text)) {
            return GoalJudgeResult.continueGoal("empty response");
        }
        String lower = text.toLowerCase();
        if (containsAny(lower, text, BLOCKED_MARKERS)
                || containsAny(lower, text, COMPLETION_MARKERS)) {
            return GoalJudgeResult.done(
                    "response explicitly indicates completion or a user/input blocker");
        }
        return GoalJudgeResult.continueGoal("response did not clearly complete the standing goal");
    }
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalJudgeResultTest`
Expected: PASS。

- [ ] **Step 8: 跑现有 goal 测试确认无回归（此时 GoalService 还没改，可能编译失败——先跳到 Task 5 一起改 GoalService）**

> 注意：改了 GoalJudge 签名后 `GoalService.evaluateAfterTurn` 里的 `judge.judge(state.getGoal(), lastResponse)` 会编译失败。这会在 Task 5 中修复。本 task 的提交与 Task 5 合并提交（因为签名变更跨两个类）。

- [ ] **Step 9: 提交（与 Task 5 一起提交）**

暂不提交，继续 Task 5。

---

### Task 5: GoalPromptTemplates 与 GoalService 适配新 judge 签名

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalPromptTemplates.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalService.java`
- Modify: `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`（如 GoalService 构造签名变化）
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalContinuationPromptTest.java`

**Interfaces:**
- Consumes: `GoalContractParser`（Task 2）、`GoalState`（Task 3）、`GoalJudgeResult`（Task 4）。
- Produces: `GoalPromptTemplates`（常量类：`CONTINUATION_PROMPT_TEMPLATE`（从 GoalService 迁移）、`CONTINUATION_PROMPT_WITH_CONTRACT_TEMPLATE`、`CONTINUATION_PROMPT_WITH_SUBGOALS_TEMPLATE`、`JUDGE_SYSTEM_PROMPT`、`JUDGE_USER_PROMPT_TEMPLATE`）。`GoalService.evaluateAfterTurn` 改用 `judge.judge(GoalJudgeRequest)`；`nextContinuationPrompt(GoalState)` 实现 contract>subgoals>plain 优先级。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalContinuationPromptTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoalContinuationPromptTest {
    @Test
    void plainWhenNoContractNoSubgoals() {
        GoalState s = new GoalState();
        s.setGoal("g");
        String p = new GoalService(null).nextContinuationPrompt(s);
        assertThat(p).contains("Goal: g").doesNotContain("Completion contract").doesNotContain("Additional criteria");
    }

    @Test
    void contractPriorityWhenBothPresent() {
        GoalState s = new GoalState();
        s.setGoal("g");
        s.addSubgoal("子目标1");
        GoalContract c = new GoalContract();
        c.setOutcome("完成");
        s.setContract(c);
        String p = new GoalService(null).nextContinuationPrompt(s);
        assertThat(p).contains("Completion contract:").contains("- Outcome: 完成").contains("- Extra criterion 1: 子目标1");
    }

    @Test
    void subgoalsWhenNoContract() {
        GoalState s = new GoalState();
        s.setGoal("g");
        s.addSubgoal("子目标A");
        String p = new GoalService(null).nextContinuationPrompt(s);
        assertThat(p).contains("Additional criteria").contains("子目标A").doesNotContain("Completion contract");
    }

    @Test
    void nullWhenNotActive() {
        GoalState s = new GoalState();
        s.setGoal("g");
        s.setStatus(GoalState.STATUS_PAUSED);
        assertThat(new GoalService(null).nextContinuationPrompt(s)).isNull();
    }
}
```

> 注意：`new GoalService(null)` 在这些纯提示组装测试里是安全的，因为 `nextContinuationPrompt(GoalState)` 不访问 `sessionRepository`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalContinuationPromptTest`
Expected: FAIL（contract/subgoals 提示还没实现）。

- [ ] **Step 3: 实现 GoalPromptTemplates**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalPromptTemplates.java
package com.jimuqu.solon.claw.goal;

/** 集中存放 goal 续轮、judge、draft 用的提示词模板常量，对标仓库逐字对齐。 */
public final class GoalPromptTemplates {

    private GoalPromptTemplates() {}

    /** 普通续轮提示（无契约无子目标）。 */
    public static final String CONTINUATION_PROMPT_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Continue working toward this goal. Take the next concrete step. "
                    + "If you believe the goal is complete, state so explicitly and stop. "
                    + "If you are blocked and need input from the user, say so clearly and stop.";

    /** 带完成契约的续轮提示：契约块告知 agent "done" 的精确定义。 */
    public static final String CONTINUATION_PROMPT_WITH_CONTRACT_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Completion contract:\n"
                    + "%s\n\n"
                    + "Continue working toward the outcome above. Take the next concrete step. "
                    + "Stay within the stated boundaries and do not violate the constraints. "
                    + "Before claiming the goal is done, satisfy the Verification criterion and "
                    + "show the concrete evidence (command output, file contents, test result). "
                    + "If you hit the stated stop condition or are otherwise blocked and need "
                    + "user input, say so clearly and stop.";

    /** 带子目标的续轮提示：逐条呈现用户补充的准则。 */
    public static final String CONTINUATION_PROMPT_WITH_SUBGOALS_TEMPLATE =
            "[Continuing toward your standing goal]\n"
                    + "Goal: %s\n\n"
                    + "Additional criteria the user added mid-loop:\n"
                    + "%s\n\n"
                    + "Continue working toward the goal AND all additional criteria. Take "
                    + "the next concrete step. If you believe the goal and every "
                    + "additional criterion are complete, state so explicitly and stop. "
                    + "If you are blocked and need input from the user, say so clearly "
                    + "and stop.";

    /** Judge 系统提示：定义 DONE/CONTINUE/WAIT 裁决契约。 */
    public static final String JUDGE_SYSTEM_PROMPT =
            "You are a strict goal-completion judge. Decide if the standing goal is DONE, "
                    + "should CONTINUE, or should WAIT. Output ONLY JSON:\n"
                    + "{\"verdict\": \"done\", \"reason\": \"...\"}\n"
                    + "{\"verdict\": \"continue\", \"reason\": \"...\"}\n"
                    + "{\"verdict\": \"wait\", \"wait_on_pid\": <int>, \"reason\": \"...\"}\n"
                    + "{\"verdict\": \"wait\", \"wait_for_seconds\": <int>, \"reason\": \"...\"}\n"
                    + "DONE: the last response satisfies the goal (and contract Verification if present). "
                    + "CONTINUE: more work needed. WAIT: genuinely blocked on a running process or time. "
                    + "If you cannot decide, return continue.";

    /** Judge 用户提示模板。 */
    public static final String JUDGE_USER_PROMPT_TEMPLATE =
            "Goal: %s\n\nLast assistant response:\n%s\n\nReturn your JSON verdict.";
}
```

- [ ] **Step 4: 修改 GoalService.evaluateAfterTurn 适配新签名 + 重写 nextContinuationPrompt**

在 `GoalService.java` 中：
1. 删除 `CONTINUATION_PROMPT_TEMPLATE` 常量（已迁移到 GoalPromptTemplates）。
2. 修改 `evaluateAfterTurn` 第 199 行附近：
```java
        state.setTurnsUsed(state.getTurnsUsed() + 1);
        state.setLastTurnAt(System.currentTimeMillis());
        GoalJudgeRequest judgeReq =
                new GoalJudgeRequest(
                        state.getGoal(), lastResponse, state.getSubgoals(), state.getContract());
        GoalJudgeResult result = judge.judge(judgeReq);
        state.setLastVerdict(result.getVerdict());
        state.setLastReason(result.getReason());

        if (result.isDone()) {
            state.setStatus(GoalState.STATUS_DONE);
            save(session, state);
            decision.setStatus(GoalState.STATUS_DONE);
            decision.setVerdict(GoalVerdict.DONE);
            decision.setReason(result.getReason());
            decision.setMessage("✓ Goal achieved: " + result.getReason());
            return decision;
        }

        if (result.isWait()) {
            // wait 屏障：设 pid 或时间，不消耗"已完成轮次"判定，但计入 turns
            if (result.getWaitOnPid() != null) {
                state.setWaitingOnPid(result.getWaitOnPid());
                state.setWaitingSince(System.currentTimeMillis());
            }
            if (result.getWaitForSeconds() != null && result.getWaitForSeconds() > 0) {
                state.setWaitingUntil(System.currentTimeMillis() + result.getWaitForSeconds() * 1000L);
                state.setWaitingSince(System.currentTimeMillis());
            }
            state.setWaitingReason(result.getReason());
            save(session, state);
            decision.setStatus(GoalState.STATUS_ACTIVE);
            decision.setVerdict(GoalVerdict.WAIT);
            decision.setReason(result.getReason());
            String tgt =
                    result.getWaitOnPid() != null
                            ? "pid " + result.getWaitOnPid()
                            : (result.getWaitForSeconds() != null ? result.getWaitForSeconds() + "s" : "");
            decision.setMessage("⏳ Goal parked — waiting on " + tgt + ": " + result.getReason());
            // 等待期间不发起续轮；屏障解除后由下一次 evaluateAfterTurn 推进
            return decision;
        }

        // CONTINUE 分支：保留原有 budget-exhausted 检查
        if (state.getTurnsUsed() >= state.getMaxTurns()) {
```
3. 替换 `nextContinuationPrompt(GoalState)`（优先级 contract>subgoals>plain）：
```java
    public String nextContinuationPrompt(GoalState state) {
        if (state == null || !GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return null;
        }
        if (state.hasContract()) {
            String contractBlock = state.getContract().renderBlock();
            if (state.getSubgoals() != null && !state.getSubgoals().isEmpty()) {
                StringBuilder extra = new StringBuilder();
                for (int i = 0; i < state.getSubgoals().size(); i++) {
                    extra.append("\n- Extra criterion ")
                            .append(i + 1)
                            .append(": ")
                            .append(state.getSubgoals().get(i));
                }
                contractBlock = contractBlock + extra.toString();
            }
            return String.format(
                    GoalPromptTemplates.CONTINUATION_PROMPT_WITH_CONTRACT_TEMPLATE,
                    state.getGoal(),
                    contractBlock);
        }
        if (state.getSubgoals() != null && !state.getSubgoals().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < state.getSubgoals().size(); i++) {
                if (i > 0) {
                    sb.append("\n");
                }
                sb.append("- ").append(state.getSubgoals().get(i));
            }
            return String.format(
                    GoalPromptTemplates.CONTINUATION_PROMPT_WITH_SUBGOALS_TEMPLATE,
                    state.getGoal(),
                    sb.toString());
        }
        return String.format(GoalPromptTemplates.CONTINUATION_PROMPT_TEMPLATE, state.getGoal());
    }
```
4. `evaluateAfterTurn` 开头追加 wait 屏障惰性检查（在 active 判定之后、turnsUsed++ 之前）：
```java
        // 等待屏障：仍在等待时不消耗轮次、不调 judge，直接 quiesce
        if (state.isWaiting()) {
            decision.setStatus(GoalState.STATUS_ACTIVE);
            decision.setVerdict(GoalVerdict.WAIT);
            decision.setReason("waiting on barrier");
            decision.setShouldContinue(false);
            decision.setMessage("⏳ Goal parked — waiting: " + state.getWaitingReason());
            return decision;
        }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalContinuationPromptTest,GoalJudgeResultTest,GoalStateSerializationTest,GoalContractTest,GoalContractParserTest`
Expected: PASS。

Run: `mvn -q test -Dtest=CommandEnhancementTest#shouldSupportGoalCommandLifecycle`
Expected: PASS（现有测试用 HeuristicGoalJudge，行为不变）。

- [ ] **Step 6: 全量编译确认无回归**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交（Task 4 + Task 5 一起）**

```bash
git add src/main/java/com/jimuqu/solon/claw/goal/GoalVerdict.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalJudge.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeRequest.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeResult.java \
  src/main/java/com/jimuqu/solon/claw/goal/HeuristicGoalJudge.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalPromptTemplates.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalService.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalJudgeResultTest.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalContinuationPromptTest.java
git commit -m "feat: LLM 目标裁决器接口与续轮提示优先级 / LLM goal judge interface with continuation prompt priority"
```

> **提示：** 此提交完成的是 LLM judge 的**接口层 + 启发式适配 + 续轮提示**，真正的 LLM-backed 实现（`LlmGoalJudge`）在 Task 7，配置接线在 Task 8。这样保证每步都能编译通过、测试通过。

---

### Task 6: AppConfig 新增 GoalConfig

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/config/AppConfig.java`
- Modify: `config.example.yml`

**Interfaces:**
- Produces: `AppConfig.GoalConfig`（`maxTurns=20`、`judgeTimeoutSeconds=30`、`judgeMaxTokens=4096`、`judgeProvider=""`、`judgeModel=""`、`maxConsecutiveParseFailures=3`）；`AppConfig.goal` 字段 + getter/setter + `copyGoal`。

- [ ] **Step 1: 修改 AppConfig.java**

在 `LearningConfig` 类之后追加：
```java
    /** goal 目标循环相关配置。 */
    public static class GoalConfig {
        /** 默认轮次预算上限。 */
        private int maxTurns = 20;

        /** judge 辅助调用超时秒数。 */
        private int judgeTimeoutSeconds = 30;

        /** judge 最大 token 数。 */
        private int judgeMaxTokens = 4096;

        /** judge 使用的 provider，留空则用会话默认。 */
        private String judgeProvider = "";

        /** judge 使用的 model，留空则用会话默认。 */
        private String judgeModel = "";

        /** 连续 JSON 解析失败上限，超过自动暂停。 */
        private int maxConsecutiveParseFailures = 3;
    }
```

在 AppConfig 类的字段区（`learning` 旁边）追加：
```java
    /** goal 目标循环配置。 */
    private GoalConfig goal = new GoalConfig();
```

在 `copyFrom`/copy 方法区追加（参考 `copyLearning`）：
```java
    /**
     * 复制 goal 配置。
     *
     * @param other 源配置。
     */
    private void copyGoal(GoalConfig other) {
        this.goal.setMaxTurns(other.getMaxTurns());
        this.goal.setJudgeTimeoutSeconds(other.getJudgeTimeoutSeconds());
        this.goal.setJudgeMaxTokens(other.getJudgeMaxTokens());
        this.goal.setJudgeProvider(other.getJudgeProvider());
        this.goal.setJudgeModel(other.getJudgeModel());
        this.goal.setMaxConsecutiveParseFailures(other.getMaxConsecutiveParseFailures());
    }
```

> 实现者注意：在 AppConfig 的 `copyFrom(AppConfig other)` 或等价合并方法里，**在 `copyLearning` 调用旁边**追加一行 `copyGoal(other.getGoal());`。先用 `grep -n "copyLearning" AppConfig.java` 定位调用点。

- [ ] **Step 2: 修改 config.example.yml**

在 `solonclaw:` 段下、`learning:` 旁边追加（带中文注释）：
```yaml
  # goal 目标循环配置：跨轮长目标 + judge 驱动自动续轮
  goal:
    maxTurns: 20              # 默认轮次预算上限，超过自动暂停
    judgeTimeoutSeconds: 30   # judge 辅助模型调用超时（秒）
    judgeMaxTokens: 4096      # judge 最大 token
    judgeProvider: ""         # judge 使用的 provider，留空用会话默认
    judgeModel: ""            # judge 使用的 model，留空用会话默认
    maxConsecutiveParseFailures: 3  # 连续 JSON 解析失败上限，超过自动暂停
```

- [ ] **Step 3: 编译确认**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/jimuqu/solon/claw/config/AppConfig.java config.example.yml
git commit -m "feat: 新增 goal 配置段 / Add goal configuration section"
```

---

## Batch 3: LLM judge 实现 + 续轮循环 wait 屏障 / LLM judge impl + continuation loop (commit 3)

### Task 7: LlmGoalJudge 实现（auxiliary 通道）

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/goal/LlmGoalJudge.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/LlmGoalJudgeTest.java`

**Interfaces:**
- Consumes: `LlmGateway.chat(session, systemPrompt, userMessage, Collections.emptyList())`、`GoalPromptTemplates.JUDGE_*`、`AppConfig.GoalConfig`。
- Produces: `LlmGoalJudge`（构造注入 `LlmGateway`、`AppConfig.GoalConfig`）。`judge(GoalJudgeRequest)` 内部：用 bounded executor + `Future.get(timeout)` 调 `chat()`，解析 JSON 得 verdict；**任何异常/超时 → fail-open 返回 `continueGoal`**；JSON 不可解析但模型有返回 → 抛 `GoalJudgeUnparseableException`（自定义 RuntimeException）让上层累计 parseFailures。

> 实现者注意：解析逻辑用一个内部方法 `parseJudgeJson(String raw)`：剥离 ```json fence，用 `ONode.deserialize(raw, LinkedHashMap.class)`，读 `verdict/reason/wait_on_pid/wait_for_seconds`。`verdict` 不在 {done,continue,wait} 时视为 continue。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/LlmGoalJudgeTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmGoalJudgeTest {
    /** 可编程返回内容的 LlmGateway，用于模拟 judge 响应。 */
    static class ScriptedLlmGateway extends FakeLlmGateway {
        String scriptedResponse;

        @Override
        public LlmResult chat(
                SessionRecord session, String systemPrompt, String userMessage, List<Object> toolObjects)
                throws Exception {
            if (scriptedResponse != null) {
                LlmResult r = new LlmResult();
                r.setAssistantMessage(org.noear.solon.ai.chat.message.ChatMessage.ofAssistant(scriptedResponse));
                r.setRawResponse(scriptedResponse);
                return r;
            }
            return super.chat(session, systemPrompt, userMessage, toolObjects);
        }
    }

    private AppConfig.GoalConfig goalConfig() {
        AppConfig.GoalConfig c = new AppConfig.GoalConfig();
        c.setJudgeTimeoutSeconds(5);
        return c;
    }

    @Test
    void parsesDoneVerdict() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"done\",\"reason\":\"all tests pass\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isDone()).isTrue();
        assertThat(r.getReason()).isEqualTo("all tests pass");
    }

    @Test
    void parsesWaitPidVerdict() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "{\"verdict\":\"wait\",\"wait_on_pid\":1234,\"reason\":\"编译中\"}";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isWait()).isTrue();
        assertThat(r.getWaitOnPid()).isEqualTo(1234);
    }

    @Test
    void stripsJsonFence() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "```json\n{\"verdict\":\"continue\",\"reason\":\"more work\"}\n```";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isContinue()).isTrue();
    }

    @Test
    void failsOpenOnException() {
        // 用一个抛异常的 gateway
        LlmGateway broken =
                new LlmGateway() {
                    @Override
                    public LlmResult chat(
                            SessionRecord s, String sp, String um, List<Object> tools) throws Exception {
                        throw new RuntimeException("network down");
                    }

                    @Override
                    public LlmResult resume(SessionRecord s, String sp, List<Object> tools) {
                        return null;
                    }
                };
        LlmGoalJudge j = new LlmGoalJudge(broken, goalConfig());
        GoalJudgeResult r = j.judge(new GoalJudgeRequest("g", "resp", null, null));
        assertThat(r.isContinue()).isTrue(); // fail-open
    }

    @Test
    void unparseableJsonThrowsForBackstop() {
        ScriptedLlmGateway gw = new ScriptedLlmGateway();
        gw.scriptedResponse = "not json at all";
        LlmGoalJudge j = new LlmGoalJudge(gw, goalConfig());
        // 模型有返回但不可解析 → 抛 GoalJudgeUnparseableException，让上层累计 parseFailures
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> j.judge(new GoalJudgeRequest("g", "resp", null, null)))
                .isInstanceOf(GoalJudgeUnparseableException.class);
    }
}
```

> 实现者注意：`FakeLlmGateway` 的 `chat` 返回的 `LlmResult.getRawResponse()`/`getAssistantMessage().content()` 是判断响应来源。`LlmGoalJudge` 应优先从 `result.getRawResponse()` 取文本，空则取 `result.getAssistantMessage().content()`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=LlmGoalJudgeTest`
Expected: FAIL — 类不存在。

- [ ] **Step 3: 实现 GoalJudgeUnparseableException + LlmGoalJudge**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeUnparseableException.java
package com.jimuqu.solon.claw.goal;

/** judge 模型有返回但 JSON 不可解析时抛出，供上层累计 consecutiveParseFailures 后自动暂停。 */
public class GoalJudgeUnparseableException extends RuntimeException {
    /** 构造异常。 */
    public GoalJudgeUnparseableException(String message) {
        super(message);
    }
}
```

```java
// src/main/java/com/jimuqu/solon/claw/goal/LlmGoalJudge.java
package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-backed goal judge：复用 LlmGateway 的非流式 auxiliary 通道裁决目标完成度。
 * 任何异常/超时 fail-open 返回 continue；JSON 不可解析抛 GoalJudgeUnparseableException。
 */
public class LlmGoalJudge implements GoalJudge {
    private static final Logger log = LoggerFactory.getLogger(LlmGoalJudge.class);

    /** LLM 网关。 */
    private final LlmGateway llmGateway;
    /** goal 配置（超时/token/provider）。 */
    private final AppConfig.GoalConfig config;
    /** auxiliary 调用用的有界线程池。 */
    private final ExecutorService executor =
            BoundedExecutorFactory.fixed("goal-judge-auxiliary", 1, 8);

    /** 构造 judge。 */
    public LlmGoalJudge(LlmGateway llmGateway, AppConfig.GoalConfig config) {
        this.llmGateway = llmGateway;
        this.config = config == null ? new AppConfig.GoalConfig() : config;
    }

    @Override
    public GoalJudgeResult judge(GoalJudgeRequest request) {
        final String systemPrompt = GoalPromptTemplates.JUDGE_SYSTEM_PROMPT;
        final String userMessage =
                String.format(
                        GoalPromptTemplates.JUDGE_USER_PROMPT_TEMPLATE,
                        StrUtil.nullToEmpty(request.getGoal()),
                        StrUtil.nullToEmpty(request.getLastResponse()));
        Future<LlmResult> future =
                executor.submit(
                        () -> {
                            // judge 不绑定真实会话历史，用空 SessionRecord 避免污染
                            SessionRecord judgeSession = new SessionRecord();
                            judgeSession.setSessionId("goal-judge");
                            return llmGateway.chat(
                                    judgeSession, systemPrompt, userMessage, Collections.emptyList());
                        });
        try {
            int timeoutSec = config.getJudgeTimeoutSeconds() > 0 ? config.getJudgeTimeoutSeconds() : 30;
            LlmResult result = future.get(timeoutSec, TimeUnit.SECONDS);
            String raw = extractText(result);
            return parseJudgeJson(raw);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("goal judge timeout, fail-open to continue");
            return GoalJudgeResult.continueGoal("judge timeout, fail-open");
        } catch (Exception e) {
            log.warn("goal judge call failed, fail-open to continue: {}", e.getMessage());
            return GoalJudgeResult.continueGoal("judge unavailable, fail-open");
        }
    }

    /** 从 LlmResult 提取文本响应。 */
    private String extractText(LlmResult result) {
        if (result == null) {
            return "";
        }
        if (StrUtil.isNotBlank(result.getRawResponse())) {
            return result.getRawResponse();
        }
        if (result.getAssistantMessage() != null) {
            return StrUtil.nullToEmpty(result.getAssistantMessage().content());
        }
        return "";
    }

    /** 解析 judge 的 JSON 裁决，剥离 markdown fence。 */
    @SuppressWarnings("unchecked")
    private GoalJudgeResult parseJudgeJson(String raw) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            throw new GoalJudgeUnparseableException("empty judge response");
        }
        // 剥离 ```json ... ``` fence
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        Map<String, Object> data;
        try {
            data = ONode.deserialize(text, LinkedHashMap.class);
        } catch (Exception e) {
            throw new GoalJudgeUnparseableException("judge response is not valid JSON: " + abbreviate(text));
        }
        String verdict = String.valueOf(data.getOrDefault("verdict", "")).toLowerCase();
        String reason = data.get("reason") == null ? "" : String.valueOf(data.get("reason"));
        if (GoalVerdict.DONE.equals(verdict)) {
            return GoalJudgeResult.done(reason);
        }
        if (GoalVerdict.WAIT.equals(verdict)) {
            Object pid = data.get("wait_on_pid");
            Object sec = data.get("wait_for_seconds");
            if (pid != null) {
                try {
                    return GoalJudgeResult.waitPid(Integer.parseInt(String.valueOf(pid)), reason);
                } catch (Exception ignored) {
                }
            }
            if (sec != null) {
                try {
                    return GoalJudgeResult.waitSeconds(Long.parseLong(String.valueOf(sec)), reason);
                } catch (Exception ignored) {
                }
            }
            return GoalJudgeResult.continueGoal("wait verdict without valid barrier, fail-open");
        }
        // continue 或未知 verdict 一律视为 continue
        return GoalJudgeResult.continueGoal(reason);
    }

    /** 截断文本用于错误日志。 */
    private String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=LlmGoalJudgeTest`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 提交（与 Task 8 一起，因为 bean 接线依赖它）**

暂不提交。

---

### Task 8: GatewayConfiguration 接线 LlmGoalJudge + GoalService 连续解析失败自动暂停

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/bootstrap/GatewayConfiguration.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalService.java`（`evaluateAfterTurn` 加 parseFailures 累计 + 自动暂停）
- Modify: `src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java`（goalService 构造改注入 config + llmGateway）

**Interfaces:**
- Consumes: `LlmGoalJudge`（Task 7）、`AppConfig.GoalConfig`（Task 6）、`GoalJudgeUnparseableException`。
- Produces: `GoalService` 新构造 `GoalService(SessionRepository, GoalJudge, AppConfig.GoalConfig)`；`evaluateAfterTurn` 捕获 `GoalJudgeUnparseableException` → `consecutiveParseFailures++`，达上限（config.maxConsecutiveParseFailures）自动 pause。

- [ ] **Step 1: 修改 GoalService 构造与 evaluateAfterTurn**

在 `GoalService.java`：
1. 新增字段 `private final AppConfig.GoalConfig goalConfig;`
2. 新增构造：
```java
    /**
     * 创建 Goal 服务实例，注入 judge 与 goal 配置。
     *
     * @param sessionRepository 会话仓储。
     * @param judge 裁决器。
     * @param goalConfig goal 配置。
     */
    public GoalService(
            SessionRepository sessionRepository,
            GoalJudge judge,
            AppConfig.GoalConfig goalConfig) {
        this.sessionRepository = sessionRepository;
        this.judge = judge == null ? new HeuristicGoalJudge() : judge;
        this.goalConfig = goalConfig == null ? new AppConfig.GoalConfig() : goalConfig;
        this.defaultMaxTurns = this.goalConfig.getMaxTurns() > 0
                ? this.goalConfig.getMaxTurns()
                : GoalState.DEFAULT_MAX_TURNS;
    }
```
3. 在 `evaluateAfterTurn` 把 `GoalJudgeResult result = judge.judge(judgeReq);` 包进 try/catch：
```java
        GoalJudgeResult result;
        try {
            result = judge.judge(judgeReq);
            state.setConsecutiveParseFailures(0); // 成功裁决（含 API 错误 fail-open）重置计数
        } catch (GoalJudgeUnparseableException e) {
            state.setConsecutiveParseFailures(state.getConsecutiveParseFailures() + 1);
            int limit = goalConfig.getMaxConsecutiveParseFailures() > 0
                    ? goalConfig.getMaxConsecutiveParseFailures()
                    : 3;
            if (state.getConsecutiveParseFailures() >= limit) {
                state.setStatus(GoalState.STATUS_PAUSED);
                state.setPausedReason("judge parse failures (" + limit + ")");
                save(session, state);
                decision.setStatus(GoalState.STATUS_PAUSED);
                decision.setVerdict("skipped");
                decision.setReason(e.getMessage());
                decision.setMessage("⏸ Goal paused — the judge model isn't returning valid JSON "
                        + "(" + limit + " times). Check auxiliary.goal_judge config or /goal clear.");
                return decision;
            }
            // 未达上限：保存计数，fail-open continue
            save(session, state);
            decision.setStatus(GoalState.STATUS_ACTIVE);
            decision.setShouldContinue(true);
            decision.setContinuationPrompt(nextContinuationPrompt(state));
            decision.setVerdict(GoalVerdict.CONTINUE);
            decision.setReason("judge parse failure " + state.getConsecutiveParseFailures() + "/" + limit + ", fail-open");
            decision.setMessage("↻ Continuing toward goal (judge parse fail "
                    + state.getConsecutiveParseFailures() + "/" + limit + ")");
            return decision;
        }
```
4. 新增 import：`import com.jimuqu.solon.claw.config.AppConfig;`

- [ ] **Step 2: 修改 GatewayConfiguration 的 goalService bean**

```java
    @Bean
    public GoalService goalService(
            SessionRepository sessionRepository,
            LlmGateway llmGateway,
            AppConfig appConfig) {
        LlmGoalJudge llmJudge = new LlmGoalJudge(llmGateway, appConfig.getGoal());
        return new GoalService(sessionRepository, llmJudge, appConfig.getGoal());
    }
```

> 实现者注意：先 `grep -n "import.*LlmGateway\|import.*AppConfig" GatewayConfiguration.java` 确认 import 是否已有；用 `grep -n "LlmGateway\|AppConfig" GatewayConfiguration.java` 看 bean 方法里能否直接注入（这两个 bean 在该配置类里通常已被其他 bean 方法消费，可直接作参数注入）。

- [ ] **Step 3: 修改 TestEnvironment 的 goalService 构造**

定位 `TestEnvironment.java:424` 的 `GoalService goalService = new GoalService(sessionRepository);`，改为：
```java
        GoalService goalService = new GoalService(sessionRepository, new HeuristicGoalJudge(), config.getGoal());
```
> 用 HeuristicGoalJudge 而非 LlmGoalJudge，因为现有测试基于启发式行为；LLM judge 单独在 LlmGoalJudgeTest 里验证。

- [ ] **Step 4: 编译 + 全量 goal 测试**

Run: `mvn -q test -Dtest=LlmGoalJudgeTest,GoalContinuationPromptTest,GoalJudgeResultTest,GoalStateSerializationTest,GoalContractTest,GoalContractParserTest,CommandEnhancementTest`
Expected: PASS。

- [ ] **Step 5: 提交（Task 7 + Task 8）**

```bash
git add src/main/java/com/jimuqu/solon/claw/goal/LlmGoalJudge.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalJudgeUnparseableException.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalService.java \
  src/main/java/com/jimuqu/solon/claw/bootstrap/GatewayConfiguration.java \
  src/test/java/com/jimuqu/solon/claw/goal/LlmGoalJudgeTest.java \
  src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java
git commit -m "feat: 接入 LLM 目标裁决器与连续解析失败自动暂停 / Wire LLM goal judge with auto-pause on parse failures"
```

---

## Batch 4: 命令面 /goal 扩展 + /subgoal + GoalMigrationSupport (commit 4)

### Task 9: GoalService subgoal/wait/unwait/show 方法

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalServiceSubgoalsTest.java`, `GoalServiceWaitBarrierTest.java`

**Interfaces:**
- Produces: `GoalService` 新方法 `addSubgoal(SessionRecord, String)`、`removeSubgoal(SessionRecord, int)`、`clearSubgoals(SessionRecord)`、`List<String> listSubgoals(SessionRecord)`、`waitOnPid(SessionRecord, int, String)`、`stopWaiting(SessionRecord)`、`String renderContract(SessionRecord)`。`set` 方法增强：接受 `GoalContract`（重载 `set(session, headline, contract, maxTurns)`）。

- [ ] **Step 1: 写失败测试**（GoalServiceSubgoalsTest + GoalServiceWaitBarrierTest）

> 实现者注意：这些测试需要一个真实的 `SessionRepository`。参考 `CommandEnhancementTest` 用 `TestEnvironment` 的 `sessionRepository`，或直接用 `SqliteDatabase` + `SqliteSessionRepository` 构造最小 fixture。优先用 `TestEnvironment.withFakeLlm().sessionRepository` 并手动 `bindNewSession`。

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalServiceSubgoalsTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalServiceSubgoalsTest {
    @Test
    void addRemoveClearSubgoals() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("subgoal-chat");
        AppConfig.GoalConfig cfg = new AppConfig.GoalConfig();
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), cfg);
        svc.set(session, "完成测试", 5);

        svc.addSubgoal(session, "覆盖 goal 包");
        svc.addSubgoal(session, "覆盖工具");
        assertThat(svc.listSubgoals(session)).containsExactly("覆盖 goal 包", "覆盖工具");

        svc.removeSubgoal(session, 1);
        assertThat(svc.listSubgoals(session)).containsExactly("覆盖工具");

        svc.clearSubgoals(session);
        assertThat(svc.listSubgoals(session)).isEmpty();
    }
}
```

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalServiceWaitBarrierTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalServiceWaitBarrierTest {
    @Test
    void waitOnPidAndStopWaiting() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("wait-chat");
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        svc.set(session, "g", 5);

        svc.waitOnPid(session, Integer.MAX_VALUE, "等编译"); // 死 pid 立即解除
        // 重读状态确认持久化
        GoalState reloaded = svc.get(session);
        assertThat(reloaded.getWaitingReason()).isNull(); // isWaiting 在 set 时已惰性清，但字段可能还在；关键看 evaluateAfterTurn 行为
    }

    @Test
    void stopWaitingClearsBarrier() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord session = env.sessionRepository.bindNewSession("wait-chat2");
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        svc.set(session, "g", 5);
        svc.waitOnPid(session, 99999, "等编译");
        svc.stopWaiting(session);
        GoalState reloaded = svc.get(session);
        assertThat(reloaded.getWaitingOnPid()).isNull();
        assertThat(reloaded.getWaitingUntil()).isEqualTo(0L);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalServiceSubgoalsTest,GoalServiceWaitBarrierTest`
Expected: FAIL — 方法不存在。

- [ ] **Step 3: 实现 GoalService 新方法**

在 `GoalService.java` 追加（`clear` 方法之后）：
```java
    /** set 的 contract 重载：带完成契约设置目标。 */
    public GoalState set(SessionRecord session, String headline, GoalContract contract, int maxTurns)
            throws Exception {
        String text = StrUtil.nullToEmpty(headline).trim();
        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("goal text is empty");
        }
        GoalState state = new GoalState();
        state.setGoal(text);
        state.setStatus(GoalState.STATUS_ACTIVE);
        state.setTurnsUsed(0);
        state.setMaxTurns(maxTurns > 0 ? maxTurns : defaultMaxTurns);
        state.setCreatedAt(System.currentTimeMillis());
        state.setLastTurnAt(0L);
        if (contract != null) {
            state.setContract(contract);
        }
        save(session, state);
        return state;
    }

    /** 追加子目标准则。 */
    public void addSubgoal(SessionRecord session, String text) throws Exception {
        GoalState state = get(session);
        if (state == null || !state.isActive()) {
            return;
        }
        state.addSubgoal(text);
        save(session, state);
    }

    /** 删除第 n 条子目标（1-based）。 */
    public boolean removeSubgoal(SessionRecord session, int oneBasedIndex) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return false;
        }
        boolean removed = state.removeSubgoal(oneBasedIndex);
        if (removed) {
            save(session, state);
        }
        return removed;
    }

    /** 清空子目标。 */
    public void clearSubgoals(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return;
        }
        state.clearSubgoals();
        save(session, state);
    }

    /** 列出子目标。 */
    public java.util.List<String> listSubgoals(SessionRecord session) {
        GoalState state = get(session);
        return state == null || state.getSubgoals() == null
                ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(state.getSubgoals());
    }

    /** 设置 pid 等待屏障。 */
    public void waitOnPid(SessionRecord session, int pid, String reason) throws Exception {
        GoalState state = get(session);
        if (state == null || !state.isActive()) {
            return;
        }
        // 校验 pid 合法性（存在则更佳，这里只校验 > 0）
        if (pid <= 0) {
            throw new IllegalArgumentException("invalid pid: " + pid);
        }
        state.setWaitingOnPid(pid);
        state.setWaitingReason(StrUtil.blankToDefault(reason, "waiting on pid " + pid));
        state.setWaitingSince(System.currentTimeMillis());
        save(session, state);
    }

    /** 清除等待屏障。 */
    public void stopWaiting(SessionRecord session) throws Exception {
        GoalState state = get(session);
        if (state == null) {
            return;
        }
        state.clearWaitBarrier();
        save(session, state);
    }

    /** 渲染当前契约块（/goal show 用）。 */
    public String renderContract(SessionRecord session) {
        GoalState state = get(session);
        if (state == null || state.getStatus() == null
                || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return "(no active goal)";
        }
        if (!state.hasContract()) {
            return "(no contract set)";
        }
        return state.getContract().renderBlock();
    }
```
并修改 `pause`/`resume`/`clear` 方法：在改 status 后调用 `state.clearWaitBarrier()`（对标仓库 pause/resume/stop_waiting 都清屏障）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalServiceSubgoalsTest,GoalServiceWaitBarrierTest`
Expected: PASS。

- [ ] **Step 5: 提交（与 Task 10/11/12 一起）**

暂不提交。

---

### Task 10: 注册 /subgoal 命令 + GatewayCommandConstants

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/command/CommandRegistry.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/support/constants/GatewayCommandConstants.java`

**Interfaces:**
- Produces: `CommandRegistry` 注册 `core("subgoal", "agent", "管理当前目标的补充准则")`；`GatewayCommandConstants.COMMAND_SUBGOAL = "subgoal"`、`SLASH_SUBGOAL`。

- [ ] **Step 1: 修改 CommandRegistry**

在静态块的 `goal` 注册行（`register(core("goal", ...))`）后追加：
```java
        register(core("subgoal", "agent", "管理当前目标的补充准则"));
```

- [ ] **Step 2: 修改 GatewayCommandConstants**

在 `COMMAND_GOAL` 常量附近追加：
```java
    /** subgoal 命令名。 */
    String COMMAND_SUBGOAL = "subgoal";
```
在 `SLASH_GOAL` 附近追加：
```java
    /** subgoal 斜杠命令。 */
    String SLASH_SUBGOAL = COMMAND_PREFIX + COMMAND_SUBGOAL;
```

- [ ] **Step 3: 编译确认**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

---

### Task 11: handleGoal 扩展 + handleSubgoal + dispatch

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/SlashCommandHelpRenderer.java`（如存在）
- Modify: `src/main/java/com/jimuqu/solon/claw/cli/LocalTerminalHelp.java`
- Test: `CommandEnhancementTest` 扩展或新增 `GoalCommandSurfaceTest`

**Interfaces:**
- Consumes: `GoalContractParser`（Task 2）、`GoalService` 新方法（Task 9）。
- Produces: `handleGoal` 扩展支持 `show`/`draft`(stub 到 Task 13)/`stop`/`done`/`wait`/`unwait`/`<text with contract>`；新增 `handleSubgoal`；dispatch 链加 `COMMAND_SUBGOAL`。

- [ ] **Step 1: 写失败测试**

在 `CommandEnhancementTest` 加测试方法（或新建 `GoalCommandSurfaceTest`）：
```java
    @Test
    void shouldSupportSubgoalCommand() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "sg-chat", "sg-user", "/goal 完成测试 --max 5");
        env.commandService.handle(message, "/goal 完成测试 --max 5");

        GatewayReply add = env.commandService.handle(message, "/subgoal 覆盖 goal 包");
        assertThat(add.getContent()).contains("覆盖 goal 包");

        GatewayReply list = env.commandService.handle(message, "/subgoal");
        assertThat(list.getContent()).contains("覆盖 goal 包");

        GatewayReply remove = env.commandService.handle(message, "/subgoal remove 1");
        assertThat(remove.getContent()).contains("removed").contains("cleared").contains("No subgoal"); // 任一

        GatewayReply clear = env.commandService.handle(message, "/subgoal clear");
        assertThat(clear.getContent()).contains("cleared").contains("No subgoal");
    }

    @Test
    void shouldSupportGoalShowAndStopDoneAliases() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        GatewayMessage message =
                new GatewayMessage(PlatformType.MEMORY, "g2-chat", "g2-user", "/goal 目标A --max 3");
        env.commandService.handle(message, "/goal 目标A --max 3");

        GatewayReply show = env.commandService.handle(message, "/goal show");
        assertThat(show.getContent()).contains("目标A");

        GatewayReply stop = env.commandService.handle(message, "/goal stop");
        assertThat(stop.getContent()).contains("cleared");

        GatewayReply doneAlias = env.commandService.handle(message, "/goal done");
        // 已 cleared 再 done 也应返回 No active goal，不报错
        assertThat(doneAlias.getContent()).contains("No active goal").contains("cleared");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=CommandEnhancementTest#shouldSupportSubgoalCommand`
Expected: FAIL — `/subgoal` 未 dispatch。

- [ ] **Step 3: 修改 DefaultCommandService**

1. dispatch 链（`handle` 方法内，`COMMAND_GOAL` 分支后）追加：
```java
        if (GatewayCommandConstants.COMMAND_SUBGOAL.equals(command)) {
            return handleSubgoal(message, args);
        }
```
2. 重写 `handleGoal`（在现有基础上扩展）：
```java
    private GatewayReply handleGoal(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        // 无参或 status：状态行
        if (StrUtil.isBlank(raw) || "status".equalsIgnoreCase(raw)) {
            return okGoalReply(session, goalService.statusLine(session));
        }
        // show：状态 + 契约块
        if ("show".equalsIgnoreCase(raw)) {
            String body = goalService.statusLine(session) + "\n" + goalService.renderContract(session);
            return okGoalReply(session, body);
        }
        // draft：留到 Task 13 实现，先返回提示
        if (raw.toLowerCase().startsWith("draft ")) {
            return okGoalReply(session, "/goal draft 暂未实现，请用 /goal <目标> 直接设置。");
        }
        // pause/resume
        if ("pause".equalsIgnoreCase(raw)) {
            GoalState state = goalService.pause(session, "user-paused");
            return okGoalReply(session, state == null ? "No goal set." : "⏸ Goal paused: " + state.getGoal());
        }
        if ("resume".equalsIgnoreCase(raw)) {
            GoalState state = goalService.resume(session, true);
            String continuationPrompt = goalService.nextContinuationPrompt(state);
            GatewayReply reply = okGoalReply(session,
                    state == null ? "No goal to resume." : "▶ Goal resumed: " + state.getGoal());
            if (StrUtil.isNotBlank(continuationPrompt)) {
                reply.getRuntimeMetadata().put("goal_kickoff", continuationPrompt);
            }
            return reply;
        }
        // clear/stop/done 别名
        if ("clear".equalsIgnoreCase(raw) || "stop".equalsIgnoreCase(raw) || "done".equalsIgnoreCase(raw)) {
            boolean had = goalService.clear(session);
            return okGoalReply(session, had ? "✓ Goal cleared." : "No active goal.");
        }
        // wait <pid> [reason]
        if (raw.toLowerCase().startsWith("wait ")) {
            String[] parts = raw.substring(5).trim().split("\\s+", 2);
            try {
                int pid = Integer.parseInt(parts[0]);
                String reason = parts.length > 1 ? parts[1] : null;
                goalService.waitOnPid(session, pid, reason);
                return okGoalReply(session, "⏳ Goal parked on pid " + pid
                        + (reason != null ? " (" + reason + ")" : "")
                        + ". 循环暂停直到该进程退出。");
            } catch (NumberFormatException e) {
                return okGoalReply(session, "无效的 pid，用法：/goal wait <pid> [reason]");
            }
        }
        // unwait
        if ("unwait".equalsIgnoreCase(raw)) {
            goalService.stopWaiting(session);
            return okGoalReply(session, "▶ Wait barrier cleared — goal loop resumes.");
        }
        // 否则：解析 inline contract + 设置新目标
        int maxTurns = parseGoalMaxTurns(raw, GoalState.DEFAULT_MAX_TURNS, log);
        String goalText = stripGoalOptions(raw);
        GoalContractParser.ParseResult parsed = GoalContractParser.parse(goalText);
        GoalState state = goalService.set(session, parsed.getHeadline(), parsed.getContract(), maxTurns);
        GatewayReply reply = okGoalReply(session,
                "⊙ Goal set (" + state.getMaxTurns() + "-turn budget): " + state.getGoal()
                        + (state.hasContract() ? "\n" + state.getContract().renderBlock() : "")
                        + "\nI'll keep working until the goal is done, you pause/clear it, or the budget is exhausted.\n"
                        + "Controls: /goal status · /goal pause · /goal resume · /goal clear · /subgoal <text>");
        reply.getRuntimeMetadata().put("goal_kickoff", state.getGoal());
        return reply;
    }

    private GatewayReply okGoalReply(SessionRecord session, String content) {
        GatewayReply reply = GatewayReply.ok(content);
        reply.setSessionId(session.getSessionId());
        reply.setBranchName(session.getBranchName());
        return reply;
    }
```
3. 新增 `handleSubgoal`：
```java
    private GatewayReply handleSubgoal(GatewayMessage message, String args) throws Exception {
        SessionRecord session = requireSession(message.sourceKey());
        String raw = StrUtil.nullToEmpty(args).trim();
        // 无参：列出
        if (StrUtil.isBlank(raw)) {
            java.util.List<String> sg = goalService.listSubgoals(session);
            String body = sg.isEmpty() ? "No subgoals." : "Subgoals:\n" + formatSubgoalList(sg);
            return okGoalReply(session, body);
        }
        // clear
        if ("clear".equalsIgnoreCase(raw)) {
            goalService.clearSubgoals(session);
            return okGoalReply(session, "✓ Subgoals cleared.");
        }
        // remove <n>
        if (raw.toLowerCase().startsWith("remove ")) {
            try {
                int n = Integer.parseInt(raw.substring(7).trim());
                boolean removed = goalService.removeSubgoal(session, n);
                return okGoalReply(session,
                        removed ? "✓ Subgoal " + n + " removed." : "No subgoal at index " + n + ".");
            } catch (NumberFormatException e) {
                return okGoalReply(session, "无效的序号，用法：/subgoal remove <n>");
            }
        }
        // 否则：追加
        goalService.addSubgoal(session, raw);
        java.util.List<String> sg = goalService.listSubgoals(session);
        return okGoalReply(session, "✓ Added subgoal: " + raw + "\n" + formatSubgoalList(sg));
    }

    private String formatSubgoalList(java.util.List<String> sg) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sg.size(); i++) {
            sb.append(i + 1).append(". ").append(sg.get(i)).append("\n");
        }
        return sb.toString().trim();
    }
```

- [ ] **Step 4: 更新帮助文本**

在 `LocalTerminalHelp.java`（定位 `/goal` 帮助行，约 line 49）更新为含 subgoal/show/wait，并加 `/subgoal` 行：
```
/goal [status|show|pause|resume|clear|stop|done|wait <pid>|unwait|<目标> --max-turns N]
/subgoal [<text>|remove <n>|clear]
```
在 `SlashCommandHelpRenderer`（如存在 goal 文案）同步更新。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=CommandEnhancementTest`
Expected: PASS（含新增的 subgoal/show/stop-done 测试）。

---

### Task 12: GoalMigrationSupport + 压缩迁移调用

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalMigrationSupport.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/engine/DefaultContextCompressionService.java`
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalMigrationSupportTest.java`

**Interfaces:**
- Consumes: `SessionRepository`（读旧/写新旧 goal_state_json）。
- Produces: `GoalMigrationSupport.migrate(oldSessionId, newSessionId, reason)` → boolean：父 active 目标深拷贝给子（status 保持 active），父置 cleared + pausedReason="migrated to <new>"。无 active 目标返回 false。

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalMigrationSupportTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalMigrationSupportTest {
    @Test
    void migratesActiveGoalAndArchivesParent() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord parent = env.sessionRepository.bindNewSession("parent-chat");
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new com.jimuqu.solon.claw.config.AppConfig.GoalConfig());
        svc.set(parent, "父目标", 5);

        SessionRecord child = env.sessionRepository.bindNewSession("child-chat");
        GoalMigrationSupport migrator = new GoalMigrationSupport(env.sessionRepository);
        boolean migrated = migrator.migrate(parent.getSessionId(), child.getSessionId(), "compression");
        assertThat(migrated).isTrue();

        GoalState parentState = svc.get(parent);
        GoalState childState = svc.get(child);
        assertThat(parentState.getStatus()).isEqualTo(GoalState.STATUS_CLEARED);
        assertThat(childState.getGoal()).isEqualTo("父目标");
        assertThat(childState.getStatus()).isEqualTo(GoalState.STATUS_ACTIVE);
    }

    @Test
    void noActiveGoalReturnsFalse() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord parent = env.sessionRepository.bindNewSession("parent2-chat");
        SessionRecord child = env.sessionRepository.bindNewSession("child2-chat");
        GoalMigrationSupport migrator = new GoalMigrationSupport(env.sessionRepository);
        assertThat(migrator.migrate(parent.getSessionId(), child.getSessionId(), "compression")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalMigrationSupportTest`
Expected: FAIL。

- [ ] **Step 3: 实现 GoalMigrationSupport**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalMigrationSupport.java
package com.jimuqu.solon.claw.goal;

import com.jimuqu.solon.claw.core.repository.SessionRepository;
import lombok.RequiredArgsConstructor;

/**
 * 上下文压缩轮转 session_id 时，把 active 目标迁移到子会话并把父会话归档为 cleared，
 * 保证一次只有一个活跃目标（对标仓库 migrate_goal_to_session）。
 */
@RequiredArgsConstructor
public class GoalMigrationSupport {
    /** 会话仓储，用于读写 goal_state_json。 */
    private final SessionRepository sessionRepository;

    /**
     * 迁移目标：子继承 active，父置 cleared。
     *
     * @param oldSessionId 父会话 id。
     * @param newSessionId 子会话 id。
     * @param reason 迁移原因（写入父 pausedReason）。
     * @return 实际迁移返回 true；父无 active 目标返回 false。
     */
    public boolean migrate(String oldSessionId, String newSessionId, String reason) throws Exception {
        SessionRecord oldSession = sessionRepository.findById(oldSessionId);
        if (oldSession == null) {
            return false;
        }
        GoalState oldState = GoalState.fromJson(oldSession.getGoalStateJson());
        if (oldState == null || !GoalState.STATUS_ACTIVE.equals(oldState.getStatus())) {
            return false;
        }
        SessionRecord newSession = sessionRepository.findById(newSessionId);
        if (newSession == null) {
            return false;
        }
        // 子继承：深拷贝（重新序列化保证隔离），保持 active
        String childJson = oldState.toJson();
        newSession.setGoalStateJson(childJson);
        sessionRepository.setGoalState(newSessionId, childJson);
        // 父归档：置 cleared
        oldState.setStatus(GoalState.STATUS_CLEARED);
        oldState.setPausedReason("migrated to " + newSessionId + " (" + reason + ")");
        sessionRepository.setGoalState(oldSessionId, oldState.toJson());
        return true;
    }
}
```

- [ ] **Step 4: 在 DefaultContextCompressionService 调用迁移**

> 实现者注意：先用 `grep -n "sessionId\|cloneSession\|newSessionId\|rotate" DefaultContextCompressionService.java` 找到 session_id 轮转点（对标仓库在 conversation_compression.py:820 调 migrate_goal_to_session）。在轮转发生处（新 session_id 创建后、旧 session_id 即将归档时）注入 `GoalMigrationSupport`（构造注入或方法参数）并调用 `migrate(oldId, newId, "compression")`。**原地压缩（不改 session_id）不调用。**

具体定位后，在轮转分支追加：
```java
        // 目标随会话压缩轮转迁移：子继承 active，父归档
        if (goalMigrationSupport != null && oldSessionId != null && !oldSessionId.equals(newSessionId)) {
            try {
                goalMigrationSupport.migrate(oldSessionId, newSessionId, "compression");
            } catch (Exception e) {
                log.warn("goal migration on compression failed: {}", e.getMessage());
            }
        }
```
并在 `DefaultContextCompressionService` 构造注入 `GoalMigrationSupport`（可为 null，兼容无 goal 场景）。同步更新 `GatewayConfiguration` 和 `TestEnvironment` 的构造调用。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalMigrationSupportTest,CommandEnhancementTest`
Expected: PASS。

- [ ] **Step 6: 提交（Task 9 + 10 + 11 + 12）**

```bash
git add src/main/java/com/jimuqu/solon/claw/goal/GoalService.java \
  src/main/java/com/jimuqu/solon/claw/command/CommandRegistry.java \
  src/main/java/com/jimuqu/solon/claw/support/constants/GatewayCommandConstants.java \
  src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java \
  src/main/java/com/jimuqu/solon/claw/cli/LocalTerminalHelp.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalMigrationSupport.java \
  src/main/java/com/jimuqu/solon/claw/engine/DefaultContextCompressionService.java \
  src/main/java/com/jimuqu/solon/claw/bootstrap/GatewayConfiguration.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalServiceSubgoalsTest.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalServiceWaitBarrierTest.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalMigrationSupportTest.java \
  src/test/java/com/jimuqu/solon/claw/CommandEnhancementTest.java \
  src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java
git commit -m "feat: 目标与子目标命令面及压缩迁移 / Goal and subgoal command surface with compression migration"
```

---

## Batch 5: 抢占检查 + draft + 状态行完善 + 配置示例 (commit 5)

### Task 13: GoalContractDrafter（/goal draft 实现）

**Files:**
- Create: `src/main/java/com/jimuqu/solon/claw/goal/GoalContractDrafter.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java`（handleGoal 的 draft 分支）
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalContractDrafterTest.java`

**Interfaces:**
- Consumes: `LlmGateway`（auxiliary）、`GoalPromptTemplates.DRAFT_CONTRACT_SYSTEM_PROMPT`（需新增到 GoalPromptTemplates）。
- Produces: `GoalContractDrafter.draft(String objective)` → `GoalContract`：aux LLM 把裸目标转 5 字段 JSON，解析失败返回空契约。

- [ ] **Step 1: 在 GoalPromptTemplates 加 DRAFT 模板**

```java
    /** draft-contract 系统提示：把裸目标转为 5 字段 JSON 契约。 */
    public static final String DRAFT_CONTRACT_SYSTEM_PROMPT =
            "You draft a completion contract for a standing goal. Output ONLY JSON with keys: "
                    + "outcome, verification, constraints, boundaries, stop_when. Each value is a short string. "
                    + "Leave a field empty if not applicable. Example:\n"
                    + "{\"outcome\":\"...\",\"verification\":\"...\",\"constraints\":\"\","
                    + "\"boundaries\":\"\",\"stop_when\":\"\"}";

    /** draft-contract 用户提示模板。 */
    public static final String DRAFT_CONTRACT_USER_TEMPLATE = "Goal: %s";
```

- [ ] **Step 2: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalContractDrafterTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.FakeLlmGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalContractDrafterTest {
    static class Scripted extends FakeLlmGateway {
        String resp;
        @Override
        public LlmResult chat(SessionRecord s, String sp, String um, List<Object> tools) throws Exception {
            if (resp != null) {
                LlmResult r = new LlmResult();
                r.setAssistantMessage(org.noear.solon.ai.chat.message.ChatMessage.ofAssistant(resp));
                r.setRawResponse(resp);
                return r;
            }
            return super.chat(s, sp, um, tools);
        }
    }

    @Test
    void draftsContractFromObjective() {
        Scripted gw = new Scripted();
        gw.resp = "{\"outcome\":\"测试通过\",\"verification\":\"mvn test\","
                + "\"constraints\":\"不改公共 API\",\"boundaries\":\"\",\"stop_when\":\"遇到阻塞\"}";
        GoalContractDrafter d = new GoalContractDrafter(gw, new AppConfig.GoalConfig());
        GoalContract c = d.draft("补齐测试");
        assertThat(c.getOutcome()).isEqualTo("测试通过");
        assertThat(c.getVerification()).isEqualTo("mvn test");
    }

    @Test
    void returnsEmptyContractOnFailure() {
        LlmGateway broken = new LlmGateway() {
            @Override
            public LlmResult chat(SessionRecord s, String sp, String um, List<Object> t) throws Exception {
                throw new RuntimeException("down");
            }
            @Override
            public LlmResult resume(SessionRecord s, String sp, List<Object> t) { return null; }
        };
        GoalContractDrafter d = new GoalContractDrafter(broken, new AppConfig.GoalConfig());
        GoalContract c = d.draft("g");
        assertThat(c.isEmpty()).isTrue(); // 失败返回空契约
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalContractDrafterTest`
Expected: FAIL。

- [ ] **Step 4: 实现 GoalContractDrafter**

```java
// src/main/java/com/jimuqu/solon/claw/goal/GoalContractDrafter.java
package com.jimuqu.solon.claw.goal;

import cn.hutool.core.util.StrUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.LlmResult;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.core.service.LlmGateway;
import com.jimuqu.solon.claw.support.BoundedExecutorFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用辅助模型把裸目标起草为 5 字段完成契约（/goal draft），失败返回空契约。
 */
public class GoalContractDrafter {
    private static final Logger log = LoggerFactory.getLogger(GoalContractDrafter.class);

    /** LLM 网关。 */
    private final LlmGateway llmGateway;
    /** goal 配置。 */
    private final AppConfig.GoalConfig config;
    /** auxiliary 线程池。 */
    private final ExecutorService executor = BoundedExecutorFactory.fixed("goal-draft-auxiliary", 1, 4);

    /** 构造 drafter。 */
    public GoalContractDrafter(LlmGateway llmGateway, AppConfig.GoalConfig config) {
        this.llmGateway = llmGateway;
        this.config = config == null ? new AppConfig.GoalConfig() : config;
    }

    /**
     * 起草完成契约。
     *
     * @param objective 裸目标文本。
     * @return 契约；失败返回空契约。
     */
    public GoalContract draft(String objective) {
        final String userMessage =
                String.format(GoalPromptTemplates.DRAFT_CONTRACT_USER_TEMPLATE, StrUtil.nullToEmpty(objective));
        Future<LlmResult> future =
                executor.submit(
                        () -> {
                            SessionRecord s = new SessionRecord();
                            s.setSessionId("goal-draft");
                            return llmGateway.chat(
                                    s, GoalPromptTemplates.DRAFT_CONTRACT_SYSTEM_PROMPT, userMessage, Collections.emptyList());
                        });
        try {
            int timeout = config.getJudgeTimeoutSeconds() > 0 ? config.getJudgeTimeoutSeconds() : 30;
            LlmResult result = future.get(timeout, TimeUnit.SECONDS);
            String raw = StrUtil.nullToEmpty(result.getRawResponse());
            if (StrUtil.isBlank(raw) && result.getAssistantMessage() != null) {
                raw = StrUtil.nullToEmpty(result.getAssistantMessage().content());
            }
            return parseContractJson(raw);
        } catch (Exception e) {
            log.warn("goal contract draft failed, returning empty contract: {}", e.getMessage());
            return new GoalContract();
        }
    }

    /** 解析 5 字段 JSON 契约，失败返回空契约。 */
    @SuppressWarnings("unchecked")
    private GoalContract parseContractJson(String raw) {
        String text = StrUtil.nullToEmpty(raw).trim();
        if (StrUtil.isBlank(text)) {
            return new GoalContract();
        }
        try {
            Map<String, Object> data = ONode.deserialize(text, LinkedHashMap.class);
            return GoalContract.fromMap(data);
        } catch (Exception e) {
            log.warn("goal contract draft JSON parse failed: {}", e.getMessage());
            return new GoalContract();
        }
    }
}
```

- [ ] **Step 5: 在 handleGoal 的 draft 分支接入**

需要给 `DefaultCommandService` 注入 `GoalContractDrafter`（构造参数 + GatewayConfiguration bean + TestEnvironment）。修改 draft 分支：
```java
        if (raw.toLowerCase().startsWith("draft ")) {
            String objective = raw.substring(6).trim();
            if (StrUtil.isBlank(objective)) {
                return okGoalReply(session, "用法：/goal draft <目标>");
            }
            GoalContract drafted = goalContractDrafter == null ? new GoalContract() : goalContractDrafter.draft(objective);
            GoalState state = goalService.set(session, objective, drafted, defaultGoalMaxTurns());
            GatewayReply reply = okGoalReply(session,
                    "⊙ Goal set with drafted contract (" + state.getMaxTurns() + "-turn budget): " + state.getGoal()
                            + (state.hasContract() ? "\n" + state.getContract().renderBlock() : ""));
            reply.getRuntimeMetadata().put("goal_kickoff", state.getGoal());
            return reply;
        }
```
其中 `defaultGoalMaxTurns()` 读 `appConfig.getGoal().getMaxTurns()`。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalContractDrafterTest`
Expected: PASS。

- [ ] **Step 7: 提交（与 Task 14/15 一起）**

暂不提交。

---

### Task 14: GatewayMessage goalContinuation 标志 + 抢占检查

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/core/model/GatewayMessage.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java`
- Modify: `src/main/java/com/jimuqu/solon/claw/engine/AgentRunSupervisor.java`（新增 `hasPendingRealMessage(sourceKey)` 查询）

**Interfaces:**
- Consumes: `AgentRunSupervisor`（busy/queue 状态）。
- Produces: `GatewayMessage.goalContinuation`（transient boolean）；`DefaultGatewayService.safeScheduleGoalKickoff`/`safeScheduleGoalContinuation` 构造续轮消息时设 `setGoalContinuation(true)`；发续轮前调 `agentRunSupervisor.hasPendingRealMessage(sourceKey)`，为 true 则跳过本轮续轮。

- [ ] **Step 1: 修改 GatewayMessage 加 transient 标志**

在 `GatewayMessage.java` 字段区追加（不参与序列化）：
```java
    /** 标记本消息是 goal 续轮合成消息（非真实用户输入），用于抢占判定。 */
    private transient boolean goalContinuation;
```

- [ ] **Step 2: 修改 DefaultGatewayService 标记续轮消息 + 抢占检查**

在 `safeScheduleGoalKickoff` 和 `safeScheduleGoalContinuation` 构造 `kickoffMessage`/`continuation` 后追加：
```java
                                    kickoffMessage.setGoalContinuation(true); // 标记为合成续轮
```
在 `safeScheduleGoalContinuation` 的 `run()` 开头（try 块内、构造消息前）追加抢占检查：
```java
                            // 抢占检查：若有待处理真实用户消息，跳过本轮续轮，让真实消息接手
                            if (agentRunSupervisor != null
                                    && agentRunSupervisor.hasPendingRealMessage(message.sourceKey())) {
                                log.debug("goal continuation skipped: real user message pending for {}", message.sourceKey());
                                return;
                            }
```

- [ ] **Step 3: 在 AgentRunSupervisor 加 hasPendingRealMessage**

> 实现者注意：`AgentRunSupervisor` 维护 `queuedMessages`（或类似队列，参考 `queueMessage` 方法）。新增 `hasPendingRealMessage(sourceKey)`：检查该 sourceKey 的队列里是否有非 heartbeat、非 goalContinuation 的真实消息。先用 `grep -n "queueMessage\|queuedMessages\|QueuedRunMessage" AgentRunSupervisor.java` 找到队列结构。

```java
    /**
     * 查询某会话是否有待处理的真实用户消息（非 heartbeat、非 goal 续轮）。
     *
     * @param sourceKey 会话来源键。
     * @return 有真实待处理消息返回 true。
     */
    public boolean hasPendingRealMessage(String sourceKey) {
        // 实现：扫描该 sourceKey 的待处理队列，任一消息 isGoalContinuation()==false 且非 heartbeat 即返回 true
        // 具体实现依赖 queueMessage 的数据结构，参考 queueMessage 方法
        // ...（实现者据实际结构补全）
        return false;
    }
```

> **降级策略：** 若 `AgentRunSupervisor` 的队列结构不便查询，`hasPendingRealMessage` 可先返回 false（功能降级到现有 interrupt 策略兜底），并在方法注释标注 TODO。抢占是增强，interrupt 默认策略已保证用户能对话。

- [ ] **Step 4: 编译确认**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

---

### Task 15: statusLine 完善 + 配置示例最终化 + 全量测试

**Files:**
- Modify: `src/main/java/com/jimuqu/solon/claw/goal/GoalService.java`（`statusLine` 加 subgoal/contract/parked meta）
- Modify: `config.example.yml`（已在 Task 6，此步确认）
- Test: `src/test/java/com/jimuqu/solon/claw/goal/GoalStatusLineTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/jimuqu/solon/claw/goal/GoalStatusLineTest.java
package com.jimuqu.solon.claw.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.SessionRecord;
import com.jimuqu.solon.claw.support.TestEnvironment;
import org.junit.jupiter.api.Test;

class GoalStatusLineTest {
    @Test
    void noGoalMessage() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord s = env.sessionRepository.bindNewSession("sl-chat");
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        assertThat(svc.statusLine(s)).contains("No active goal").contains("/goal");
    }

    @Test
    void activeWithSubgoalsAndContractMeta() throws Exception {
        TestEnvironment env = TestEnvironment.withFakeLlm();
        SessionRecord s = env.sessionRepository.bindNewSession("sl-chat2");
        GoalService svc = new GoalService(env.sessionRepository, new HeuristicGoalJudge(), new AppConfig.GoalConfig());
        GoalContract c = new GoalContract();
        c.setOutcome("完成");
        svc.set(s, "目标X", c, 10);
        svc.addSubgoal(s, "子目标1");
        svc.addSubgoal(s, "子目标2");
        String line = svc.statusLine(s);
        assertThat(line).contains("⊙ Goal (active").contains("目标X").contains("2 subgoal").contains("contract");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=GoalStatusLineTest`
Expected: FAIL（meta 未含 subgoal/contract）。

- [ ] **Step 3: 重写 GoalService.statusLine**

```java
    public String statusLine(SessionRecord session) {
        GoalState state = get(session);
        if (state == null || GoalState.STATUS_CLEARED.equals(state.getStatus())) {
            return "No active goal. Set one with /goal <text>.";
        }
        String turns = state.getTurnsUsed() + "/" + state.getMaxTurns() + " turns";
        String meta = turns;
        if (state.getSubgoals() != null && !state.getSubgoals().isEmpty()) {
            meta += ", " + state.getSubgoals().size() + " subgoal(s)";
        }
        if (state.hasContract()) {
            meta += ", contract";
        }
        // 等待屏障状态
        if (state.isActive() && state.isWaiting()) {
            String reason = StrUtil.blankToDefault(state.getWaitingReason(), "waiting");
            return "⏳ Goal (parked, " + meta + " — " + reason + "): " + state.getGoal();
        }
        if (GoalState.STATUS_ACTIVE.equals(state.getStatus())) {
            return "⊙ Goal (active, " + meta + "): " + state.getGoal();
        }
        if (GoalState.STATUS_PAUSED.equals(state.getStatus())) {
            String extra = StrUtil.isBlank(state.getPausedReason()) ? "" : " — " + state.getPausedReason();
            return "⏸ Goal (paused, " + meta + extra + "): " + state.getGoal();
        }
        if (GoalState.STATUS_DONE.equals(state.getStatus())) {
            return "✓ Goal done (" + meta + "): " + state.getGoal();
        }
        return "Goal (" + state.getStatus() + ", " + meta + "): " + state.getGoal();
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=GoalStatusLineTest`
Expected: PASS。

- [ ] **Step 5: 全量测试（最终回归）**

Run: `mvn -q test`
Expected: 全绿（含所有新增 goal 测试 + 现有测试无回归）。

- [ ] **Step 6: 提交（Task 13 + 14 + 15）**

```bash
git add src/main/java/com/jimuqu/solon/claw/goal/GoalPromptTemplates.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalContractDrafter.java \
  src/main/java/com/jimuqu/solon/claw/core/model/GatewayMessage.java \
  src/main/java/com/jimuqu/solon/claw/gateway/service/DefaultGatewayService.java \
  src/main/java/com/jimuqu/solon/claw/engine/AgentRunSupervisor.java \
  src/main/java/com/jimuqu/solon/claw/gateway/command/DefaultCommandService.java \
  src/main/java/com/jimuqu/solon/claw/bootstrap/GatewayConfiguration.java \
  src/main/java/com/jimuqu/solon/claw/goal/GoalService.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalContractDrafterTest.java \
  src/test/java/com/jimuqu/solon/claw/goal/GoalStatusLineTest.java \
  src/test/java/com/jimuqu/solon/claw/support/TestEnvironment.java
git commit -m "feat: 目标 draft 起草、抢占检查与状态行完善 / Goal draft contract, preemption check and status line polish"
```

---

## 完成检查 / Completion Checklist

- [ ] 5 个有效 commit 全部落地到 `feat/goal-replication-20260706`
- [ ] `mvn -q test` 全量通过
- [ ] 现有 `CommandEnhancementTest#shouldSupportGoalCommandLifecycle` 仍通过（向后兼容）
- [ ] 提示合 main（累计 5 次有效 commit）
- [ ] commit message 中英双语，无旧项目关键词
- [ ] 所有新增类/字段/方法有中文 Javadoc/注释
- [ ] config.example.yml 的 `solonclaw.goal` 段有中文注释

## 待确认范围（实现期可调，已在 spec 标注）

- **抢占检查** `hasPendingRealMessage`：若 `AgentRunSupervisor` 队列结构不便查询，可降级为返回 false（interrupt 策略兜底），标注 TODO。
- **wait session 屏障**：本仓库无 `process_registry`，只做 pid + 时间两种屏障。
- **Ctrl+C 自动暂停**：gateway 路径无此概念，只靠 `/goal pause`；本地 CLI 路径可后续按需补。
