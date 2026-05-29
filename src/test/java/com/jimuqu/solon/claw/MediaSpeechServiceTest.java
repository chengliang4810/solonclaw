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
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

public class MediaSpeechServiceTest {
    @Test
    void shouldReturnSafeErrorWhenImageProviderUnavailable() {
        ImageGenerationService service =
                new ImageGenerationService(
                        config("image-unavailable"),
                        new AttachmentCacheService(config("image-unavailable")),
                        Collections.<ImageGenProvider>singletonList(new FakeImageProvider(false, null)));

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

        assertThat(imageResult.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(imageResult.get("mediaReference")).asString().startsWith("media://");
        assertThat(ttsResult.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(ttsResult.get("kind")).isEqualTo("voice");
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
        public ImageGenResult generate(String prompt, String aspectRatio, Map<String, Object> options) {
            return ImageGenResult.ok(url);
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
}
