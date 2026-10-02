package com.example.server.generation;

import com.example.server.config.AuthInterceptor;
import com.example.server.controller.ApiExceptionHandler;
import com.example.server.controller.StoryboardController;
import com.example.server.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class StoryboardApiTest {
    @Test void authenticatedProjectDraftRevisionAndConfirmationApiEnforcesOwnershipAndVersion() throws Exception {
        var flow = new StoryboardFlowTest(); flow.setup();
        var json = flow.flow.json;
        AuthService auth = mock(AuthService.class);
        when(auth.resolveUser("Bearer owner")).thenReturn(1L);
        when(auth.resolveUser("Bearer other")).thenReturn(2L);
        when(auth.resolveUser(null)).thenThrow(new SecurityException("登录已失效"));
        var mvc = MockMvcBuilders.standaloneSetup(new StoryboardController(flow.service))
                .setControllerAdvice(new ApiExceptionHandler()).addInterceptors(new AuthInterceptor(auth, json)).build();
        String body = json.writeValueAsString(flow.brief);
        mvc.perform(post("/generation/projects").contentType("application/json").content(body).header("Idempotency-Key", "api")).andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/generation/projects").contentType("application/json").content(body)
                .header("Authorization", "Bearer owner").header("Idempotency-Key", "api"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.project.status").value("DRAFT")).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("data").path("project").path("id").asText();
        mvc.perform(post("/generation/projects").contentType("application/json").content(body).header("Authorization", "Bearer owner").header("Idempotency-Key", "api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(true));
        mvc.perform(get("/generation/projects/" + id + "/revisions").header("Authorization", "Bearer other")).andExpect(status().isNotFound());
        mvc.perform(post("/generation/projects/" + id + "/confirm").header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"expectedRevision\":2}")).andExpect(status().isConflict());
        mvc.perform(post("/generation/projects/" + id + "/confirm").header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"expectedRevision\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mvc.perform(post("/generation/projects/" + id + "/revisions").header("Authorization", "Bearer owner").contentType("application/json")
                .content("{\"expectedRevision\":1,\"draft\":{\"script\":\"abc\",\"shots\":[]}}")).andExpect(status().isBadRequest());
    }
}
