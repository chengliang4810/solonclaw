package com.jimuqu.solon.claw.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.pdf.PdfTalent;

public class SolonAiPdfSkillTest {
    @Test
    void shouldCreateAndParsePdfViaOfficialPdfSkill() throws Exception {
        Path workspaceHome = Files.createTempDirectory("jimuqu-pdf-skill");
        AppConfig config = new AppConfig();
        config.getRuntime().setCacheDir(workspaceHome.resolve("cache").toAbsolutePath().toString());

        SolonAiLlmGateway gateway = new SolonAiLlmGateway(config);
        PdfTalent pdfSkill = gateway.pdfSkill();

        String result = pdfSkill.create("report.pdf", "# Solon PDF Test\n\nHello PDF", "markdown");
        String parsed = pdfSkill.parse("report.pdf");

        assertThat(result).contains("PDF");
        assertThat(Files.exists(workspaceHome.resolve("cache").resolve("pdf").resolve("report.pdf")))
                .isTrue();
        assertThat(parsed).containsIgnoringCase("Solon");
        assertThat(parsed).containsIgnoringCase("Hello");
    }
}
