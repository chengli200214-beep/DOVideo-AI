package com.example.server.generation;

import com.example.server.exception.BusinessException;
import com.example.server.storyboard.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryboardFlowTest {
    GenerationFlowTest flow;
    StoryboardRepository repository;
    StoryboardService service;
    TemplateStoryboardPlanner planner;
    CreativeBrief brief = new CreativeBrief("产品介绍", "  展示产品与核心卖点  ", "白色运动鞋",
            List.of("轻便", "透气", "易搭配"), "自然光，简洁产品展示", 16, "9:16", null, 3);
    @BeforeEach void setup() throws Exception {
        flow = new GenerationFlowTest(); flow.setup();
        repository = new StoryboardRepository(flow.jdbc);
        planner = spy(new TemplateStoryboardPlanner());
        service = new StoryboardService(repository, planner, flow.assets, flow.json);
    }
    StoryboardDraft change(StoryboardDraft draft, String prompt) {
        var shots = new ArrayList<>(draft.shots());
        var shot = shots.getFirst();
        shots.set(0, new StoryboardDraft.Shot(shot.id(), shot.sequence(), shot.title(), shot.subject(), shot.action(), shot.setting(),
                shot.camera(), shot.referenceAssetId(), shot.desiredDurationSeconds(), shot.frameRatio(), shot.caption(), shot.narration(),
                prompt, 999, shot.parameters()));
        return new StoryboardDraft(draft.script(), shots);
    }
    @Test void templateDraftIsEditableVersionedAndRequiresFreshConfirmationWithoutModelCalls() throws Exception {
        var created = service.create(1, "project", brief);
        var project = created.project();
        assertEquals("DRAFT", project.status());
        assertEquals(brief.goal(), project.brief().goal());
        assertEquals("TEMPLATE", project.revision().origin());
        assertEquals(16, project.revision().draft().shots().stream().mapToInt(StoryboardDraft.Shot::desiredDurationSeconds).sum());
        assertTrue(service.create(1, "project", brief).reused());
        var first = project.revision().draft();
        service.confirm(1, project.id(), 1); service.confirm(1, project.id(), 1);
        assertEquals(1, service.history(1, project.id()).confirmations().size());
        var draft = change(first, "新的产品画面提示词");
        var edited = service.edit(1, project.id(), 1, draft);
        assertEquals("DRAFT", edited.status()); assertNull(edited.confirmedRevision());
        assertEquals(2, edited.revision().number()); assertEquals(2, edited.revision().draft().shots().getFirst().promptVersion());
        assertEquals(1, edited.revision().draft().shots().get(1).promptVersion());
        assertEquals(2, service.edit(1, project.id(), 1, draft).revision().number());
        assertThrows(BusinessException.class, () -> service.confirm(1, project.id(), 1));
        service.confirm(1, project.id(), 2);
        var restarted = new StoryboardService(new StoryboardRepository(flow.jdbc), planner, flow.assets, flow.json);
        var history = restarted.history(1, project.id());
        assertEquals(first, history.revisions().getFirst().draft());
        assertEquals("USER", history.revisions().get(1).origin());
        assertEquals(2, history.confirmations().size());
        assertEquals("CONFIRMED", restarted.get(1, project.id()).status());
        assertEquals(0, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class));
        verify(flow.provider, never()).submit(any(), any());
    }
    @Test void concurrentCreationIsIdempotentAndConflictingIntentIsRejected() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<java.util.concurrent.Future<StoryboardService.Created>>();
            for (int index = 0; index < 6; index++) results.add(pool.submit(() -> { start.await(); return service.create(1, "same", brief); }));
            start.countDown(); String id = results.getFirst().get().project().id();
            for (var result : results) assertEquals(id, result.get().project().id());
        }
        assertEquals(1, repository.recent(1).size());
        assertThrows(BusinessException.class, () -> service.create(1, "same", new CreativeBrief(brief.title(), "different goal", brief.productName(),
                brief.sellingPoints(), brief.style(), brief.desiredDurationSeconds(), brief.frameRatio(), null, 3)));
        assertNotEquals(repository.recent(1).getFirst().id(), service.create(2, "same", brief).project().id());
    }
    @Test void concurrentEditsCannotOverwriteEachOtherOrConfirmAnOldRevision() throws Exception {
        var project = service.create(1, "edits", brief).project();
        var a = change(project.revision().draft(), "版本 A");
        var b = change(project.revision().draft(), "版本 B");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> saveAfter(start, project.id(), a));
            var second = pool.submit(() -> saveAfter(start, project.id(), b));
            start.countDown(); assertNotEquals(first.get(), second.get());
        }
        assertEquals(2, service.history(1, project.id()).revisions().size());
        assertThrows(BusinessException.class, () -> service.confirm(1, project.id(), 1));
    }
    private boolean saveAfter(CountDownLatch start, String id, StoryboardDraft draft) throws Exception {
        start.await();
        try { service.edit(1, id, 1, draft); return true; }
        catch (BusinessException conflict) { return false; }
    }
    @Test void ownershipAndInvalidEditsLeaveNoRevisionOrGenerationTask() throws Exception {
        var project = service.create(1, "validation", brief).project();
        assertThrows(NoSuchElementException.class, () -> service.get(2, project.id()));
        assertThrows(NoSuchElementException.class, () -> service.history(2, project.id()));
        assertThrows(NoSuchElementException.class, () -> service.confirm(2, project.id(), 1));
        assertThrows(IllegalArgumentException.class, () -> service.edit(1, project.id(), 1, new StoryboardDraft("script", List.of())));
        var same = project.revision().draft().shots().getFirst();
        assertThrows(IllegalArgumentException.class, () -> service.edit(1, project.id(), 1,
                new StoryboardDraft("script", List.of(same, same, same))));
        assertEquals(1, repository.revisions(project.id()).size());
        assertEquals(0, flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class));
        assertTrue(service.recent(2).isEmpty());
    }
    @Test void ownedReferenceImagesAreRetainedAndOtherUsersImagesAreRejected() throws Exception {
        String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=";
        var asset = flow.assets.inline(1, png);
        var imageBrief = new CreativeBrief(brief.title(), brief.goal(), brief.productName(), brief.sellingPoints(), brief.style(), 16, "9:16", asset.id(), 3);
        var project = service.create(1, "image", imageBrief).project();
        assertTrue(project.revision().draft().shots().stream().allMatch(shot -> asset.id().equals(shot.referenceAssetId())));
        assertThrows(NoSuchElementException.class, () -> service.create(2, "image", imageBrief));
    }
    @Test void malformedDraftsHaveTwoAttemptsThenRequireManualRepair() throws Exception {
        doReturn(new StoryboardDraft("", List.of())).when(planner).draft(any(), anyList());
        var project = service.create(1, "malformed", brief).project();
        verify(planner, times(2)).draft(any(), anyList());
        assertEquals("NEEDS_EDIT", project.status()); assertEquals("MANUAL_REQUIRED", project.revision().origin());
        assertEquals(2, service.history(1, project.id()).attempts().size());
        assertThrows(BusinessException.class, () -> service.confirm(1, project.id(), 1));
        var repaired = new TemplateStoryboardPlanner().draft(brief, List.of());
        service.edit(1, project.id(), 1, repaired);
        assertEquals("CONFIRMED", service.confirm(1, project.id(), 2).status());
    }
    @Test void validCorrectionIsAcceptedAfterOneInvalidAttempt() throws Exception {
        var corrected = new TemplateStoryboardPlanner().draft(brief, List.of());
        doReturn(null, corrected).when(planner).draft(any(), anyList());
        var project = service.create(1, "corrected", brief).project();
        assertEquals("DRAFT", project.status()); assertTrue(project.revision().validationErrors().isEmpty());
        assertEquals(2, service.history(1, project.id()).attempts().size());
    }
    @Test void revisionAndConfirmationFailuresRollBackProjectState() throws Exception {
        var project = service.create(1, "atomic", brief).project();
        flow.jdbc.execute("ALTER TABLE storyboard_revisions ADD CONSTRAINT no_user_revision CHECK (origin <> 'USER')");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.edit(1, project.id(), 1, change(project.revision().draft(), "change")));
        assertEquals(1, service.get(1, project.id()).revision().number());
        flow.jdbc.execute("ALTER TABLE storyboard_confirmations ADD CONSTRAINT no_first_confirmation CHECK (revision <> 1)");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> service.confirm(1, project.id(), 1));
        assertEquals("DRAFT", service.get(1, project.id()).status()); assertNull(service.get(1, project.id()).confirmedRevision());
    }
}
