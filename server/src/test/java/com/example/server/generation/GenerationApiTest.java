package com.example.server.generation;

import com.example.server.config.AuthInterceptor;
import com.example.server.controller.ApiExceptionHandler;
import com.example.server.controller.GenerationController;
import com.example.server.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class GenerationApiTest {
    @Test
    void authenticatedApiRunsFullLoopAndMapsValidationConflictAndOwnershipErrors() throws Exception {
        var flow = new GenerationFlowTest();
        flow.setup();
        var json = flow.json;
        var service = flow.service;
        AuthService auth = mock(AuthService.class);
        when(auth.resolveUser("Bearer owner")).thenReturn(1L);
        when(auth.resolveUser("Bearer other")).thenReturn(2L);
        when(auth.resolveUser(null)).thenThrow(new SecurityException("登录已失效"));
        var mvc = MockMvcBuilders.standaloneSetup(new GenerationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new AuthInterceptor(auth, json)).build();
        String payload = json.writeValueAsString(flow.text);
        mvc.perform(post("/generation/tasks").contentType("application/json").content(payload).header("Idempotency-Key", "api"))
                .andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/generation/tasks").contentType("application/json").content(payload)
                        .header("Idempotency-Key", "api").header("Authorization", "Bearer owner"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.task.state").value("QUEUED")).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("data").path("task").path("id").asText();
        mvc.perform(post("/generation/tasks").contentType("application/json").content(payload)
                        .header("Idempotency-Key", "api").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(true));
        mvc.perform(post("/generation/tasks").contentType("application/json").content(payload.replace("cat running", "different"))
                        .header("Idempotency-Key", "api").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict());
        mvc.perform(post("/generation/tasks").contentType("application/json").content(payload)
                        .header("Authorization", "Bearer owner")).andExpect(status().isBadRequest());
        mvc.perform(post("/generation/tasks").contentType("application/json").content(payload.replace("cat running", ""))
                        .header("Idempotency-Key", "invalid").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/generation/tasks/" + id).header("Authorization", "Bearer other")).andExpect(status().isNotFound());
        mvc.perform(get("/generation/tasks/" + id + "/artifact").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict());
        flow.step(id); flow.step(id); flow.step(id);
        mvc.perform(get("/generation/tasks/" + id).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.requestJson").doesNotExist()).andExpect(jsonPath("$.data.sourceUrl").doesNotExist());
        mvc.perform(get("/generation/tasks/" + id + "/artifact").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("http://minio.local/presigned"));
    }
}
