package com.jimuqu.solon.claw.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.RuntimeConfigResolver;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;

class ImageProviderRequestTest {
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZQmcAAAAASUVORK5CYII=";

    @TempDir Path tempDir;

    @Test
    void providersReadCurrentProfileCredentialForEveryCall() throws Exception {
        Path primaryProfile = tempDir.resolve("primary-profile");
        Path secondaryProfile = tempDir.resolve("secondary-profile");
        Files.createDirectories(primaryProfile);
        Files.createDirectories(secondaryProfile);
        Files.writeString(
                primaryProfile.resolve("config.yml"),
                "providers:\n"
                        + "  openai:\n"
                        + "    apiKey: primary-openai-key\n"
                        + "  xai:\n"
                        + "    apiKey: primary-xai-key\n");
        Files.writeString(
                secondaryProfile.resolve("config.yml"),
                "providers:\n"
                        + "  openai:\n"
                        + "    apiKey: secondary-openai-key\n"
                        + "  xai:\n"
                        + "    apiKey: secondary-xai-key\n");
        AtomicReference<RuntimeConfigResolver> profile =
                new AtomicReference<RuntimeConfigResolver>(
                        RuntimeConfigResolver.open(primaryProfile.toString()));
        AtomicReference<String> authorization = new AtomicReference<String>();
        HttpServer server =
                imageServer(
                        new AtomicReference<String>(),
                        new AtomicReference<String>(),
                        authorization);
        OpenAiImageProvider openAi =
                new OpenAiImageProvider(
                        () -> profile.get().get("providers.openai.apiKey"), endpoint(server));
        XaiImageProvider xAi =
                new XaiImageProvider(
                        () -> profile.get().get("providers.xai.apiKey"), endpoint(server));

        try {
            assertThat(openAi.isAvailable()).isTrue();
            assertThat(xAi.isAvailable()).isTrue();
            openAi.generate("test", "square", null, Collections.emptyList());
            assertThat(authorization.get()).isEqualTo("Bearer primary-openai-key");
            xAi.generate("test", "square", null, Collections.emptyList());
            assertThat(authorization.get()).isEqualTo("Bearer primary-xai-key");

            profile.set(RuntimeConfigResolver.open(secondaryProfile.toString()));
            openAi.generate("test", "square", null, Collections.emptyList());
            assertThat(authorization.get()).isEqualTo("Bearer secondary-openai-key");
            xAi.generate("test", "square", null, Collections.emptyList());
            assertThat(authorization.get()).isEqualTo("Bearer secondary-xai-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providersReturnPreciseErrorWhenCurrentProfileCredentialIsMissing() {
        OpenAiImageProvider openAi = new OpenAiImageProvider(() -> null);
        XaiImageProvider xAi = new XaiImageProvider(() -> null);

        assertThat(openAi.isAvailable()).isFalse();
        assertThat(xAi.isAvailable()).isFalse();
        assertThat(openAi.generate("test", "square", null, Collections.emptyList()).getError())
                .isEqualTo("OpenAI image provider requires OPENAI_API_KEY");
        assertThat(xAi.generate("test", "square", null, Collections.emptyList()).getError())
                .isEqualTo("xAI image provider requires XAI_API_KEY");
    }

    @Test
    void openAiProviderUsesConfiguredAspectAndFixedProviderParameters() throws Exception {
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = imageServer(path, body);
        try {
            OpenAiImageProvider provider = new OpenAiImageProvider("test-key", endpoint(server));

            ImageGenProvider.ImageGenResult result =
                    provider.generate("绘制码头", "landscape", null, Collections.emptyList());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getUrl()).startsWith("data:image/png;base64,");
            assertThat(path.get()).isEqualTo("/v1/images/generations");
            Map<String, Object> payload = ONode.deserialize(body.get(), Map.class);
            assertThat(payload)
                    .containsEntry("model", "gpt-image-2")
                    .containsEntry("size", "1536x1024")
                    .containsEntry("quality", "medium")
                    .doesNotContainKeys("image_url", "reference_image_urls", "output_format");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiProviderSendsPrimaryAndReferenceImagesToEditEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = imageServer(path, body);
        Path primary = imageFile("primary.png");
        Path reference = imageFile("reference.png");
        try {
            OpenAiImageProvider provider = new OpenAiImageProvider("test-key", endpoint(server));

            ImageGenProvider.ImageGenResult result =
                    provider.generate(
                            "保持主体，换成夜景",
                            "portrait",
                            primary.toString(),
                            Collections.singletonList(reference.toString()));

            assertThat(result.isSuccess()).isTrue();
            assertThat(path.get()).isEqualTo("/v1/images/edits");
            assertThat(body.get())
                    .contains("name=\"image\"")
                    .contains("filename=\"primary.png\"")
                    .contains("filename=\"reference.png\"")
                    .contains("1024x1536")
                    .contains("medium");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void xAiProviderSendsPreparedImagesToJsonEditEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = imageServer(path, body);
        Path primary = imageFile("primary.png");
        Path reference = imageFile("reference.png");
        try {
            XaiImageProvider provider = new XaiImageProvider("test-key", endpoint(server));

            ImageGenProvider.ImageGenResult result =
                    provider.generate(
                            "把背景改为海边",
                            "square",
                            primary.toString(),
                            Collections.singletonList(reference.toString()));

            assertThat(result.isSuccess()).isTrue();
            assertThat(path.get()).isEqualTo("/v1/images/edits");
            Map<String, Object> payload = ONode.deserialize(body.get(), Map.class);
            assertThat(payload).containsEntry("model", "grok-imagine-image-quality");
            assertThat((java.util.List<?>) payload.get("images")).hasSize(2);
            assertThat(body.get()).contains("data:image/png;base64,");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer imageServer(AtomicReference<String> path, AtomicReference<String> body)
            throws Exception {
        return imageServer(path, body, null);
    }

    private HttpServer imageServer(
            AtomicReference<String> path,
            AtomicReference<String> body,
            AtomicReference<String> authorization)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/images/",
                exchange -> {
                    path.set(exchange.getRequestURI().getPath());
                    if (authorization != null) {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    }
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.ISO_8859_1));
                    byte[] response =
                            ("{\"data\":[{\"b64_json\":\"" + PNG_BASE64 + "\"}]}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private Path imageFile(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, java.util.Base64.getDecoder().decode(PNG_BASE64));
        return file;
    }
}
