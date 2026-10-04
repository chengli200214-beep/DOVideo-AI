package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static com.example.server.service.UrlIngestService.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrlIngestServiceTest {
    private static final String URL = "https://8.8.8.8/v.mp4";
    private final Map<String, String> store = new HashMap<>();
    private final List<Runnable> pending = new ArrayList<>();
    private StringRedisTemplate redis;
    private MediaIngestService ingest;
    private MediaService media;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> store.put(call.getArgument(0), call.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        ingest = mock(MediaIngestService.class);
        media = mock(MediaService.class);
    }

    private UrlIngestService service(Executor executor) {
        return new UrlIngestService(ingest, media, redis, new ObjectMapper(), executor);
    }

    private static MediaFile file(long id) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setUserId(1L);
        file.setFilename("WEB_v.mp4");
        file.setStatus("COMPLETED");
        return file;
    }

    @Test
    void submitReturnsBeforeDownloadingAndCompletesInBackground() throws Exception {
        var service = service(pending::add);
        when(ingest.ingestUrl(URL, 1L)).thenReturn(file(7L));
        when(media.requireOwnedMedia(7L, 1L)).thenReturn(file(7L));

        var queued = service.submit(URL, 1L);
        assertEquals(QUEUED, queued.status());
        verifyNoInteractions(ingest);

        pending.removeFirst().run();
        var done = service.status(queued.id(), 1L);
        assertEquals(COMPLETED, done.status());
        assertEquals(7L, done.media().id());
    }

    @Test
    void failureKeepsTheDownloaderMessage() throws Exception {
        var service = service(pending::add);
        when(ingest.ingestUrl(URL, 1L)).thenThrow(new IllegalStateException("yt-dlp 下载失败: Unsupported URL"));

        var queued = service.submit(URL, 1L);
        pending.removeFirst().run();

        var failed = service.status(queued.id(), 1L);
        assertEquals(FAILED, failed.status());
        assertTrue(failed.error().contains("Unsupported URL"));
    }

    @Test
    void otherUsersCannotReadTheJob() {
        var service = service(pending::add);
        var queued = service.submit(URL, 1L);
        var error = assertThrows(BusinessException.class, () -> service.status(queued.id(), 2L));
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    void privateUrlIsRejectedBeforeAnyJobExists() {
        var service = service(pending::add);
        assertThrows(IllegalArgumentException.class, () -> service.submit("http://127.0.0.1/v.mp4", 1L));
        assertTrue(pending.isEmpty());
        assertTrue(store.isEmpty());
    }

    @Test
    void limitsActiveJobsPerUserAndFreesTheSlotWhenOneFinishes() throws Exception {
        var service = service(pending::add);
        when(ingest.ingestUrl(URL, 1L)).thenReturn(file(7L));
        service.submit(URL, 1L);
        service.submit(URL, 1L);
        var error = assertThrows(BusinessException.class, () -> service.submit(URL, 1L));
        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode());
        assertDoesNotThrow(() -> service.submit(URL, 2L));

        pending.removeFirst().run();
        assertDoesNotThrow(() -> service.submit(URL, 1L));
    }

    @Test
    void fullQueueMarksTheJobFailedAndReleasesTheSlot() {
        var service = service(task -> { throw new RejectedExecutionException("full"); });
        for (int attempt = 0; attempt < 3; attempt++) {
            var error = assertThrows(BusinessException.class, () -> service.submit(URL, 1L));
            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.errorCode());
        }
        assertTrue(store.values().stream().allMatch(job -> job.contains("\"FAILED\"")));
    }
}
