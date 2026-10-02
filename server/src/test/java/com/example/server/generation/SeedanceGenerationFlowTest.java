package com.example.server.generation;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.example.server.generation.GenerationTask.State.*;

class SeedanceGenerationFlowTest {
    GenerationFlowTest flow;
    int posts, queries;
    boolean uncertain;
    GenerationProvider provider;
    @BeforeEach void setup() throws Exception {
        flow=new GenerationFlowTest(); flow.setup(); flow.properties=SeedanceGenerationProviderTest.config();
        provider=new SeedanceGenerationProvider(flow.properties,flow.json,new OkHttpClient.Builder().addInterceptor(chain -> {
            if(chain.request().method().equals("POST")) {
                posts++;
                if(uncertain) throw new java.io.IOException("offline timeout");
                return SeedanceGenerationProviderTest.response(chain.request(),200,"{\"id\":\"cgt-original\"}");
            }
            queries++;
            return SeedanceGenerationProviderTest.response(chain.request(),200,"{\"id\":\"cgt-original\",\"model\":\""+SeedanceGenerationProvider.MODEL
                    +"\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://artifact.example.com/video.mp4\"}}");
        }));
        flow.service=new GenerationService(flow.repository,flow.properties,List.of(flow.provider,provider),flow.json,flow.artifacts,flow.assets);
        flow.worker=new GenerationWorker(flow.repository,flow.service,flow.json,flow.artifacts);
    }
    @Test void privateImageLoopArchivesAfterRestartAndProviderSwitchWithoutAnotherPost() throws Exception {
        var asset=flow.assets.inline(1,SeedanceGenerationProviderTest.png(720,1280));
        var request=new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO,"product",null,"720x1280",null,7L,asset.id());
        String id=flow.service.submit(1,"seedance-private",request).task().id();
        flow.step(id); assertEquals(RUNNING,flow.service.get(1,id).state()); assertEquals(1,posts);
        var trace=flow.service.trace(1,id);
        assertEquals(5,trace.effectiveSubmission().path("parameters").path("duration").asInt());
        assertEquals("[private-reference]",trace.effectiveSubmission().path("parameters").path("content").get(1).path("image_url").path("url").asText());
        assertEquals(asset.sha256(),trace.effectiveSubmission().path("imageReference").path("sha256").asText());
        assertFalse(flow.json.writeValueAsString(trace).contains("data:image"));
        assertFalse(flow.repository.byId(id).requestJson().contains("data:image"));
        flow.properties.setPaidEnabled(false); flow.properties.setProvider("mock"); flow.properties.getSeedance().setRecoveryEnabled(true);
        flow.worker=new GenerationWorker(new GenerationRepository(flow.jdbc),flow.service,flow.json,flow.artifacts);
        flow.step(id); assertEquals(SAVING,flow.service.get(1,id).state()); flow.step(id); assertEquals(SUCCEEDED,flow.service.get(1,id).state());
        assertTrue(flow.service.submit(1,"seedance-private",request).reused()); assertEquals(1,posts); assertEquals(1,queries);
        assertEquals(1,flow.jdbc.queryForObject("SELECT used_tasks FROM generation_authorizations",Integer.class));
        verify(flow.artifacts,times(1)).save(any(),anyString());
    }
    @Test void unknownSubmissionsKeepBudgetAndDoNotResubmitOnReplayOrRestart() throws Exception {
        uncertain=true; String id=flow.service.submit(1,"seedance-unknown",flow.text).task().id(); flow.step(id);
        assertEquals(SUBMISSION_UNKNOWN,flow.service.get(1,id).state()); assertEquals("SUBMISSION_TRANSPORT_ERROR",flow.service.get(1,id).errorCode());
        flow.worker=new GenerationWorker(new GenerationRepository(flow.jdbc),flow.service,flow.json,flow.artifacts);
        assertTrue(flow.service.submit(1,"seedance-unknown",flow.text).reused()); flow.step(id);
        assertEquals(1,posts); assertEquals(0,queries); assertEquals(1,flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations",Integer.class));
    }
    @Test void invalidPrivateImageFailsBeforeReservationAndBeforeHttpSubmission() throws Exception {
        var asset=flow.assets.inline(1,SeedanceGenerationProviderTest.png(1,1));
        String id=flow.service.submit(1,"small-image",new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO,"product",null,"720x1280",null,null,asset.id())).task().id();
        flow.step(id); assertEquals(FAILED,flow.service.get(1,id).state()); assertEquals("SUBMISSION_PREPARATION_FAILED",flow.service.get(1,id).errorCode());
        assertEquals(0,posts); assertEquals(0,flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations",Integer.class));
    }
    @Test void queuedRealTaskStaysUnsubmittedWhenOnlyRecoveryIsEnabled() throws Exception {
        String id=flow.service.submit(1,"queued-recovery",flow.text).task().id();
        flow.properties.setPaidEnabled(false); flow.properties.getSeedance().setRecoveryEnabled(true);
        assertThrows(com.example.server.exception.BusinessException.class,()->flow.step(id));
        assertEquals(QUEUED,flow.service.get(1,id).state()); assertEquals(0,posts);
        assertEquals(0,flow.jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations",Integer.class));
    }
}
