package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalInputSanitizer;
import org.junit.jupiter.api.Test;

public class TerminalInputSanitizerTest {
    @Test
    void shouldPreservePlainText() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello world"))
                .isEqualTo("hello world");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("see [53;1R section"))
                .isEqualTo("see [53;1R section");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("\u001B[31mred\u001B[0m"))
                .isEqualTo("\u001B[31mred\u001B[0m");
    }

    @Test
    void shouldStripDsrResponsesLikeHermesCli() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("\u001B[53;1R"))
                .isEqualTo("");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello\u001B[53;1Rworld"))
                .isEqualTo("helloworld");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("typed^[[53;1Rmore"))
                .isEqualTo("typedmore");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "line 1\n\u001B[53;1Rline 2"))
                .isEqualTo("line 1\nline 2");
    }

    @Test
    void shouldStripSgrMouseReportsLikeHermesCli() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc\u001B[<65;1;49Mdef"))
                .isEqualTo("abcdef");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc^[[<65;1;49Mdef"))
                .isEqualTo("abcdef");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc<65;1;49Mdef"))
                .isEqualTo("abcdef");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("<65;1;49M<35;1;42Mhello"))
                .isEqualTo("hello");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("render <div> literal"))
                .isEqualTo("render <div> literal");
    }
}
