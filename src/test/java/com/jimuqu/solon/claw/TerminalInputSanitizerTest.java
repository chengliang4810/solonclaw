package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.cli.TerminalInputSanitizer;
import org.junit.jupiter.api.Test;

public class TerminalInputSanitizerTest {
    @Test
    void shouldPreservePlainText() {
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello world"))
                .isEqualTo("hello world");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("")).isEqualTo("");
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
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("^[[53;1R")).isEqualTo("");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello\u001B[53;1Rworld"))
                .isEqualTo("helloworld");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("hello\u009B53;1Rworld"))
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
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("abc\u009B<65;1;49Mdef"))
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
    void shouldStripDegradedMouseBurstNoiseWithoutDroppingPlainText() {
        String burst =
                "M6M35;220;56M6M35;218;56M169;48M;157;47M;44M20;43M79;40M78;40M0M7M"
                        + "35;49;41M48;41M;47;40M9;15;32M[I;31M5;211;26M35;211;25M7M;220;1MM0M"
                        + "09;25M24M23M3;22MM18M99;26M32MM38M63;44M47MM1;51M M4M54M";
        String burstWithRecoverableFragments =
                "<35;159;11M;44M20;43M0M7M<35;124;26M;47;40M9;15;32M5M2M";

        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses(burst)).isEqualTo("");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                burstWithRecoverableFragments))
                .isEqualTo("");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("Mmm MMM mmm yummy"))
                .isEqualTo("Mmm MMM mmm yummy");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("see 1;2;3M for details"))
                .isEqualTo("see 1;2;3M for details");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("1234;56;78M9;10;11M"))
                .isEqualTo("1234;56;78M9;10;11M");
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
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "typed]11;rgb:ffff/ffff/ffff\u0007more"))
                .isEqualTo("typedmore");
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "typed^]11;rgb:0000/0000/0000^Gmore"))
                .isEqualTo("typedmore");
    }

    @Test
    void shouldStripBracketedPasteWrappersFromTerminalInput() {
        assertThat(
                        TerminalInputSanitizer.stripLeakedTerminalResponses(
                                "\u001B[200~hello\u001B[201~"))
                .isEqualTo("hello");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("a\u001B[200~b\u001B[201~c"))
                .isEqualTo("abc");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("\u009B200~text\u009B201~"))
                .isEqualTo("text");
        assertThat(TerminalInputSanitizer.stripLeakedTerminalResponses("^[[200~text^[[201~"))
                .isEqualTo("text");
    }
}
