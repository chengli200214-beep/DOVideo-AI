package com.example.server.film;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.generation.*;
import com.example.server.storyboard.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

@Service
public class QualityEvaluationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction,readTransaction;
    private final StoryboardRepository projects;
    private final ShotGenerationService shots;
    private final GenerationService generation;
    private final ObjectMapper json;
    private final List<EvaluationCase> cases;
    public QualityEvaluationService(JdbcTemplate jdbc,StoryboardRepository projects,ShotGenerationService shots,GenerationService generation,ObjectMapper json) throws Exception {
        this.jdbc=jdbc; this.projects=projects; this.shots=shots; this.generation=generation; this.json=json;
        var manager=new DataSourceTransactionManager(jdbc.getDataSource()); transaction=new TransactionTemplate(manager); readTransaction=new TransactionTemplate(manager);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); readTransaction.setReadOnly(true);
        try(var input=new ClassPathResource("generation/evaluation-cases.json").getInputStream()) { cases=json.readValue(input,new TypeReference<List<EvaluationCase>>(){}); }
    }
    public List<EvaluationCase> cases() { return cases; }
    public Review review(long user,String projectId,String versionId,ReviewInput input) {
        if(input==null || input.expectedReviewVersion()<0 || input.expectedReviewVersion()==Integer.MAX_VALUE || !score(input.contentScore()) || !score(input.motionScore()) || !score(input.consistencyScore())
                || input.notes()==null || input.notes().length()>2000) throw new IllegalArgumentException("三个评分均需为 1–5，评价文字最多 2000 字");
        return transaction.execute(tx -> {
            if(jdbc.queryForList("SELECT id FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE",projectId,user).isEmpty()) throw new NoSuchElementException("创作项目不存在");
            StoryboardRepository.requireActive(projects.byId(projectId,user));
            var owned=jdbc.queryForList("SELECT task_id FROM shot_generation_versions WHERE id=? AND project_id=?",versionId,projectId);
            if(owned.isEmpty()) throw new NoSuchElementException("镜头生成版本不存在");
            if(generation.get(user,(String)owned.getFirst().get("task_id")).state()!=GenerationTask.State.SUCCEEDED) throw conflict("只能对已归档视频进行质量评分");
            Review current=latest(versionId,Long.MAX_VALUE); int expected=input.expectedReviewVersion();
            if(current!=null && current.number()==expected+1 && current.contentScore()==input.contentScore() && current.motionScore()==input.motionScore()
                    && current.consistencyScore()==input.consistencyScore() && current.usable()==input.usable() && current.notes().equals(input.notes().trim())) return current;
            if((current==null?0:current.number())!=expected) throw conflict("质量评价已变化，请刷新后核对");
            long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO generation_quality_reviews VALUES (?,?,?,?,?,?,?,?)",versionId,expected+1,input.contentScore(),input.motionScore(),input.consistencyScore(),input.usable(),input.notes().trim(),now);
            return latest(versionId,Long.MAX_VALUE);
        });
    }
    public Report report(long user,String id) throws Exception {
        try { return readTransaction.execute(tx -> { try { return snapshot(user,id); } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new CheckedFailure(e); } }); }
        catch(CheckedFailure e) { throw (Exception)e.getCause(); }
    }
    private Report snapshot(long user,String id) throws Exception {
        if(projects.byId(id,user)==null) throw new NoSuchElementException("创作项目不存在");
        long cutoff=System.currentTimeMillis(); var overview=shots.overview(user,id);
        List<Row> rows=new ArrayList<>(); List<Long> completed=new ArrayList<>();
        Map<String,Integer> states=new LinkedHashMap<>(); for(var state:GenerationTask.State.values()) states.put(state.name(),0);
        Map<String,List<Row>> groups=new LinkedHashMap<>();
        for(var version:overview.versions()) {
            if(version.createdAt()>cutoff) continue;
            var task=version.task(); var trace=generation.trace(user,task.id()); var input=trace.input();
            String caseId="CUSTOM",variant="CUSTOM";
            for(var fixed:cases) if(input.path("seed").isIntegralNumber() && input.path("seed").asLong()==fixed.seed()) {
                if(input.path("prompt").asText().equals(fixed.originalPrompt())) { caseId=fixed.id(); variant="ORIGINAL"; break; }
                if(input.path("prompt").asText().equals(fixed.structuredPrompt())) { caseId=fixed.id(); variant="STRUCTURED"; break; }
            }
            String reference=input.path("referenceImageId").isNull()?null:input.path("referenceImageId").asText(null);
            String referenceHash="";
            if(reference!=null) referenceHash=jdbc.queryForObject("SELECT sha256 FROM generation_assets WHERE id=? AND user_id=?",String.class,reference,user);
            String controls=task.provider()+"|"+task.model()+"|"+input.path("kind").asText()+"|"+input.path("imageSize").asText()+"|"+input.path("seed")+"|"+input.path("negativePrompt")+"|"+referenceHash;
            String controlHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(controls.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Long duration=null;
            if(task.state()==GenerationTask.State.SUCCEEDED) {
                var event=trace.events().stream().filter(item -> item.state().equals("SUCCEEDED") && item.occurredAt()<=cutoff).findFirst();
                if(event.isPresent()) { duration=Math.max(0,event.get().occurredAt()-task.createdAt()); completed.add(duration); }
            }
            var row=new Row(version.id(),version.revision(),version.shotId(),version.version(),task.id(),task.provider(),task.model(),task.state().name(),caseId,variant,controlHash,
                input,version.reservation(),version.actualCost(),version.costStatus(),duration,latest(version.id(),cutoff));
            rows.add(row); states.merge(row.state(),1,Integer::sum);
            groups.computeIfAbsent(caseId+":"+controlHash,key -> new ArrayList<>()).add(row);
        }
        int success=(int)rows.stream().filter(row -> row.state().equals("SUCCEEDED")).count();
        int reviewed=(int)rows.stream().filter(row -> row.review()!=null).count();
        int usable=(int)rows.stream().filter(row -> row.review()!=null && row.review().usable() && row.state().equals("SUCCEEDED")).count();
        int mock=(int)rows.stream().filter(row -> row.provider().equals("mock")).count();
        long unknown=rows.stream().filter(row -> row.costStatus().equals("UNKNOWN")).count();
        long unsettled=rows.stream().filter(row -> row.actualCost()==null).count();
        BigDecimal known=rows.stream().map(Row::actualCost).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        Double average=reviewed==0?null:rows.stream().filter(row -> row.review()!=null).mapToDouble(row -> (row.review().contentScore()+row.review().motionScore()+row.review().consistencyScore())/3.0).average().orElseThrow();
        completed.sort(Long::compareTo);
        Double median=completed.isEmpty()?null:completed.size()%2==1?completed.get(completed.size()/2).doubleValue():(completed.get(completed.size()/2-1)/2.0+completed.get(completed.size()/2)/2.0);
        List<Comparison> comparisons=new ArrayList<>();
        for(var group:groups.values()) {
            var first=group.getFirst(); if(first.caseId().equals("CUSTOM")) continue;
            var original=group.stream().filter(row -> row.variant().equals("ORIGINAL")).toList();
            var structured=group.stream().filter(row -> row.variant().equals("STRUCTURED")).toList();
            boolean comparable=!first.provider().equals("mock") && original.stream().anyMatch(row -> row.review()!=null) && structured.stream().anyMatch(row -> row.review()!=null);
            comparisons.add(new Comparison(first.caseId(),first.controlHash(),first.provider(),first.model(),stats(original),stats(structured),comparable));
        }
        var summary=new Summary(rows.size(),states,success,reviewed,usable,rows.size()-reviewed,mock,unknown,unsettled,known,
            overview.budget().reservedCost(),rows.isEmpty()?null:success/(double)rows.size(),average,completed.size(),median,
            usable==0 || unsettled>0?null:known.divide(BigDecimal.valueOf(usable),6,java.math.RoundingMode.HALF_UP));
        return new Report("aigc-evaluation-v1",id,cutoff,summary,comparisons,rows,
            "All shot versions including failed, pending and unreviewed are counted. Scores are human input. Mock is workflow-only; it is never comparable as real model quality. Actual supplier costs remain unknown without billing reconciliation.");
    }
    private VariantStats stats(List<Row> rows) {
        var scored=rows.stream().filter(row -> row.review()!=null).toList();
        Double score=scored.isEmpty()?null:scored.stream().mapToDouble(row -> (row.review().contentScore()+row.review().motionScore()+row.review().consistencyScore())/3.0).average().orElseThrow();
        return new VariantStats(rows.size(),(int)rows.stream().filter(row -> row.state().equals("SUCCEEDED")).count(),scored.size(),(int)scored.stream().filter(row -> row.review().usable()).count(),score);
    }
    private Review latest(String id,long cutoff) { return jdbc.query("SELECT * FROM generation_quality_reviews WHERE version_id=? AND created_at<=? ORDER BY review_version DESC LIMIT 1",(rs,i) -> new Review(rs.getInt("review_version"),rs.getInt("content_score"),rs.getInt("motion_score"),rs.getInt("consistency_score"),rs.getBoolean("usable"),rs.getString("notes"),rs.getLong("created_at")),id,cutoff).stream().findFirst().orElse(null); }
    public String csv(Report report) {
        StringBuilder out=new StringBuilder("\uFEFFversion_id,revision,shot_id,version,provider,model,state,case,variant,prompt,reservation,actual_cost,cost_status,completed_ms,content_score,motion_score,consistency_score,usable,notes\r\n");
        for(var row:report.rows()) {
            var r=row.review(); Object[] fields={row.versionId(),row.revision(),row.shotId(),row.version(),row.provider(),row.model(),row.state(),row.caseId(),row.variant(),row.input().path("prompt").asText(),row.reservation(),row.actualCost(),row.costStatus(),row.completedMs(),r==null?null:r.contentScore(),r==null?null:r.motionScore(),r==null?null:r.consistencyScore(),r==null?null:r.usable(),r==null?null:r.notes()};
            out.append(Arrays.stream(fields).map(QualityEvaluationService::csvField).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        } return out.toString();
    }
    private static String csvField(Object value) { String text=value==null?"":value.toString(); String first=text.stripLeading(); if(!first.isEmpty() && "=+@-".indexOf(first.charAt(0))>=0) text="'"+text; return "\""+text.replace("\"","\"\"")+"\""; }
    private static boolean score(int value) { return value>=1 && value<=5; }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT,message); }
    private static class CheckedFailure extends RuntimeException { CheckedFailure(Exception e) { super(e); } }
    public record EvaluationCase(String id,String title,long seed,String originalPrompt,String structuredPrompt) { }
    public record ReviewInput(int expectedReviewVersion,int contentScore,int motionScore,int consistencyScore,boolean usable,String notes) { }
    public record Review(int number,int contentScore,int motionScore,int consistencyScore,boolean usable,String notes,long createdAt) { }
    public record Row(String versionId,int revision,String shotId,int version,String taskId,String provider,String model,String state,String caseId,String variant,String controlHash,
        com.fasterxml.jackson.databind.JsonNode input,BigDecimal reservation,BigDecimal actualCost,String costStatus,Long completedMs,Review review) { }
    public record Summary(int totalVersions,Map<String,Integer> stateCounts,int succeeded,int reviewed,int usable,int unreviewed,int mockVersions,long unknownCostTasks,long unsettledCostTasks,BigDecimal knownActualCost,
        BigDecimal reservedCost,Double successRate,Double meanHumanScore,int timedSuccesses,Double medianCompletedMs,BigDecimal costPerUsableVideo) { }
    public record VariantStats(int total,int succeeded,int reviewed,int usable,Double meanHumanScore) { }
    public record Comparison(String caseId,String controlHash,String provider,String model,VariantStats original,VariantStats structured,boolean comparable) { }
    public record Report(String schema,String projectId,long cutoffAt,Summary summary,List<Comparison> comparisons,List<Row> rows,String limitations) { }
}
