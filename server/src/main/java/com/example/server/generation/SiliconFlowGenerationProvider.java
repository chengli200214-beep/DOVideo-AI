package com.example.server.generation;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class SiliconFlowGenerationProvider implements GenerationProvider {
    private final GenerationProperties properties;
    private final ObjectMapper json;
    // Never retry a POST submission behind the state machine's back.
    private final OkHttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public SiliconFlowGenerationProvider(GenerationProperties properties, ObjectMapper json) {
        this(properties, json, new OkHttpClient.Builder());
    }
    SiliconFlowGenerationProvider(GenerationProperties properties, ObjectMapper json, OkHttpClient.Builder builder) {
        this.properties = properties;
        this.json = json;
        this.http = builder.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .callTimeout(60, TimeUnit.SECONDS).build();
    }
    public String name() { return "siliconflow"; }
    public void requireEnabled() {
        if (!properties.isPaidEnabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "真实视频生成调用尚未启用");
        }
        requireConfiguration();
    }
    public void requireRecoveryEnabled() {
        if (!properties.isPaidEnabled() && !properties.isRecoveryEnabled())
            throw new BusinessException(ErrorCode.FORBIDDEN, "原视频任务查询与归档恢复尚未启用");
        requireConfiguration();
    }
    private void requireConfiguration() {
        URI base = URI.create(properties.getBaseUrl());
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || properties.getApiKey().isBlank() || properties.getArtifactHosts().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "视频生成服务配置不完整");
        }
    }
    public String submit(GenerationTask task, GenerationRequest request) throws Exception {
        requireEnabled();
        properties.requireAuthorization(task.model());
        Map<String, Object> body = submissionBody(task.model(), request);
        try {
            JsonNode result = post("/video/submit", body, task.id(), true);
            JsonNode id = result == null ? null : result.path("requestId");
            if (id == null || !id.isTextual() || !id.textValue().matches("[A-Za-z0-9_-]{1,128}"))
                throw new Uncertain("SUBMISSION_MISSING_REQUEST_ID");
            return id.textValue();
        } catch (Uncertain diagnostic) { throw diagnostic; }
        catch (IOException transport) { throw new Uncertain("SUBMISSION_TRANSPORT_ERROR"); }
    }
    static Map<String, Object> submissionBody(String model, GenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", request.prompt());
        body.put("image_size", request.imageSize());
        if (request.negativePrompt() != null) body.put("negative_prompt", request.negativePrompt());
        if (request.image() != null) body.put("image", request.image());
        if (request.seed() != null) body.put("seed", request.seed());
        return body;
    }
    public Output poll(GenerationTask task) throws Exception {
        requireRecoveryEnabled();
        if (task.remoteId() == null || !task.remoteId().matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("查询原任务需要有效 requestId");
        JsonNode result = post("/video/status", Map.of("requestId", task.remoteId()), task.id(), false);
        return switch (result.path("status").asText()) {
            case "InQueue", "InProgress" -> new Output(Output.Status.PENDING, null);
            case "Failed" -> new Output(Output.Status.FAILED, null);
            case "Succeed" -> {
                JsonNode videos = result.path("results").path("videos");
                if (!videos.isArray() || videos.size() != 1 || videos.get(0).path("url").asText().isBlank()) {
                    throw new IOException("Expected exactly one video artifact");
                }
                yield new Output(Output.Status.SUCCEEDED, videos.get(0).path("url").asText());
            }
            default -> throw new IOException("Unknown provider status");
        };
    }
    private JsonNode post(String path, Object payload, String trace, boolean submitting) throws Exception {
        Request request = new Request.Builder().url(properties.getBaseUrl().replaceAll("/+$", "") + path)
                .header("Authorization", "Bearer " + properties.getApiKey()).header("X-Trace-Id", trace)
                .post(RequestBody.create(json.writeValueAsBytes(payload), MediaType.get("application/json"))).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                int code = response.code();
                if (submitting && code >= 400 && code < 500 && code != 408) {
                    throw new Rejected("Provider rejected submission: " + code, "SUBMISSION_HTTP_" + code);
                }
                if (submitting) throw new Uncertain("SUBMISSION_HTTP_" + code);
                throw new IOException("Provider HTTP " + code);
            }
            if (response.body() == null) {
                if (submitting) throw new Uncertain("SUBMISSION_EMPTY_RESPONSE");
                throw new IOException("Empty provider response");
            }
            // Bound response parsing and do not log credentials, prompts, or signed URLs.
            byte[] bytes = response.body().byteStream().readNBytes(1_048_577);
            if (bytes.length > 1_048_576) {
                if (submitting) throw new Uncertain("SUBMISSION_RESPONSE_TOO_LARGE");
                throw new IOException("Provider response too large");
            }
            try { return json.readTree(bytes); }
            catch (IOException invalid) {
                if (submitting) throw new Uncertain("SUBMISSION_INVALID_RESPONSE");
                throw invalid;
            }
        }
    }
}
