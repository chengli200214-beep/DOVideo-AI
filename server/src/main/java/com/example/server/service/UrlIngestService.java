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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 链接视频入库：yt-dlp 拉取可能长达 30 分钟，不能占住 HTTP 线程。
 * 提交时登记数据库任务，下载在独立线程池执行；Redis 只作兼容缓存。
 */
@Service
public class UrlIngestService {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    public record Job(String id, Long userId, Status status, Long mediaId, String error,
                  long createdAt, long updatedAt) {
    }

    public record JobView(String id, Status status, MediaSummary media, String error, long createdAt) {
    }

    static final int MAX_ACTIVE_PER_USER = 2;
    private static final String JOB_PREFIX = "media:url-ingest:";
    private static final Duration JOB_TTL = Duration.ofHours(24);
    /** 单个任务：下载最长 30 分钟，外加 MD5 与最大 2GB 的 MinIO 上传余量。 */
    private static final Duration RUNNING_STALE_AFTER = Duration.ofMinutes(60);
    /** 最坏排队：队列 8 / 2 个工作线程 × 30 分钟 = 2 小时，再留 1 小时余量。 */
    private static final Duration QUEUED_STALE_AFTER = Duration.ofHours(3);
    private static final String INTERRUPTED_MESSAGE = "任务因服务重启中断，请重新提交";
    private static final Pattern JOB_ID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final Logger log = LoggerFactory.getLogger(UrlIngestService.class);

    private final MediaIngestService ingest;
    private final MediaService mediaService;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final UrlIngestJobRepository jobs;
    private final Executor executor;
    private final Clock clock;
    /** 单实例内的并发计数；进程重启后自然清零，不会像 Redis 计数那样因崩溃泄漏名额。 */
    private final Map<Long, Integer> active = new ConcurrentHashMap<>();

    @Autowired
    public UrlIngestService(MediaIngestService ingest,
                            MediaService mediaService,
                            StringRedisTemplate redis,
                            ObjectMapper json,
                            UrlIngestJobRepository jobs,
                            @Qualifier("urlIngestExecutor") Executor executor) {
        this(ingest, mediaService, redis, json, jobs, executor, Clock.systemUTC());
    }

    UrlIngestService(MediaIngestService ingest,
                     MediaService mediaService,
                     StringRedisTemplate redis,
                     ObjectMapper json,
                     UrlIngestJobRepository jobs,
                     Executor executor,
                     Clock clock) {
        this.ingest = ingest;
        this.mediaService = mediaService;
        this.redis = redis;
        this.json = json;
        this.jobs = jobs;
        this.executor = executor;
        this.clock = clock;
    }

