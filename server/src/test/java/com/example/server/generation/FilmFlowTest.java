package com.example.server.generation;

import com.example.server.film.*;
import com.example.server.exception.BusinessException;
import com.example.server.storyboard.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FilmFlowTest {
    ShotGenerationFlowTest shots;
    GenerationFlowTest flow;
    FilmService service;
    CompositionRepository repository;
    CompositionRenderer renderer;
    CompositionWorker worker;
    List<ShotGenerationService.Version> versions;
    @BeforeEach void setup() throws Exception {
        shots=new ShotGenerationFlowTest(); shots.setup(); shots.ready(6); flow=shots.flow;
        versions=shots.service.submit(1,shots.project.id(),"batch",shots.input("INITIAL",shots.ids())).versions();
        for(var v:versions) shots.complete(v.task().id());
        repository=new CompositionRepository(flow.jdbc);
        service=new FilmService(flow.jdbc,shots.storyboard.repository,repository,flow.service,flow.artifacts,flow.json);
        renderer=mock(CompositionRenderer.class);
        when(renderer.render(any(),any())).thenAnswer(call -> { var task=(CompositionRepository.Task)call.getArgument(0);
            return new CompositionRenderer.Result(new GenerationArtifactStore.Artifact("composed/1/"+task.id()+"/"+task.leaseToken()+".mp4",123,"film-sha"),"{\"durationSeconds\":3}"); });
        worker=new CompositionWorker(repository,renderer,flow.json);
    }
    FilmService.SelectionInput selection(int expected) { return new FilmService.SelectionInput(1,expected,versions.stream().map(ShotGenerationService.Version::id).toList()); }
    String project() { return shots.project.id(); }
    @Test void orderedImmutableSelectionAndIdempotentCompositionSurviveDraftChangesAndRestart() throws Exception {
        var reversed=versions.reversed().stream().map(ShotGenerationService.Version::id).toList();
        var saved=service.select(1,project(),new FilmService.SelectionInput(1,0,reversed));
        assertEquals(shots.ids(),saved.snapshot().clips().stream().map(FilmSpec.Clip::shotId).toList());
        assertEquals(1,service.select(1,project(),selection(0)).number());
        var submitted=service.submit(1,project(),"film",new FilmService.SubmitInput(1,true));
        assertTrue(service.submit(1,project(),"film",new FilmService.SubmitInput(1,true)).reused());
        assertThrows(BusinessException.class,()->service.submit(1,project(),"film",new FilmService.SubmitInput(1,false)));
        shots.storyboard.service.edit(1,project(),1,shots.storyboard.change(shots.project.revision().draft(),"changed after enqueue"));
        assertThrows(BusinessException.class,()->service.submit(1,project(),"another",new FilmService.SubmitInput(1,true)));
        new CompositionWorker(new CompositionRepository(flow.jdbc),renderer,flow.json).process(submitted.task().id());
        var detail=service.get(1,project(),submitted.task().id()); assertEquals("SUCCEEDED",detail.task().state()); assertEquals(saved.snapshot(),detail.input());
        assertEquals(3,detail.events().size()); assertEquals("http://minio.local/presigned",service.artifact(1,project(),submitted.task().id()));
        assertEquals(3,shots.count("generation_tasks")); verify(flow.provider,times(3)).submit(any(),any()); verify(renderer,times(1)).render(any(),eq(saved.snapshot()));
    }
    @Test void crossProjectVersionsIncompleteAndUnfinishedSelectionsCannotBeSaved() throws Exception {
        assertThrows(NoSuchElementException.class,()->service.select(2,project(),selection(0)));
        assertThrows(IllegalArgumentException.class,()->service.select(1,project(),new FilmService.SelectionInput(1,0,List.of(versions.getFirst().id()))));
        assertThrows(NoSuchElementException.class,()->service.select(1,project(),new FilmService.SelectionInput(1,0,List.of(UUID.randomUUID().toString(),versions.get(1).id(),versions.get(2).id()))));
        flow.jdbc.update("UPDATE generation_tasks SET state='RUNNING' WHERE id=?",versions.getFirst().task().id());
        assertThrows(BusinessException.class,()->service.select(1,project(),selection(0))); assertNull(service.overview(1,project()).selection());
        assertThrows(NoSuchElementException.class,()->service.overview(2,project()));
    }
    @Test void concurrentCompositionRequestsCreateOneJobAndStaleLeaseCannotPublish() throws Exception {
        service.select(1,project(),selection(0)); CountDownLatch start=new CountDownLatch(1);
        String id;
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<java.util.concurrent.Future<FilmService.Submitted>>();
            for(int i=0;i<4;i++) jobs.add(pool.submit(()->{start.await();return service.submit(1,project(),"same",new FilmService.SubmitInput(1,true));}));
            start.countDown(); id=jobs.getFirst().get().task().id(); for(var job:jobs) assertEquals(id,job.get().task().id());
        }
        var old=repository.claim(id,System.currentTimeMillis()); assertNull(repository.claim(id,System.currentTimeMillis()));
        flow.jdbc.update("UPDATE composition_tasks SET lease_until=0 WHERE id=?",id);
        var current=repository.claim(id,System.currentTimeMillis()); assertNotEquals(old.leaseToken(),current.leaseToken());
        assertFalse(repository.finish(old,"SUCCEEDED",new GenerationArtifactStore.Artifact("old",123,"sha"),"{}",null,0,System.currentTimeMillis()));
        assertTrue(repository.finish(current,"SUCCEEDED",new GenerationArtifactStore.Artifact("new",123,"sha"),"{}",null,0,System.currentTimeMillis()));
        assertEquals(1,shots.count("composition_tasks")); assertThrows(NoSuchElementException.class,()->service.get(2,project(),id));
    }
    @Test void boundedRenderFailuresAndExplicitRecoveryDoNotCreateOrResubmitModelTasks() throws Exception {
        service.select(1,project(),selection(0)); var job=service.submit(1,project(),"retry",new FilmService.SubmitInput(1,true)).task();
        doThrow(new CompositionRenderer.Failure("FFMPEG_UNAVAILABLE")).when(renderer).render(any(),any());
        for(int i=0;i<3;i++) { flow.jdbc.update("UPDATE composition_tasks SET next_run_at=0 WHERE id=?",job.id()); worker.process(job.id()); }
        assertEquals("FAILED",service.get(1,project(),job.id()).task().state()); assertEquals(3,service.get(1,project(),job.id()).task().attempts());
        verify(flow.provider,times(3)).submit(any(),any()); service.retry(1,project(),job.id());
        doReturn(new CompositionRenderer.Result(new GenerationArtifactStore.Artifact("recovered",123,"sha"),"{}")).when(renderer).render(any(),any());
        worker.process(job.id()); assertEquals("SUCCEEDED",service.get(1,project(),job.id()).task().state());
        assertEquals(1,shots.count("composition_tasks")); assertEquals(3,shots.service.overview(1,project()).budget().usedVersions());
        assertThrows(BusinessException.class,()->service.retry(1,project(),job.id()));
    }
    @Test void interruptedRenderingIsReclaimedWithTheSameInputSnapshot() throws Exception {
        service.select(1,project(),selection(0)); var job=service.submit(1,project(),"restart",new FilmService.SubmitInput(1,false)).task();
        var claimed=repository.claim(job.id(),System.currentTimeMillis());
        flow.jdbc.update("UPDATE composition_tasks SET lease_until=0 WHERE id=?",job.id());
        new CompositionWorker(new CompositionRepository(flow.jdbc),renderer,flow.json).process(job.id());
        assertEquals("SUCCEEDED",service.get(1,project(),job.id()).task().state()); assertEquals(claimed.snapshotJson(),repository.byId(job.id()).snapshotJson());
        verify(flow.provider,times(3)).submit(any(),any());
    }
}
