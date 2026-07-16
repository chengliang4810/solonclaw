package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.provider.ImageGenProvider;
import com.jimuqu.solon.claw.support.AttachmentCacheService;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.noear.snack4.ONode;

/** xAI 图片生成 Provider，支持文本生成和最多三张来源图片编辑。 */
public class XaiImageProvider implements ImageGenProvider {
    /** 文本生成模型。 */
    private static final String GENERATION_MODEL = "grok-imagine-image";

    /** 图片编辑模型。 */
    private static final String EDIT_MODEL = "grok-imagine-image-quality";

    /** 固定生成分辨率，当前不暴露为 Agent 工具参数。 */
    private static final String RESOLUTION = "1k";

    /** 图片响应可能包含 base64 正文，因此使用受控的大响应上限。 */
    private static final long MAX_RESPONSE_BYTES = 48L * 1024L * 1024L;

    /** 每次请求读取当前 Profile 的 xAI API 密钥。 */
    private final Supplier<String> apiKeySupplier;

    /** xAI API 基础地址。 */
    private final String baseUrl;

    /** 使用官方 xAI API 地址创建 Provider。 */
    public XaiImageProvider(String apiKey) {
        this(() -> apiKey, "https://api.x.ai/v1");
    }

    /** 使用动态凭据读取器创建 Provider，避免跨 Profile 缓存密钥。 */
    public XaiImageProvider(Supplier<String> apiKeySupplier) {
        this(apiKeySupplier, "https://api.x.ai/v1");
    }

    /**
     * 使用指定基础地址创建 Provider，主要用于定向测试。
     *
     * @param apiKey API 密钥。
     * @param baseUrl API 基础地址。
     */
    XaiImageProvider(String apiKey, String baseUrl) {
        this(() -> apiKey, baseUrl);
    }

    /** 使用动态凭据读取器和指定基础地址创建 Provider。 */
    XaiImageProvider(Supplier<String> apiKeySupplier, String baseUrl) {
        this.apiKeySupplier = apiKeySupplier;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    /**
     * @return Provider 稳定名称。
     */
    @Override
    public String name() {
        return "xai-image";
    }

    /**
     * @return 配置了 API 密钥和基础地址时可用。
     */
    @Override
    public boolean isAvailable() {
        return StrUtil.isNotBlank(apiKey()) && StrUtil.isNotBlank(baseUrl);
    }

    /**
     * @return xAI 支持图片编辑。
     */
    @Override
    public boolean supportsImageInput() {
        return true;
    }

    /**
     * @return xAI 编辑端点最多接收三张来源图片。
     */
    @Override
    public int maxSourceImages() {
        return 3;
    }

    /** 调用 xAI 图片生成或编辑端点。 */
    @Override
    public ImageGenResult generate(
            String prompt, String aspectRatio, String imageUrl, List<String> referenceImageUrls) {
        String apiKey = apiKey();
        if (StrUtil.isBlank(apiKey)) {
            return ImageGenResult.fail("xAI image provider requires XAI_API_KEY");
        }
        List<String> sources = MediaOptionHelper.imageSources(imageUrl, referenceImageUrls);
        if (sources.size() > maxSourceImages()) {
            return ImageGenResult.fail("xAI image editing accepts at most 3 source images");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            String endpoint;
            if (sources.isEmpty()) {
                payload.put("model", GENERATION_MODEL);
                payload.put("prompt", prompt);
                payload.put("aspect_ratio", aspectRatio(aspectRatio));
                payload.put("resolution", RESOLUTION);
                endpoint = baseUrl + "/images/generations";
            } else {
                payload.put("model", EDIT_MODEL);
                payload.put("prompt", prompt);
                List<Map<String, String>> images = new ArrayList<Map<String, String>>();
                for (String source : sources) {
                    Map<String, String> image = new LinkedHashMap<String, String>();
                    image.put("url", dataUrl(source));
                    image.put("type", "image_url");
                    images.add(image);
                }
                if (images.size() == 1) {
                    payload.put("image", images.get(0));
                } else {
                    payload.put("images", images);
                }
                endpoint = baseUrl + "/images/edits";
            }
            HttpRequest request =
                    HttpRequest.post(endpoint)
                            .timeout(120000)
                            .setFollowRedirects(false)
                            .bearerAuth(apiKey.trim())
                            .contentType(ContentType.JSON.toString())
                            .body(ONode.serialize(payload));
            try (HttpResponse response = request.execute()) {
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    return ImageGenResult.fail(
                            HutoolHttpErrorFormatter.failure("xAI image request", response));
                }
                String body = BoundedAttachmentIO.readHutoolText(response, MAX_RESPONSE_BYTES);
                return parseResponse(body);
            }
        } catch (Exception e) {
            return ImageGenResult.fail(
                    StrUtil.blankToDefault(
                            SecretRedactor.redact(e.getMessage(), 400),
                            "xAI image request failed"));
        }
    }

    /** 读取本次调用对应 Profile 的密钥。 */
    private String apiKey() {
        return apiKeySupplier == null ? null : apiKeySupplier.get();
    }

    /** 解析 xAI 图片响应中的 base64、URL 或公开存储 URL。 */
    private ImageGenResult parseResponse(String body) {
        ONode result = ONode.ofJson(body);
        ONode first = result.get("data").get(0);
        String publicUrl = first.get("file_output").get("public_url").getString();
        if (StrUtil.isNotBlank(publicUrl)) {
            return ImageGenResult.ok(publicUrl.trim());
        }
        String base64 = first.get("b64_json").getString();
        if (StrUtil.isNotBlank(base64)) {
            return ImageGenResult.ok("data:image/png;base64," + base64.trim());
        }
        String url = first.get("url").getString();
        return StrUtil.isBlank(url)
                ? ImageGenResult.fail("xAI image response contained no image")
                : ImageGenResult.ok(url.trim());
    }

    /** 把受控缓存文件转换为 xAI 编辑端点接受的 data URL。 */
    private String dataUrl(String source) throws Exception {
        File file = new File(source);
        if (!file.isFile()) {
            throw new IllegalArgumentException("Prepared source image is missing");
        }
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length > MediaInputBoundaryService.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Prepared source image exceeds input limit");
        }
        String mime = AttachmentCacheService.normalizeMimeType(file, null, file.getName());
        if (!mime.startsWith("image/")) {
            throw new IllegalArgumentException("Prepared source is not an image");
        }
        return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(data);
    }

    /** 把工具宽高比映射为 xAI API 枚举。 */
    private String aspectRatio(String value) {
        if (ImageAspectRatio.portrait.name().equals(value)) {
            return "9:16";
        }
        if (ImageAspectRatio.square.name().equals(value)) {
            return "1:1";
        }
        return "16:9";
    }

    /** 移除基础地址末尾斜杠。 */
    private static String trimTrailingSlash(String value) {
        String result = StrUtil.nullToEmpty(value).trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
