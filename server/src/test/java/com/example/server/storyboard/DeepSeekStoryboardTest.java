package com.example.server.storyboard;

import com.example.server.config.AuthInterceptor;
import com.example.server.controller.*;
import com.example.server.exception.BusinessException;
import com.example.server.generation.GenerationAssetService;
import com.example.server.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** All HTTP is redirected by this test's interceptor to a loopback MockWebServer. No real model call. */
class DeepSeekStoryboardTest {
    ObjectMapper json=new ObjectMapper(); JdbcTemplate jdbc;
    StoryboardService storyboards; StoryboardModelRepository repository;
    StoryboardModelProperties properties; DeepSeekStoryboardPlanner planner;
    StoryboardModelService service; StoryboardModelWorker worker; MockWebServer server;
    CreativeBrief brief=new CreativeBrief("产品视频","展示核心卖点","水杯",List.of("便携","简洁"),"自然光",10,"9:16",null,2);
    @BeforeEach void setup() throws Exception {
        var data=new JdbcDataSource(); data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"); jdbc=new JdbcTemplate(data);
        for (String migration:List.of("V4__create_generation_tasks.sql","V5__generation_inputs_and_trace.sql","V6__creative_storyboards.sql","V7__shot_generations.sql","V8__films_and_reviews.sql","V9__deepseek_storyboard_planning.sql","V10__generation_poll_deadlines.sql","V11__project_library_and_asset_cleanup.sql")) {
            String ddl=new String(new ClassPathResource("db/migration/"+migration).getInputStream().readAllBytes(),StandardCharsets.UTF_8).replace(" CHARACTER SET ascii COLLATE ascii_bin","");
            try (var connection=data.getConnection()) { ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))); }
        }
        storyboards=new StoryboardService(new StoryboardRepository(jdbc),new TemplateStoryboardPlanner(),mock(GenerationAssetService.class),json);
        properties=new StoryboardModelProperties(); properties.setPaidEnabled(true); properties.setApiKey("test-only-key");
        properties.setAuthorizationId("test-auth"); properties.setApprovedModel(properties.getModel()); properties.setMaxCalls(10);
        properties.setBudgetLimit(new BigDecimal("10")); properties.setReservationPerCall(BigDecimal.ONE);
        properties.setInputPricePerMillion(new BigDecimal("0.01")); properties.setOutputPricePerMillion(new BigDecimal("0.02"));
        server=new MockWebServer(); server.start();
        planner=new DeepSeekStoryboardPlanner(properties,json,new OkHttpClient.Builder().addInterceptor(chain->
                chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())));
        repository=new StoryboardModelRepository(jdbc); service=new StoryboardModelService(repository,storyboards,properties,planner,json);
        worker=new StoryboardModelWorker(repository,storyboards,planner,json);
    }
    @AfterEach void close() throws Exception { server.shutdown(); }
    String project(String key) throws Exception { return storyboards.create(1,key,brief).project().id(); }
    String content() throws Exception {
        var template=new TemplateStoryboardPlanner().draft(brief,List.of());
        var shots=template.shots().stream().map(shot->Map.of("title",shot.title(),"subject",shot.subject(),"action",shot.action(),"setting",shot.setting(),
                "camera",shot.camera(),"desiredDurationSeconds",shot.desiredDurationSeconds(),"caption",shot.caption(),"narration",shot.narration(),"prompt",shot.prompt())).toList();
        return json.writeValueAsString(Map.of("script","DeepSeek 测试脚本","shots",shots));
    }
    MockResponse response(String content,String finish) throws Exception { return new MockResponse().setBody(json.writeValueAsString(Map.of("id","response-test","choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",content))),"usage",Map.of("prompt_tokens",100,"completion_tokens",200)))).addHeader("Content-Type","application/json"); }
    @Test void validResultCreatesUnconfirmedImmutableRevisionAndRetainsInputUsageAndTrace() throws Exception {
        String id=project("valid"); storyboards.confirm(1,id,1);
        var task=service.submit(1,id,"call",1); server.enqueue(response(content(),"stop")); worker.process(task.id());
        var saved=service.get(1,id,task.id()); assertEquals("SUCCEEDED",saved.status()); assertEquals(2,saved.savedRevision());
        var view=storyboards.get(1,id); assertEquals("DRAFT",view.status()); assertNull(view.confirmedRevision()); assertEquals("MODEL",view.revision().origin());
        assertEquals("DeepSeek 测试脚本",view.revision().draft().script()); assertEquals(2,storyboards.history(1,id).revisions().size());
        assertEquals(new BigDecimal("0.000005"),saved.estimatedCost()); assertEquals(new BigDecimal("1.000000"),saved.reservedCost());
        assertTrue(saved.responseJson().contains("response-test")); assertTrue(saved.usageJson().contains("prompt_tokens"));
        assertFalse(saved.requestJson().contains("test-only-key"));
        var request=server.takeRequest(1,TimeUnit.SECONDS); assertNotNull(request);
        assertEquals(task.id(),request.getHeader("X-Trace-Id"));
        var body=json.readTree(request.getBody().readUtf8()); assertEquals("json_object",body.path("response_format").path("type").asText());
        assertEquals("disabled",body.path("thinking").path("type").asText()); assertFalse(body.path("stream").asBoolean());
        worker.process(task.id()); assertEquals(1,server.getRequestCount());
        assertEquals(task.id(),service.submit(1,id,"call",1).id());
        assertThrows(NoSuchElementException.class,()->service.get(2,id,task.id()));
    }
    @Test void disabledGateRejectsBeforeReservationAndSendsNoHttpEvenWithCredentials() throws Exception {
        properties.setPaidEnabled(false); assertFalse(service.runtime().ready());
        assertThrows(BusinessException.class,()->service.submit(1,project("disabled"),"call",1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM storyboard_model_tasks",Integer.class)); assertEquals(0,server.getRequestCount());
    }
    @Test void incompleteAuthorizationUnsafeEndpointAndTooSmallReservationCannotInvoke() throws Exception {
        String id=project("config"); properties.setBaseUrl("https://evil.example/v1");
        assertThrows(BusinessException.class,()->service.submit(1,id,"call",1));
        properties.setBaseUrl("https:/missing-host"); assertFalse(service.runtime().ready());
        properties.setBaseUrl("https://api.deepseek.com"); properties.setApprovedModel("another-model");
        assertThrows(BusinessException.class,()->service.submit(1,id,"call",1));
        properties.setApprovedModel(properties.getModel()); properties.setReservationPerCall(new BigDecimal("0.000001"));
        assertThrows(BusinessException.class,()->service.submit(1,id,"call",1)); assertEquals(0,server.getRequestCount());
    }
    @Test void concurrentSameIntentReservesExactlyOneCallAndRejectsConflictingIntent() throws Exception {
        String id=project("concurrent"); CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<StoryboardModelRepository.Task>>();
            for (int i=0;i<6;i++) futures.add(pool.submit(()->{start.await();return service.submit(1,id,"same",1);}));
            start.countDown(); String task=futures.getFirst().get().id(); for (var future:futures) assertEquals(task,future.get().id());
        }
        assertEquals(1,repository.budget("test-auth").usedCalls()); assertEquals(1,repository.recent(1,id).size());
        assertThrows(BusinessException.class,()->service.submit(1,project("other"),"same",1)); assertEquals(0,server.getRequestCount());
    }
    @Test void concurrentDifferentProjectsCannotExceedGlobalQuotaOrMutateAuthorization() throws Exception {
        properties.setMaxCalls(1); String a=project("a"),b=project("b"); CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->submitAfter(start,a)); var two=pool.submit(()->submitAfter(start,b)); start.countDown(); assertNotEquals(one.get(),two.get());
        }
        assertEquals(1,repository.budget("test-auth").usedCalls()); properties.setMaxCalls(2);
        assertThrows(BusinessException.class,()->service.submit(1,project("c"),"new",1));
        assertEquals(1,repository.budget("test-auth").usedCalls()); assertEquals(0,server.getRequestCount());
    }
    boolean submitAfter(CountDownLatch latch,String id) throws Exception { latch.await(); try { service.submit(1,id,id,1); return true; } catch (BusinessException cap) { return false; } }
    @Test void invalidJsonEmptyContentAndTruncationRemainVisibleWithoutAutomaticRepairCalls() throws Exception {
        for (var output:List.of(response("{broken","stop"),response("","stop"),response(content(),"length"),response(content().replace("5","1"),"stop"))) {
            String id=project(UUID.randomUUID().toString()); var task=service.submit(1,id,id,1); server.enqueue(output); worker.process(task.id());
            var result=service.get(1,id,task.id()); assertEquals("FAILED",result.status()); assertNotNull(result.responseJson());
            assertEquals(1,storyboards.get(1,id).revision().number()); worker.process(task.id());
        }
        assertEquals(4,server.getRequestCount()); assertEquals(4,repository.budget("test-auth").usedCalls());
    }
    @Test void modelCannotInjectAssetIdsDuplicateJsonFieldsOrExtraProperties() throws Exception {
        for (String bad:List.of(content().replace("\"title\":","\"referenceAssetId\":\"other-user\",\"title\":"),content().replace("\"script\":","\"script\":\"duplicate\",\"script\":"),"```json\n"+content()+"\n```")) {
            String id=project(UUID.randomUUID().toString()); var task=service.submit(1,id,id,1); server.enqueue(response(bad,"stop")); worker.process(task.id());
            assertEquals("FAILED",service.get(1,id,task.id()).status()); assertEquals(1,storyboards.get(1,id).revision().number());
        }
        assertEquals(3,server.getRequestCount());
    }
    @Test void providerRejectionAndServerErrorsDoNotRetryAndDoNotReleaseConsumedQuota() throws Exception {
        int[] codes={401,429,500}; String[] states={"REJECTED","REJECTED","UNKNOWN"};
        for (int i=0;i<codes.length;i++) {
            String id=project("http"+i); var task=service.submit(1,id,id,1); server.enqueue(new MockResponse().setResponseCode(codes[i]).setBody("secret diagnostic"));
            worker.process(task.id()); worker.process(task.id()); var result=service.get(1,id,task.id());
            assertEquals(states[i],result.status()); assertEquals("HTTP_"+codes[i],result.errorCode()); assertNull(result.responseJson());
        }
        assertEquals(3,server.getRequestCount()); assertEquals(3,repository.budget("test-auth").usedCalls());
    }
    @Test void transportDisconnectAndTimeoutAreUnknownWithExactlyOnePost() throws Exception {
        for (MockResponse output:List.of(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))) {
            properties.setTimeoutSeconds(1); String id=project(UUID.randomUUID().toString()); var task=service.submit(1,id,id,1); server.enqueue(output); worker.process(task.id());
            assertEquals("UNKNOWN",service.get(1,id,task.id()).status()); worker.process(task.id());
        }
        assertEquals(2,server.getRequestCount());
    }
    @Test void expiredSubmissionAndStaleWorkerCannotOverwriteOrResubmitAfterRestart() throws Exception {
        String id=project("restart"); var task=service.submit(1,id,"call",1); var claimed=repository.claim(task.id(),0);
        var restarted=new StoryboardModelRepository(jdbc); restarted.recover(System.currentTimeMillis());
        repository.finish(claimed,new DeepSeekStoryboardPlanner.Completion(new TemplateStoryboardPlanner().draft(brief,List.of()),"{}","result",null,null,null),"{}","[]",true,System.currentTimeMillis());
        worker.process(task.id()); assertEquals("UNKNOWN",service.get(1,id,task.id()).status()); assertEquals(1,storyboards.get(1,id).revision().number());
        assertEquals(1,restarted.budget("test-auth").usedCalls()); assertEquals(0,server.getRequestCount());
    }
    @Test void changedParentRetainsGeneratedOutputButNeverOverwritesHumanEdit() throws Exception {
        String id=project("stale"); var task=service.submit(1,id,"call",1);
        var delayed=spy(planner); doAnswer(call->{
            var view=storyboards.get(1,id); storyboards.edit(1,id,1,view.revision().draft());
            return new DeepSeekStoryboardPlanner.Completion(new TemplateStoryboardPlanner().draft(brief,List.of()),"{}","result",null,null,null);
        }).when(delayed).execute(any(),any());
        new StoryboardModelWorker(repository,storyboards,delayed,json).process(task.id());
        var result=service.get(1,id,task.id()); assertEquals("CONFLICT",result.status()); assertNotNull(result.resultJson());
        assertEquals("USER",storyboards.get(1,id).revision().origin()); assertEquals(2,storyboards.get(1,id).revision().number());
        assertEquals(0,server.getRequestCount());
    }
    @Test void savingFailureRollsBackNewRevisionAndNeverRepeatsTheAlreadyReturnedModelCall() throws Exception {
        String id=project("db-failure"); var task=service.submit(1,id,"call",1);
        jdbc.execute("ALTER TABLE storyboard_model_tasks ADD CONSTRAINT reject_success CHECK (status <> 'SUCCEEDED')");
        server.enqueue(response(content(),"stop")); worker.process(task.id()); worker.process(task.id());
        assertEquals("UNKNOWN",service.get(1,id,task.id()).status()); assertEquals(1,storyboards.get(1,id).revision().number());
        assertEquals(1,storyboards.history(1,id).revisions().size()); assertEquals(1,repository.budget("test-auth").usedCalls());
        assertEquals(1,server.getRequestCount());
    }
    @Test void oneActiveProjectCallAndQueuedParentChangePreventUnintendedRequests() throws Exception {
        String id=project("busy"); var task=service.submit(1,id,"first",1);
        assertThrows(BusinessException.class,()->service.submit(1,id,"second",1));
        var view=storyboards.get(1,id); storyboards.edit(1,id,1,view.revision().draft()); worker.process(task.id());
        assertEquals("CONFLICT",service.get(1,id,task.id()).status()); assertEquals(1,repository.budget("test-auth").usedCalls());
        assertEquals(0,server.getRequestCount());
    }
    @Test void workerRechecksDisabledGateAndPolicyBeforeNetwork() throws Exception {
        String id=project("off"); var first=service.submit(1,id,"first",1); properties.setPaidEnabled(false); worker.process(first.id());
        assertEquals("BLOCKED",service.get(1,id,first.id()).status()); properties.setPaidEnabled(true);
        var second=service.submit(1,id,"second",1); properties.setModel("deepseek-v4-pro"); properties.setApprovedModel("deepseek-v4-pro"); worker.process(second.id());
        assertEquals("BLOCKED",service.get(1,id,second.id()).status()); assertEquals(0,server.getRequestCount());
    }
    @Test void siliconFlowUsesItsOwnThinkingFieldAndDoesNotReceivePrivateAssetIdentifier() throws Exception {
        properties.setBaseUrl("https://api.siliconflow.cn/v1"); properties.setModel("deepseek-ai/DeepSeek-V3.2"); properties.setApprovedModel(properties.getModel());
        var imageBrief=new CreativeBrief(brief.title(),brief.goal(),brief.productName(),brief.sellingPoints(),brief.style(),10,"9:16","private-asset-id",2);
        var body=json.readTree(planner.prepare(imageBrief)); assertFalse(body.path("enable_thinking").asBoolean(true)); assertFalse(body.has("thinking"));
        assertFalse(body.toString().contains("private-asset-id"));
    }
    @Test void apiRequiresAuthenticationOwnershipAndExplicitGateAndDoesNotExposeCredentials() throws Exception {
        var auth=mock(AuthService.class); when(auth.resolveUser("Bearer owner")).thenReturn(1L); when(auth.resolveUser("Bearer other")).thenReturn(2L);
        when(auth.resolveUser(null)).thenThrow(new SecurityException("未登录"));
        var mvc=MockMvcBuilders.standaloneSetup(new StoryboardModelController(service)).setControllerAdvice(new ApiExceptionHandler()).addInterceptors(new AuthInterceptor(auth,json)).build();
        String id=project("api");
        mvc.perform(get("/generation/storyboard-model/runtime")).andExpect(status().isUnauthorized());
        String output=mvc.perform(get("/generation/storyboard-model/runtime").header("Authorization","Bearer owner")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(output.contains("test-only-key"));
        mvc.perform(post("/generation/projects/"+id+"/planning").header("Authorization","Bearer other").header("Idempotency-Key","x").contentType("application/json").content("{\"expectedRevision\":1}")).andExpect(status().isNotFound());
        properties.setPaidEnabled(false);
        mvc.perform(post("/generation/projects/"+id+"/planning").header("Authorization","Bearer owner").header("Idempotency-Key","x").contentType("application/json").content("{\"expectedRevision\":1}")).andExpect(status().isForbidden());
        assertEquals(0,server.getRequestCount());
    }
}
