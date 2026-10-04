package com.example.server.storyboard;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.generation.GenerationAssetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.example.server.storyboard.StoryboardDraft.*;

@Service
public class StoryboardService {
    private final StoryboardRepository repository;
    private final StoryboardPlanner planner;
    private final GenerationAssetService assets;
    private final ObjectMapper json;
    public StoryboardService(StoryboardRepository repository, StoryboardPlanner planner, GenerationAssetService assets, ObjectMapper json) {
        this.repository = repository; this.planner = planner; this.assets = assets; this.json = json;
    }
    public Created create(long user, String key, CreativeBrief original) throws Exception {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Idempotency-Key 格式不合法");
        CreativeBrief brief = normalizeBrief(user, original);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsBytes(brief)));
        var existing = repository.byKey(user, key);
        if (existing != null) return reused(user, existing, hash);
        StoryboardDraft draft = null;
        List<String> errors = List.of();
        List<StoryboardRepository.Attempt> attempts = new ArrayList<>();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                draft = planner.draft(brief, errors);
                errors = validate(user, brief, draft, true);
            } catch (RuntimeException failure) {
                draft = null; errors = List.of("草稿规划失败，请人工编辑");
            }
            attempts.add(new StoryboardRepository.Attempt(attempt, json.writeValueAsString(draft), json.writeValueAsString(errors)));
            if (errors.isEmpty()) break;
        }
        boolean valid = errors.isEmpty();
        if (valid) draft = normalizeDraft(draft, null);
        else draft = new StoryboardDraft("请根据原始需求补充分镜与脚本。", List.of());
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        var revision = new StoryboardRepository.Revision(1, null, valid ? "TEMPLATE" : "MANUAL_REQUIRED", planner.name(),
                json.writeValueAsString(draft), json.writeValueAsString(errors), now);
        try {
            // No planning or storage call takes place inside the transaction.
            repository.create(id, user, key, hash, json.writeValueAsString(original), valid ? "DRAFT" : "NEEDS_EDIT", revision, attempts, now);
        } catch (DuplicateKeyException race) {
            existing = repository.byKey(user, key);
            if (existing == null) throw race;
            return reused(user, existing, hash);
        }
        return new Created(get(user, id), false);
    }
    public View get(long user, String id) throws Exception {
        var project = owned(user, id);
        return view(project, repository.revision(id, project.latestRevision()));
    }
    public List<Summary> recent(long user) throws Exception {
        List<Summary> summaries = new ArrayList<>();
        for (var project : repository.recent(user)) {
            var brief = json.readValue(project.briefJson(), CreativeBrief.class);
            summaries.add(new Summary(project.id(), brief.title(), project.status(), project.latestRevision(), project.updatedAt()));
        }
        return summaries;
    }
    public Page page(long user, String query, boolean archived, int limit, Long beforeTime, String beforeId) throws Exception {
        String search = query == null ? "" : query.trim();
        if (search.length() > 120 || limit < 1 || limit > 50 || (beforeTime == null) != (beforeId == null)
                || (beforeTime != null && (beforeTime < 0 || !beforeId.matches("[a-fA-F0-9-]{36}"))))
            throw new IllegalArgumentException("项目查询参数不合法");
        var rows = repository.page(user, search, archived, limit + 1, beforeTime, beforeId);
        var items = new ArrayList<LibraryItem>();
        for (var row : rows.stream().limit(limit).toList()) {
            var brief = json.readValue(row.briefJson(), CreativeBrief.class);
            items.add(new LibraryItem(row.id(), brief.title(), row.status(), row.latestRevision(), row.updatedAt(), row.archivedAt() != null));
        }
        var last = items.isEmpty() ? null : items.getLast();
        return new Page(items, rows.size() > limit ? new Cursor(last.updatedAt(), last.id()) : null);
    }
    public View archive(long user, String id, boolean archived) throws Exception {
        repository.archive(user, id, archived, System.currentTimeMillis());
        return get(user, id);
    }
    public History history(long user, String id) throws Exception {
        owned(user, id);
        List<Revision> revisions = new ArrayList<>();
        for (var stored : repository.revisions(id)) revisions.add(revision(stored));
        List<PlanningAttempt> attempts = new ArrayList<>();
        for (var attempt : repository.attempts(id)) attempts.add(new PlanningAttempt(attempt.number(),
                json.readValue(attempt.json(), StoryboardDraft.class), errors(attempt.validationJson())));
        return new History(revisions, attempts, repository.confirmations(id));
    }
    public View edit(long user, String id, int expected, StoryboardDraft input) throws Exception {
        checkExpected(expected);
        var project = owned(user, id);
        StoryboardRepository.requireActive(project);
        var brief = normalizeBrief(user, json.readValue(project.briefJson(), CreativeBrief.class));
        List<String> errors = validate(user, brief, input, false);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        // Derive prompt versions from the immutable parent, never trust the client's version counter.
        var parents = repository.revisions(id);
        var parent = parents.stream().filter(item -> item.number() == expected).findFirst()
                .orElseThrow(() -> conflict("分镜版本已变化，请重新读取后保存"));
        var draft = normalizeDraft(input, json.readValue(parent.json(), StoryboardDraft.class));
        String payload = json.writeValueAsString(draft);
        if (repository.edit(id, user, expected, payload, System.currentTimeMillis())) return get(user, id);
        var current = owned(user, id);
        var saved = repository.revision(id, current.latestRevision());
        if (current.latestRevision() == expected + 1 && saved.json().equals(payload) && "USER".equals(saved.origin())) {
            // A lost save response is safe to repeat with the same parent and edited content.
            return view(current, saved);
        }
        throw conflict("分镜版本已变化，请重新读取后保存；本次编辑未覆盖已有版本");
    }
    public View confirm(long user, String id, int expected) throws Exception {
        checkExpected(expected);
        var project = owned(user, id);
        StoryboardRepository.requireActive(project);
        if (project.latestRevision() != expected) throw conflict("仅可确认当前分镜版本");
        var draft = json.readValue(repository.revision(id, expected).json(), StoryboardDraft.class);
        var errors = validate(user, normalizeBrief(user, json.readValue(project.briefJson(), CreativeBrief.class)), draft, false);
        if (!errors.isEmpty()) throw conflict("分镜结构未通过校验，请先编辑保存");
        if (!repository.confirm(id, user, expected, System.currentTimeMillis())) throw conflict("分镜版本已变化，请重新核对后确认");
        return get(user, id);
    }
    private Created reused(long user, StoryboardRepository.Project project, String hash) throws Exception {
        if (!project.briefHash().equals(hash)) throw conflict("同一 Idempotency-Key 不可用于不同创作需求");
        return new Created(get(user, project.id()), true);
    }
    private StoryboardRepository.Project owned(long user, String id) {
        var project = repository.byId(id, user);
        if (project == null) throw new NoSuchElementException("创作项目不存在");
        return project;
    }
    private View view(StoryboardRepository.Project project, StoryboardRepository.Revision current) throws Exception {
        return new View(project.id(), project.status(), json.readValue(project.briefJson(), CreativeBrief.class), revision(current),
                project.confirmedRevision(), project.createdAt(), project.updatedAt(), project.archivedAt() != null);
    }
    private Revision revision(StoryboardRepository.Revision revision) throws Exception {
        return new Revision(revision.number(), revision.parent(), revision.origin(), revision.planner(),
                json.readValue(revision.json(), StoryboardDraft.class), errors(revision.validationJson()), revision.createdAt());
    }
    private List<String> errors(String text) throws Exception { return json.readValue(text, new TypeReference<List<String>>() { }); }
    private CreativeBrief normalizeBrief(long user, CreativeBrief brief) {
        if (brief == null) throw new IllegalArgumentException("创作需求不能为空");
        if (!text(brief.title(), 120, true) || !text(brief.goal(), 1000, true) || !text(brief.productName(), 80, true)
                || !text(brief.style(), 120, true) || brief.sellingPoints() == null || brief.sellingPoints().isEmpty()
                || brief.sellingPoints().size() > 8 || brief.sellingPoints().stream().anyMatch(point -> !text(point, 120, true))
                || !Set.of("16:9", "9:16", "1:1").contains(brief.frameRatio() == null ? "" : brief.frameRatio())
                || brief.shotCount() < 2 || brief.shotCount() > 8 || brief.desiredDurationSeconds() < brief.shotCount() * 2
                || brief.desiredDurationSeconds() > brief.shotCount() * 15) {
            throw new IllegalArgumentException("创作需求不合法：2–8 个镜头，期望总时长需在镜头数 × 2 到镜头数 × 15 秒之间");
        }
        String image = blank(brief.referenceAssetId());
        if (image != null) assets.owned(user, image);
        return new CreativeBrief(brief.title().trim(), brief.goal().trim(), brief.productName().trim(),
                brief.sellingPoints().stream().map(String::trim).toList(), brief.style().trim(), brief.desiredDurationSeconds(),
                brief.frameRatio(), image, brief.shotCount());
    }
    public List<String> validatePlanned(long user, CreativeBrief brief, StoryboardDraft draft) { return validate(user, brief, draft, true); }
    public CreativeBrief normalizePlanningBrief(long user, CreativeBrief brief) { return normalizeBrief(user, brief); }
    private List<String> validate(long user, CreativeBrief brief, StoryboardDraft draft, boolean planned) {
        List<String> errors = new ArrayList<>();
        if (draft == null) return List.of("草稿为空");
        if (!text(draft.script(), 4000, true)) errors.add("脚本需为 1–4000 字");
        if (draft.shots() == null || draft.shots().size() < 2 || draft.shots().size() > 8) {
            errors.add("分镜需包含 2–8 个镜头"); return errors;
        }
        if (planned && draft.shots().size() != brief.shotCount()) errors.add("草稿镜头数与需求不一致");
        Set<String> ids = new HashSet<>();
        int duration = 0;
        for (int index = 0; index < draft.shots().size(); index++) {
            Shot shot = draft.shots().get(index);
            String prefix = "第 " + (index + 1) + " 镜头：";
            if (shot == null) { errors.add(prefix + "镜头为空"); continue; }
            if (shot.id() == null || !shot.id().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") || !ids.add(shot.id().toLowerCase())) errors.add(prefix + "镜头 ID 无效或重复");
            if (shot.sequence() != index + 1) errors.add(prefix + "序号需连续");
            if (!text(shot.title(), 120, true) || !text(shot.subject(), 200, true) || !text(shot.action(), 500, true)
                    || !text(shot.setting(), 500, true) || !text(shot.camera(), 200, true)) errors.add(prefix + "画面字段缺失或过长");
            if (!text(shot.caption(), 120, false) || !text(shot.narration(), 500, false) || !text(shot.prompt(), 2000, true)) errors.add(prefix + "字幕、口播或提示词不合法");
            if (shot.desiredDurationSeconds() < 2 || shot.desiredDurationSeconds() > 15) errors.add(prefix + "期望时长需为 2–15 秒");
            duration += Math.max(0, Math.min(shot.desiredDurationSeconds(), 1000));
            if (!brief.frameRatio().equals(shot.frameRatio())) errors.add(prefix + "画幅需与项目一致");
            if (shot.parameters() != null && (!text(shot.parameters().negativePrompt(), 2000, false)
                    || (shot.parameters().seed() != null && shot.parameters().seed() < 0))) errors.add(prefix + "生成参数不合法");
            if (blank(shot.referenceAssetId()) != null) {
                try { assets.owned(user, shot.referenceAssetId()); }
                catch (NoSuchElementException missing) { errors.add(prefix + "参考素材不可用"); }
            }
        }
        if (duration != brief.desiredDurationSeconds()) errors.add("镜头期望时长之和需为 " + brief.desiredDurationSeconds() + " 秒");
        return errors.stream().limit(12).toList();
    }
    private StoryboardDraft normalizeDraft(StoryboardDraft input, StoryboardDraft parent) {
        Map<String, Shot> previous = new HashMap<>();
        if (parent != null && parent.shots() != null) for (Shot shot : parent.shots()) previous.put(shot.id().toLowerCase(), shot);
        List<Shot> shots = new ArrayList<>();
        for (Shot shot : input.shots()) {
            Shot old = previous.get(shot.id().toLowerCase());
            String prompt = shot.prompt().trim();
            int version = old == null ? 1 : old.promptVersion() + (old.prompt().equals(prompt) ? 0 : 1);
            var parameters = shot.parameters() == null ? new Parameters(null, null)
                    : new Parameters(blank(shot.parameters().negativePrompt()), shot.parameters().seed());
            shots.add(new Shot(shot.id().toLowerCase(), shot.sequence(), shot.title().trim(), shot.subject().trim(), shot.action().trim(),
                    shot.setting().trim(), shot.camera().trim(), blank(shot.referenceAssetId()), shot.desiredDurationSeconds(), shot.frameRatio(),
                    shot.caption() == null ? "" : shot.caption().trim(), shot.narration() == null ? "" : shot.narration().trim(), prompt, version, parameters));
        }
        return new StoryboardDraft(input.script().trim(), shots);
    }
    private static boolean text(String value, int max, boolean required) { return value == null ? !required : value.length() <= max && (!required || !value.isBlank()); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void checkExpected(int number) { if (number < 1 || number == Integer.MAX_VALUE) throw new IllegalArgumentException("分镜版本号不合法"); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
    public record Created(View project, boolean reused) { }
    public record View(String id, String status, CreativeBrief brief, Revision revision, Integer confirmedRevision, long createdAt, long updatedAt, boolean archived) { }
    public record Revision(int number, Integer parent, String origin, String planner, StoryboardDraft draft, List<String> validationErrors, long createdAt) { }
    public record Summary(String id, String title, String status, int revision, long updatedAt) { }
    public record LibraryItem(String id, String title, String status, int revision, long updatedAt, boolean archived) { }
    public record Cursor(long updatedAt, String id) { }
    public record Page(List<LibraryItem> items, Cursor nextCursor) { }
    public record PlanningAttempt(int number, StoryboardDraft draft, List<String> validationErrors) { }
    public record History(List<Revision> revisions, List<PlanningAttempt> attempts, List<StoryboardRepository.Confirmation> confirmations) { }
}
