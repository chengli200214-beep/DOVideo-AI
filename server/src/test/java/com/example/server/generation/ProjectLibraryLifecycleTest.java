package com.example.server.generation;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.film.FilmService;
import com.example.server.storyboard.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectLibraryLifecycleTest {
    @Test void cursorPagesReachProjectsBeyondFiftyWithoutDuplicatesOrCrossUserResults() throws Exception {
        var fixture = new StoryboardFlowTest(); fixture.setup();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 55; i++) expected.add(fixture.service.create(1, "project-" + i, brief(fixture, "Project " + i)).project().id());
        var foreign = fixture.service.create(2, "other-owner", brief(fixture, "Project other owner")).project();
        // Exercise the id tie-breaker: every project has the same ordering timestamp.
        fixture.flow.jdbc.update("UPDATE creative_projects SET updated_at=1000");
        var actual = new ArrayList<String>();
        StoryboardService.Cursor cursor = null;
        do {
            var page = fixture.service.page(1, "", false, 20, cursor == null ? null : cursor.updatedAt(), cursor == null ? null : cursor.id());
            assertTrue(page.items().size() <= 20);
            actual.addAll(page.items().stream().map(StoryboardService.LibraryItem::id).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(expected.stream().sorted().toList(), actual);
        assertEquals(55, new HashSet<>(actual).size()); assertFalse(actual.contains(foreign.id()));
        assertEquals(50, fixture.service.recent(1).size());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.page(1, "", false, 20, 1000L, null));
    }

    @Test void searchTreatsSqlWildcardsAsLiteralAndArchiveViewsRemainOwnerScoped() throws Exception {
        var fixture = new StoryboardFlowTest(); fixture.setup();
        var exact = fixture.service.create(1, "exact", brief(fixture, "100%_! Product Clip")).project();
        fixture.service.create(1, "ordinary", brief(fixture, "100abcX! Product Clip"));
        fixture.service.create(2, "foreign", brief(fixture, "100%_! Product Clip"));
        var matching = fixture.service.page(1, "%_!", false, 20, null, null);
        assertEquals(List.of(exact.id()), matching.items().stream().map(StoryboardService.LibraryItem::id).toList());
        assertEquals(2, fixture.service.page(1, "product CLIP", false, 20, null, null).items().size());
        assertEquals(exact.id(), fixture.service.page(1, exact.id(), false, 20, null, null).items().getFirst().id());
        assertThrows(NoSuchElementException.class, () -> fixture.service.archive(2, exact.id(), true));
        assertTrue(fixture.service.archive(1, exact.id(), true).archived());
        assertTrue(fixture.service.page(1, "%_!", false, 20, null, null).items().isEmpty());
        var archived = fixture.service.page(1, "%_!", true, 20, null, null).items();
        assertEquals(1, archived.size()); assertTrue(archived.getFirst().archived());
        assertTrue(fixture.service.page(2, "%_!", true, 20, null, null).items().isEmpty());
    }

    @Test void activeAndUnknownVideoPlanningAndFilmTasksPreventArchiving() throws Exception {
        var fixture = new FilmFlowTest(); fixture.setup();
        var storyboards = fixture.shots.storyboard.service;
        String project = fixture.project();
        var extra = fixture.shots.service.submit(1, project, "additional", new ShotGenerationService.SubmitInput(1, List.of(fixture.shots.ids().getFirst()), "REGENERATE")).versions().getFirst();
        for (String state : List.of("QUEUED", "SUBMITTING", "RUNNING", "SAVING", "SUBMISSION_UNKNOWN")) {
            fixture.flow.jdbc.update("UPDATE generation_tasks SET state=? WHERE id=?", state, extra.task().id());
            assertConflict(() -> storyboards.archive(1, project, true));
        }
        fixture.flow.jdbc.update("UPDATE generation_tasks SET state='FAILED' WHERE id=?", extra.task().id());
        var planning = new StoryboardModelRepository(fixture.flow.jdbc);
        var plan = planning.create(1, project, "planning", 1, "{}", enabledPlanning(), System.currentTimeMillis());
        for (String state : List.of("QUEUED", "SUBMITTING", "UNKNOWN")) {
            fixture.flow.jdbc.update("UPDATE storyboard_model_tasks SET status=? WHERE id=?", state, plan.id());
            assertConflict(() -> storyboards.archive(1, project, true));
        }
        fixture.flow.jdbc.update("UPDATE storyboard_model_tasks SET status='FAILED' WHERE id=?", plan.id());
        fixture.service.select(1, project, fixture.selection(0));
        var film = fixture.service.submit(1, project, "film", new FilmService.SubmitInput(1, false)).task();
        for (String state : List.of("QUEUED", "RENDERING")) {
            fixture.flow.jdbc.update("UPDATE composition_tasks SET state=? WHERE id=?", state, film.id());
            assertConflict(() -> storyboards.archive(1, project, true));
        }
        fixture.flow.jdbc.update("UPDATE composition_tasks SET state='FAILED' WHERE id=?", film.id());
        assertTrue(storyboards.archive(1, project, true).archived());
        verify(fixture.flow.provider, times(3)).submit(any(), any());
    }

    @Test void archivedProjectsRejectAllNewWorkButRestoreKeepsHistoryAndArtifacts() throws Exception {
        var fixture = new FilmFlowTest(); fixture.setup();
        var storyboards = fixture.shots.storyboard.service; String project = fixture.project();
        fixture.service.select(1, project, fixture.selection(0));
        var failedFilm = fixture.service.submit(1, project, "failed-film", new FilmService.SubmitInput(1, false)).task();
        fixture.flow.jdbc.update("UPDATE composition_tasks SET state='FAILED' WHERE id=?", failedFilm.id());
        var history = storyboards.history(1, project);
        var originalShotJson = fixture.shots.service.overview(1, project).versions().stream().map(ShotGenerationService.Version::shotJson).toList();
        storyboards.archive(1, project, true);
        var changed = fixture.shots.storyboard.change(fixture.shots.project.revision().draft(), "changed after restore");
        assertConflict(() -> storyboards.edit(1, project, 1, changed));
        assertConflict(() -> storyboards.confirm(1, project, 1));
        assertConflict(() -> fixture.shots.service.configure(1, project, new ShotGenerationService.BudgetInput(6, BigDecimal.ZERO)));
        assertConflict(() -> fixture.shots.service.quote(1, project, 1));
        assertConflict(() -> fixture.shots.service.submit(1, project, "archived-shot", new ShotGenerationService.SubmitInput(1, List.of(fixture.shots.ids().getFirst()), "REGENERATE")));
        assertConflict(() -> fixture.service.select(1, project, fixture.selection(1)));
        assertConflict(() -> fixture.service.submit(1, project, "archived-film", new FilmService.SubmitInput(1, true)));
        assertConflict(() -> fixture.service.retry(1, project, failedFilm.id()));
        var evaluation = new com.example.server.film.QualityEvaluationService(fixture.flow.jdbc, fixture.shots.storyboard.repository,
                fixture.shots.service, fixture.flow.service, fixture.flow.json);
        assertConflict(() -> evaluation.review(1, project, fixture.versions.getFirst().id(),
                new com.example.server.film.QualityEvaluationService.ReviewInput(0, 3, 3, 3, true, "offline test")));
        var properties = enabledPlanning();
        var planner = spy(new DeepSeekStoryboardPlanner(properties, fixture.flow.json));
        var model = new StoryboardModelService(new StoryboardModelRepository(fixture.flow.jdbc), storyboards, properties, planner, fixture.flow.json);
        assertConflict(() -> model.submit(1, project, "archived-plan", 1));
        verify(planner, never()).execute(any(), any());
        assertEquals(0, fixture.shots.count("storyboard_model_tasks"));
        assertEquals(0, fixture.shots.count("generation_quality_reviews"));
        assertEquals(3, fixture.shots.count("generation_tasks")); assertEquals(1, fixture.shots.count("composition_tasks"));
        assertNotNull(fixture.flow.service.artifact(1, fixture.versions.getFirst().task().id()));
        assertEquals(history, storyboards.history(1, project));

        assertFalse(storyboards.archive(1, project, false).archived());
        assertEquals(history, storyboards.history(1, project));
        assertEquals(originalShotJson, fixture.shots.service.overview(1, project).versions().stream().map(ShotGenerationService.Version::shotJson).toList());
        assertEquals(2, storyboards.edit(1, project, 1, changed).revision().number());
        assertEquals("CONFIRMED", storyboards.confirm(1, project, 2).status());
    }

    private CreativeBrief brief(StoryboardFlowTest fixture, String title) {
        var b = fixture.brief;
        return new CreativeBrief(title, b.goal(), b.productName(), b.sellingPoints(), b.style(), b.desiredDurationSeconds(), b.frameRatio(), null, b.shotCount());
    }
    private StoryboardModelProperties enabledPlanning() {
        var properties = new StoryboardModelProperties(); properties.setPaidEnabled(true); properties.setApiKey("offline-test-only");
        properties.setAuthorizationId("offline-planning"); properties.setApprovedModel(properties.getModel()); properties.setMaxCalls(10);
        properties.setBudgetLimit(BigDecimal.TEN); properties.setReservationPerCall(BigDecimal.ONE);
        properties.setInputPricePerMillion(new BigDecimal("0.01")); properties.setOutputPricePerMillion(new BigDecimal("0.02"));
        return properties;
    }
    private void assertConflict(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.CONFLICT, assertThrows(BusinessException.class, action).errorCode());
    }
}
