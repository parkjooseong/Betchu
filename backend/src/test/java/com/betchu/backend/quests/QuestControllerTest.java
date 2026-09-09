package com.betchu.backend.quests;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(QuestController.class)
@Import(SecurityConfig.class)
class QuestControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean QuestService service;
  @MockitoBean QuestLifecycleService lifecycle;
  private final UUID user = UUID.randomUUID();

  @Test
  void everyQuestRouteRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/quests")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/quests")).andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/v1/quests/" + UUID.randomUUID() + "/draft"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "{", "{\"title\":\"private\"}"})
  void malformedInputReturnsSafeStructuredError(String body) throws Exception {
    mvc.perform(
            post("/api/v1/quests")
                .with(signedIn())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_QUEST_REQUEST"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    verifyNoInteractions(service);
  }

  @Test
  void discardBindsCurrentPrincipalAndRequiresAnExpectedVersion() throws Exception {
    UUID quest = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    when(service.discard(user, quest, 3, key))
        .thenReturn(new QuestModels.DiscardResult(quest, "DISCARDED", 4));
    mvc.perform(
            delete("/api/v1/quests/" + quest + "/draft")
                .with(signedIn())
                .header("Idempotency-Key", key))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_QUEST_REQUEST"));
    mvc.perform(
            delete("/api/v1/quests/" + quest + "/draft?expectedRowVersion=3")
                .with(signedIn())
                .header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowVersion").value(4));
    verify(service).discard(user, quest, 3, key);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PATCH", "DELETE"})
  void authenticatedWebMutationsHaveCorsSupport(String method) throws Exception {
    mvc.perform(
            options("/api/v1/quests/" + UUID.randomUUID() + "/draft")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", method)
                .header(
                    "Access-Control-Request-Headers", "authorization,idempotency-key,content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
  }

  private RequestPostProcessor signedIn() {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser(user, UUID.randomUUID()), null, List.of()));
  }
}
