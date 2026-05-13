package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalInputSanitizer;
import org.junit.jupiter.api.Test;

public class TerminalInputSanitizerTest {
    @Test
    void shouldPreservePlainText() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello world"))
                .isEqualTo("hello world");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses(""))
                .isEqualTo("");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("see [53;1R section"))
                .isEqualTo("see [53;1R section");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("\u001B[31mred\u001B[0m"))
                .isEqualTo("\u001B[31mred\u001B[0m");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "render <div class='hero'> literal"))
                .isEqualTo("render <div class='hero'> literal");
    }

    @Test
    void shouldStripDsrResponsesLikeJimuquCli() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("\u001B[53;1R"))
                .isEqualTo("");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("^[[53;1R"))
                .isEqualTo("");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello\u001B[53;1Rworld"))
                .isEqualTo("helloworld");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "a\u001B[53;1Rb\u001B[51;1Rc\u001B[50;9Rd"))
                .isEqualTo("abcd");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("typed^[[53;1Rmore"))
                .isEqualTo("typedmore");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "line 1\n\u001B[53;1Rline 2"))
                .isEqualTo("line 1\nline 2");
    }

    @Test
    void shouldStripSgrMouseReportsLikeJimuquCli() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc\u001B[<65;1;49Mdef"))
                .isEqualTo("abcdef");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc^[[<65;1;49Mdef"))
                .isEqualTo("abcdef");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc<65;1;49Mdef"))
                .isEqualTo("abcdef");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "abc\u001B[<10000;12345;98765Mdef"))
                .isEqualTo("abcdef");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("<65;1;49M<35;1;42Mhello"))
                .isEqualTo("hello");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "<65;1;49M<35;1;42Mhello<64;1;40m"))
                .isEqualTo("hello");
    }

    @Test
    void shouldStripOscResponsesFromTerminalInput() {
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "typed\u001B]52;c;c2VjcmV0\u0007more"))
                .isEqualTo("typedmore");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "typed\u001B]52;c;c2VjcmV0\u001B\\more"))
                .isEqualTo("typedmore");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "typed\u009D52;c;c2VjcmV0\u009Cmore"))
                .isEqualTo("typedmore");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "open \u001B]8;;https://example.invalid\u001B\\link\u001B]8;;\u001B\\ now"))
                .isEqualTo("open link now");
    }

    @Test
    void shouldStripBracketedPasteWrappersFromTerminalInput() {
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "\u001B[200~hello\u001B[201~"))
                .isEqualTo("hello");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "a\u001B[200~b\u001B[201~c"))
                .isEqualTo("abc");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("^[[200~text^[[201~"))
                .isEqualTo("text");
    }
}
