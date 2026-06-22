package com.jimuqu.solon.claw;

import static org.assertj.core.api.Assertions.assertThat;

import com.jimuqu.solon.claw.config.AppConfig;
import com.jimuqu.solon.claw.core.model.MessageAttachment;
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
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class MediaSpeechServiceTest {
    @Test
    void shouldReturnSafeErrorWhenImageProviderUnavailable() {
        ImageGenerationService service =
                new ImageGenerationService(
                        config("image-unavailable"),
                        new AttachmentCacheService(config("image-unavailable")),
                        Collections.<ImageGenProvider>singletonList(
                                new FakeImageProvider(false, null)));

        ImageGenerationService.ImageGenerationOutcome outcome =
                service.generate("画一张图", "1:1", Collections.<String, Object>emptyMap());

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
                service.generate("画一张图", "1:1", Collections.<String, Object>emptyMap());

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
            config.getSecurity().setAllowPrivateUrls(true);
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
                    service.generate("画一张图", "1:1", Collections.<String, Object>emptyMap());

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
                ONode.deserialize(tools.generateImage("画图", "1:1", null), Map.class);
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
                String prompt, String aspectRatio, Map<String, Object> options) {
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
