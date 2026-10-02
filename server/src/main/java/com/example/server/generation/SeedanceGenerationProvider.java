package com.example.server.generation;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Ark Seedance Mini: one POST, GET polling, fixed 5s/720p clips, no implicit retries. */
@Component
public class SeedanceGenerationProvider implements GenerationProvider {
    public static final String MODEL = "doubao-seedance-2-0-mini-260615";
    public static final String BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private final GenerationProperties properties;
    private final ObjectMapper json;
    private final OkHttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public SeedanceGenerationProvider(GenerationProperties properties, ObjectMapper json) {
        this(properties, json, new OkHttpClient.Builder());
    }
    SeedanceGenerationProvider(GenerationProperties properties, ObjectMapper json, OkHttpClient.Builder builder) {
        this.properties = properties; this.json = json;
        http = builder.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .callTimeout(60, TimeUnit.SECONDS).build();
    }
    public String name() { return "seedance"; }
    public void requireEnabled() {
        if (!properties.isPaidEnabled() || !name().equals(properties.getProvider()))
            throw new BusinessException(ErrorCode.FORBIDDEN, "Seedance 真实视频生成尚未启用");
        requireConfiguration();
    }
    public void requireRecoveryEnabled() {
        if (!properties.getSeedance().isRecoveryEnabled()
                && !(properties.isPaidEnabled() && name().equals(properties.getProvider())))
            throw new BusinessException(ErrorCode.FORBIDDEN, "Seedance 原任务查询与归档恢复尚未启用");
        requireConfiguration();
    }
    private void requireConfiguration() {
        var config = properties.getSeedance();
        if (config.getApiKey() == null || config.getApiKey().isBlank()
                || config.getBaseUrl() == null || !BASE_URL.equals(config.getBaseUrl().replaceAll("/+$", "")))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Seedance 需配置方舟密钥和官方北京 API 地址");
    }
    public void validateRequest(String model, GenerationRequest request) {
        parameters(model, request, true);
        if (request.kind() == GenerationRequest.Kind.IMAGE_TO_VIDEO) {
            String image = request.image();
            if (image == null || !image.matches("data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+") || image.length() > 7_000_000)
                throw new IllegalArgumentException("Seedance 图生视频需要私有 PNG/JPEG 参考图");
            try {
                byte[] bytes = Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1));
                var picture = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                if (picture == null || Math.min(picture.getWidth(), picture.getHeight()) < 300
                        || Math.max(picture.getWidth(), picture.getHeight()) > 4096)
                    throw new IllegalArgumentException("Seedance 参考图短边至少 300 像素、最长边至多 4096 像素");
            } catch (IOException invalid) { throw new IllegalArgumentException("Seedance 参考图无法解析"); }
        }
    }
    @Override public Map<String, Object> submissionParameters(String model, GenerationRequest request) {
        return parameters(model, request, true);
    }
    private static Map<String, Object> parameters(String model, GenerationRequest request, boolean redacted) {
        if (!MODEL.equals(model) || request == null || request.kind() == null || request.prompt() == null
                || request.prompt().isBlank() || request.prompt().length() > 2000
                || (request.negativePrompt() != null && !request.negativePrompt().isBlank())
                || (request.seed() != null && (request.seed() < 0 || request.seed() > Integer.MAX_VALUE)))
            throw new IllegalArgumentException("Seedance Mini 模型或生成参数不支持");
        String ratio = switch (request.imageSize()) {
            case "1280x720" -> "16:9";
            case "720x1280" -> "9:16";
            case "720x720" -> "1:1";
            default -> throw new IllegalArgumentException("Seedance 首版仅支持 720P 横屏、竖屏和方形");
        };
        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.prompt()));
        if (request.kind() == GenerationRequest.Kind.IMAGE_TO_VIDEO)
            content.add(Map.of("type", "image_url", "role", "first_frame", "image_url",
                    Map.of("url", redacted ? "[private-reference]" : request.image())));
        else if (request.image() != null || request.referenceImageId() != null)
            throw new IllegalArgumentException("文生视频不应附带图片");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model); body.put("content", content);
        // Fixed defaults are recorded in each task's effective parameters and do not drift on restart.
        body.put("duration", 5); body.put("resolution", "720p"); body.put("ratio", ratio);
        body.put("generate_audio", false); body.put("watermark", true);
        if (request.seed() != null) body.put("seed", request.seed());
        return body;
    }
    public String submit(GenerationTask task, GenerationRequest request) throws Exception {
        requireEnabled(); properties.requireAuthorization(task.model()); validateRequest(task.model(), request);
        Request call = new Request.Builder().url(BASE_URL + "/contents/generations/tasks")
                .header("Authorization", "Bearer " + properties.getSeedance().getApiKey())
                .header("X-Client-Request-Id", task.id())
                .post(RequestBody.create(json.writeValueAsBytes(parameters(task.model(), request, false)), MediaType.get("application/json"))).build();
        try {
            JsonNode result = exchange(call, true);
            JsonNode id = result == null ? null : result.path("id");
            if (id == null || !id.isTextual() || !id.textValue().matches("[A-Za-z0-9_-]{1,128}"))
                throw new Uncertain("SUBMISSION_MISSING_REQUEST_ID");
            return id.textValue();
        } catch (Uncertain safe) { throw safe; }
        catch (IOException transport) { throw new Uncertain("SUBMISSION_TRANSPORT_ERROR"); }
    }
    public Output poll(GenerationTask task) throws Exception {
        requireRecoveryEnabled();
        if (task.remoteId() == null || !task.remoteId().matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("查询 Seedance 原任务需要有效任务 ID");
        Request call = new Request.Builder().url(BASE_URL + "/contents/generations/tasks/" + task.remoteId())
                .header("Authorization", "Bearer " + properties.getSeedance().getApiKey())
                .header("X-Client-Request-Id", task.id()).get().build();
        JsonNode result = exchange(call, false);
        if (result == null || !result.path("id").asText().equals(task.remoteId())
                || !result.path("model").asText().equals(task.model()))
            throw new IOException("Seedance task identity mismatch");
        return switch (result.path("status").asText()) {
            case "queued", "running" -> new Output(Output.Status.PENDING, null);
            case "failed", "cancelled", "expired" -> new Output(Output.Status.FAILED, null);
            case "succeeded" -> {
                JsonNode url = result.path("content").path("video_url");
                if (!url.isTextual() || url.textValue().isBlank()) throw new IOException("Missing Seedance video artifact");
                yield new Output(Output.Status.SUCCEEDED, url.textValue());
            }
            default -> throw new IOException("Unknown Seedance status");
        };
    }
    private JsonNode exchange(Request request, boolean submitting) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                int status = response.code();
                if (submitting && status >= 400 && status < 500 && status != 408)
                    throw new Rejected("Seedance rejected submission: " + status, "SUBMISSION_HTTP_" + status);
                if (submitting) throw new Uncertain("SUBMISSION_HTTP_" + status);
                throw new IOException("Seedance HTTP " + status);
            }
            if (response.body() == null) {
                if (submitting) throw new Uncertain("SUBMISSION_EMPTY_RESPONSE");
                throw new IOException("Empty Seedance response");
            }
            byte[] bytes = response.body().byteStream().readNBytes(1_048_577);
            if (bytes.length > 1_048_576) {
                if (submitting) throw new Uncertain("SUBMISSION_RESPONSE_TOO_LARGE");
                throw new IOException("Seedance response too large");
            }
            try { return json.readTree(bytes); }
            catch (IOException invalid) {
                if (submitting) throw new Uncertain("SUBMISSION_INVALID_RESPONSE");
                throw new IOException("Invalid Seedance response");
            }
        }
    }
}
