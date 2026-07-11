package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.enums.PlatformType;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
import com.jimuqu.solon.claw.media.ImageAspectRatio;
import com.jimuqu.solon.claw.media.ImageGenerationService;
import com.jimuqu.solon.claw.media.SpeechService;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.plugin.provider.SpeechProvider;
import com.jimuqu.solon.claw.plugin.provider.TranscriptionProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.tool.runtime.MediaSpeechTools;
import com.jimuqu.solon.claw.tool.runtime.SecurityPolicyService;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.core.Props;

public class MediaSpeechServiceTest {
    @Test
    void shouldLoadIndependentTtsAndSttConfiguration() throws Exception {
        File workspaceHome = Files.createTempDirectory("solonclaw-speech-config").toFile();
        FileUtil.writeUtf8String(
                "providers:\n"
                        + "  default:\n"
                        + "    baseUrl: https://api.openai.com\n"
                        + "    defaultModel: gpt-5.4\n"
                        + "    dialect: openai\n"
                        + "model:\n"
                        + "  providerKey: default\n"
                        + "solonclaw:\n"
                        + "  speech:\n"
                        + "    tts:\n"
                        + "      enabled: true\n"
                        + "      endpoint: https://speech.example.com/v1/audio/speech\n"
                        + "      apiKey: tts-key\n"
                        + "      model: tts-model\n"
                        + "      voice: cedar\n"
                        + "      responseFormat: opus\n"
                        + "      speed: 1.25\n"
                        + "      timeoutSeconds: 31\n"
                        + "    stt:\n"
                        + "      enabled: true\n"
                        + "      endpoint: https://stt.example.com/v1/audio/transcriptions\n"
                        + "      apiKey: stt-key\n"
                        + "      model: stt-model\n"
                        + "      language: zh\n"
                        + "      prompt: 专有名词\n"
                        + "      timeoutSeconds: 47\n",
                new File(workspaceHome, "config.yml"));
        Props props = new Props();
        props.put("solonclaw.workspace", workspaceHome.getAbsolutePath());

        AppConfig config = AppConfig.load(props);

        assertThat(config.getSpeech().getTts().isEnabled()).isTrue();
        assertThat(config.getSpeech().getTts().getEndpoint())
                .isEqualTo("https://speech.example.com/v1/audio/speech");
        assertThat(config.getSpeech().getTts().getApiKey()).isEqualTo("tts-key");
        assertThat(config.getSpeech().getTts().getModel()).isEqualTo("tts-model");
        assertThat(config.getSpeech().getTts().getVoice()).isEqualTo("cedar");
        assertThat(config.getSpeech().getTts().getResponseFormat()).isEqualTo("opus");
        assertThat(config.getSpeech().getTts().getSpeed()).isEqualTo(1.25d);
        assertThat(config.getSpeech().getTts().getTimeoutSeconds()).isEqualTo(31);
        assertThat(config.getSpeech().getStt().isEnabled()).isTrue();
        assertThat(config.getSpeech().getStt().getEndpoint())
                .isEqualTo("https://stt.example.com/v1/audio/transcriptions");
        assertThat(config.getSpeech().getStt().getApiKey()).isEqualTo("stt-key");
        assertThat(config.getSpeech().getStt().getModel()).isEqualTo("stt-model");
        assertThat(config.getSpeech().getStt().getLanguage()).isEqualTo("zh");
        assertThat(config.getSpeech().getStt().getPrompt()).isEqualTo("专有名词");
        assertThat(config.getSpeech().getStt().getTimeoutSeconds()).isEqualTo(47);
    }

