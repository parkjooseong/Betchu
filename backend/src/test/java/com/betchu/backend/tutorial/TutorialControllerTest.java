package com.betchu.backend.tutorial;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.config.SecurityConfig;
import com.betchu.backend.tutorial.TutorialModels.*;
import java.time.Instant;
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
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = TutorialController.class, properties = "betchu.security.enabled=false")
@Import(SecurityConfig.class)
class TutorialControllerTest {
  @Autowired MockMvc mvc;
  @Autowired JsonMapper mapper;
  @MockitoBean TutorialService service;
  private final UUID user = UUID.randomUUID(), quest = UUID.randomUUID(), key = UUID.randomUUID();

  @Test
  void routesNeverBecomePublicThroughLegacyFlag() throws Exception {
    mvc.perform(get("/api/v1/tutorials")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/tutorials/" + quest)).andExpect(status().isUnauthorized());
    for (String path :
        List.of(
            "",
            "/" + quest + "/approve",
            "/" + quest + "/reject",
            "/" + quest + "/select-result",
            "/" + quest + "/final-approve",
            "/" + quest + "/reject-result",
            "/" + quest + "/cancel",
            "/" + quest + "/confirm-cancel",
            "/" + quest + "/reject-cancel"))
      mvc.perform(post("/api/v1/tutorials" + path)).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "{\"dueAt\":123,\"resultAt\":\"2026-09-07T01:00:00Z\"}",
        "{\"dueAt\":\"2026-09-07T01:00:00\",\"resultAt\":\"2026-09-07T02:00:00Z\"}",
        "{\"dueAt\":\"2026-09-07T01:00:00Z\",\"resultAt\":\"2026-09-07T02:00:00Z\",\"stake\":0}"
      })
  void creationRequiresExplicitDateOffsetsAndOnlyTheTwoDateFields(String input) throws Exception {
    mvc.perform(
            post("/api/v1/tutorials")
                .with(signedIn())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_TUTORIAL_REQUEST"));
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{\"expectedRowVersion\":\"0\",\"selectedResult\":\"SUCCESS\"}",
        "{\"expectedRowVersion\":-1,\"selectedResult\":\"SUCCESS\"}",
        "{\"expectedRowVersion\":0.0,\"selectedResult\":\"SUCCESS\"}",
        "{\"expectedRowVersion\":0,\"selectedResult\":\"UNKNOWN\"}",
        "{\"expectedRowVersion\":0,\"selectedResult\":\"SUCCESS\",\"userId\":\"PRIVATE_INPUT\"}"
      })
  void resultInputRejectsCoercionAndOwnershipInjection(String input) throws Exception {
    mvc.perform(
            post("/api/v1/tutorials/" + quest + "/select-result")
                .with(signedIn())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_TUTORIAL_REQUEST"))
        .andExpect(jsonPath("$.detail").value("튜토리얼 요청과 날짜를 확인해 주세요."));
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "invalid", "1-1-1-1-1"})
  void mutationKeysMustBeCanonicalUuids(String value) throws Exception {
    mvc.perform(
            post("/api/v1/tutorials/" + quest + "/cancel")
                .with(signedIn())
                .header("Idempotency-Key", value)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRowVersion\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_IDEMPOTENCY_KEY"));
    verifyNoInteractions(service);
  }

  @Test
  void creatorJsonOmitsPartnerSelectionAndUsesTheAuthenticatedIdentity() throws Exception {
    Instant now = Instant.parse("2026-09-07T01:00:00Z");
    View view =
        new View(
            quest,
            UUID.randomUUID(),
            user,
            "CREATOR",
            "PENDING_FINAL_APPROVAL",
            3,
            UUID.randomUUID(),
            UUID.randomUUID(),
            Result.FAILURE,
            null,
            List.of(),
            null,
            null,
            null,
            now);
    when(service.get(user, quest)).thenReturn(view);
    mvc.perform(get("/api/v1/tutorials/" + quest).with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.partnerSelection").doesNotExist())
        .andExpect(jsonPath("$.viewerRole").value("CREATOR"));
    assertThat(mapper.valueToTree(view).has("partnerSelection")).isFalse();
    verify(service).get(user, quest);
  }

  @Test
  void webCanSendAuthenticatedIdempotentRequests() throws Exception {
    mvc.perform(
            options("/api/v1/tutorials/" + quest + "/approve")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "POST")
                .header(
                    "Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
  }

  private RequestPostProcessor signedIn() {
    return authentication(
        new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(user, UUID.randomUUID()), null, List.of()));
  }
}
