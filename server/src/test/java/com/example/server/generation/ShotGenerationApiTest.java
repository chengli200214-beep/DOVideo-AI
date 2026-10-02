package com.example.server.generation;

import com.example.server.config.AuthInterceptor;
import com.example.server.controller.*;
import com.example.server.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class ShotGenerationApiTest {
    @Test void authenticatedShotApiEnforcesBudgetConfirmationReplayAndIsolation() throws Exception {
        var flow = new ShotGenerationFlowTest(); flow.setup(); var json = flow.flow.json;
        var auth = mock(AuthService.class);
        when(auth.resolveUser("Bearer owner")).thenReturn(1L); when(auth.resolveUser("Bearer other")).thenReturn(2L);
        when(auth.resolveUser(null)).thenThrow(new SecurityException("登录已失效"));
        var mvc = MockMvcBuilders.standaloneSetup(new ShotGenerationController(flow.service))
                .setControllerAdvice(new ApiExceptionHandler()).addInterceptors(new AuthInterceptor(auth, json)).build();
        String base = "/generation/projects/" + flow.project.id();
        mvc.perform(get(base + "/generations")).andExpect(status().isUnauthorized());
        mvc.perform(get(base + "/generations").header("Authorization", "Bearer other")).andExpect(status().isNotFound());
        mvc.perform(get(base + "/generation-quote?revision=1").header("Authorization", "Bearer owner")).andExpect(status().isConflict());
        flow.storyboard.service.confirm(1, flow.project.id(), 1);
        String body = json.writeValueAsString(flow.input("INITIAL", flow.ids()));
        mvc.perform(post(base + "/generations").contentType("application/json").content(body).header("Authorization", "Bearer owner").header("Idempotency-Key", "api"))
                .andExpect(status().isConflict());
        mvc.perform(post(base + "/budget").contentType("application/json").content("{\"maxVersions\":6,\"costLimit\":0}").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/generation-quote?revision=1").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shots[0].imageSize").value("720x1280"));
        mvc.perform(post(base + "/generations").contentType("application/json").content(body).header("Authorization", "Bearer owner").header("Idempotency-Key", "api"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.versions.length()").value(3));
        mvc.perform(post(base + "/generations").contentType("application/json").content(body).header("Authorization", "Bearer owner").header("Idempotency-Key", "api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(true));
    }
}
