package com.example.server.storyboard;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.generation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

/** A project row lock serializes confirmation, shot versions and conservative budget reservations. */
@Service
public class ShotGenerationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StoryboardRepository storyboards;
    private final GenerationService generation;
    private final GenerationProperties properties;
    private final ObjectMapper json;
    public ShotGenerationService(JdbcTemplate jdbc, StoryboardRepository storyboards, GenerationService generation,
                                 GenerationProperties properties, ObjectMapper json) {
        this.jdbc = jdbc; this.storyboards = storyboards; this.generation = generation;
        this.properties = properties; this.json = json;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    public Budget configure(long user, String projectId, BudgetInput input) {
        if (input == null || input.maxVersions() < 1 || input.maxVersions() > 1000 || input.costLimit() == null
                || input.costLimit().signum() < 0 || input.costLimit().scale() > 6
                || input.costLimit().precision() - input.costLimit().scale() > 12) {
            throw new IllegalArgumentException("项目额度需为 1–1000 个生成版本，预留金额需为非负数且最多 6 位小数");
        }
        return transaction.execute(tx -> {
            lock(user, projectId);
            Budget old = budget(projectId);
            if (old.usedVersions() > 0) {
                if (old.maxVersions() == input.maxVersions() && old.costLimit().compareTo(input.costLimit()) == 0) return old;
                throw conflict("首次生成后项目额度固定，请在生成前核对额度");
            }
            jdbc.update("INSERT IGNORE INTO project_generation_budgets(project_id,max_versions,cost_limit) VALUES (?,?,?)",
                    projectId, input.maxVersions(), input.costLimit());
            jdbc.update("UPDATE project_generation_budgets SET max_versions=?,cost_limit=? WHERE project_id=?",
                    input.maxVersions(), input.costLimit(), projectId);
            return budget(projectId);
        });
    }
    public Quote quote(long user, String projectId, int revision) throws Exception {
        var project = owned(user, projectId);
        requireConfirmed(project, revision);
        var draft = json.readValue(storyboards.revision(projectId, revision).json(), StoryboardDraft.class);
        List<ShotPlan> plans = new ArrayList<>();
        for (var shot : draft.shots()) {
            var request = request(shot);
            var cap = generation.capabilities().stream().filter(item -> item.kind() == request.kind()).findFirst().orElseThrow();
            plans.add(new ShotPlan(shot.id(), shot.sequence(), shot.title(), request.kind(), cap.provider(), cap.model(),
                    request.imageSize(), cap.available(), cost()));
        }
        return new Quote(revision, plans, cost().multiply(BigDecimal.valueOf(plans.size())), budget(projectId));
    }
    public Submitted submit(long user, String projectId, String key, SubmitInput input) throws Exception {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Idempotency-Key 格式不合法");
        if (input == null || input.revision() < 1 || input.shotIds() == null || input.shotIds().isEmpty()
                || input.shotIds().size() > 8 || input.mode() == null || !Set.of("INITIAL", "REGENERATE").contains(input.mode())
                || (input.mode().equals("REGENERATE") && input.shotIds().size() != 1)
                || input.shotIds().stream().anyMatch(id -> id == null || !id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) {
            throw new IllegalArgumentException("请选择 1–8 个镜头；局部重生成每次只能选择一个镜头");
        }
        List<String> ids = input.shotIds().stream().map(String::toLowerCase).sorted().toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("镜头 ID 不可重复");
        var canonical = new SubmitInput(input.revision(), ids, input.mode());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        // SQL and in-memory validation only inside the transaction: no provider, image fetch or object upload.
        try {
            return transaction.execute(tx -> {
                try {
                    var project = lock(user, projectId);
                    var existing = jdbc.queryForList("SELECT id,request_hash FROM shot_generation_operations WHERE project_id=? AND idempotency_key=?", projectId, key);
                    if (!existing.isEmpty()) {
                        if (!hash.equals(existing.getFirst().get("request_hash"))) throw conflict("同一 Idempotency-Key 不可用于不同镜头生成请求");
                        return new Submitted((String) existing.getFirst().get("id"), true,
                                versions(user, projectId, (String) existing.getFirst().get("id")), budget(projectId));
                    }
                    requireConfirmed(project, input.revision());
                    var draft = json.readValue(storyboards.revision(projectId, input.revision()).json(), StoryboardDraft.class);
                    var shots = draft.shots().stream().filter(shot -> ids.contains(shot.id())).toList();
                    if (shots.size() != ids.size()) throw new NoSuchElementException("镜头不属于该分镜版本");
                    for (var shot : shots) {
                        var previous = jdbc.queryForList("SELECT task_id FROM shot_generation_versions WHERE project_id=? AND revision=? AND shot_id=? ORDER BY version DESC LIMIT 1",
                                projectId, input.revision(), shot.id());
                        if (input.mode().equals("INITIAL") && !previous.isEmpty()) throw conflict("该镜头已有生成版本，请使用局部重生成");
                        if (input.mode().equals("REGENERATE")) {
                            if (previous.isEmpty()) throw conflict("该镜头尚未生成，请先提交首次生成");
                            var state = generation.get(user, (String) previous.getFirst().get("task_id")).state();
                            if (state != GenerationTask.State.SUCCEEDED && state != GenerationTask.State.FAILED)
                                throw conflict("该镜头仍在处理中或提交结果未知，请先查询或核对原任务");
                        }
                    }
                    BigDecimal perTask = cost(), reserved = perTask.multiply(BigDecimal.valueOf(shots.size()));
                    int admitted = jdbc.update("""
                        UPDATE project_generation_budgets SET used_versions=used_versions+?,reserved_cost=reserved_cost+?
                        WHERE project_id=? AND used_versions+?<=max_versions AND reserved_cost+?<=cost_limit
                        """, shots.size(), reserved, projectId, shots.size(), reserved);
                    if (admitted != 1) throw conflict("项目生成版本数或预留预算不足，请先配置额度");
                    String operation = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
                    jdbc.update("INSERT INTO shot_generation_operations VALUES (?,?,?,?,?,?,?)", operation, projectId, key, hash, input.revision(), input.mode(), now);
                    for (var shot : shots) {
                        int version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM shot_generation_versions WHERE project_id=? AND revision=? AND shot_id=?",
                                Integer.class, projectId, input.revision(), shot.id());
                        String versionId = UUID.randomUUID().toString();
                        var submitted = generation.submit(user, "shot:" + versionId, request(shot));
                        boolean paid = !submitted.task().provider().equals("mock");
                        jdbc.update("INSERT INTO shot_generation_versions VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", versionId, projectId, input.revision(), shot.id(),
                                version, operation, submitted.task().id(), json.writeValueAsString(shot), perTask,
                                paid ? properties.getAuthorizationId() : null, paid ? properties.authorizationHash() : null, now);
                    }
                    return new Submitted(operation, false, versions(user, projectId, operation), budget(projectId));
                } catch (RuntimeException error) { throw error; }
                catch (Exception error) { throw new CheckedFailure(error); }
            });
        } catch (CheckedFailure error) { throw (Exception) error.getCause(); }
    }
    public Overview overview(long user, String projectId) {
        owned(user, projectId);
        var versions = versions(user, projectId, null);
        BigDecimal known = versions.stream().filter(item -> item.actualCost() != null).map(Version::actualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        long unknown = versions.stream().filter(item -> item.costStatus().equals("UNKNOWN")).count();
        return new Overview(budget(projectId), known, unknown, versions);
    }
    private List<Version> versions(long user, String projectId, String operation) {
        return jdbc.query("SELECT * FROM shot_generation_versions WHERE project_id=?" + (operation == null ? "" : " AND operation_id=?") + " ORDER BY created_at,revision,shot_id,version",
                (rs, index) -> {
                    var task = generation.get(user, rs.getString("task_id"));
                    boolean free = task.provider().equals("mock");
                    boolean submitted = !jdbc.queryForList("SELECT task_id FROM generation_reservations WHERE task_id=?", task.id()).isEmpty();
                    String status = free ? "FREE_MOCK" : submitted ? "UNKNOWN" : "NOT_SUBMITTED";
                    return new Version(rs.getString("id"), rs.getInt("revision"), rs.getString("shot_id"), rs.getInt("version"),
                            rs.getString("operation_id"), rs.getString("shot_json"), rs.getBigDecimal("reserved_cost"),
                            free || (!submitted && task.state() == GenerationTask.State.FAILED) ? BigDecimal.ZERO : null,
                            status, rs.getLong("created_at"), task);
                }, operation == null ? new Object[]{projectId} : new Object[]{projectId, operation});
    }
    private GenerationRequest request(StoryboardDraft.Shot shot) {
        var kind = shot.referenceAssetId() == null ? GenerationRequest.Kind.TEXT_TO_VIDEO : GenerationRequest.Kind.IMAGE_TO_VIDEO;
        String size = properties.capability(kind).getSizes().stream().filter(value -> matchesRatio(value, shot.frameRatio())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("该模型没有配置匹配分镜画幅的输出尺寸"));
        var parameters = shot.parameters();
        return new GenerationRequest(kind, shot.prompt(), parameters == null ? null : parameters.negativePrompt(), size, null,
                parameters == null ? null : parameters.seed(), shot.referenceAssetId());
    }
    private boolean matchesRatio(String size, String ratio) {
        try {
            String[] dimensions = size.split("x"), aspect = ratio.split(":");
            return dimensions.length == 2 && Long.parseLong(dimensions[0]) > 0 && Long.parseLong(dimensions[1]) > 0
                    && Math.multiplyExact(Long.parseLong(dimensions[0]), Long.parseLong(aspect[1]))
                    == Math.multiplyExact(Long.parseLong(dimensions[1]), Long.parseLong(aspect[0]));
        } catch (RuntimeException invalid) { return false; }
    }
    private BigDecimal cost() {
        if (properties.getProvider().equals("mock")) return BigDecimal.ZERO;
        var cost = properties.getReservationPerTask();
        if (cost == null || cost.signum() <= 0 || cost.scale() > 6 || cost.precision() - cost.scale() > 12)
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先配置有效的单任务预留金额与付费授权");
        return cost;
    }
    private Budget budget(String id) {
        return jdbc.query("SELECT * FROM project_generation_budgets WHERE project_id=?", (rs, row) ->
                new Budget(rs.getInt("max_versions"), rs.getBigDecimal("cost_limit"), rs.getInt("used_versions"), rs.getBigDecimal("reserved_cost")), id)
                .stream().findFirst().orElse(new Budget(0, BigDecimal.ZERO, 0, BigDecimal.ZERO));
    }
    private StoryboardRepository.Project owned(long user, String id) {
        var project = storyboards.byId(id, user);
        if (project == null) throw new NoSuchElementException("创作项目不存在");
        return project;
    }
    private StoryboardRepository.Project lock(long user, String id) {
        if (jdbc.queryForList("SELECT id FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE", id, user).isEmpty())
            throw new NoSuchElementException("创作项目不存在");
        return owned(user, id);
    }
    private void requireConfirmed(StoryboardRepository.Project project, int revision) {
        if (!"CONFIRMED".equals(project.status()) || project.latestRevision() != revision
                || !Objects.equals(project.confirmedRevision(), revision)) throw conflict("仅可提交当前已确认的分镜版本");
    }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
    private static class CheckedFailure extends RuntimeException { CheckedFailure(Exception cause) { super(cause); } }
    public record BudgetInput(int maxVersions, BigDecimal costLimit) { }
    public record Budget(int maxVersions, BigDecimal costLimit, int usedVersions, BigDecimal reservedCost) { }
    public record SubmitInput(int revision, List<String> shotIds, String mode) { }
    public record ShotPlan(String shotId, int sequence, String title, GenerationRequest.Kind kind, String provider,
                           String model, String imageSize, boolean available, BigDecimal reservation) { }
    public record Quote(int revision, List<ShotPlan> shots, BigDecimal totalReservation, Budget budget) { }
    public record Version(String id, int revision, String shotId, int version, String operationId, String shotJson,
                          BigDecimal reservation, BigDecimal actualCost, String costStatus, long createdAt, GenerationService.View task) { }
    public record Submitted(String operationId, boolean reused, List<Version> versions, Budget budget) { }
    public record Overview(Budget budget, BigDecimal knownActualCost, long unknownCostTasks, List<Version> versions) { }
}
