package com.example.aigc;

import com.example.server.generation.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Boots the actual standalone configuration, including its provider list, without paid traffic. */
class AigcProviderWiringTest {
    @Test void standaloneAppRegistersSeedanceAndEnforcesPaidGate() throws Exception {
        try (MockWebServer minio = new MockWebServer()) {
            minio.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getRequestUrl().queryParameterNames().contains("policy"))
                        return new MockResponse().setBody("{\"Version\":\"2012-10-17\",\"Statement\":[]}").addHeader("Content-Type","application/json");
                    return request.getMethod().equals("HEAD") ? new MockResponse().setResponseCode(200)
                        : new MockResponse().setBody("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>");
                }
            });
            minio.start();
            new WebApplicationContextRunner().withUserConfiguration(AigcApplication.class)
                // No scheduled worker can perform storage work or provider calls in this wiring test.
                .withBean("taskScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class))
                .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:standaloneWiring;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
                    "spring.flyway.enabled=false", "minio.endpoint="+minio.url("/"),
                    "minio.accessKey=offline-test", "minio.secretKey=offline-secret", "minio.bucketName=aigc",
                    "generation.provider=seedance", "generation.paid-enabled=true", "generation.seedance.api-key=offline-ark-key",
                    "generation.authorization-id=standalone-wiring-test", "generation.approved-models="+SeedanceGenerationProvider.MODEL,
                    "generation.max-paid-tasks=2147483647", "generation.budget-limit=999999999999", "generation.reservation-per-task=5",
                    "generation.text-model="+SeedanceGenerationProvider.MODEL, "generation.image-model="+SeedanceGenerationProvider.MODEL
                ).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(GenerationProvider.class).values()).extracting(GenerationProvider::name)
                        .containsExactlyInAnyOrder("mock", "siliconflow", "seedance");
                    var service = context.getBean(GenerationService.class);
                    assertThat(service.capabilities()).hasSize(2).allMatch(cap -> cap.available() && cap.provider().equals("seedance"));
                    context.getBean(GenerationProperties.class).setPaidEnabled(false);
                    assertThat(service.capabilities()).allMatch(cap -> !cap.available());
                    assertThat(context.getBean(SeedanceGenerationProvider.class)).isNotNull();
                });
        }
    }
}
