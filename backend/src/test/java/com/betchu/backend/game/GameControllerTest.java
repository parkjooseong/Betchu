package com.betchu.backend.game;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.config.SecurityConfig;
import com.betchu.backend.game.GameModels.*;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import com.betchu.backend.starter.StarterModels.Species;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = GameController.class, properties = "betchu.security.enabled=false")
@Import(SecurityConfig.class)
class GameControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean MonsterService monsters;
  @MockitoBean HomeService home;
  private final UUID userId = UUID.randomUUID();
  private final UUID requestKey = UUID.randomUUID();

  @Test
  void allPersistentGameEndpointsRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/home")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/monsters/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/monsters/partner")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/monsters/starter")).andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/v1/monsters/me/name")).andExpect(status().isUnauthorized());
    verifyNoInteractions(monsters, home);
  }

  @Test
  void creationUsesAuthenticatedOwnerAndReturnsPersistedProjection() throws Exception {
    MonsterView monster =
        new MonsterView(
            UUID.randomUUID(),
            "별이",
            Species.STARLIGHT,
            GrowthStage.EGG,
            0,
            Milestone.HATCH,
            List.of(),
            List.of(),
            1,
            0,
            80,
            100,
            10,
            0,
            0,
            100,
            10,
            700,
            0);
    when(monsters.create(eq(userId), eq(requestKey), any(PreviewRequest.class)))
        .thenReturn(monster);
    mvc.perform(
            post("/api/v1/monsters/starter")
                .with(signedIn())
                .header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"species\":\"STARLIGHT\",\"name\":\"별이\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(monster.id().toString()))
        .andExpect(jsonPath("$.name").value("별이"))
        .andExpect(jsonPath("$.combatPower").value(700));
    verify(monsters).create(userId, requestKey, new PreviewRequest(Species.STARLIGHT, "별이"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-a-uuid", "1-1-1-1-1", " 00000000-0000-0000-0000-000000000001"})
  void creationRejectsNoncanonicalOrMissingRequestKeys(String key) throws Exception {
    mvc.perform(
            post("/api/v1/monsters/starter")
                .with(signedIn())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"species\":\"STARLIGHT\",\"name\":\"별이\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_IDEMPOTENCY_KEY"));
    verifyNoInteractions(monsters);
  }

  @Test
  void omittedRequestKeyAndServiceRuleFailuresUseProblemResponses() throws Exception {
    mvc.perform(
            post("/api/v1/monsters/starter")
                .with(signedIn())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"species\":\"STARLIGHT\",\"name\":\"별이\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_IDEMPOTENCY_KEY"));
    when(monsters.rename(userId, "bad"))
        .thenThrow(
            new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STARTER_SELECTION", "이름을 확인해 주세요."));
    mvc.perform(
            patch("/api/v1/monsters/me/name")
                .with(signedIn())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.errorCode").value("INVALID_STARTER_SELECTION"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "{\"species\":\"UNKNOWN\",\"name\":\"별이\"}",
        "{\"species\":\"WAVE\",\"name\":123}",
        "{\"species\":\"WAVE\",\"name\":null}",
        "{\"species\":\"WAVE\",\"name\":\"별이\",\"userId\":\"other\"}"
      })
  void starterJsonRejectsCoercionAndClientOwnershipFields(String body) throws Exception {
    mvc.perform(
            post("/api/v1/monsters/starter")
                .with(signedIn())
                .header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    verifyNoInteractions(monsters);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "{\"name\":123}",
        "{\"name\":null}",
        "{\"name\":\"별이\",\"userId\":\"other\"}"
      })
  void renameJsonRejectsCoercionAndExtraProperties(String body) throws Exception {
    mvc.perform(
            patch("/api/v1/monsters/me/name")
                .with(signedIn())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    verifyNoInteractions(monsters);
  }

  @Test
  void webAllowsNamePatchWithAuthorizationOnlyForConfiguredOrigins() throws Exception {
    mvc.perform(
            options("/api/v1/monsters/me/name")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    mvc.perform(
            options("/api/v1/monsters/me/name")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "PATCH"))
        .andExpect(status().isForbidden());
  }

  @Test
  void gameLocksCannotSilentlyRunWithoutATransaction() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    GameAccess access = new GameAccess(jdbc);
    assertThatThrownBy(() -> access.lockUser(userId)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> access.lockRelationship(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(jdbc);
  }

  private RequestPostProcessor signedIn() {
    return authentication(
        new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(userId, UUID.randomUUID()), null, List.of()));
  }
}
