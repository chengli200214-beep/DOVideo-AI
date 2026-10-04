package com.example.server.generation;

import com.example.server.utils.MinioUtils;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class MinioGenerationArtifactStore implements GenerationArtifactStore {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private final MinioClient minio;
    private final MinioUtils urls;
    private final String bucket;
    private final GenerationProperties properties;
    private final OkHttpClient download;

    public MinioGenerationArtifactStore(MinioUtils urls,
            @Value("${minio.bucketName}") String bucket,
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.accessKey}") String accessKey,
            @Value("${minio.secretKey}") String secretKey, GenerationProperties properties) {
        OkHttpClient bounded = new OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build();
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
                .httpClient(bounded).build();
        this.urls = urls;
        this.bucket = bucket;
        this.properties = properties;
        this.download = bounded.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .dns(host -> {
                    var addresses = Arrays.asList(InetAddress.getAllByName(host));
                    for (InetAddress address : addresses) {
                        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                                || address.isSiteLocalAddress() || address.isMulticastAddress()
                                || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                            throw new java.net.UnknownHostException("Non-public artifact address");
                        }
                    }
                    return addresses;
                }).build();
    }

    public Artifact save(GenerationTask task, String sourceUrl) throws Exception {
        String key = "generated/" + task.userId() + "/" + task.id() + "/video.mp4";
        Path temp = Files.createTempFile("generation-", ".mp4");
        try {
            if ("mock".equals(task.provider()) && "mock://sample.mp4".equals(sourceUrl)) {
                try (InputStream input = new ClassPathResource("generation/sample.mp4").getInputStream()) {
                    copyBounded(input, temp);
                }
            } else {
                HttpUrl url = HttpUrl.parse(sourceUrl);
                Set<String> hosts = Arrays.stream(properties.artifactHostsFor(task.provider()).split(","))
                        .map(String::trim).map(String::toLowerCase).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
                if (url == null || !url.isHttps() || url.port() != 443 || !url.username().isEmpty()
                        || !url.password().isEmpty() || !hosts.contains(url.host())) {
                    throw new IllegalArgumentException("Artifact host is not allowed");
                }
                try (Response response = download.newCall(new Request.Builder().url(url).build()).execute()) {
                    if (!response.isSuccessful() || response.body() == null
                            || response.body().contentLength() > MAX_BYTES) {
                        throw new java.io.IOException("Artifact download failed");
                    }
                    try (InputStream input = response.body().byteStream()) { copyBounded(input, temp); }
                }
            }
            byte[] header;
            try (InputStream input = Files.newInputStream(temp)) { header = input.readNBytes(12); }
            if (header.length < 12 || !new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")) {
                throw new java.io.IOException("Expected an MP4 artifact");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(temp)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            long size = Files.size(temp);
            try (InputStream input = Files.newInputStream(temp)) {
                // Stable object name makes a retry after an interrupted DB commit safe.
                minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                        .stream(input, size, -1).contentType("video/mp4").build());
            }
            return new Artifact(key, size, HexFormat.of().formatHex(digest.digest()));
        } finally { Files.deleteIfExists(temp); }
    }

    public String readableUrl(String key) { return urls.readableSource(urls.objectUrl(key)); }

    public void removeReference(String key) throws Exception {
        if (key == null || !key.startsWith("generation-inputs/") || key.contains(".."))
            throw new IllegalArgumentException("只能清理参考素材");
        minio.removeObject(io.minio.RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    public void copyArtifact(String key, Path target) throws Exception {
        try (var input = minio.getObject(io.minio.GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            copyBounded(input, target);
        }
    }
    public InputStream openArtifact(String key) throws Exception { return minio.getObject(io.minio.GetObjectArgs.builder().bucket(bucket).object(key).build()); }
    public Artifact saveFile(String key, Path file) throws Exception {
        long size = Files.size(file);
        if (size < 12 || size >= MAX_BYTES) throw new java.io.IOException("Invalid composition size");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        try (InputStream input = Files.newInputStream(file)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input, size, -1).contentType("video/mp4").build());
        }
        return new Artifact(key, size, HexFormat.of().formatHex(digest.digest()));
    }

    public void putReference(String key, byte[] bytes, String mime) throws Exception {
        try (var input = new java.io.ByteArrayInputStream(bytes)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, bytes.length, -1).contentType(mime).build());
        }
    }
    public byte[] readReference(String key) throws Exception {
        try (var input = minio.getObject(io.minio.GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] bytes = input.readNBytes(5 * 1024 * 1024 + 1);
            if (bytes.length > 5 * 1024 * 1024) throw new java.io.IOException("Reference image exceeds size limit");
            return bytes;
        }
    }

    private void copyBounded(InputStream input, Path target) throws Exception {
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_BYTES) throw new java.io.IOException("Artifact exceeds size limit");
                output.write(buffer, 0, count);
            }
        }
    }
}
