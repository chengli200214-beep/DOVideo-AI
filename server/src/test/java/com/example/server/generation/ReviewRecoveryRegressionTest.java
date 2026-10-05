package com.example.server.generation;

import com.example.server.exception.BusinessException;
import com.example.server.storyboard.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Cross-service regressions: no supplier HTTP is performed. */
class ReviewRecoveryRegressionTest {
    ShotGenerationFlowTest fixture;
    StoryboardModelRepository planning;
    StoryboardModelProperties policy;
    @BeforeEach void setup() throws Exception {
        fixture = new ShotGenerationFlowTest(); fixture.setup();
        planning = new StoryboardModelRepository(fixture.flow.jdbc);
        policy = new StoryboardModelProperties(); policy.setPaidEnabled(true); policy.setApiKey("offline-only");
        policy.setAuthorizationId("review-offline"); policy.setApprovedModel(policy.getModel()); policy.setMaxCalls(2);
        policy.setBudgetLimit(new BigDecimal("2")); policy.setReservationPerCall(BigDecimal.ONE);
        policy.setInputPricePerMillion(new BigDecimal("0.01")); policy.setOutputPricePerMillion(new BigDecimal("0.02"));
    }
    StoryboardModelRepository.Task plan(String key) {
        return planning.create(1, fixture.project.id(), key, 1, "{}", policy, System.currentTimeMillis());
    }
    @Test void queuedAndClaimedPlanningBlockConfirmationUntilOutputIsSaved() throws Exception {
        var task = plan("planning");
        assertThrows(BusinessException.class, () -> fixture.storyboard.service.confirm(1, fixture.project.id(), 1));
        var claimed = planning.claim(task.id(), System.currentTimeMillis());
        assertThrows(BusinessException.class, () -> fixture.storyboard.service.confirm(1, fixture.project.id(), 1));
        assertNull(fixture.storyboard.service.get(1, fixture.project.id()).confirmedRevision());
        var draft = new TemplateStoryboardPlanner().draft(fixture.storyboard.brief, List.of());
        planning.finish(claimed, new DeepSeekStoryboardPlanner.Completion(draft, "{}", "offline", null, null, null),
                fixture.flow.json.writeValueAsString(draft), "[]", true, System.currentTimeMillis());
        fixture.storyboard.service.confirm(1, fixture.project.id(), 2);
        assertEquals(2, fixture.storyboard.service.get(1, fixture.project.id()).confirmedRevision());
        verify(fixture.flow.provider, never()).submit(any(), any());
    }
    @Test void planningOnConfirmedProjectBlocksVideoSubmissionsBeforeAnyQuotaIsUsed() throws Exception {
        fixture.ready(6); var task = plan("confirmed");
        for (boolean claimed : List.of(false, true)) {
            if (claimed) planning.claim(task.id(), System.currentTimeMillis());
            assertThrows(BusinessException.class, () -> fixture.service.submit(1, fixture.project.id(), "video" + claimed,
                    fixture.input("INITIAL", fixture.ids())));
        }
        assertEquals(0, fixture.count("generation_tasks"));
        assertEquals(0, fixture.service.overview(1, fixture.project.id()).budget().usedVersions());
        assertEquals(1, fixture.storyboard.service.get(1, fixture.project.id()).confirmedRevision());
    }
    @Test void existingInFlightVideosBlockNewPlanningAndDoNotConsumePlanningAllowance() throws Exception {
        fixture.ready(6);
        var versions = fixture.service.submit(1, fixture.project.id(), "original", fixture.input("INITIAL", fixture.ids())).versions();
        assertThrows(BusinessException.class, () -> plan("late-planning"));
        assertEquals(0, planning.budget(policy.getAuthorizationId()).usedCalls());
        for (var version : versions) fixture.complete(version.task().id());
        assertNotNull(plan("after-completion"));
    }
    @Test void legacyConcurrentConfirmationIsPreservedAndModelOutputBecomesConflict() throws Exception {
        var task = plan("legacy"); var claimed = planning.claim(task.id(), System.currentTimeMillis());
        // Simulate another, older server instance that has not yet acquired the new guard.
        fixture.flow.jdbc.update("UPDATE creative_projects SET status='CONFIRMED',confirmed_revision=1 WHERE id=?", fixture.project.id());
        var draft = fixture.project.revision().draft();
        planning.finish(claimed, new DeepSeekStoryboardPlanner.Completion(draft, "{}", "offline", null, null, null),
                fixture.flow.json.writeValueAsString(draft), "[]", true, System.currentTimeMillis());
        assertEquals("CONFLICT", planning.owned(1, fixture.project.id(), task.id()).status());
        var retained = fixture.storyboard.service.get(1, fixture.project.id());
        assertEquals("CONFIRMED", retained.status()); assertEquals(1, retained.revision().number()); assertEquals(1, retained.confirmedRevision());
    }
    @Test void metadataFailureLeavesDurableCleanupAndRetryDoesNotHideOrDeleteTheNewAsset() throws Exception {
        var flow = fixture.flow;
        var keys = new ArrayList<String>();
        doAnswer(call -> { keys.add(call.getArgument(0)); return null; }).when(flow.artifacts).putReference(anyString(), any(), anyString());
        flow.jdbc.execute("ALTER TABLE generation_assets ADD CONSTRAINT reject_metadata CHECK(user_id<0)");
        String image = SeedanceGenerationProviderTest.png(2, 2);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> flow.assets.inline(1, image));
        assertEquals(0, fixture.count("generation_assets")); assertEquals(1, fixture.count("generation_asset_cleanup"));
        flow.jdbc.execute("ALTER TABLE generation_assets DROP CONSTRAINT reject_metadata");
        var retried = flow.assets.inline(1, image);
        assertEquals(2, keys.size()); assertNotEquals(keys.getFirst(), retried.objectKey());
        var cleanup = new GenerationAssetLifecycleService(flow.jdbc, flow.assets, flow.artifacts);
        cleanup.cleanupDue(); cleanup.cleanupDue();
        verify(flow.artifacts, times(1)).removeReference(keys.getFirst());
        verify(flow.artifacts, never()).removeReference(retried.objectKey());
        assertEquals(retried, flow.assets.owned(1, retried.id()));
    }
    @Test void abandonedUploadIntentIsRecoveredButCommittedAssetIsNeverRemoved() throws Exception {
        var flow = fixture.flow; var cleanup = new GenerationAssetLifecycleService(flow.jdbc, flow.assets, flow.artifacts);
        String abandoned = UUID.randomUUID().toString(), key = "generation-inputs/1/" + abandoned + ".png";
        flow.jdbc.update("INSERT INTO generation_asset_cleanup(id,user_id,object_key,state,next_run_at,created_at) VALUES (?,1,?,'UPLOADING',0,0)", abandoned, key);
        var committed = flow.assets.inline(1, SeedanceGenerationProviderTest.png(2, 2));
        flow.jdbc.update("INSERT INTO generation_asset_cleanup(id,user_id,object_key,state,next_run_at,created_at) VALUES (?,1,?,'UPLOADING',0,0)", committed.id(), committed.objectKey());
        cleanup.cleanupDue(); cleanup.cleanupDue();
        verify(flow.artifacts, times(1)).removeReference(key);
        verify(flow.artifacts, never()).removeReference(committed.objectKey());
        assertEquals(committed, flow.assets.owned(1, committed.id()));
    }
}
