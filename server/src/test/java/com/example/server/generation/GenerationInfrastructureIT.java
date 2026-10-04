package com.example.server.generation;

import com.example.server.config.AuthInterceptor;
import com.example.server.config.MinioConfig;
import com.example.server.config.WebConfig;
import com.example.server.controller.ApiExceptionHandler;
import com.example.server.controller.GenerationController;
import com.example.server.controller.GenerationInputController;
import com.example.server.controller.GenerationAssetLibraryController;
import com.example.server.controller.StoryboardController;
import com.example.server.controller.ShotGenerationController;
import com.example.server.storyboard.ShotGenerationService;
import com.example.server.film.*;
import com.example.server.controller.FilmController;
import com.example.server.controller.EvaluationController;
import com.example.server.storyboard.CreativeBrief;
import com.example.server.storyboard.StoryboardRepository;
import com.example.server.storyboard.StoryboardService;
import com.example.server.storyboard.TemplateStoryboardPlanner;
import com.example.server.storyboard.StoryboardModelProperties;
import com.example.server.storyboard.StoryboardModelRepository;
import com.example.server.storyboard.StoryboardModelService;
import com.example.server.storyboard.DeepSeekStoryboardPlanner;
import com.example.server.storyboard.StoryboardModelWorker;
import com.example.server.controller.StoryboardModelController;
import com.example.server.controller.UserController;
import com.example.server.mapper.UserMapper;
import com.example.server.service.AuthService;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.example.server.generation.GenerationTask.State.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real infrastructure acceptance. All paid model gates remain closed. */
class GenerationInfrastructureIT {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private int port;
    private final List<String> passed = new ArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
    })
    @MapperScan(basePackageClasses = UserMapper.class)
    @Import({GenerationRepository.class, GenerationProperties.class, MockGenerationProvider.class,
            GenerationService.class, GenerationWorker.class, GenerationSchedulingConfig.class,
            GenerationAssetService.class, GenerationInputController.class,
            GenerationAssetLifecycleService.class, GenerationAssetLibraryController.class,
            StoryboardController.class, StoryboardRepository.class, StoryboardService.class, TemplateStoryboardPlanner.class,
            StoryboardModelProperties.class,StoryboardModelRepository.class,StoryboardModelService.class,DeepSeekStoryboardPlanner.class,StoryboardModelWorker.class,StoryboardModelController.class,
            ShotGenerationService.class, ShotGenerationController.class,
            FilmService.class, CompositionRepository.class, CompositionWorker.class, FfmpegCompositionRenderer.class, FilmController.class,
            QualityEvaluationService.class, EvaluationController.class,
            MinioGenerationArtifactStore.class, MinioConfig.class, MinioUtils.class,
            AuthService.class, AuthInterceptor.class, WebConfig.class,
            UserController.class, GenerationController.class, ApiExceptionHandler.class})
    static class TestApplication { }

    private ServletWebServerApplicationContext start(boolean paused) {
        return start(paused,paused);
    }
    private ServletWebServerApplicationContext start(boolean paused,boolean pauseComposition) {
        String[] options = {
                "--spring.config.name=generation-infrastructure-it",
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + required("GENERATION_IT_DB_URL"),
                "--spring.datasource.username=generation_it",
                "--spring.datasource.password=" + required("GENERATION_IT_DB_PASSWORD"),
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.flyway.locations=classpath:db/migration",
                "--spring.flyway.baseline-on-migrate=false",
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + required("GENERATION_IT_REDIS_PORT"),
                "--spring.data.redis.password=" + required("GENERATION_IT_REDIS_PASSWORD"),
                "--minio.endpoint=" + required("GENERATION_IT_MINIO_URL"),
                "--minio.accessKey=generation-it",
                "--minio.secretKey=" + required("GENERATION_IT_MINIO_PASSWORD"),
                "--minio.bucketName=generation-it",
                "--generation.provider=mock", "--generation.paid-enabled=false", "--generation.api-key=",
                "--generation.recovery-enabled=false", "--generation.seedance.recovery-enabled=false",
                "--generation.seedance.api-key=", "--storyboard.model.paid-enabled=false", "--storyboard.model.api-key=",
                "--generation.worker-delay-ms=100",
                "--generation.worker-initial-delay-ms=" + (paused ? "600000" : "0")
                , "--spring.task.scheduling.pool.size=3", "--composition.worker-delay-ms=100",
                "--composition.ffmpeg-dir=" + required("GENERATION_IT_FFMPEG_DIR"),
                "--composition.worker-initial-delay-ms=" + (pauseComposition ? "600000" : "0")
        };
        var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TestApplication.class).run(options);
        port = context.getWebServer().getPort();
        assertEquals(List.of("mock"), context.getBeansOfType(GenerationProvider.class).values().stream().map(GenerationProvider::name).toList());
        assertFalse(context.getBean(GenerationProperties.class).isPaidEnabled());
        return context;
    }

    @Test
    void realMysqlRedisMinioHttpLoopAndApplicationRestart() throws Exception {
        Files.deleteIfExists(Path.of("target/generation-infrastructure-acceptance.json"));
        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String token;
        String otherToken;
        String saving;
        String running;
        String queued;
        String unknown;
        String image;
        String referenceImage;
        String creativeProject;
        String shotProject;
        String shotBody;
        String targetShot;
        String shotOperation;
        String filmJob;
        List<String> shotTasks = new ArrayList<>();
        String savingRemote;
        String runningRemote;
        String body = json.writeValueAsString(new GenerationRequest(GenerationRequest.Kind.TEXT_TO_VIDEO,
                "mock acceptance " + run, null, "1280x720", null, 42L));

        try (var first = start(true)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            assertEquals(11, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND type='SQL'", Integer.class));
            assertEquals("ascii_bin", jdbc.queryForObject("""
                    SELECT COLLATION_NAME FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='generation_tasks' AND column_name='idempotency_key'
                    """, String.class));
            passed.add("Real MySQL Flyway V1-V11 migrations and case-sensitive idempotency collation");
            token = registerAndLogin("owner_" + run);
            otherToken = registerAndLogin("other_" + run);
            request("GET", "/generation/tasks/missing", null, null, null, 401);
            passed.add("Real registration, login and Redis authentication");

            String key = "concurrent-" + run;
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<java.util.concurrent.Future<JsonNode>>();
                for (int i = 0; i < 8; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return submit(body, token, key, null);
                    }));
                }
                start.countDown();
                saving = futures.getFirst().get().path("data").path("task").path("id").asText();
                for (var future : futures) assertEquals(saving, future.get().path("data").path("task").path("id").asText());
            }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks WHERE idempotency_key=?", Integer.class, key));
            submit(body.replace("mock acceptance", "changed acceptance"), token, key, 409);
            request("GET", "/generation/tasks/" + saving, null, otherToken, null, 404);
            request("GET", "/generation/tasks/" + saving + "/artifact", null, token, null, 409);
            String upper = submit(body, token, "Case-" + run, 202).path("data").path("task").path("id").asText();
            String lower = submit(body, token, "case-" + run, 202).path("data").path("task").path("id").asText();
            assertNotEquals(upper, lower);
            passed.add("Eight concurrent HTTP submissions create one MySQL task; conflicts and owner isolation enforced");

            var worker = first.getBean(GenerationWorker.class);
            worker.process(saving);
            worker.process(saving);
            GenerationTask persisted = first.getBean(GenerationRepository.class).byId(saving);
            assertEquals(SAVING, persisted.state());
            savingRemote = persisted.remoteId();
            // Simulate a crash after the object write but before recording SUCCEEDED.
            first.getBean(GenerationArtifactStore.class).save(persisted, persisted.sourceUrl());

            running = submit(body, token, "running-" + run, 202).path("data").path("task").path("id").asText();
            worker.process(running);
            runningRemote = first.getBean(GenerationRepository.class).byId(running).remoteId();
            queued = submit(body, token, "queued-" + run, 202).path("data").path("task").path("id").asText();
            unknown = submit(body, token, "uncertain-" + run, 202).path("data").path("task").path("id").asText();
            var repository = first.getBean(GenerationRepository.class);
            var claim = repository.claim(unknown, System.currentTimeMillis());
            assertTrue(repository.beginSubmission(claim, System.currentTimeMillis()));
            jdbc.update("UPDATE generation_tasks SET lease_until=0 WHERE id=?", unknown);

            String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZkAAAAASUVORK5CYII=";
            byte[] imageBytes = java.util.Base64.getDecoder().decode(png.substring(png.indexOf(',') + 1));
            JsonNode asset = uploadImage(token, imageBytes).path("data");
            referenceImage = asset.path("id").asText();
            assertEquals(referenceImage, uploadImage(token, imageBytes).path("data").path("id").asText());
            request("GET", "/generation/assets/" + referenceImage, null, otherToken, null, 404);
            request("GET", "/generation/assets/" + referenceImage + "/preview", null, otherToken, null, 404);
            assertTrue(request("GET", "/generation/capabilities", null, token, null, 200).path("data").get(0).path("available").asBoolean());
            String imageBody = json.writeValueAsString(new GenerationRequest(GenerationRequest.Kind.IMAGE_TO_VIDEO,
                    "animate mock", null, "960x960", null, null, referenceImage));
            image = submit(imageBody, token, "image-" + run, 202).path("data").path("task").path("id").asText();
            assertFalse(repository.byId(image).requestJson().contains("data:image"));
            passed.add("Authenticated multipart reference upload deduplicates content, enforces ownership and persists asset IDs without inline data");
            int generationCount = jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class);
            String briefBody = json.writeValueAsString(new CreativeBrief("产品介绍", "展示产品卖点", "测试产品",
                    List.of("轻便", "易用"), "自然光", 15, "9:16", referenceImage, 3));
            JsonNode creative = request("POST", "/generation/projects", briefBody, token, "creative-" + run, 201).path("data").path("project");
            creativeProject = creative.path("id").asText();
            JsonNode planningRuntime=request("GET","/generation/storyboard-model/runtime",null,token,null,200).path("data");
            assertFalse(planningRuntime.path("ready").asBoolean());
            request("POST","/generation/projects/"+creativeProject+"/planning","{\"expectedRevision\":1}",token,"disabled-planning-"+run,403);
            request("GET","/generation/projects/"+creativeProject+"/planning",null,otherToken,null,404);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM storyboard_model_tasks",Integer.class));
            passed.add("DeepSeek planning endpoints require ownership; default paid gate rejects before MySQL reservation and leaves all model task counts unchanged");
            verifyMysqlPlanningReservations(first,token);
            request("POST", "/generation/projects", briefBody, token, "creative-" + run, 200);
            request("GET", "/generation/projects/" + creativeProject, null, otherToken, null, 404);
            request("POST", "/generation/projects/" + creativeProject + "/confirm", "{\"expectedRevision\":1}", token, null, 200);
            var edited = (com.fasterxml.jackson.databind.node.ObjectNode) creative.path("revision").path("draft").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) edited.path("shots").get(0)).put("prompt", "edited product scene");
            String editedBody = json.writeValueAsString(Map.of("expectedRevision", 1, "draft", edited));
            JsonNode revision = request("POST", "/generation/projects/" + creativeProject + "/revisions", editedBody, token, null, 200).path("data");
            assertEquals("DRAFT", revision.path("status").asText());
            assertEquals(2, revision.path("revision").path("number").asInt());
            request("POST", "/generation/projects/" + creativeProject + "/revisions", editedBody, token, null, 200);
            request("POST", "/generation/projects/" + creativeProject + "/confirm", "{\"expectedRevision\":1}", token, null, 409);
            assertEquals(generationCount, jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class));
            var shotCreative = request("POST", "/generation/projects", briefBody, token, "shot-creative-" + run, 201).path("data").path("project");
            shotProject = shotCreative.path("id").asText();
            var shotIds = new ArrayList<String>();
            shotCreative.path("revision").path("draft").path("shots").forEach(shot -> shotIds.add(shot.path("id").asText()));
            targetShot = shotIds.getFirst();
            shotBody = json.writeValueAsString(Map.of("revision", 1, "mode", "INITIAL", "shotIds", shotIds));
            request("POST", "/generation/projects/" + shotProject + "/generations", shotBody, token, "shots-" + run, 409);
            request("POST", "/generation/projects/" + shotProject + "/confirm", "{\"expectedRevision\":1}", token, null, 200);
            request("POST", "/generation/projects/" + shotProject + "/budget", "{\"maxVersions\":4,\"costLimit\":0}", token, null, 200);
            JsonNode quote = request("GET", "/generation/projects/" + shotProject + "/generation-quote?revision=1", null, token, null, 200).path("data");
            assertEquals("IMAGE_TO_VIDEO", quote.path("shots").get(0).path("kind").asText());
            assertEquals("720x1280", quote.path("shots").get(0).path("imageSize").asText());
            final String shotPath = "/generation/projects/" + shotProject + "/generations", submitShots = shotBody, ownerToken = token;
            CountDownLatch shotStart = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(4)) {
                var results = new ArrayList<java.util.concurrent.Future<JsonNode>>();
                for (int index = 0; index < 4; index++) results.add(pool.submit(() -> {
                    shotStart.await(); return request("POST", shotPath, submitShots, ownerToken, "shots-" + run, null).path("data");
                }));
                shotStart.countDown(); var result = results.getFirst().get(); shotOperation = result.path("operationId").asText();
                for (var response : results) assertEquals(shotOperation, response.get().path("operationId").asText());
                result.path("versions").forEach(version -> shotTasks.add(version.path("task").path("id").asText()));
                assertEquals(3, result.path("budget").path("usedVersions").asInt());
            }
            assertEquals(generationCount + 3, jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks", Integer.class));
            request("GET", shotPath, null, otherToken, null, 404);
            request("GET", shotPath, null, null, null, 401);
        }

        try (var restarted = start(false,true)) {
            JsonNode saved = waitFor(token, saving, "SUCCEEDED");
            JsonNode resumed = waitFor(token, running, "SUCCEEDED");
            waitFor(token, queued, "SUCCEEDED");
            waitFor(token, image, "SUCCEEDED");
            waitFor(token, unknown, "SUBMISSION_UNKNOWN");
            assertEquals(savingRemote, saved.path("data").path("remoteId").asText());
            assertEquals(runningRemote, resumed.path("data").path("remoteId").asText());
            submit(body, token, "concurrent-" + run, 200);
            request("POST", "/generation/tasks/" + unknown + "/retry", "{}", token, null, 409);
            request("GET", "/generation/tasks/" + saving + "/artifact", null, otherToken, null, 404);
            passed.add("Application restart resumes QUEUED/RUNNING/SAVING using persistent DB state and the original Redis session");
            passed.add("Expired SUBMITTING remains SUBMISSION_UNKNOWN and cannot be blindly retried");

            String link = request("GET", "/generation/tasks/" + saving + "/artifact", null, token, null, 200).path("data").asText();
            HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(URI.create(link)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            byte[] expected;
            try (var input = new ClassPathResource("generation/sample.mp4").getInputStream()) { expected = input.readAllBytes(); }
            assertArrayEquals(expected, download.body());
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(download.body()));
            assertEquals(sha, saved.path("data").path("artifactSha256").asText());
            assertEquals(expected.length, saved.path("data").path("artifactSize").asLong());
            var minio = restarted.getBean(MinioClient.class);
            int objects = 0;
            for (var object : minio.listObjects(ListObjectsArgs.builder().bucket("generation-it")
                    .prefix(saved.path("data").path("artifactKey").asText()).recursive(true).build())) {
                assertEquals(saved.path("data").path("artifactKey").asText(), object.get().objectName());
                objects++;
            }
            assertEquals(1, objects);
            URI unsigned = new URI(URI.create(link).getScheme(), URI.create(link).getAuthority(), URI.create(link).getPath(), null, null);
            assertNotEquals(200, http.send(HttpRequest.newBuilder(unsigned).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            passed.add("Actual MinIO signed download matches MP4 bytes, length and SHA-256; retry keeps one object and anonymous download is denied");
            passed.add("Both text-to-video and image-to-video finish through automatic scheduled workers");
            JsonNode trace = request("GET", "/generation/tasks/" + image + "/trace", null, token, null, 200).path("data");
            assertEquals(5, trace.path("events").size());
            assertEquals(referenceImage, trace.path("effectiveSubmission").path("imageReference").path("id").asText());
            assertFalse(trace.toString().contains("data:image"));
            request("GET", "/generation/tasks/" + image + "/trace", null, otherToken, null, 404);
            String preview = request("GET", "/generation/assets/" + referenceImage + "/preview", null, token, null, 200).path("data").asText();
            var imageDownload = http.send(HttpRequest.newBuilder(URI.create(preview)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, imageDownload.statusCode());
            assertEquals(68, imageDownload.body().length);
            URI unsignedImage = new URI(URI.create(preview).getScheme(), URI.create(preview).getAuthority(), URI.create(preview).getPath(), null, null);
            assertNotEquals(200, http.send(HttpRequest.newBuilder(unsignedImage).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            passed.add("Reference objects remain private after restart; owner-only trace preserves effective parameters and ordered stage timestamps");
            JsonNode creative = request("GET", "/generation/projects/" + creativeProject, null, token, null, 200).path("data");
            assertEquals("DRAFT", creative.path("status").asText());
            assertEquals(referenceImage, creative.path("revision").path("draft").path("shots").get(0).path("referenceAssetId").asText());
            assertEquals(2, creative.path("revision").path("draft").path("shots").get(0).path("promptVersion").asInt());
            JsonNode versions = request("GET", "/generation/projects/" + creativeProject + "/revisions", null, token, null, 200).path("data");
            assertEquals(2, versions.path("revisions").size()); assertEquals(1, versions.path("confirmations").size());
            assertNotEquals("edited product scene", versions.path("revisions").get(0).path("draft").path("shots").get(0).path("prompt").asText());
            request("POST", "/generation/projects/" + creativeProject + "/confirm", "{\"expectedRevision\":2}", token, null, 200);
            request("GET", "/generation/projects/" + creativeProject + "/revisions", null, otherToken, null, 404);
            passed.add("Template storyboard creation, idempotent editing, immutable revisions and confirmation history survive application restart; confirmation creates no video task");
            for (String task : shotTasks) {
                var completed = waitFor(token, task, "SUCCEEDED").path("data");
                assertNotNull(completed.get("artifactSha256")); assertTrue(completed.path("artifactSize").asLong() > 0);
                request("GET", "/generation/tasks/" + task + "/artifact", null, otherToken, null, 404);
            }
            String shotPath = "/generation/projects/" + shotProject + "/generations";
            var replay = request("POST", shotPath, shotBody, token, "shots-" + run, 200).path("data");
            assertEquals(shotOperation, replay.path("operationId").asText()); assertTrue(replay.path("reused").asBoolean());
            String regenerate = json.writeValueAsString(Map.of("revision", 1, "mode", "REGENERATE", "shotIds", List.of(targetShot)));
            var regenerated = request("POST", shotPath, regenerate, token, "regenerate-" + run, 201).path("data");
            assertEquals(1, regenerated.path("versions").size()); assertEquals(2, regenerated.path("versions").get(0).path("version").asInt());
            String regeneratedTask = regenerated.path("versions").get(0).path("task").path("id").asText();
            waitFor(token, regeneratedTask, "SUCCEEDED");
            request("POST", shotPath, regenerate, token, "regenerate-" + run, 200);
            request("POST", shotPath, regenerate, token, "over-budget-" + run, 409);
            var overview = request("GET", shotPath, null, token, null, 200).path("data");
            assertEquals(4, overview.path("versions").size()); assertEquals(4, overview.path("budget").path("usedVersions").asInt());
            assertEquals(0, overview.path("unknownCostTasks").asInt());
            passed.add("Concurrent confirmed-storyboard submission creates one three-shot batch; queued shot tasks resume after restart and archive private MinIO artifacts; local regeneration creates only one new version and enforces project quota");
            request("GET", "/generation/runtime", null, null, null, 401);
            assertTrue(request("GET", "/generation/runtime", null, token, null, 200).path("data").path("available").asBoolean());
            assertTrue(request("GET", "/generation/runtime", null, token, null, 200).path("data").path("captionsAvailable").asBoolean());
            var selectionIds = new ArrayList<String>();
            // The local regenerated version replaces only its matching shot, other shots keep their first versions.
            overview.path("versions").forEach(v -> { if (!v.path("shotId").asText().equals(targetShot) || v.path("version").asInt()==2) selectionIds.add(v.path("id").asText()); });
            String selectionBody=json.writeValueAsString(Map.of("revision",1,"expectedSelectionVersion",0,"versionIds",selectionIds));
            request("POST", "/generation/projects/"+shotProject+"/selections",selectionBody,otherToken,null,404);
            var selection=request("POST", "/generation/projects/"+shotProject+"/selections",selectionBody,token,null,200).path("data");
            assertEquals(1,selection.path("number").asInt()); assertEquals(3,selection.path("snapshot").path("clips").size());
            request("POST", "/generation/projects/"+shotProject+"/selections",selectionBody,token,null,200);
            String filmBody="{\"selectionVersion\":1,\"burnCaptions\":true}";
            filmJob=request("POST", "/generation/projects/"+shotProject+"/films",filmBody,token,"film-"+run,201).path("data").path("task").path("id").asText();
            request("POST", "/generation/projects/"+shotProject+"/films",filmBody,token,"film-"+run,200);
            assertEquals("QUEUED",request("GET", "/generation/projects/"+shotProject+"/films/"+filmJob,null,token,null,200).path("data").path("task").path("state").asText());
            String reviewVersion=selectionIds.getFirst();
            String reviewBody="{\"expectedReviewVersion\":0,\"contentScore\":3,\"motionScore\":3,\"consistencyScore\":3,\"usable\":false,\"notes\":\"Mock workflow test; not a real quality judgment\"}";
            request("POST","/generation/projects/"+shotProject+"/reviews/"+reviewVersion,reviewBody,otherToken,null,404);
            request("POST","/generation/projects/"+shotProject+"/reviews/"+reviewVersion,reviewBody,token,null,200);
            request("POST","/generation/projects/"+shotProject+"/reviews/"+reviewVersion,reviewBody,token,null,200);
            var evaluation=request("GET","/generation/projects/"+shotProject+"/evaluation-report",null,token,null,200).path("data");
            assertEquals(4,evaluation.path("summary").path("totalVersions").asInt()); assertEquals(1,evaluation.path("summary").path("reviewed").asInt());
            assertEquals(4,evaluation.path("summary").path("mockVersions").asInt());
            verifyMysqlReservations(restarted);
        }
        try (var third = start(false)) {
            var overview = request("GET", "/generation/projects/" + shotProject + "/generations", null, token, null, 200).path("data");
            assertEquals(4, overview.path("versions").size()); assertEquals(4, overview.path("budget").path("usedVersions").asInt());
            overview.path("versions").forEach(version -> assertEquals("SUCCEEDED", version.path("task").path("state").asText()));
            request("POST", "/generation/projects/" + shotProject + "/budget", "{\"maxVersions\":10,\"costLimit\":0}", token, null, 409);
            passed.add("Shot input snapshots, four generation versions, completed artifacts and immutable consumed project quota survive a second application restart");
            String filmBase="/generation/projects/"+shotProject+"/films/"+filmJob;
            JsonNode rendered=null;
            for(int attempt=0;attempt<120;attempt++) {
                rendered=request("GET",filmBase,null,token,null,200).path("data");
                if(rendered.path("task").path("state").asText().equals("SUCCEEDED")) break;
                assertNotEquals("FAILED",rendered.path("task").path("state").asText(),rendered.toString()); Thread.sleep(500);
            }
            assertEquals("SUCCEEDED",rendered.path("task").path("state").asText());
            var media=json.readTree(rendered.path("task").path("metadataJson").asText());
            assertEquals(720,media.path("output").path("width").asInt()); assertEquals(1280,media.path("output").path("height").asInt());
            assertEquals("h264",media.path("output").path("videoCodec").asText()); assertTrue(media.path("output").path("hasAudio").asBoolean());
            assertTrue(media.path("output").path("durationSeconds").asDouble()>2); assertTrue(media.path("burnCaptions").asBoolean());
            request("GET",filmBase+"/artifact",null,otherToken,null,404);
            String filmLink=request("GET",filmBase+"/artifact",null,token,null,200).path("data").asText();
            var filmDownload=http.send(HttpRequest.newBuilder(URI.create(filmLink)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,filmDownload.statusCode());
            assertEquals(rendered.path("task").path("artifactSha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(filmDownload.body())));
            var direct=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+filmBase+"/download")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,direct.statusCode()); assertArrayEquals(filmDownload.body(),direct.body());
            var denied=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+filmBase+"/download")).header("Authorization","Bearer "+otherToken).GET().build(),HttpResponse.BodyHandlers.discarding()); assertEquals(404,denied.statusCode());
            Files.write(Path.of("target/final-demo.mp4"),filmDownload.body());
            Files.writeString(Path.of("target/final-demo-metadata.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(media));
            var csv=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/generation/projects/"+shotProject+"/evaluation-report.csv")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,csv.statusCode()); assertTrue(csv.body().contains("Mock workflow test")); assertEquals(6,csv.body().split("\r\n",-1).length);
            var report=request("GET","/generation/projects/"+shotProject+"/evaluation-report",null,token,null,200).path("data");
            assertEquals(1,report.path("summary").path("reviewed").asInt());
            assertEquals(4,third.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM shot_generation_versions WHERE project_id=?",Integer.class,shotProject));
            passed.add("Queued composition survives restart and executes real FFmpeg normalization, Chinese subtitle burn-in, audio padding and concat; private H.264 MP4 metadata, SHA-256 and owner-only streaming download verified without creating model tasks");
            passed.add("Human-review idempotency, ownership, all-version denominator and Mock labeling persist across restart; authenticated JSON and formula-safe CSV exports verified");
            verifyProjectLibrary(third, token, otherToken, run, shotProject, filmBase);
            verifyAssetCleanup(third, token, otherToken, referenceImage);
            verifyCanonicalLoginThrottle(third, token, run);
            passed.add("Video provider=mock; video and DeepSeek paid gates are closed in all server instances");
        }

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/generation-infrastructure-acceptance.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("result", "PASS", "checks", passed, "modelProvider", "mock", "paidCalls", 0)), StandardCharsets.UTF_8);
    }

    private void verifyProjectLibrary(ServletWebServerApplicationContext context, String token, String otherToken,
                                      String run, String completedProject, String filmBase) throws Exception {
        String prefix = "library" + run;
        var created = new ArrayList<String>();
        for (String suffix : List.of(" literal%_!", " literalXa!", " third")) {
            var brief = new CreativeBrief(prefix + suffix, "验证项目库", "测试产品", List.of("易用"),
                    "自然光", 10, "9:16", null, 2);
            created.add(request("POST", "/generation/projects", json.writeValueAsString(brief), token,
                    "library-" + created.size() + "-" + run, 201).path("data").path("project").path("id").asText());
        }
        // Equal timestamps exercise the UUID tie-breaker rather than relying on request timing.
        var jdbc = context.getBean(JdbcTemplate.class);
        long tiedTime = System.currentTimeMillis();
        for (String id : created) jdbc.update("UPDATE creative_projects SET updated_at=? WHERE id=?", tiedTime, id);
        String pageBase = "/generation/projects/page?q=" + encode(prefix) + "&limit=1";
        String cursor = "";
        var seen = new java.util.HashSet<String>();
        for (int index = 0; index < created.size(); index++) {
            var page = request("GET", pageBase + cursor, null, token, null, 200).path("data");
            assertEquals(1, page.path("items").size());
            assertTrue(seen.add(page.path("items").get(0).path("id").asText()));
            var next = page.path("nextCursor");
            if (index == created.size() - 1) assertTrue(next.isNull());
            else {
                assertFalse(next.isNull());
                cursor = "&beforeUpdatedAt=" + next.path("updatedAt").asLong() + "&beforeId=" + next.path("id").asText();
            }
        }
        assertEquals(new java.util.HashSet<>(created), seen);
        var literal = request("GET", "/generation/projects/page?q=" + encode(prefix + " literal%_!"),
                null, token, null, 200).path("data").path("items");
        assertEquals(1, literal.size());
        assertEquals(created.getFirst(), literal.get(0).path("id").asText());
        assertEquals(0, request("GET", pageBase, null, otherToken, null, 200).path("data").path("items").size());

        String archivePath = "/generation/projects/" + completedProject + "/archive";
        request("POST", archivePath, "{\"archived\":true}", otherToken, null, 404);
        assertTrue(request("POST", archivePath, "{\"archived\":true}", token, null, 200).path("data").path("archived").asBoolean());
        String byId = "/generation/projects/page?q=" + completedProject;
        assertEquals(0, request("GET", byId, null, token, null, 200).path("data").path("items").size());
        assertEquals(1, request("GET", byId + "&archived=true", null, token, null, 200).path("data").path("items").size());
        assertTrue(request("GET", "/generation/projects/" + completedProject, null, token, null, 200)
                .path("data").path("archived").asBoolean());
        assertFalse(request("GET", filmBase + "/artifact", null, token, null, 200).path("data").asText().isBlank());
        request("POST", "/generation/projects/" + completedProject + "/confirm", "{\"expectedRevision\":1}", token, null, 409);
        assertFalse(request("POST", archivePath, "{\"archived\":false}", token, null, 200).path("data").path("archived").asBoolean());
        assertEquals(1, request("GET", byId, null, token, null, 200).path("data").path("items").size());
        passed.add("Real MySQL project library paginates equal timestamps without duplicates, treats %/_/! search characters literally, enforces ownership, and archives/restores a completed project while preserving readable films");
    }

    private void verifyAssetCleanup(ServletWebServerApplicationContext context, String token, String otherToken,
                                    String protectedId) throws Exception {
        request("DELETE", "/generation/assets/" + protectedId, null, otherToken, null, 404);
        request("DELETE", "/generation/assets/" + protectedId, null, token, null, 409);
        var pixels = new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, 0x663399);
        var png = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(pixels, "png", png));
        var unused = uploadImage(token, png.toByteArray()).path("data");
        String id = unused.path("id").asText(), key = unused.path("objectKey").asText();
        assertNotEquals(protectedId, id);
        var minio = context.getBean(MinioClient.class);
        assertEquals(png.size(), minio.statObject(io.minio.StatObjectArgs.builder().bucket("generation-it").object(key).build()).size());
        var page = request("GET", "/generation/assets?limit=20", null, token, null, 200).path("data").path("items");
        boolean foundUnused = false, foundProtected = false;
        for (var item : page) {
            if (item.path("asset").path("id").asText().equals(id)) {
                foundUnused = true;
                assertFalse(item.path("referenced").asBoolean());
            }
            if (item.path("asset").path("id").asText().equals(protectedId)) {
                foundProtected = true;
                assertTrue(item.path("referenced").asBoolean());
            }
        }
        assertTrue(foundUnused && foundProtected);
        request("DELETE", "/generation/assets/" + id, null, otherToken, null, 404);
        assertEquals(id, request("DELETE", "/generation/assets/" + id, null, token, null, 200).path("data").path("id").asText());
        assertEquals(id, request("DELETE", "/generation/assets/" + id, null, token, null, 200).path("data").path("id").asText());
        request("GET", "/generation/assets/" + id, null, token, null, 404);
        context.getBean(GenerationAssetLifecycleService.class).cleanupDue();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String state = "";
        do {
            for (var cleanup : request("GET", "/generation/assets/cleanup", null, token, null, 200).path("data")) {
                if (cleanup.path("id").asText().equals(id)) state = cleanup.path("state").asText();
            }
            if (state.equals("SUCCEEDED")) break;
            assertNotEquals("FAILED", state);
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertEquals("SUCCEEDED", state);
        for (var object : minio.listObjects(ListObjectsArgs.builder().bucket("generation-it").prefix(key).recursive(true).build())) {
            assertNotEquals(key, object.get().objectName());
        }
        assertEquals("SUCCEEDED", request("DELETE", "/generation/assets/" + id, null, token, null, 200).path("data").path("state").asText());
        assertEquals(1, context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM generation_asset_cleanup WHERE id=?", Integer.class, id));
        request("GET", "/generation/assets/" + protectedId + "/preview", null, token, null, 200);
        passed.add("Real MinIO unused-reference deletion is owner-scoped and idempotent, persisted cleanup reaches SUCCEEDED and removes the object, while project/task references remain protected with HTTP 409 and stay readable");
    }

    private void verifyCanonicalLoginThrottle(ServletWebServerApplicationContext context, String ownerToken,
                                              String run) throws Exception {
        String username = "Throttle_" + run;
        registerAndLogin(username);
        for (int attempt = 0; attempt < 8; attempt++) {
            String spelling = attempt % 2 == 0 ? username.toUpperCase(java.util.Locale.ROOT) : username.toLowerCase(java.util.Locale.ROOT);
            String body = json.writeValueAsString(Map.of("username", spelling, "password", "wrong-password"));
            request("POST", "/user/login", body, null, null, 401);
        }
        var redis = context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        String canonicalKey = "auth:login-failures:" + username.toLowerCase(java.util.Locale.ROOT);
        assertEquals("8", redis.opsForValue().get(canonicalKey));
        assertNull(redis.opsForValue().get("auth:login-failures:" + username.toUpperCase(java.util.Locale.ROOT)));
        Long ttl = redis.getExpire(canonicalKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= Duration.ofMinutes(10).toMillis());
        String valid = json.writeValueAsString(Map.of("username", username.toUpperCase(java.util.Locale.ROOT),
                "password", "integration-test-only-password"));
        request("POST", "/user/login", valid, null, null, 429);
        assertEquals("8", redis.opsForValue().get(canonicalKey));
        // Shorten only this disposable test account's TTL to exercise expiration without a ten-minute sleep.
        assertTrue(Boolean.TRUE.equals(redis.expire(canonicalKey, Duration.ofMillis(150))));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(canonicalKey)) && System.nanoTime() < deadline) Thread.sleep(25);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(canonicalKey)));
        assertFalse(request("POST", "/user/login", valid, null, null, 200).path("data").path("token").asText().isBlank());
        request("GET", "/generation/tasks", null, ownerToken, null, 200);
        passed.add("Real Redis Lua records eight mixed-case login failures under one canonical key with an expiring TTL; all case variants are blocked until expiry, then login resumes without affecting the project owner's session");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode waitFor(String token, String id, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode result;
        do {
            result = request("GET", "/generation/tasks/" + id, null, token, null, 200);
            if (result.path("data").path("state").asText().equals(state)) return result;
            assertNotEquals("FAILED", result.path("data").path("state").asText(), result.toString());
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Task did not reach " + state + ": " + result);
        return result;
    }

    private String registerAndLogin(String username) throws Exception {
        String body = json.writeValueAsString(Map.of("username", username, "password", "integration-test-only-password", "nickname", "mock acceptance"));
        request("POST", "/user/register", body, null, null, 200);
        return request("POST", "/user/login", body, null, null, 200).path("data").path("token").asText();
    }
    private void verifyMysqlPlanningReservations(ServletWebServerApplicationContext context,String token) throws Exception {
        // Database-only contract: the installed worker's actual paid gate remains false throughout.
        long user=context.getBean(AuthService.class).resolveUser("Bearer "+token);
        var projects=context.getBean(StoryboardService.class);
        var brief=new CreativeBrief("数据库预算测试","验证调用额度","测试产品",List.of("易用"),"自然光",10,"9:16",null,2);
        String a=projects.create(user,"offline-planning-a",brief).project().id();
        String b=projects.create(user,"offline-planning-b",brief).project().id();
        var p=new StoryboardModelProperties(); p.setPaidEnabled(true); p.setApiKey("offline-contract-only");
        p.setAuthorizationId("offline-planning-policy"); p.setApprovedModel(p.getModel()); p.setMaxCalls(1);
        p.setBudgetLimit(java.math.BigDecimal.ONE); p.setReservationPerCall(java.math.BigDecimal.ONE);
        p.setInputPricePerMillion(new java.math.BigDecimal("0.01")); p.setOutputPricePerMillion(new java.math.BigDecimal("0.02"));
        var repository=context.getBean(StoryboardModelRepository.class); CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String id:List.of(a,b)) results.add(pool.submit(()->{
                start.await(); try { repository.create(user,id,id,1,"{}",p,System.currentTimeMillis()); return true; }
                catch (com.example.server.exception.BusinessException cap) { return false; }
            }));
            start.countDown(); assertNotEquals(results.getFirst().get(),results.get(1).get());
        }
        assertEquals(1,new StoryboardModelRepository(context.getBean(JdbcTemplate.class)).budget(p.getAuthorizationId()).usedCalls());
        assertFalse(context.getBean(StoryboardModelProperties.class).isPaidEnabled());
        passed.add("Database-only concurrent MySQL DeepSeek reservations consume exactly one call across two projects under a one-call global authorization; persistent policy counters verified without any model HTTP request");
    }
    private JsonNode submit(String body, String token, String key, Integer status) throws Exception {
        return request("POST", "/generation/tasks", body, token, key, status);
    }
    private JsonNode uploadImage(String token, byte[] bytes) throws Exception {
        String boundary = "generation-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"reference.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/generation/assets"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    private void verifyMysqlReservations(ServletWebServerApplicationContext context) throws Exception {
        // Database-only policy test: no paid adapter or model HTTP request exists in this application.
        var repository = context.getBean(GenerationRepository.class);
        var jdbc = context.getBean(JdbcTemplate.class);
        var approval = new GenerationProperties();
        approval.setPaidEnabled(true); approval.setAuthorizationId("offline-mysql-policy");
        approval.setApprovedModels(List.of("offline-contract-model")); approval.setMaxPaidTasks(8);
        approval.setBudgetLimit(new java.math.BigDecimal("10"));
        approval.setReservationPerTask(new java.math.BigDecimal("6"));
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        // Publish synthetic records only after both leases exist. Otherwise the live scheduler can
        // legitimately defer an unsupported provider between INSERT and this test's claim.
        var claimedTasks = transaction.execute(status -> {
            var claims = new ArrayList<GenerationTask>();
            for (String id : List.of(first, second)) {
                long claimTime = System.currentTimeMillis();
                repository.insert(id, 1, id, "offline", "offline-database-policy", "offline-contract-model", "{}", claimTime);
                var claimed = repository.claim(id, claimTime);
                assertNotNull(claimed, "Synthetic reservation task must be leased before becoming visible");
                claims.add(claimed);
            }
            return List.copyOf(claims);
        });
        assertNotNull(claimedTasks);
        var a = claimedTasks.get(0);
        var b = claimedTasks.get(1);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f = pool.submit(() -> reserveAfter(start, repository, a, approval));
            var s = pool.submit(() -> reserveAfter(start, repository, b, approval));
            start.countDown();
            assertNotEquals(f.get(), s.get());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM generation_reservations", Integer.class));
        assertEquals(0, new java.math.BigDecimal("6").compareTo(jdbc.queryForObject(
                "SELECT reserved_cost FROM generation_authorizations WHERE id='offline-mysql-policy'", java.math.BigDecimal.class)));
        var restartedRepository = new GenerationRepository(jdbc);
        assertEquals(1, jdbc.queryForObject("SELECT used_tasks FROM generation_authorizations WHERE id='offline-mysql-policy'", Integer.class));
        for (var claimed : List.of(a, b)) {
            assertTrue(restartedRepository.finish(claimed, FAILED, null, null, null,
                    "OFFLINE_DATABASE_POLICY_TEST", 0, false, now + 600_000, System.currentTimeMillis()));
        }
        passed.add("Database-only concurrent MySQL reservations admit one 6 CNY reservation under a 10 CNY cap and persist counters across repository restart; no model call");
    }
    private boolean reserveAfter(CountDownLatch start, GenerationRepository repository, GenerationTask task,
                                 GenerationProperties approval) throws Exception {
        start.await();
        try { return repository.beginSubmission(task, System.currentTimeMillis(), "{}", approval); }
        catch (com.example.server.exception.BusinessException denied) { return false; }
    }
    private JsonNode request(String method, String path, String body, String token, String key, Integer status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (status == null) assertTrue(response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 202, response.body());
        else assertEquals(status.intValue(), response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    private String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Run scripts/test-generation-infrastructure.ps1 (Windows) or scripts/test-generation-infrastructure.sh (Linux); missing " + key);
        return value;
    }
}
