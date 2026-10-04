package com.example.server.generation;

import com.example.server.exception.BusinessException;
import com.example.server.storyboard.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static com.example.server.generation.GenerationTask.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShotGenerationFlowTest {
    StoryboardFlowTest storyboard;
    GenerationFlowTest flow;
    ShotGenerationService service;
    StoryboardService.View project;
    @BeforeEach void setup() throws Exception {
        storyboard = new StoryboardFlowTest(); storyboard.setup(); flow = storyboard.flow;
        project = storyboard.service.create(1, "project", storyboard.brief).project();
        service = new ShotGenerationService(flow.jdbc, storyboard.repository, flow.service, flow.properties, flow.json);
    }
    List<String> ids() { return project.revision().draft().shots().stream().map(StoryboardDraft.Shot::id).toList(); }
    ShotGenerationService.SubmitInput input(String mode, List<String> ids) { return new ShotGenerationService.SubmitInput(1, ids, mode); }
    void ready(int max) throws Exception {
        storyboard.service.confirm(1, project.id(), 1);
        service.configure(1, project.id(), new ShotGenerationService.BudgetInput(max, BigDecimal.ZERO));
    }
    void complete(String id) throws Exception { flow.step(id); flow.step(id); flow.step(id); }
    int count(String table) { return flow.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Test void batchAndLocalRegenerationReuseWorkersAndKeepImmutableInputsAcrossRestart() throws Exception {
        ready(6);
        var quote = service.quote(1, project.id(), 1);
        assertEquals(3, quote.shots().size()); assertEquals("720x1280", quote.shots().getFirst().imageSize());
        var first = service.submit(1, project.id(), "initial", input("INITIAL", ids()));
        assertEquals(3, first.versions().size()); assertEquals(3, first.budget().usedVersions());
        verify(flow.provider, never()).submit(any(), any());
        for (var version : first.versions()) complete(version.task().id());
        String target = ids().getFirst();
        var local = service.submit(1, project.id(), "regenerate", input("REGENERATE", List.of(target)));
        assertEquals(1, local.versions().size()); assertEquals(2, local.versions().getFirst().version());
        assertEquals(4, count("generation_tasks"));
        var restarted = new ShotGenerationService(flow.jdbc, new StoryboardRepository(flow.jdbc), flow.service, flow.properties, flow.json);
        assertTrue(restarted.submit(1, project.id(), "initial", input("INITIAL", ids().reversed())).reused());
        var overview = restarted.overview(1, project.id());
        assertEquals(4, overview.versions().size()); assertEquals(4, overview.budget().usedVersions());
        assertEquals(0, overview.unknownCostTasks()); assertEquals(0, overview.knownActualCost().signum());
        assertEquals(1, overview.versions().stream().filter(v -> v.shotId().equals(ids().get(1))).count());
        var request = flow.json.readTree(flow.repository.byId(local.versions().getFirst().task().id()).requestJson());
        assertEquals(project.revision().draft().shots().getFirst().prompt(), request.path("prompt").asText());
        assertFalse(request.has("duration")); assertEquals("720x1280", request.path("imageSize").asText());
        complete(local.versions().getFirst().task().id());
        assertEquals(SUCCEEDED, flow.service.get(1, local.versions().getFirst().task().id()).state());
    }
    @Test void confirmationOwnershipAndConflictingKeysAreEnforcedButReplaySurvivesLaterEdits() throws Exception {
        service.configure(1, project.id(), new ShotGenerationService.BudgetInput(6, BigDecimal.ZERO));
        assertThrows(BusinessException.class, () -> service.submit(1, project.id(), "key", input("INITIAL", ids())));
        ready(6);
        var first = service.submit(1, project.id(), "key", input("INITIAL", ids()));
        assertThrows(NoSuchElementException.class, () -> service.overview(2, project.id()));
        assertThrows(NoSuchElementException.class, () -> service.submit(2, project.id(), "key", input("INITIAL", ids())));
        assertThrows(BusinessException.class, () -> service.submit(1, project.id(), "key", input("INITIAL", List.of(ids().getFirst()))));
        assertThrows(BusinessException.class, () -> service.submit(1, project.id(), "other-key", input("INITIAL", ids())));
        storyboard.service.edit(1, project.id(), 1, storyboard.change(project.revision().draft(), "changed"));
        assertTrue(service.submit(1, project.id(), "key", input("INITIAL", ids())).reused());
        assertThrows(BusinessException.class, () -> service.submit(1, project.id(), "new", input("REGENERATE", List.of(ids().getFirst()))));
        assertEquals(first.versions().getFirst().shotJson(), service.overview(1, project.id()).versions().getFirst().shotJson());
        assertEquals(3, count("generation_tasks"));
    }
    @Test void concurrentSameIntentCreatesOneBatchAndDifferentIntentsCannotOverdrawProjectQuota() throws Exception {
        ready(3);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<java.util.concurrent.Future<ShotGenerationService.Submitted>>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(() -> { start.await(); return service.submit(1, project.id(), "same", input("INITIAL", ids())); }));
            start.countDown(); String operation = results.getFirst().get().operationId();
            for (var result : results) assertEquals(operation, result.get().operationId());
        }
        assertEquals(1, count("shot_generation_operations")); assertEquals(3, count("generation_tasks"));
        for (var version : service.overview(1, project.id()).versions()) complete(version.task().id());
        CountDownLatch retry = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> deniedAfter(retry, "a", ids().getFirst()));
            var b = pool.submit(() -> deniedAfter(retry, "b", ids().get(1)));
            retry.countDown(); assertTrue(a.get()); assertTrue(b.get());
        }
        assertEquals(3, service.overview(1, project.id()).budget().usedVersions());
        assertThrows(BusinessException.class, () -> service.configure(1, project.id(), new ShotGenerationService.BudgetInput(9, BigDecimal.ZERO)));
        assertEquals(3, service.configure(1, project.id(), new ShotGenerationService.BudgetInput(3, BigDecimal.ZERO)).usedVersions());
    }
    boolean deniedAfter(CountDownLatch start, String key, String shot) throws Exception {
        start.await(); try { service.submit(1, project.id(), key, input("REGENERATE", List.of(shot))); return false; }
        catch (BusinessException expected) { return true; }
    }
    @Test void concurrentDifferentShotsCompeteForOneRemainingVersionWithoutOverspending() throws Exception {
        ready(1);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> initialAfter(start, "a", ids().getFirst()));
            var b = pool.submit(() -> initialAfter(start, "b", ids().get(1)));
            start.countDown(); assertNotEquals(a.get(), b.get());
        }
        assertEquals(1, count("generation_tasks")); assertEquals(1, count("shot_generation_versions"));
        assertEquals(1, service.overview(1, project.id()).budget().usedVersions());
    }
    boolean initialAfter(CountDownLatch start, String key, String shot) throws Exception {
        start.await(); try { service.submit(1, project.id(), key, input("INITIAL", List.of(shot))); return true; }
        catch (BusinessException expected) { return false; }
    }
    @Test void failedPartialBatchRollsBackTasksVersionsOperationAndBudgetTogether() throws Exception {
        ready(6);
        String reject = ids().get(1);
        flow.jdbc.execute("ALTER TABLE shot_generation_versions ADD CONSTRAINT reject_shot CHECK (shot_id <> '" + reject + "')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.submit(1, project.id(), "partial", input("INITIAL", ids())));
        assertEquals(0, count("generation_tasks")); assertEquals(0, count("generation_events"));
        assertEquals(0, count("shot_generation_operations")); assertEquals(0, service.overview(1, project.id()).budget().usedVersions());
        verify(flow.provider, never()).submit(any(), any());
    }
    @Test void referenceShotsUsePrivateAssetIdsAndCapabilityFailureRollsBackWholeBatch() throws Exception {
        var asset = flow.assets.inline(1, "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=");
        var b = storyboard.brief;
        project = storyboard.service.create(1, "image", new CreativeBrief(b.title(), b.goal(), b.productName(), b.sellingPoints(), b.style(),
                b.desiredDurationSeconds(), b.frameRatio(), asset.id(), b.shotCount())).project();
        ready(6);
        flow.properties.getImage().setSizes(List.of("1280x720"));
        assertThrows(IllegalArgumentException.class, () -> service.submit(1, project.id(), "image", input("INITIAL", ids())));
        assertEquals(0, count("generation_tasks")); assertEquals(0, service.overview(1, project.id()).budget().usedVersions());
        flow.properties.getImage().setSizes(List.of("720x1280"));
        var first = service.submit(1, project.id(), "image", input("INITIAL", ids()));
        var request = flow.json.readTree(flow.repository.byId(first.versions().getFirst().task().id()).requestJson());
        assertEquals("IMAGE_TO_VIDEO", request.path("kind").asText()); assertEquals(asset.id(), request.path("referenceImageId").asText());
        assertFalse(request.toString().contains("data:image"));
    }
    @Test void recoveryKeepsSameVersionAndUnknownSubmissionCannotBeRegenerated() throws Exception {
        ready(6);
        var first = service.submit(1, project.id(), "initial", input("INITIAL", List.of(ids().getFirst()))).versions().getFirst();
        assertThrows(BusinessException.class, () -> service.submit(1, project.id(), "active", input("REGENERATE", List.of(first.shotId()))));
        flow.jdbc.update("UPDATE generation_tasks SET state='SUBMISSION_UNKNOWN' WHERE id=?", first.task().id());
        assertThrows(BusinessException.class, () -> service.submit(1, project.id(), "unknown", input("REGENERATE", List.of(first.shotId()))));
        flow.service.reconcile(1, first.task().id(), "mock-reconciled");
        flow.jdbc.update("UPDATE generation_tasks SET state='FAILED',recoverable=TRUE,error_code='ARTIFACT_SAVE_FAILED' WHERE id=?", first.task().id());
        flow.service.retry(1, first.task().id()); flow.step(first.task().id()); flow.step(first.task().id());
        assertEquals(SUCCEEDED, flow.service.get(1, first.task().id()).state());
        assertEquals(1, count("shot_generation_versions")); assertEquals(1, service.overview(1, project.id()).budget().usedVersions());
        verify(flow.provider, never()).submit(any(), any());
    }
    @Test void closedPaidProviderCreatesNoTaskAndNoReservation() throws Exception {
        ready(6); flow.properties.setProvider("seedance"); flow.properties.setReservationPerTask(new BigDecimal("6"));
        var paid = mock(GenerationProvider.class); when(paid.name()).thenReturn("seedance");
        var generation = new GenerationService(flow.repository, flow.properties, List.of(paid), flow.json, flow.artifacts, flow.assets);
        var paidService = new ShotGenerationService(flow.jdbc, storyboard.repository, generation, flow.properties, flow.json);
        // Even with a project allowance, global model authorization remains mandatory.
        flow.jdbc.update("UPDATE project_generation_budgets SET cost_limit=100 WHERE project_id=?", project.id());
        assertThrows(BusinessException.class, () -> paidService.submit(1, project.id(), "paid", input("INITIAL", ids())));
        assertEquals(0, count("generation_tasks")); assertEquals(0, count("generation_reservations"));
        assertEquals(0, paidService.overview(1, project.id()).budget().usedVersions()); verify(paid, never()).submit(any(), any());
    }
    @Test void editingRevisionCannotBypassActiveOrUnknownEarlierShotSubmissions() throws Exception {
        ready(6);
        String shotId=ids().getFirst();
        var original=service.submit(1,project.id(),"original",input("INITIAL",List.of(shotId))).versions().getFirst();
        storyboard.service.edit(1,project.id(),1,storyboard.change(project.revision().draft(),"changed while the original task is pending"));
        storyboard.service.confirm(1,project.id(),2);
        for(var state:List.of(QUEUED,SUBMITTING,RUNNING,SAVING,SUBMISSION_UNKNOWN)) {
            flow.jdbc.update("UPDATE generation_tasks SET state=? WHERE id=?",state.name(),original.task().id());
            for(String mode:List.of("INITIAL","REGENERATE")) {
                assertThrows(BusinessException.class,()->service.submit(1,project.id(),mode+state.name(),new ShotGenerationService.SubmitInput(2,List.of(shotId),mode)));
            }
        }
        assertEquals(1,count("generation_tasks")); assertEquals(1,service.overview(1,project.id()).budget().usedVersions());
        verify(flow.provider,never()).submit(any(),any());
    }
    @Test void paidBudgetIsConservativeAndAuthorizationChangesAreBlockedBeforeAnyProviderCall() throws Exception {
        ready(3);
        flow.properties.setProvider("seedance"); flow.properties.setPaidEnabled(true);
        flow.properties.setAuthorizationId("db-only-test"); flow.properties.setApprovedModels(List.of(flow.properties.getTextModel()));
        flow.properties.setMaxPaidTasks(3); flow.properties.setBudgetLimit(new BigDecimal("30")); flow.properties.setReservationPerTask(new BigDecimal("6"));
        var paid = mock(GenerationProvider.class); when(paid.name()).thenReturn("seedance");
        var generation = new GenerationService(flow.repository, flow.properties, List.of(paid), flow.json, flow.artifacts, flow.assets);
        var paidService = new ShotGenerationService(flow.jdbc, storyboard.repository, generation, flow.properties, flow.json);
        paidService.configure(1, project.id(), new ShotGenerationService.BudgetInput(3, new BigDecimal("10")));
        var first = paidService.submit(1, project.id(), "paid-one", input("INITIAL", List.of(ids().getFirst()))).versions().getFirst();
        assertThrows(BusinessException.class, () -> paidService.submit(1, project.id(), "paid-two", input("INITIAL", List.of(ids().get(1)))));
        var task = flow.repository.claim(first.task().id(), System.currentTimeMillis());
        assertTrue(flow.repository.beginSubmission(task, System.currentTimeMillis(), "{}", flow.properties));
        flow.jdbc.update("UPDATE generation_tasks SET state='SUBMISSION_UNKNOWN' WHERE id=?", task.id());
        assertEquals(1, paidService.overview(1, project.id()).unknownCostTasks()); assertNull(paidService.overview(1, project.id()).versions().getFirst().actualCost());
        assertEquals(0, new BigDecimal("6").compareTo(paidService.overview(1, project.id()).budget().reservedCost()));
        flow.jdbc.update("UPDATE generation_tasks SET state='QUEUED' WHERE id=?", task.id());
        flow.properties.setAuthorizationId("changed-test");
        assertThrows(BusinessException.class, () -> flow.repository.beginSubmission(task, System.currentTimeMillis(), "{}", flow.properties));
        assertEquals(QUEUED, flow.repository.byId(task.id()).state()); assertEquals(1, count("generation_reservations"));
        verify(paid, never()).submit(any(), any());
    }
}
