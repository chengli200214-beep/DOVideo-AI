package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.MediaSummary;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.utils.PublicNetworkAddresses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 链接视频入库：yt-dlp 拉取可能长达 30 分钟，不能占住 HTTP 线程。
 * 提交时只做链接校验并登记任务，下载在独立线程池中执行，状态存 Redis 供前端轮询。
 */
@Service
public class UrlIngestService {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    public record Job(String id, Long userId, Status status, Long mediaId, String error) {
    }

    public record JobView(String id, Status status, MediaSummary media, String error) {
    }

    static final int MAX_ACTIVE_PER_USER = 2;
    private static final String JOB_PREFIX = "media:url-ingest:";
    private static final Duration JOB_TTL = Duration.ofHours(24);
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Logger log = LoggerFactory.getLogger(UrlIngestService.class);

    private final MediaIngestService ingest;
    private final MediaService mediaService;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Executor executor;
    /** 单实例内的并发计数；进程重启后自然清零，不会像 Redis 计数那样因崩溃泄漏名额。 */
    private final Map<Long, Integer> active = new ConcurrentHashMap<>();

    public UrlIngestService(MediaIngestService ingest,
                            MediaService mediaService,
                            StringRedisTemplate redis,
                            ObjectMapper json,
                            @Qualifier("urlIngestExecutor") Executor executor) {
        this.ingest = ingest;
        this.mediaService = mediaService;
        this.redis = redis;
        this.json = json;
        this.executor = executor;
    }

    public JobView submit(String url, Long userId) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("视频链接不能为空");
        String normalizedUrl = url.trim();
        PublicNetworkAddresses.requirePublicHttpUrl(normalizedUrl);
        if (!tryAcquire(userId)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "同时进行的链接下载过多，请等待已有任务完成");
        }
        Job job = new Job(UUID.randomUUID().toString(), userId, Status.QUEUED, null, null);
        try {
            save(job);
            executor.execute(() -> run(job, normalizedUrl));
        } catch (RejectedExecutionException e) {
            release(userId);
            save(new Job(job.id(), userId, Status.FAILED, null, "链接下载队列已满"));
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "链接下载队列已满，请稍后再试");
        } catch (RuntimeException e) {
            release(userId);
            throw e;
        }
        return view(job);
    }

    public JobView status(String jobId, Long userId) {
        String raw = jobId == null ? null : redis.opsForValue().get(JOB_PREFIX + jobId);
        Job job = raw == null ? null : read(raw);
        if (job == null || !job.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "链接下载任务不存在或已过期");
        }
        return view(job);
    }

    private void run(Job job, String url) {
        try {
            save(new Job(job.id(), job.userId(), Status.RUNNING, null, null));
            MediaFile media = ingest.ingestUrl(url, job.userId());
            save(new Job(job.id(), job.userId(), Status.COMPLETED, media.getId(), null));
        } catch (Exception e) {
            log.warn("url_ingest_failed job={} type={}", job.id(), e.getClass().getSimpleName());
            try {
                save(new Job(job.id(), job.userId(), Status.FAILED, null, errorMessage(e)));
            } catch (RuntimeException saveError) {
                log.warn("url_ingest_state_save_failed job={} type={}", job.id(), saveError.getClass().getSimpleName());
            }
        } finally {
            release(job.userId());
        }
    }

    private JobView view(Job job) {
        MediaSummary media = job.mediaId() == null
                ? null
                : MediaSummary.from(mediaService.requireOwnedMedia(job.mediaId(), job.userId()));
        return new JobView(job.id(), job.status(), media, job.error());
    }

    private boolean tryAcquire(Long userId) {
        boolean[] acquired = {false};
        active.compute(userId, (id, count) -> {
            int current = count == null ? 0 : count;
            if (current >= MAX_ACTIVE_PER_USER) return count;
            acquired[0] = true;
            return current + 1;
        });
        return acquired[0];
    }

    private void release(Long userId) {
        active.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
    }

    private void save(Job job) {
        try {
            redis.opsForValue().set(JOB_PREFIX + job.id(), json.writeValueAsString(job), JOB_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存链接下载任务", e);
        }
    }

    private Job read(String raw) {
        try {
            return json.readValue(raw, Job.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return "链接下载失败";
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
