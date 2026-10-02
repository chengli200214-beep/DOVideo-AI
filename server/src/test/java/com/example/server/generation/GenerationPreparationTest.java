package com.example.server.generation;

import com.example.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.example.server.generation.GenerationTask.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationPreparationTest {
    GenerationFlowTest flow;
    String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=";
    @BeforeEach void setup() throws Exception { flow = new GenerationFlowTest(); flow.setup(); }

    @Test void independentlyConfiguredCapabilitiesRejectUnsupportedParameters() throws Exception {
        flow.properties.getText().setSizes(List.of("960x960"));
        flow.properties.getText().setSeed(false);
        flow.properties.getText().setNegativePrompt(false);
        var caps = flow.service.capabilities();
        assertEquals(List.of("960x960"), caps.getFirst().sizes());
        assertEquals(3, caps.get(1).sizes().size());
        assertThrows(IllegalArgumentException.class, () -> flow.service.submit(1, "size", flow.text));
        var valid = new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO, "product", null, "960x960", null, null);
        flow.service.submit(1, "valid", valid);
        assertThrows(IllegalArgumentException.class, () -> flow.service.submit(1, "seed",
                new GenerationRequest(valid.kind(), valid.prompt(), null, valid.imageSize(), null, 1L)));
        assertThrows(IllegalArgumentException.class, () -> flow.service.submit(1, "negative",
                new GenerationRequest(valid.kind(), valid.prompt(), "blur", valid.imageSize(), null, null)));
        flow.properties.getImage().setEnabled(false);
        assertFalse(flow.service.capabilities().get(1).available());
    }

    @Test void privateAssetDeduplicationOwnershipAndTraceSurviveRestartWithoutInlineData() throws Exception {
        var asset = flow.assets.inline(1, png);
        assertEquals(asset.id(), flow.assets.inline(1, png).id());
        assertThrows(NoSuchElementException.class, () -> flow.assets.owned(2, asset.id()));
        var request = new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO, "  animate product  ", null, "960x960", null, 42L, asset.id());
        assertThrows(NoSuchElementException.class, () -> flow.service.submit(2, "stolen", request));
        var first = flow.service.submit(1, "asset", request);
        String id = first.task().id();
        var legacy = new GenerationRequest(request.kind(), request.prompt(), null, request.imageSize(), png, 42L);
        assertTrue(flow.service.submit(1, "asset", legacy).reused());
        assertFalse(flow.repository.byId(id).requestJson().contains("data:image"));
        flow.step(id); flow.step(id); flow.step(id);
        var restarted = new GenerationService(new GenerationRepository(flow.jdbc), flow.properties, List.of(flow.provider),
                flow.json, flow.artifacts, new GenerationAssetService(flow.jdbc, flow.artifacts));
        var trace = restarted.trace(1, id);
        assertEquals(request.prompt(), trace.originalPrompt());
        assertEquals("animate product", trace.effectiveSubmission().path("parameters").path("prompt").asText());
        assertEquals(asset.sha256(), trace.effectiveSubmission().path("imageReference").path("sha256").asText());
        assertFalse(flow.json.writeValueAsString(trace).contains("data:image"));
        assertEquals(List.of("QUEUED", "SUBMITTING", "RUNNING", "SAVING", "SUCCEEDED"), trace.events().stream().map(GenerationRepository.Event::state).toList());
        assertThrows(NoSuchElementException.class, () -> restarted.trace(2, id));
        assertEquals(1, restarted.recent(1).size()); assertTrue(restarted.recent(2).isEmpty());
        verify(flow.provider).submit(any(), argThat(input -> png.equals(input.image()) && input.referenceImageId().equals(asset.id())));
    }

    @Test void corruptPersistedAssetFailsBeforeProviderSubmission() throws Exception {
        var asset = flow.assets.inline(1, png);
        var request = new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO, "animate", null, "960x960", null, null, asset.id());
        String id = flow.service.submit(1, "corrupt", request).task().id();
        when(flow.artifacts.readReference(anyString())).thenReturn(new byte[] {1, 2});
        flow.step(id);
        assertEquals(FAILED, flow.service.get(1, id).state());
        assertEquals("SUBMISSION_PREPARATION_FAILED", flow.service.get(1, id).errorCode());
        verify(flow.provider, never()).submit(any(), any());
    }

    @Test void traceFailureRollsBackTaskTransitionRatherThanLosingHistory() throws Exception {
        String id = flow.service.submit(1, "audit", flow.text).task().id();
        var claimed = flow.repository.claim(id, System.currentTimeMillis());
        flow.jdbc.execute("ALTER TABLE generation_events ADD CONSTRAINT deny_submission CHECK (state <> 'SUBMITTING')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> flow.repository.beginSubmission(claimed, System.currentTimeMillis()));
        assertEquals(QUEUED, flow.repository.byId(id).state());
        assertEquals(1, flow.repository.events(id).size());
    }

    void approve(int max, String budget, String perTask) {
        flow.properties.setProvider("siliconflow"); flow.properties.setPaidEnabled(true);
        flow.properties.setAuthorizationId("offline-approval-test");
        flow.properties.setApprovedModels(List.of(flow.properties.getTextModel()));
        flow.properties.setMaxPaidTasks(max); flow.properties.setBudgetLimit(new BigDecimal(budget));
        flow.properties.setReservationPerTask(new BigDecimal(perTask));
        doReturn("siliconflow").when(flow.provider).name();
    }
    @Test void enablingPaidFlagAloneNeverPermitsSubmission() throws Exception {
        flow.properties.setProvider("siliconflow"); flow.properties.setPaidEnabled(true);
        doReturn("siliconflow").when(flow.provider).name();
        assertThrows(BusinessException.class, () -> flow.service.submit(1, "not-approved", flow.text));
        verify(flow.provider, never()).submit(any(), any());
    }
    @Test void concurrentBudgetReservationsRemainBoundedAcrossRestartAndUnknownResults() throws Exception {
        approve(8, "10", "6");
        doThrow(new java.io.IOException("offline ambiguous response")).when(flow.provider).submit(any(), any());
        String first = flow.service.submit(1, "quota-1", flow.text).task().id();
        String second = flow.service.submit(1, "quota-2", flow.text).task().id();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); flow.worker.process(first); return null; });
            var b = pool.submit(() -> { start.await(); flow.worker.process(second); return null; });
            start.countDown(); a.get(); b.get();
        }
        verify(flow.provider, times(1)).submit(any(), any());
        assertEquals(1, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations", Integer.class));
        assertEquals(0, new BigDecimal("6").compareTo(flow.jdbc.queryForObject("SELECT reserved_cost FROM generation_authorizations", BigDecimal.class)));
        assertEquals(1, List.of(flow.repository.byId(first), flow.repository.byId(second)).stream().filter(task -> task.state() == SUBMISSION_UNKNOWN).count());
        var repository = new GenerationRepository(flow.jdbc);
        var worker = new GenerationWorker(repository, flow.service, flow.json, flow.artifacts);
        worker.process(first); worker.process(second);
        String third = flow.service.submit(1, "quota-3", flow.text).task().id();
        worker.process(third);
        assertEquals(FAILED, repository.byId(third).state());
        assertEquals("SUBMISSION_AUTHORIZATION_DENIED", repository.byId(third).errorCode());
        verify(flow.provider, times(1)).submit(any(), any());
    }
    @Test void taskCountLimitIsEnforcedEvenWhenBudgetRemains() throws Exception {
        approve(1, "100", "1");
        String first = flow.service.submit(1, "limit-1", flow.text).task().id();
        String second = flow.service.submit(1, "limit-2", flow.text).task().id();
        flow.step(first); flow.step(second);
        assertEquals(RUNNING, flow.service.get(1, first).state());
        assertEquals(FAILED, flow.service.get(1, second).state());
        verify(flow.provider, times(1)).submit(any(), any());
    }
    @Test void sameAuthorizationCannotExpandItsPolicyAfterRestart() throws Exception {
        approve(1, "100", "1");
        String first = flow.service.submit(1, "scope-1", flow.text).task().id();
        flow.step(first);
        flow.properties.setMaxPaidTasks(10);
        String second = flow.service.submit(1, "scope-2", flow.text).task().id();
        flow.step(second);
        assertEquals(FAILED, flow.service.get(1, second).state());
        assertEquals(1, flow.jdbc.queryForObject("SELECT used_tasks FROM generation_authorizations", Integer.class));
        verify(flow.provider, times(1)).submit(any(), any());
    }
}
