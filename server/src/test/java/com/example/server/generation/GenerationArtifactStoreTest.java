package com.example.server.generation;

import com.example.server.utils.MinioUtils;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationArtifactStoreTest {
    GenerationTask task = new GenerationTask("task-id", 7, "hash", "mock", "mock-video", "{}",
            GenerationTask.State.SAVING, "mock-task-id", "mock://sample.mp4", null, null, null,
            null, 0, false, 0, null, 0, 0, 0);

    @Test
    void savesActualPlayableFixtureThroughMinioSdkWithStableKeyAndChecksum() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("location")) {
                        return new MockResponse().setHeader("Content-Type", "application/xml")
                                .setBody("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>");
                    }
                    return new MockResponse().setHeader("ETag", "\"fixture-etag\"");
                }
            });
            server.start();
            var store = new MinioGenerationArtifactStore(mock(MinioUtils.class), "media", server.url("/").toString(),
                    "test-access-key", "test-secret-key", new GenerationProperties());
            byte[] expected;
            try (var stream = new ClassPathResource("generation/sample.mp4").getInputStream()) { expected = stream.readAllBytes(); }
            var first = store.save(task, "mock://sample.mp4");
            var second = store.save(task, "mock://sample.mp4");
            assertEquals(first, second);
            assertEquals("generated/7/task-id/video.mp4", first.key());
            assertEquals(expected.length, first.size());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)), first.sha256());
            int puts = 0;
            for (int i = 0; i < server.getRequestCount(); i++) {
                RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(request);
                if (request.getMethod().equals("PUT")) {
                    puts++;
                    assertEquals("/media/generated/7/task-id/video.mp4", request.getPath());
                    assertEquals("video/mp4", request.getHeader("Content-Type"));
                    assertArrayEquals(expected, request.getBody().readByteArray());
                }
            }
            assertEquals(2, puts);
        }
    }

    @Test
    void rejectsUnapprovedSourcesBeforeAnyDownload() {
        var config = new GenerationProperties();
        config.setArtifactHosts("approved.example.com");
        var store = new MinioGenerationArtifactStore(mock(MinioUtils.class), "media", "http://127.0.0.1:9000",
                "test-key", "test-secret", config);
        for (String source : new String[]{"file:///etc/passwd", "http://approved.example.com/a.mp4",
                "https://127.0.0.1/a.mp4", "https://other.example.com/a.mp4", "https://approved.example.com:444/a.mp4"}) {
            assertThrows(IllegalArgumentException.class, () -> store.save(task, source));
        }
    }
}