    public JobView submit(String url, Long userId) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("视频链接不能为空");
        String normalizedUrl = url.trim();
        // 下载阶段 YtDlpUtils 会再次校验，属纵深防御，两处都保留
        PublicNetworkAddresses.requirePublicHttpUrl(normalizedUrl);
        if (!tryAcquire(userId)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "同时进行的链接下载过多，请等待已有任务完成");
        }
        long now = clock.millis();
        Job job = new Job(UUID.randomUUID().toString(), userId, Status.QUEUED, null, null, now, now);
        try {
            jobs.create(job);
            cache(job);
            executor.execute(() -> run(job, normalizedUrl));
        } catch (RejectedExecutionException e) {
            release(userId);
            save(next(job, Status.FAILED, null, "链接下载队列已满"));
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "链接下载队列已满，请稍后再试");
        } catch (RuntimeException e) {
            release(userId);
            throw e;
        }
        return view(job);
    }

    public JobView status(String jobId, Long userId) {
        boolean legacy = false;
        Job job = jobId == null || !JOB_ID.matcher(jobId).matches() ? null : jobs.find(jobId);
        if (job == null && jobId != null && JOB_ID.matcher(jobId).matches()) {
            // Pre-V12 tasks are retained until their existing Redis TTL expires.
            try { String raw = redis.opsForValue().get(JOB_PREFIX + jobId); job = raw == null ? null : read(raw); legacy = job != null; }
            catch (RuntimeException unavailable) { log.warn("legacy_url_ingest_cache_unavailable"); }
        }
        if (job == null || !job.userId().equals(userId) || (!legacy && clock.millis() - job.createdAt() > JOB_TTL.toMillis())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "链接下载任务不存在或已过期");
        }
        if (job.status() != Status.COMPLETED) {
            MediaFile committed = mediaService.completedIngest("url:" + job.id(), userId);
            if (committed != null) {
                job = next(job, Status.COMPLETED, committed.getId(), null);
                try { save(job); } catch (RuntimeException unavailable) { log.warn("url_ingest_completion_repair_deferred job={}", job.id()); }
            }
        }
        return view(job);
    }

    private void run(Job job, String url) {
        try {
            Job running = next(job, Status.RUNNING, null, null);
            if (!jobs.start(running)) return;
            cache(running);
            MediaFile media = ingest.ingestUrl(url, job.userId(), "url:" + job.id());
            save(next(job, Status.COMPLETED, media.getId(), null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("url_ingest_failed job={}", job.id(), e);
            try {
                MediaFile committed = mediaService.completedIngest("url:" + job.id(), job.userId());
                save(committed == null ? next(job, Status.FAILED, null, userMessage(e))
                        : next(job, Status.COMPLETED, committed.getId(), null));
            } catch (RuntimeException saveError) {
                log.warn("url_ingest_state_save_failed job={} type={}", job.id(), saveError.getClass().getSimpleName());
            }
        } finally {
            release(job.userId());
        }
    }

    private JobView view(Job job) {
        long now = clock.millis();
        boolean stale = switch (job.status()) {
            case RUNNING -> now - job.updatedAt() > RUNNING_STALE_AFTER.toMillis();
            case QUEUED -> now - job.createdAt() > QUEUED_STALE_AFTER.toMillis();
            default -> false;
        };
        if (stale) {
            return new JobView(job.id(), Status.FAILED, null, INTERRUPTED_MESSAGE, job.createdAt());
        }
        MediaSummary media = null;
        if (job.mediaId() != null) {
            try {
                media = MediaSummary.from(mediaService.requireOwnedMedia(job.mediaId(), job.userId()));
            } catch (NoSuchElementException e) {
                // 入库成功后媒体被用户删除：任务仍算完成，只是没有可展示的媒体
            }
        }
        return new JobView(job.id(), job.status(), media, job.error(), job.createdAt());
    }

    private Job next(Job job, Status status, Long mediaId, String error) {
        return new Job(job.id(), job.userId(), status, mediaId, error, job.createdAt(), clock.millis());
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
        jobs.update(job);
        cache(job);
    }

    private void cache(Job job) {
        try {
            redis.opsForValue().set(JOB_PREFIX + job.id(), json.writeValueAsString(job), JOB_TTL);
        } catch (Exception e) {
            log.warn("url_ingest_cache_write_failed job={} type={}", job.id(), e.getClass().getSimpleName());
        }
    }

    private Job read(String raw) {
        try {
            return json.readValue(raw, Job.class);
        } catch (JsonProcessingException e) {
            log.warn("url_ingest_state_corrupt");
            return null;
        }
    }

    /** 面向用户的失败原因：只放行自己的校验信息与已知下载错误，避免泄漏路径、SQL 等内部细节。 */
    static String userMessage(Exception e) {
        String message = e.getMessage();
        if (e instanceof IllegalArgumentException) {
            return message == null || message.isBlank() ? "链接下载失败" : message;
        }
        if (message != null && message.startsWith("视频链接下载超时")) return "视频链接下载超时";
        if (message != null && message.startsWith("yt-dlp 下载失败")) {
            return message.contains("Unsupported URL") ? "不支持该平台链接" : "该链接无法下载（站点不支持或需要登录）";
        }
        return "链接下载失败";
    }
}
