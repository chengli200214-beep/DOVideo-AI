package com.example.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercise the actual ORM mapping, migration and unique index with Redis unavailable. */
class DurableMediaIngestTest {
    JdbcTemplate jdbc; MediaService service; MediaFileMapper mapper, delegate; MinioUtils minio;
    @BeforeEach void setup() throws Exception {
        var data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__create_core_tables.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V12__durable_media_ingest.sql"));
        }
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.addMapper(MediaFileMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data); factory.setConfiguration(config); factory.afterPropertiesSet();
        delegate = new SqlSessionTemplate(factory.getObject()).getMapper(MediaFileMapper.class);
        mapper = spy(delegate);
        var redis = mock(StringRedisTemplate.class); when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis unavailable"));
        minio = mock(MinioUtils.class);
        service = new MediaService(mapper, redis, minio, new ObjectMapper(), mock(AgentCheckpointService.class), mock(AgentTelemetry.class),
                mock(QdrantVectorStore.class), mock(VideoContextService.class));
    }
    @Test void simultaneousRetriesPersistOneOwnedMediaAndCompensateOnlyTheLosingObjects() throws Exception {
        String key = "chunk:" + UUID.randomUUID(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<Future<MediaFile>>();
            for (int i = 0; i < 4; i++) {
                String url = "http://minio.local/" + i + ".mp4";
                results.add(pool.submit(() -> { start.await(); return service.saveUploadedMediaOnce("sample.mp4", url, 7L, "hash", key); }));
            }
            start.countDown(); var id = results.getFirst().get(10, TimeUnit.SECONDS).getId();
            for (var result : results) assertEquals(id, result.get(10, TimeUnit.SECONDS).getId());
            assertEquals(id, service.completedIngest(key, 7L).getId());
            assertThrows(SecurityException.class, () -> service.completedIngest(key, 8L));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_files", Integer.class));
            assertEquals(key, mapper.selectById(id).getIngestKey());
        }
    }
    @Test void lostInsertAcknowledgementResolvesCommittedIdentityWithoutDeletingItsObject() {
        String key = "url:" + UUID.randomUUID(), url = "http://minio.local/committed.mp4";
        doAnswer(call -> { delegate.insert(call.getArgument(0, MediaFile.class)); throw new IllegalStateException("lost insert acknowledgement"); }).when(mapper).insert(any(MediaFile.class));
        var result = service.saveUploadedMediaOnce("sample.mp4", url, 7L, "hash", key);
        assertNotNull(result.getId()); assertEquals(result.getId(), service.completedIngest(key, 7L).getId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM media_files", Integer.class));
        verify(minio, never()).removeFile(anyString());
    }
}
