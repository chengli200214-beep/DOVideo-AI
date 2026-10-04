package com.example.server.generation;

import com.example.server.exception.BusinessException;
import com.example.server.storyboard.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationAssetLifecycleTest {
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=";
    GenerationFlowTest flow;
    GenerationAssetLifecycleService service;
    @BeforeEach void setup() throws Exception {
        flow = new GenerationFlowTest(); flow.setup();
        service = new GenerationAssetLifecycleService(flow.jdbc, flow.assets, flow.artifacts);
    }

    @Test void deletingUnusedAssetIsOwnerScopedAndCleanupIsIdempotent() throws Exception {
        var asset = flow.assets.inline(1, IMAGE);
        assertFalse(service.page(1, 20, null, null).items().getFirst().referenced());
        assertTrue(service.page(2, 20, null, null).items().isEmpty());
        assertThrows(NoSuchElementException.class, () -> service.remove(2, asset.id()));
        var deleted = service.remove(1, asset.id());
        assertEquals("PENDING", deleted.state()); assertEquals(deleted, service.remove(1, asset.id()));
        assertThrows(NoSuchElementException.class, () -> flow.assets.owned(1, asset.id()));
        assertTrue(service.page(1, 20, null, null).items().isEmpty());
        verify(flow.artifacts, never()).removeReference(anyString());
        service.cleanupDue(); service.cleanupDue();
        assertEquals("SUCCEEDED", service.cleanups(1).getFirst().state());
        assertEquals("SUCCEEDED", service.remove(1, asset.id()).state());
        assertTrue(service.cleanups(2).isEmpty());
        verify(flow.artifacts, times(1)).removeReference(asset.objectKey());
    }

    @Test void failedObjectRemovalKeepsRetryableCleanupAndNeverDeletesAnIdenticalLaterUpload() throws Exception {
        var original = flow.assets.inline(1, IMAGE); service.remove(1, original.id());
        var replacement = flow.assets.inline(1, IMAGE);
        assertNotEquals(original.id(), replacement.id()); assertNotEquals(original.objectKey(), replacement.objectKey());
        assertEquals(original.sha256(), replacement.sha256());
        doThrow(new IOException("offline storage unavailable")).when(flow.artifacts).removeReference(original.objectKey());
        for (int attempt = 0; attempt < 5; attempt++) {
            flow.jdbc.update("UPDATE generation_asset_cleanup SET next_run_at=0 WHERE id=?", original.id());
            service.cleanupDue();
        }
        var failed = service.cleanups(1).getFirst();
        assertEquals("FAILED", failed.state()); assertEquals(5, failed.attempts());
        assertThrows(NoSuchElementException.class, () -> service.retry(2, original.id()));
        var retry = service.retry(1, original.id()); assertEquals("PENDING", retry.state()); assertEquals(0, retry.attempts());
        doNothing().when(flow.artifacts).removeReference(original.objectKey());
        service.cleanupDue(); service.cleanupDue();
        assertEquals("SUCCEEDED", service.cleanups(1).getFirst().state());
        assertEquals(replacement, flow.assets.owned(1, replacement.id()));
        assertEquals(IMAGE, flow.assets.submissionImage(1, replacement.id()));
        verify(flow.artifacts, times(6)).removeReference(original.objectKey());
        verify(flow.artifacts, never()).removeReference(replacement.objectKey());
    }

    @Test void projectAndHistoricalRevisionReferencesSurviveMissingLegacyRegistryRows() throws Exception {
        var asset = flow.assets.inline(1, IMAGE);
        var repository = new StoryboardRepository(flow.jdbc);
        var storyboards = new StoryboardService(repository, new TemplateStoryboardPlanner(), flow.assets, flow.json);
        var brief = new CreativeBrief("Product", "Show product", "Cup", List.of("Portable"), "Natural light", 10, "9:16", null, 2);
        var project = storyboards.create(1, "project", brief).project();
        var original = project.revision().draft(); var changed = new ArrayList<>(original.shots()); var first = changed.getFirst();
        changed.set(0, new StoryboardDraft.Shot(first.id(), first.sequence(), first.title(), first.subject(), first.action(), first.setting(), first.camera(), asset.id(),
                first.desiredDurationSeconds(), first.frameRatio(), first.caption(), first.narration(), first.prompt(), first.promptVersion(), first.parameters()));
        storyboards.edit(1, project.id(), 1, new StoryboardDraft(original.script(), changed));
        assertTrue(service.page(1, 20, null, null).items().getFirst().referenced());
        assertThrows(BusinessException.class, () -> service.remove(1, asset.id()));
        storyboards.edit(1, project.id(), 2, original);
        // A pre-upgrade historical document has no normalized links, and the current revision no longer uses the image.
        flow.jdbc.update("DELETE FROM generation_asset_references WHERE asset_id=?", asset.id());
        storyboards.archive(1, project.id(), true);
        assertThrows(BusinessException.class, () -> service.remove(1, asset.id()));
        assertEquals(asset, flow.assets.owned(1, asset.id()));
        assertTrue(service.cleanups(1).isEmpty()); verify(flow.artifacts, never()).removeReference(anyString());
    }

    @Test void legacyProjectBriefAndFinishedGenerationInputsAlsoPreventDeletion() throws Exception {
        var projectAsset = flow.assets.inline(1, IMAGE);
        var storyboards = new StoryboardService(new StoryboardRepository(flow.jdbc), new TemplateStoryboardPlanner(), flow.assets, flow.json);
        var brief = new CreativeBrief("Product", "Show product", "Cup", List.of("Portable"), "Natural light", 10, "9:16", projectAsset.id(), 2);
        storyboards.create(1, "referenced-project", brief);
        flow.jdbc.update("DELETE FROM generation_asset_references WHERE asset_id=?", projectAsset.id());
        assertThrows(BusinessException.class, () -> service.remove(1, projectAsset.id()));
        var taskAsset = flow.assets.inline(2, IMAGE);
        var task = flow.service.submit(2, "referenced-task", input(taskAsset.id())).task();
        flow.jdbc.update("UPDATE generation_tasks SET state='FAILED' WHERE id=?", task.id());
        flow.jdbc.update("DELETE FROM generation_asset_references WHERE asset_id=?", taskAsset.id());
        assertThrows(BusinessException.class, () -> service.remove(2, taskAsset.id()));
        assertTrue(service.page(2, 20, null, null).items().getFirst().referenced());
        assertTrue(service.cleanups(1).isEmpty()); assertTrue(service.cleanups(2).isEmpty());
    }

    @Test void concurrentAttachmentAndDeletionHaveNoDanglingReferencesInEitherCommitOrder() throws Exception {
        var attachedAsset = flow.assets.inline(1, IMAGE);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(flow.jdbc.getDataSource()));
        var attached = new CountDownLatch(1); var releaseAttach = new CountDownLatch(1); var deleteStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var attachment = pool.submit(() -> transactions.execute(tx -> {
                    var result = submit(1, "attach-wins", attachedAsset.id());
                    attached.countDown(); await(releaseAttach); return result;
                }));
                assertTrue(attached.await(5, TimeUnit.SECONDS));
                var deletion = pool.submit(() -> { deleteStarted.countDown(); return service.remove(1, attachedAsset.id()); });
                assertTrue(deleteStarted.await(5, TimeUnit.SECONDS)); releaseAttach.countDown();
                assertNotNull(attachment.get(10, TimeUnit.SECONDS));
                assertInstanceOf(BusinessException.class, assertThrows(ExecutionException.class, () -> deletion.get(10, TimeUnit.SECONDS)).getCause());
            } finally { releaseAttach.countDown(); }
        }
        assertEquals(attachedAsset, flow.assets.owned(1, attachedAsset.id()));
        assertEquals(1, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_asset_references WHERE asset_id=?", Integer.class, attachedAsset.id()));

        var removedAsset = flow.assets.inline(2, IMAGE);
        var removed = new CountDownLatch(1); var releaseDelete = new CountDownLatch(1); var attachStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var deletion = pool.submit(() -> transactions.execute(tx -> {
                    var result = service.remove(2, removedAsset.id()); removed.countDown(); await(releaseDelete); return result;
                }));
                assertTrue(removed.await(5, TimeUnit.SECONDS));
                var attachment = pool.submit(() -> { attachStarted.countDown(); return flow.service.submit(2, "delete-wins", input(removedAsset.id())); });
                assertTrue(attachStarted.await(5, TimeUnit.SECONDS)); releaseDelete.countDown();
                assertEquals("PENDING", deletion.get(10, TimeUnit.SECONDS).state());
                assertInstanceOf(NoSuchElementException.class, assertThrows(ExecutionException.class, () -> attachment.get(10, TimeUnit.SECONDS)).getCause());
            } finally { releaseDelete.countDown(); }
        }
        assertEquals(0, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_assets WHERE id=?", Integer.class, removedAsset.id()));
        assertEquals(0, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_asset_references WHERE asset_id=?", Integer.class, removedAsset.id()));
        assertEquals(0, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks WHERE user_id=2", Integer.class));
        verify(flow.provider, never()).submit(any(), any());
    }

    @Test void staleCleanupCannotFinishNewLeaseEvenAfterManualRetryResetsAttemptNumber() throws Exception {
        var asset = flow.assets.inline(1, IMAGE); service.remove(1, asset.id());
        var firstEntered = new CountDownLatch(1); var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var releaseSecond = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) { firstEntered.countDown(); await(releaseFirst); }
            else { secondEntered.countDown(); await(releaseSecond); }
            return null;
        }).when(flow.artifacts).removeReference(asset.objectKey());
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var oldWorker = pool.submit(service::cleanupDue);
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                String oldLease = flow.jdbc.queryForObject("SELECT lease_token FROM generation_asset_cleanup WHERE id=?", String.class, asset.id());
                // Simulate other recovery attempts exhausting while this original storage call remains delayed.
                flow.jdbc.update("UPDATE generation_asset_cleanup SET state='FAILED',attempts=5 WHERE id=?", asset.id());
                service.retry(1, asset.id());
                var newWorker = pool.submit(service::cleanupDue);
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                String newLease = flow.jdbc.queryForObject("SELECT lease_token FROM generation_asset_cleanup WHERE id=?", String.class, asset.id());
                assertNotEquals(oldLease, newLease);
                assertEquals(1, service.cleanups(1).getFirst().attempts());
                releaseFirst.countDown(); oldWorker.get(10, TimeUnit.SECONDS);
                assertEquals("DELETING", service.cleanups(1).getFirst().state());
                assertEquals(newLease, flow.jdbc.queryForObject("SELECT lease_token FROM generation_asset_cleanup WHERE id=?", String.class, asset.id()));
                releaseSecond.countDown(); newWorker.get(10, TimeUnit.SECONDS);
                assertEquals("SUCCEEDED", service.cleanups(1).getFirst().state());
            } finally { releaseFirst.countDown(); releaseSecond.countDown(); }
        }
    }

    private GenerationRequest input(String asset) { return new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO, "Product in natural light", null, "1280x720", null, 7L, asset); }
    private GenerationService.Submission submit(long user, String key, String asset) {
        try { return flow.service.submit(user, key, input(asset)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
