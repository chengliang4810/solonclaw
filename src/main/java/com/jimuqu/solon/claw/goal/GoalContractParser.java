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
