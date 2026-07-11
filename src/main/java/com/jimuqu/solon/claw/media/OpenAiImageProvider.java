package com.jimuqu.solon.claw.media;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.jimuqu.solon.claw.plugin.provider.ImageGenProvider;
import com.jimuqu.solon.claw.support.BoundedAttachmentIO;
import com.jimuqu.solon.claw.support.HutoolHttpErrorFormatter;
import com.jimuqu.solon.claw.support.SecretRedactor;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.noear.snack4.ONode;

/** OpenAI 图片生成 Provider，支持文本生成和多图编辑。 */
public class OpenAiImageProvider implements ImageGenProvider {
    /** 当前固定使用的图片模型，模型选择不暴露给 Agent 工具参数。 */
    private static final String MODEL = "gpt-image-2";

    /** 当前固定质量档位，避免工具 schema 暴露但运行时静默忽略。 */
    private static final String QUALITY = "medium";

    /** 图片响应可能包含 base64 正文，因此允许比普通 JSON 更大的受控响应。 */
    private static final long MAX_RESPONSE_BYTES = 48L * 1024L * 1024L;

    /** 每次请求读取当前 Profile 的 OpenAI API 密钥。 */
    private final Supplier<String> apiKeySupplier;

    /** OpenAI Images API 基础地址。 */
    private final String baseUrl;

    /** 使用官方 OpenAI API 地址创建 Provider。 */
    public OpenAiImageProvider(String apiKey) {
        this(() -> apiKey, "https://api.openai.com/v1");
    }

    /** 使用动态凭据读取器创建 Provider，避免跨 Profile 缓存密钥。 */
    public OpenAiImageProvider(Supplier<String> apiKeySupplier) {
        this(apiKeySupplier, "https://api.openai.com/v1");
    }

    /**
     * 使用指定 API 基础地址创建 Provider，主要用于兼容端点和定向测试。
     *
     * @param apiKey API 密钥。
     * @param baseUrl Images API 基础地址。
     */
    OpenAiImageProvider(String apiKey, String baseUrl) {
        this(() -> apiKey, baseUrl);
    }

    /** 使用动态凭据读取器和指定基础地址创建 Provider。 */
    OpenAiImageProvider(Supplier<String> apiKeySupplier, String baseUrl) {
        this.apiKeySupplier = apiKeySupplier;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    /**
     * @return Provider 稳定名称。
     */
    @Override
    public String name() {
        return "openai-image";
    }

    /**
     * @return 配置了 API 密钥和基础地址时可用。
     */
    @Override
    public boolean isAvailable() {
        return StrUtil.isNotBlank(apiKey()) && StrUtil.isNotBlank(baseUrl);
    }

    /**
     * @return gpt-image-2 支持图片编辑。
     */
    @Override
    public boolean supportsImageInput() {
        return true;
    }

    /**
     * @return gpt-image-2 单次最多接收 16 张来源图片。
     */
    @Override
    public int maxSourceImages() {
        return 16;
    }

    /** 调用 OpenAI 图片生成或编辑端点。 */
    @Override
    public ImageGenResult generate(
            String prompt, String aspectRatio, String imageUrl, List<String> referenceImageUrls) {
        String apiKey = apiKey();
        if (StrUtil.isBlank(apiKey)) {
            return ImageGenResult.fail("OpenAI image provider requires OPENAI_API_KEY");
        }
        List<String> sources = sources(imageUrl, referenceImageUrls);
        if (sources.size() > maxSourceImages()) {
            return ImageGenResult.fail("OpenAI image editing accepts at most 16 source images");
        }
        try {
            HttpRequest request;
            if (sources.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("model", MODEL);
                payload.put("prompt", prompt);
                payload.put("size", size(aspectRatio));
                payload.put("quality", QUALITY);
                payload.put("n", Integer.valueOf(1));
                request =
                        HttpRequest.post(baseUrl + "/images/generations")
                                .contentType(ContentType.JSON.toString())
                                .body(ONode.serialize(payload));
            } else {
                File[] files = sourceFiles(sources);
                request =
                        HttpRequest.post(baseUrl + "/images/edits")
                                .form("model", MODEL)
                                .form("prompt", prompt)
                                .form("size", size(aspectRatio))
                                .form("quality", QUALITY)
                                .form("n", "1")
                                .form("image", files);
            }
            request.timeout(120000).setFollowRedirects(false).bearerAuth(apiKey.trim());
            try (HttpResponse response = request.execute()) {
                if (response.getStatus() < 200 || response.getStatus() >= 300) {
                    return ImageGenResult.fail(
                            HutoolHttpErrorFormatter.failure("OpenAI image request", response));
                }
                String body = BoundedAttachmentIO.readHutoolText(response, MAX_RESPONSE_BYTES);
                return parseResponse(body);
            }
        } catch (Exception e) {
            return ImageGenResult.fail(
                    StrUtil.blankToDefault(
                            SecretRedactor.redact(e.getMessage(), 400),
                            "OpenAI image request failed"));
        }
    }

    /** 读取本次调用对应 Profile 的密钥。 */
    private String apiKey() {
        return apiKeySupplier == null ? null : apiKeySupplier.get();
    }

    /** 解析 OpenAI 图片响应中的 base64 或 URL。 */
    private ImageGenResult parseResponse(String body) {
        ONode result = ONode.ofJson(body);
        String base64 = result.get("data").get(0).get("b64_json").getString();
        if (StrUtil.isNotBlank(base64)) {
            return ImageGenResult.ok("data:image/png;base64," + base64.trim());
        }
        String url = result.get("data").get(0).get("url").getString();
        return StrUtil.isBlank(url)
                ? ImageGenResult.fail("OpenAI image response contained no image")
                : ImageGenResult.ok(url.trim());
    }

    /** 汇总主图和参考图。 */
    private List<String> sources(String imageUrl, List<String> references) {
        List<String> result = new ArrayList<String>();
        if (StrUtil.isNotBlank(imageUrl)) {
            result.add(imageUrl.trim());
        }
        if (references != null) {
            for (String reference : references) {
                if (StrUtil.isNotBlank(reference)) {
                    result.add(reference.trim());
                }
            }
        }
        return result;
    }

    /** 把已由服务层校验的缓存路径转换为 multipart 文件数组。 */
    private File[] sourceFiles(List<String> sources) {
        File[] files = new File[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            File file = new File(sources.get(i));
            if (!file.isFile()) {
                throw new IllegalArgumentException("Prepared source image is missing");
            }
            files[i] = file;
        }
        return files;
    }

    /** 把工具宽高比映射为 OpenAI 支持的像素尺寸。 */
    private String size(String aspectRatio) {
        if (ImageAspectRatio.portrait.name().equals(aspectRatio)) {
            return "1024x1536";
        }
        if (ImageAspectRatio.square.name().equals(aspectRatio)) {
            return "1024x1024";
        }
        return "1536x1024";
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
