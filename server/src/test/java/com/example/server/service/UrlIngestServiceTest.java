package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.server.service.UrlIngestService.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrlIngestServiceTest {
    private static final String URL = "https://8.8.8.8/v.mp4";
    private final Map<String, String> store = new HashMap<>();
    private final List<Runnable> pending = new ArrayList<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private MediaIngestService ingest;
    private MediaService media;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> store.put(call.getArgument(0), call.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        ingest = mock(MediaIngestService.class);
        media = mock(MediaService.class);
    }

    private UrlIngestService service(Executor executor) {
        return new UrlIngestService(ingest, media, redis, new ObjectMapper(), executor, clock);
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

    private String failureMessageFor(Exception failure) throws Exception {
        var service = service(pending::add);
        when(ingest.ingestUrl(URL, 1L)).thenThrow(failure);
        var queued = service.submit(URL, 1L);
        pending.removeFirst().run();
        var failed = service.status(queued.id(), 1L);
        assertEquals(FAILED, failed.status());
        return failed.error();
    }

    @Test
    void failureShowsAFriendlyMessageWithoutInternalDetails() throws Exception {
        String error = failureMessageFor(new IllegalStateException(
                "yt-dlp 下载失败: ERROR: Unsupported URL ... C:\\tmp\\abc.mp4"));
        assertEquals("不支持该平台链接", error);
        assertFalse(error.contains("tmp"));
    }

    @Test
    void otherDownloaderFailuresGetAGenericSiteMessage() throws Exception {
        assertEquals("该链接无法下载（站点不支持或需要登录）",
                failureMessageFor(new IllegalStateException("yt-dlp 下载失败: HTTP 403")));
    }

    @Test
    void timeoutMessageIsKept() throws Exception {
        assertEquals("视频链接下载超时", failureMessageFor(new IllegalStateException("视频链接下载超时")));
    }

    @Test
    void validationMessagesAreKept() throws Exception {
        assertEquals("文件过大", failureMessageFor(new IllegalArgumentException("文件过大")));
    }

    @Test
    void unexpectedFailuresAreGeneric() throws Exception {
        assertEquals("链接下载失败", failureMessageFor(new RuntimeException("jdbc:mysql://db:3306 refused")));
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

    @Test
    void staleRunningJobIsReportedFailed() throws Exception {
        var service = service(pending::add);
        var queued = service.submit(URL, 1L);
        // simulate a worker that died after marking RUNNING
        var job = store.get(store.keySet().iterator().next());
        store.put(store.keySet().iterator().next(), job.replace("\"QUEUED\"", "\"RUNNING\""));
        now.addAndGet(Duration.ofMinutes(55).toMillis());
        assertEquals(RUNNING, service.status(queued.id(), 1L).status());
        now.addAndGet(Duration.ofMinutes(6).toMillis());
        var stale = service.status(queued.id(), 1L);
        assertEquals(FAILED, stale.status());
        assertEquals("任务因服务重启中断，请重新提交", stale.error());
    }

    @Test
    void staleQueuedJobIsReportedFailed() {
        var service = service(pending::add);
        var queued = service.submit(URL, 1L);
        now.addAndGet(Duration.ofMinutes(179).toMillis());
        assertEquals(QUEUED, service.status(queued.id(), 1L).status());
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertEquals(FAILED, service.status(queued.id(), 1L).status());
    }

    @Test
    void completedJobWhoseMediaWasDeletedHasNoMedia() throws Exception {
        var service = service(pending::add);
        when(ingest.ingestUrl(URL, 1L)).thenReturn(file(7L));
        when(media.requireOwnedMedia(7L, 1L)).thenThrow(new NoSuchElementException("文件不存在"));
        var queued = service.submit(URL, 1L);
        pending.removeFirst().run();
        var done = service.status(queued.id(), 1L);
        assertEquals(COMPLETED, done.status());
        assertNull(done.media());
    }

    @Test
    void unknownNullAndMalformedJobIdsAreNotFound() {
        var service = service(pending::add);
        var unknown = assertThrows(BusinessException.class,
                () -> service.status("123e4567-e89b-12d3-a456-426614174000", 1L));
        assertEquals(ErrorCode.NOT_FOUND, unknown.errorCode());
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.status(null, 1L)).errorCode());
        clearInvocations(values);
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.status("../../etc", 1L)).errorCode());
        verify(values, never()).get(anyString());
    }

    @Test
    void redisFailureDuringSubmitReleasesTheSlot() {
        var service = service(pending::add);
        doThrow(new IllegalStateException("redis down"))
                .doAnswer(call -> store.put(call.getArgument(0), call.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        assertThrows(IllegalStateException.class, () -> service.submit(URL, 1L));
        assertDoesNotThrow(() -> service.submit(URL, 1L));
        assertDoesNotThrow(() -> service.submit(URL, 1L));
    }
}
