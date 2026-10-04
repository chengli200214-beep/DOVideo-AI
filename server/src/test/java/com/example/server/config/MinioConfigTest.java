package com.example.server.config;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MinioConfigTest {
    @Test void newlyCreatedBucketHasItsPrivatePolicyChecked() throws Exception {
        try (var storage = new Storage(false, null, "NoSuchBucketPolicy")) {
            assertNotNull(storage.connect(false));
            assertEquals(1, storage.requests.stream().filter(r -> "PUT".equals(r.getMethod())).count());
            assertEquals(1, storage.policyReads());
        }
    }

    @Test void existingBucketWithoutPolicyStaysPrivateEvenWhenLegacyFlagIsTrue() throws Exception {
        try (var storage = new Storage(true, null, "NoSuchBucketPolicy")) {
            assertNotNull(storage.connect(true));
            assertEquals(1, storage.policyReads()); storage.assertNoPolicyMutation();
        }
    }

    @Test void authenticatedReadAndAnonymousDenyPoliciesArePreserved() throws Exception {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":["arn:aws:iam::123456789012:role/media"]},"Action":["s3:GetObject"],"Resource":"arn:aws:s3:::aigc/*"},
              {"Effect":"Deny","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::aigc/private/*"}
            ]}
            """;
        try (var storage = new Storage(true, policy, null)) {
            assertNotNull(storage.connect(false)); storage.assertNoPolicyMutation();
        }
    }

    @Test void oldAnonymousReadPoliciesBlockStartupInsteadOfTrustingTheDisabledFlag() throws Exception {
        for (String principal : List.of("\"*\"", "{\"AWS\":\"*\"}", "{\"AWS\":[\"*\"]}")) {
            String policy = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":" + principal
                    + ",\"Action\":[\"s3:GetObject\"],\"Resource\":\"arn:aws:s3:::aigc/*\"}]}";
            try (var storage = new Storage(true, policy, null)) {
                var failure = assertThrows(IllegalStateException.class, () -> storage.connect(false));
                assertTrue(failure.getMessage().contains("匿名读取"));
                assertTrue(failure.getMessage().contains("mc anonymous set none")); storage.assertNoPolicyMutation();
            }
        }
    }

    @Test void wildcardReadActionsAndNotActionCannotBypassThePrivacyCheck() throws Exception {
        for (String action : List.of("\"Action\":\"s3:Get*\"", "\"Action\":\"*\"", "\"NotAction\":\"s3:PutObject\"")) {
            String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":\"*\"," + action
                    + ",\"Resource\":\"arn:aws:s3:::aigc/*\"}}";
            try (var storage = new Storage(true, policy, null)) {
                assertThrows(IllegalStateException.class, () -> storage.connect(false)); storage.assertNoPolicyMutation();
            }
        }
    }

    @Test void unreadablePolicyDoesNotGetTreatedAsPrivate() throws Exception {
        try (var storage = new Storage(true, null, "AccessDenied")) {
            var failure = assertThrows(IllegalStateException.class, () -> storage.connect(false));
            assertTrue(failure.getMessage().contains("无法核对")); storage.assertNoPolicyMutation();
        }
    }

    private static final class Storage implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        Storage(boolean exists, String policy, String error) throws Exception {
            var present = new AtomicBoolean(exists);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    requests.add(request);
                    if (request.getRequestUrl().queryParameterNames().contains("policy")) {
                        if (error != null) return new MockResponse().setResponseCode(error.equals("NoSuchBucketPolicy") ? 404 : 403)
                                .addHeader("Content-Type", "application/xml").setBody("<Error><Code>" + error + "</Code><Message>policy unavailable</Message><BucketName>aigc</BucketName><RequestId>offline</RequestId></Error>");
                        return new MockResponse().addHeader("Content-Type", "application/json").setBody(policy);
                    }
                    if ("HEAD".equals(request.getMethod())) return new MockResponse().setResponseCode(present.get() ? 200 : 404);
                    if ("PUT".equals(request.getMethod())) { present.set(true); return new MockResponse().setResponseCode(200); }
                    return new MockResponse().addHeader("Content-Type", "application/xml")
                            .setBody("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>");
                }
            });
            server.start();
        }
        io.minio.MinioClient connect(boolean legacyPublicRead) {
            return new MinioConfig().minioClient(server.url("/").toString(), "offline-test", "offline-secret", "aigc", legacyPublicRead);
        }
        long policyReads() { return requests.stream().filter(r -> r.getRequestUrl().queryParameterNames().contains("policy")).count(); }
        void assertNoPolicyMutation() { assertTrue(requests.stream().allMatch(r -> List.of("GET", "HEAD").contains(r.getMethod()))); }
        @Override public void close() throws Exception { server.close(); }
    }
}
