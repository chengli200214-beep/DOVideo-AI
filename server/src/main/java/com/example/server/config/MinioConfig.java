package com.example.server.config;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.accessKey}") String accessKey,
            @Value("${minio.secretKey}") String secretKey,
            @Value("${minio.bucketName}") String bucketName,
            @Value("${minio.public-read:false}") boolean publicRead) {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("minio_bucket_created bucket={}", bucketName);
            }
            requirePrivateBucket(client, bucketName);
            // 媒体一律通过短时效预签名地址访问（见 MinioUtils.readableSource，默认 1 小时有效），
            // 桶本身无需公开可读。历史上的整桶 public-read 策略会让所有用户私有视频绕过
            // AuthInterceptor 鉴权被匿名下载，属于严重越权隐患，且对播放没有任何功能收益，
            // 因此这里显式忽略该开关，只保留告警以便发现误配。
            if (publicRead) {
                log.warn("minio_public_read_ignored bucket={} reason=media_served_via_short_lived_presigned_url",
                        bucketName);
            }
            return client;
        } catch (Exception e) {
            if (e instanceof IllegalStateException privacyFailure) throw privacyFailure;
            throw new IllegalStateException("MinIO 初始化失败", e);
        }
    }

    private static void requirePrivateBucket(MinioClient client, String bucket) throws Exception {
        String policy;
        try { policy = client.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build()); }
        catch (ErrorResponseException failure) {
            if ("NoSuchBucketPolicy".equals(failure.errorResponse().code())) return;
            throw new IllegalStateException("无法核对 MinIO 桶访问策略，拒绝启动；请授予服务账号读取桶策略的权限", failure);
        }
        if (policy == null || policy.isBlank()) return;
        JsonNode document = new ObjectMapper().reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(policy);
        JsonNode statements = document.path("Statement");
        if (statements.isObject()) checkStatement(statements, bucket);
        else if (statements.isArray()) for (var statement : statements) checkStatement(statement, bucket);
        else throw new IllegalStateException("MinIO 桶策略格式无法核对，拒绝启动");
    }

    private static void checkStatement(JsonNode statement, String bucket) {
        if (!statement.isObject()) throw new IllegalStateException("MinIO 桶策略格式无法核对，拒绝启动");
        if (!"Allow".equals(statement.path("Effect").asText())) return;
        boolean anonymous = wildcardPrincipal(statement.path("Principal"))
                || (statement.has("NotPrincipal") && !wildcardPrincipal(statement.path("NotPrincipal")));
        boolean read = actionIncludes(statement.path("Action"), "s3:GetObject")
                || actionIncludes(statement.path("Action"), "s3:GetObjectVersion")
                || (statement.has("NotAction") && (!actionIncludes(statement.path("NotAction"), "s3:GetObject")
                    || !actionIncludes(statement.path("NotAction"), "s3:GetObjectVersion")));
        if (anonymous && read) throw new IllegalStateException("MinIO 桶 " + bucket
                + " 存在匿名读取策略，私有产物可能绕过应用鉴权；请在 MinIO 控制台关闭匿名访问，"
                + "或由管理员执行 mc anonymous set none <alias>/" + bucket + " 后重启。应用不会自动改写桶策略。");
    }

    private static boolean wildcardPrincipal(JsonNode principal) {
        if (principal.isTextual()) return "*".equals(principal.textValue());
        if (principal.isArray() || principal.isObject()) for (var value : principal) if (wildcardPrincipal(value)) return true;
        return false;
    }

    private static boolean actionIncludes(JsonNode actions, String target) {
        if (actions.isArray()) {
            for (var action : actions) if (actionIncludes(action, target)) return true;
            return false;
        }
        if (!actions.isTextual()) return false;
        StringBuilder pattern = new StringBuilder();
        for (char value : actions.textValue().toCharArray()) {
            if (value == '*') pattern.append(".*");
            else if (value == '?') pattern.append('.');
            else pattern.append(java.util.regex.Pattern.quote(String.valueOf(value)));
        }
        return java.util.regex.Pattern.compile(pattern.toString(), java.util.regex.Pattern.CASE_INSENSITIVE).matcher(target).matches();
    }
}
