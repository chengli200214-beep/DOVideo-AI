package com.example.server.generation;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class GenerationService {
    private final GenerationRepository repository;
    private final GenerationProperties properties;
    private final List<GenerationProvider> providers;
    private final ObjectMapper json;
    private final GenerationArtifactStore artifacts;
    private final GenerationAssetService assets;

    public GenerationService(GenerationRepository repository, GenerationProperties properties,
            List<GenerationProvider> providers, ObjectMapper json, GenerationArtifactStore artifacts, GenerationAssetService assets) {
        this.repository = repository;
        this.properties = properties;
        this.providers = providers;
        this.json = json;
        this.artifacts = artifacts;
        this.assets = assets;
    }

    public Submission submit(long user, String key, GenerationRequest input) throws Exception {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key 需为 1 到 128 位 ASCII 字母、数字或 _.:-");
        }
        GenerationRequest request = normalize(user, input);
        String payload = json.writeValueAsString(request);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        GenerationTask existing = repository.byKey(user, key);
        if (existing != null) return reused(existing, hash);
        GenerationProvider provider = provider(properties.getProvider());
        provider.requireEnabled();
        String model = provider.name().equals("mock") ? "mock-video"
                : request.kind() == GenerationRequest.Kind.TEXT_TO_VIDEO
                ? properties.getTextModel() : properties.getImageModel();
        if (model == null || model.isBlank()) throw new IllegalArgumentException("模型尚未配置");
        if (!provider.name().equals("mock")) properties.requireAuthorization(model);
        String id = UUID.randomUUID().toString();
        try {
            repository.insert(id, user, key, hash, provider.name(), model, payload, input.prompt(), System.currentTimeMillis());
            return new Submission(view(repository.byId(id)), false);
        } catch (DuplicateKeyException duplicate) {
            existing = repository.byKey(user, key);
            if (existing == null) throw duplicate;
            return reused(existing, hash);
        }
    }

    public View get(long user, String id) { return view(owned(user, id)); }
    public List<View> recent(long user) { return repository.recent(user).stream().map(this::view).toList(); }
    public Trace trace(long user, String id) throws Exception {
        owned(user, id);
        var detail = repository.details(id);
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree((String) detail.get("request_json"));
        if (input.hasNonNull("image")) { input.remove("image"); input.put("legacyInlineImage", true); }
        return new Trace((String) detail.get("original_prompt"), input,
                detail.get("effective_json") == null ? null : json.readTree((String) detail.get("effective_json")), repository.events(id));
    }
    public List<ModelCapability> capabilities() {
        return java.util.Arrays.stream(GenerationRequest.Kind.values()).map(kind -> {
            var cap = properties.capability(kind);
            String model = properties.getProvider().equals("mock") ? "mock-video" :
                    kind == GenerationRequest.Kind.TEXT_TO_VIDEO ? properties.getTextModel() : properties.getImageModel();
            boolean available = cap.isEnabled();
            try {
                provider(properties.getProvider()).requireEnabled();
                if (!properties.getProvider().equals("mock")) properties.requireAuthorization(model);
            } catch (RuntimeException blocked) { available = false; }
            return new ModelCapability(kind, properties.getProvider(), model, cap.isEnabled(), available,
                    cap.getSizes(), cap.isNegativePrompt(), cap.isSeed(), Math.min(2000, cap.getMaxPromptLength()),
                    kind == GenerationRequest.Kind.IMAGE_TO_VIDEO, 5 * 1024 * 1024, 4096);
        }).toList();
    }
    PreparedSubmission prepare(GenerationTask task) throws Exception {
        var stored = json.readValue(task.requestJson(), GenerationRequest.class);
        var selectedProvider = provider(task.provider());
        var parameters = selectedProvider.submissionParameters(task.model(), stored);
        var audit = new java.util.LinkedHashMap<String, Object>();
        audit.put("parameters", parameters);
        GenerationRequest actual = stored;
        if (stored.referenceImageId() != null) {
            var asset = assets.owned(task.userId(), stored.referenceImageId());
            audit.put("imageReference", asset);
            audit.put("redactedFields", List.of("image", "content.image_url.url"));
            actual = new GenerationRequest(stored.kind(), stored.prompt(), stored.negativePrompt(), stored.imageSize(),
                    assets.submissionImage(task.userId(), stored.referenceImageId()), stored.seed(), stored.referenceImageId());
        } else if (stored.image() != null) {
            // Legacy V4 queued requests remain readable, but never expose their embedded image in the trace.
            audit.put("redactedFields", List.of("image", "content.image_url.url"));
        }
        selectedProvider.validateRequest(task.model(), actual);
        return new PreparedSubmission(actual, json.writeValueAsString(audit));
    }
    boolean beginSubmission(GenerationTask task, PreparedSubmission submission, long now) {
        return repository.beginSubmission(task, now, submission.auditJson(), properties);
    }
    public String artifact(long user, String id) {
        GenerationTask task = owned(user, id);
        if (task.state() != GenerationTask.State.SUCCEEDED || task.artifactKey() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "视频产物尚未保存完成");
        }
        return artifacts.readableUrl(task.artifactKey());
    }
    public View retry(long user, String id) {
        GenerationTask task = owned(user, id);
        provider(task.provider()).requireRecoveryEnabled();
        if (!repository.retry(id, user, System.currentTimeMillis())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该任务不能恢复；仅查询或保存失败且已有模型任务 ID 时可恢复");
        }
        return get(user, id);
    }
    public View reconcile(long user, String id, String remoteId) {
        GenerationTask task = owned(user, id);
        if (remoteId == null || !remoteId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("requestId 格式不合法");
        }
        provider(task.provider()).requireRecoveryEnabled();
        try {
            if (!repository.reconcile(id, user, remoteId, System.currentTimeMillis())) {
                throw new BusinessException(ErrorCode.CONFLICT, "仅提交结果未知的任务允许补录 requestId");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(ErrorCode.CONFLICT, "该模型任务 ID 已绑定其他任务");
        }
        return get(user, id);
    }

    GenerationProvider provider(String name) {
        return providers.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "视频生成 provider 不可用"));
    }
    private GenerationTask owned(long user, String id) {
        GenerationTask task = repository.byId(id);
        if (task == null || task.userId() != user) throw new NoSuchElementException("生成任务不存在");
        return task;
    }
    private Submission reused(GenerationTask existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一 Idempotency-Key 不可用于不同生成参数");
        }
        return new Submission(view(existing), true);
    }
    private View view(GenerationTask task) {
        boolean recoveryAvailable = true;
        try { provider(task.provider()).requireRecoveryEnabled(); }
        catch (RuntimeException disabled) { recoveryAvailable = false; }
        return new View(task.id(), task.provider(), task.model(), task.state(), task.remoteId(),
                task.artifactKey(), task.artifactSize(), task.artifactSha256(), task.errorCode(),
                task.recoverable(), task.attempts(), task.createdAt(), task.updatedAt(), recoveryAvailable);
    }
    private GenerationRequest normalize(long user, GenerationRequest request) throws Exception {
        if (request == null || request.kind() == null) throw new IllegalArgumentException("生成模式不能为空");
        var cap = properties.capability(request.kind());
        if (request == null || request.kind() == null || request.prompt() == null
                || !cap.isEnabled() || request.prompt().isBlank() || request.prompt().length() > Math.min(2000, cap.getMaxPromptLength())
                || (request.negativePrompt() != null && (request.negativePrompt().length() > 2000
                        || (!request.negativePrompt().isBlank() && !cap.isNegativePrompt())))
                || !cap.getSizes().contains(request.imageSize() == null ? "" : request.imageSize())
                || (request.seed() != null && !cap.isSeed())
                || (request.seed() != null && request.seed() < 0)) {
            throw new IllegalArgumentException("生成参数不合法");
        }
        String image = request.image();
        String imageId = request.referenceImageId();
        if (request.kind() == GenerationRequest.Kind.TEXT_TO_VIDEO) {
            if ((image != null && !image.isBlank()) || imageId != null) throw new IllegalArgumentException("文生视频不应附带图片");
            image = null;
        } else {
            if (imageId != null) {
                if (image != null && !image.isBlank()) throw new IllegalArgumentException("图片与素材 ID 只能提供一项");
                assets.owned(user, imageId);
            } else imageId = assets.inline(user, image).id();
            image = null;
        }
        return new GenerationRequest(request.kind(), request.prompt().trim(),
                request.negativePrompt() == null || request.negativePrompt().isBlank() ? null : request.negativePrompt().trim(),
                request.imageSize(), image, request.seed(), imageId);
    }

    public record Submission(View task, boolean reused) { }
    record PreparedSubmission(GenerationRequest request, String auditJson) { }
    public record Trace(String originalPrompt, com.fasterxml.jackson.databind.JsonNode input,
                        com.fasterxml.jackson.databind.JsonNode effectiveSubmission, List<GenerationRepository.Event> events) { }
    public record ModelCapability(GenerationRequest.Kind kind, String provider, String model, boolean enabled,
            boolean available, List<String> sizes, boolean negativePrompt, boolean seed, int maxPromptLength,
            boolean referenceImageRequired, int maxImageBytes, int maxImageEdge) { }
    public record View(String id, String provider, String model, GenerationTask.State state,
            String remoteId, String artifactKey, Long artifactSize, String artifactSha256,
            String errorCode, boolean recoverable, int attempts, long createdAt, long updatedAt, boolean recoveryAvailable) { }
}