    @Test
    void shouldReturnSafeErrorWhenImageProviderUnavailable() {
        ImageGenerationService service =
                new ImageGenerationService(
                        config("image-unavailable"),
                        new AttachmentCacheService(config("image-unavailable")),
                        Collections.<ImageGenProvider>singletonList(
                                new FakeImageProvider(false, null)));

        ImageGenerationService.ImageGenerationOutcome outcome =
                service.generate("画一张图", "square", null, Collections.<String>emptyList());

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getError()).contains("No available image generation provider");
    }

    @Test
    void shouldCacheSuccessfulImageGenerationOutput() {
        AppConfig config = config("image-success");
        AttachmentCacheService cacheService = new AttachmentCacheService(config);
        ImageGenerationService service =
                new ImageGenerationService(
                        config,
                        cacheService,
                        Collections.<ImageGenProvider>singletonList(
                                new FakeImageProvider(true, "data:image/png;base64,iVBORw0KGgo=")));

        ImageGenerationService.ImageGenerationOutcome outcome =
                service.generate("画一张图", "square", null, Collections.<String>emptyList());

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getAttachment()).isNotNull();
        assertThat(outcome.getAttachment().getKind()).isEqualTo("image");
        assertThat(outcome.getMediaReference()).startsWith("media://");
        assertThat(outcome.getMediaUsage().get("generatedImages")).isEqualTo(1);
    }

    @Test
    void shouldDownloadGeneratedImageUrlBeforeCaching() throws Exception {
        byte[] imageBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/generated.png",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, imageBytes.length);
                    exchange.getResponseBody().write(imageBytes);
                    exchange.close();
                });
        server.start();
        try {
            AppConfig config = config("image-url");
            AttachmentCacheService cacheService = new AttachmentCacheService(config);
            ImageGenerationService service =
                    new ImageGenerationService(
                            config,
                            cacheService,
                            Collections.<ImageGenProvider>singletonList(
                                    new FakeImageProvider(
                                            true,
                                            "http://127.0.0.1:"
                                                    + server.getAddress().getPort()
                                                    + "/generated.png")),
                            new AllowLocalImageSecurityPolicyService(config));

            ImageGenerationService.ImageGenerationOutcome outcome =
                    service.generate("画一张图", "square", null, Collections.<String>emptyList());

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(
                            Files.readAllBytes(
                                    new File(outcome.getAttachment().getLocalPath()).toPath()))
                    .containsExactly(imageBytes);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCacheSuccessfulTtsOutputAsVoiceAttachment() {
        AppConfig config = config("tts-success");
        SpeechService service =
                new SpeechService(
                        config,
                        new AttachmentCacheService(config),
                        Collections.<SpeechProvider>singletonList(new FakeSpeechProvider(true)),
                        Collections.<TranscriptionProvider>emptyList());

        SpeechService.SpeechOutcome outcome =
                service.synthesize("你好", "zh-CN", Collections.<String, Object>emptyMap());

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getAttachment().getKind()).isEqualTo("voice");
        assertThat(outcome.getAttachment().getMimeType()).isEqualTo("audio/wav");
        assertThat(outcome.getMediaUsage().get("audioOutputBytes")).isEqualTo(4L);
    }

    @Test
    void shouldUseConfiguredOpenAiCompatibleTtsProvider() throws Exception {
        byte[] audio = new byte[] {1, 2, 3, 4, 5};
        AtomicReference<String> requestBody = new AtomicReference<String>();
        AtomicReference<String> authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/audio/speech",
                exchange -> {
                    requestBody.set(
                            new String(
                                    IoUtil.readBytes(exchange.getRequestBody()),
                                    StandardCharsets.UTF_8));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getResponseHeaders().add("Content-Type", "audio/ogg");
                    exchange.sendResponseHeaders(200, audio.length);
                    exchange.getResponseBody().write(audio);
                    exchange.close();
                });
        server.start();
        try {
            AppConfig config = config("tts-http");
            config.getSpeech().getTts().setEnabled(true);
            config.getSpeech()
                    .getTts()
                    .setEndpoint(
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1/audio/speech");
            config.getSpeech().getTts().setApiKey("tts-test-key");
            config.getSpeech().getTts().setModel("tts-model");
            config.getSpeech().getTts().setVoice("cedar");
            config.getSpeech().getTts().setResponseFormat("opus");
            config.getSpeech().getTts().setSpeed(1.25d);
            SpeechService service =
                    new SpeechService(
                            config,
                            new AttachmentCacheService(config),
                            Collections.<SpeechProvider>emptyList(),
                            Collections.<TranscriptionProvider>emptyList());

            SpeechService.SpeechOutcome outcome =
                    service.synthesize("你好", null, Collections.<String, Object>emptyMap());

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(outcome.getProvider()).isEqualTo("openai-tts");
            assertThat(outcome.getAttachment().getMimeType()).isEqualTo("audio/ogg");
            assertThat(
                            Files.readAllBytes(
                                    new File(outcome.getAttachment().getLocalPath()).toPath()))
                    .containsExactly(audio);
            assertThat(authorization.get()).isEqualTo("Bearer tts-test-key");
            Map<String, Object> payload = ONode.deserialize(requestBody.get(), Map.class);
            assertThat(payload)
                    .containsEntry("model", "tts-model")
                    .containsEntry("input", "你好")
                    .containsEntry("voice", "cedar")
                    .containsEntry("response_format", "opus");
            assertThat(((Number) payload.get("speed")).doubleValue()).isEqualTo(1.25d);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseConfiguredIndependentOpenAiCompatibleSttProvider() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<String>();
        AtomicReference<String> contentType = new AtomicReference<String>();
        AtomicReference<String> authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/audio/transcriptions",
                exchange -> {
                    requestBody.set(
                            new String(
                                    IoUtil.readBytes(exchange.getRequestBody()),
                                    StandardCharsets.ISO_8859_1));
                    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response =
                            ONode.serialize(Collections.singletonMap("text", "转写成功"))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            AppConfig config = config("stt-http");
            config.getSpeech().getStt().setEnabled(true);
            config.getSpeech()
                    .getStt()
                    .setEndpoint(
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/v1/audio/transcriptions");
            config.getSpeech().getStt().setApiKey("stt-test-key");
            config.getSpeech().getStt().setModel("stt-model");
            config.getSpeech().getStt().setLanguage("zh");
            config.getSpeech().getStt().setPrompt("domain-term");
            AttachmentCacheService cacheService = new AttachmentCacheService(config);
            MessageAttachment attachment =
                    cacheService.cacheBytes(
                            PlatformType.MEMORY,
                            "voice",
                            "sample.wav",
                            "audio/wav",
                            false,
                            null,
                            new byte[] {10, 20, 30});
            SpeechService service =
                    new SpeechService(
                            config,
                            cacheService,
                            Collections.<SpeechProvider>emptyList(),
                            Collections.<TranscriptionProvider>emptyList());

            SpeechService.TranscriptionOutcome outcome =
                    service.transcribe(attachment, Collections.<String, Object>emptyMap());

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(outcome.getProvider()).isEqualTo("openai-stt");
            assertThat(outcome.getText()).isEqualTo("转写成功");
            assertThat(authorization.get()).isEqualTo("Bearer stt-test-key");
            assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
            assertThat(requestBody.get())
                    .contains("name=\"model\"")
                    .contains("stt-model")
                    .contains("name=\"file\"")
                    .contains("filename=\"speech.wav\"")
                    .contains("name=\"language\"")
                    .contains("zh")
                    .contains("name=\"prompt\"")
                    .contains("domain-term");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnSafeTranscriptionDiagnosticWhenUnavailable() {
        AppConfig config = config("transcription-unavailable");
        SpeechService service =
                new SpeechService(
                        config,
                        new AttachmentCacheService(config),
                        Collections.<SpeechProvider>emptyList(),
                        Collections.<TranscriptionProvider>emptyList());

        MessageAttachment voice = new MessageAttachment();
        voice.setKind("voice");
        voice.setMimeType("audio/wav");
        voice.setLocalPath("media://missing.wav");
        SpeechService.TranscriptionOutcome outcome =
                service.transcribe(voice, Collections.<String, Object>emptyMap());

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getError()).contains("No available transcription provider");
    }

    @Test
    void shouldExposeImageAndSpeechTools() {
        AppConfig config = config("tools");
        AttachmentCacheService cacheService = new AttachmentCacheService(config);
        ImageGenerationService imageService =
                new ImageGenerationService(
                        config,
                        cacheService,
                        Collections.<ImageGenProvider>singletonList(
                                new FakeImageProvider(true, "data:image/png;base64,iVBORw0KGgo=")));
        SpeechService speechService =
                new SpeechService(
                        config,
                        cacheService,
                        Collections.<SpeechProvider>singletonList(new FakeSpeechProvider(true)),
                        Collections.<TranscriptionProvider>emptyList());
        MediaSpeechTools tools = new MediaSpeechTools(imageService, speechService);

        Map<String, Object> imageResult =
                ONode.deserialize(
                        tools.generateImage(
                                "画图",
                                ImageAspectRatio.square,
                                null,
                                Collections.<String>emptyList()),
                        Map.class);
        Map<String, Object> ttsResult =
                ONode.deserialize(tools.textToSpeech("你好", "zh-CN", null), Map.class);

        assertThat(imageResult.get("status")).isEqualTo("success");
        assertThat(imageResult.get("mediaReference")).asString().startsWith("media://");
        assertThat(ttsResult.get("status")).isEqualTo("success");
        assertThat(ttsResult.get("kind")).isEqualTo("voice");
    }

    @Test
    void speechTranscribeToolRejectsLocalPathOutsideMediaReferenceBoundary() throws Exception {
        AppConfig config = config("transcribe-local-path");
        File rawAudio = new File(config.getRuntime().getCacheDir(), "raw.wav");
        rawAudio.getParentFile().mkdirs();
        Files.write(rawAudio.toPath(), new byte[] {1, 2, 3});
        CountingTranscriptionProvider transcriptionProvider = new CountingTranscriptionProvider();
        SpeechService speechService =
                new SpeechService(
                        config,
                        new AttachmentCacheService(config),
                        Collections.<SpeechProvider>emptyList(),
                        Collections.<TranscriptionProvider>singletonList(transcriptionProvider));
        MediaSpeechTools tools = new MediaSpeechTools(null, speechService);

        Map<String, Object> result =
                ONode.deserialize(
                        tools.transcribeSpeech(rawAudio.getAbsolutePath(), "audio/wav", null),
                        Map.class);

        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("error")).asString().contains("media://");
        assertThat(transcriptionProvider.calls.get()).isZero();
    }

    private AppConfig config(String name) {
        AppConfig config = new AppConfig();
        config.getRuntime().setHome("target/test-runtime/" + name);
        config.getRuntime().setCacheDir("target/test-runtime/" + name + "/cache");
        return config;
    }

    private static class FakeImageProvider implements ImageGenProvider {
        private final boolean available;
        private final String url;

        private FakeImageProvider(boolean available, String url) {
            this.available = available;
            this.url = url;
        }

        @Override
        public String name() {
            return "fake-image";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public ImageGenResult generate(
                String prompt,
                String aspectRatio,
                String imageUrl,
                java.util.List<String> referenceImageUrls) {
            return ImageGenResult.ok(url);
        }
    }

    private static class AllowLocalImageSecurityPolicyService extends SecurityPolicyService {
        private AllowLocalImageSecurityPolicyService(AppConfig appConfig) {
            super(appConfig);
        }

        @Override
        public UrlVerdict checkUrlBlockingPrivate(String url) {
            if (url != null && url.contains("127.0.0.1")) {
                return UrlVerdict.allow();
            }
            return super.checkUrlBlockingPrivate(url);
        }
    }

    private static class FakeSpeechProvider implements SpeechProvider {
        private final boolean available;

        private FakeSpeechProvider(boolean available) {
            this.available = available;
        }

        @Override
        public String name() {
            return "fake-speech";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public SpeechResult synthesize(String text, String voice, Map<String, Object> options) {
            return SpeechResult.ok("audio/wav", new byte[] {1, 2, 3, 4});
        }
    }

    private static class CountingTranscriptionProvider implements TranscriptionProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "fake-transcription";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public TranscriptionResult transcribe(
                byte[] audio, String mimeType, Map<String, Object> options) {
            calls.incrementAndGet();
            return TranscriptionResult.ok("transcribed");
        }
    }
}
