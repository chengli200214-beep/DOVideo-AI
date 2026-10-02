package com.example.server.generation;
import com.example.server.film.*;
import com.example.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityEvaluationTest {
    FilmFlowTest films;
    QualityEvaluationService service;
    @BeforeEach void setup() throws Exception { films=new FilmFlowTest(); films.setup(); service=new QualityEvaluationService(films.flow.jdbc,films.shots.storyboard.repository,films.shots.service,films.flow.service,films.flow.json); }
    @Test void reviewsAreVersionedIdempotentAndRequireCompletedOwnedVideo() throws Exception {
        String project=films.project(),version=films.versions.getFirst().id();
        var input=new QualityEvaluationService.ReviewInput(0,3,4,5,true,"人工测试评价");
        assertEquals(1,service.review(1,project,version,input).number()); assertEquals(1,service.review(1,project,version,input).number());
        assertThrows(BusinessException.class,()->service.review(1,project,version,new QualityEvaluationService.ReviewInput(0,1,1,1,false,"conflict")));
        assertEquals(2,service.review(1,project,version,new QualityEvaluationService.ReviewInput(1,4,4,4,true,"changed")).number());
        assertEquals(2,films.shots.count("generation_quality_reviews"));
        assertThrows(NoSuchElementException.class,()->service.review(2,project,version,input));
        assertThrows(IllegalArgumentException.class,()->service.review(1,project,version,new QualityEvaluationService.ReviewInput(2,0,5,5,true,"invalid")));
        films.flow.jdbc.update("UPDATE generation_tasks SET state='RUNNING' WHERE id=?",films.versions.get(1).task().id());
        assertThrows(BusinessException.class,()->service.review(1,project,films.versions.get(1).id(),input));
    }
    @Test void metricsCountFailuresPendingAndUnratedInsteadOfInflatingSuccessOrQuality() throws Exception {
        var first=films.versions.getFirst();
        service.review(1,films.project(),first.id(),new QualityEvaluationService.ReviewInput(0,3,3,3,true,"test-only"));
        films.flow.jdbc.update("UPDATE generation_tasks SET state='FAILED' WHERE id=?",films.versions.get(1).task().id());
        films.flow.jdbc.update("UPDATE generation_tasks SET state='SUBMISSION_UNKNOWN' WHERE id=?",films.versions.get(2).task().id());
        var report=service.report(1,films.project()); var summary=report.summary();
        assertEquals(3,summary.totalVersions()); assertEquals(1,summary.succeeded()); assertEquals(1,summary.reviewed()); assertEquals(2,summary.unreviewed());
        assertEquals(1/3.0,summary.successRate()); assertEquals(3,summary.meanHumanScore()); assertEquals(3,summary.mockVersions());
        assertEquals(1,summary.stateCounts().get("FAILED")); assertEquals(1,summary.stateCounts().get("SUBMISSION_UNKNOWN")); assertEquals(1,summary.timedSuccesses());
        assertThrows(NoSuchElementException.class,()->service.report(2,films.project()));
    }
    @Test void fixedPromptVariantsNeedMatchingSeedAndControlsAndMockNeverCountsAsRealComparison() throws Exception {
        var fixed=service.cases().getFirst();
        for(int i=0;i<2;i++) {
            String task=films.versions.get(i).task().id();
            var original=films.flow.json.readValue(films.flow.repository.byId(task).requestJson(),GenerationRequest.class);
            var input=new GenerationRequest(original.kind(),i==0?fixed.originalPrompt():fixed.structuredPrompt(),null,original.imageSize(),null,fixed.seed());
            films.flow.jdbc.update("UPDATE generation_tasks SET request_json=? WHERE id=?",films.flow.json.writeValueAsString(input),task);
            service.review(1,films.project(),films.versions.get(i).id(),new QualityEvaluationService.ReviewInput(0,3,3,3,true,"fixture"));
        }
        var report=service.report(1,films.project());
        assertEquals(1,report.comparisons().size()); assertFalse(report.comparisons().getFirst().comparable());
        assertEquals(1,report.comparisons().getFirst().original().reviewed()); assertEquals(1,report.comparisons().getFirst().structured().reviewed());
        films.flow.jdbc.update("UPDATE generation_tasks SET model='different-model' WHERE id=?",films.versions.get(1).task().id());
        assertEquals(2,service.report(1,films.project()).comparisons().size());
    }
    @Test void unknownActualSupplierCostsNeverBecomeReservedAmountAndCsvEscapesHumanFormulaFields() throws Exception {
        String task=films.versions.getFirst().task().id();
        films.flow.jdbc.update("UPDATE generation_tasks SET provider='offline-cost',state='SUBMISSION_UNKNOWN' WHERE id=?",task);
        films.flow.jdbc.update("INSERT INTO generation_authorizations(id,policy_hash) VALUES ('offline','hash')");
        films.flow.jdbc.update("INSERT INTO generation_reservations VALUES (?,'offline',6,?)",task,System.currentTimeMillis());
        var report=service.report(1,films.project()); assertEquals(1,report.summary().unknownCostTasks()); assertEquals(1,report.summary().unsettledCostTasks()); assertNull(report.summary().costPerUsableVideo());
        String version=films.versions.get(1).id();
        service.review(1,films.project(),version,new QualityEvaluationService.ReviewInput(0,3,3,3,true,"=SUM(1,2)\n\"test\""));
        String csv=service.csv(service.report(1,films.project())); assertTrue(csv.contains("\"'=SUM(1,2)\n\"\"test\"\"\""));
        assertFalse(report.rows().stream().anyMatch(row->row.input().has("image") && row.input().hasNonNull("image")));
    }
}
